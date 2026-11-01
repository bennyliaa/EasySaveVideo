package com.easysavevideo.app.storage

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.easysavevideo.core.model.*
import com.easysavevideo.core.domain.RecoveryManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded startup IO; one coalesced write job rather than one queued job per callback. */
class BrowserPersistence(context: Context, private val errors: ErrorSink,
    acceptedVaultPolicy: () -> VaultAutoLockPolicy? = { null },
    acceptedSaveMethodSettings: (() -> SaveSettings?)? = null, private val onWriteFailure: () -> Unit) : AutoCloseable {
    constructor(context: Context, errors: ErrorSink, onWriteFailure: () -> Unit) :
        this(context, errors, { null }, null, onWriteFailure)

    private val settingsStore = AndroidSettingsStore(context, acceptedVaultPolicy, acceptedSaveMethodSettings)
    private val sessionStore = AndroidBrowserSessionStore(context)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val completed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val writing = AtomicBoolean(false)
    private val failureNotified = AtomicBoolean(false)
    private val preserveSession = AtomicBoolean(false)
    private val sessionResetRequested = AtomicBoolean(false)
    val sessionWritesPaused: Boolean get() = preserveSession.get()
    private data class PendingSettings(val value: AppSettings, val includeNetwork: Boolean, val includeConcurrency: Boolean, val includeVaultPolicy: Boolean)
    private val settingsPending = AtomicReference<PendingSettings?>(null)
    private val networkPreferenceConfirmed = AtomicBoolean(false)
    var settingsLoaded: Boolean = false; private set
    private val sessionPending = AtomicReference<BrowserSessionCheckpoint?>(null)
    private val empty = BrowserSessionCheckpoint(emptyList(), null)
    fun start(onReady: (AppSettings, BrowserSessionCheckpoint?, Boolean) -> Unit) {
        val cancellation = CancellationSource()
        val operation = OperationContext(UUID.randomUUID().toString(), System.currentTimeMillis() + 2_000, cancellation)
        val timeout = Runnable {
            if (!closed.get() && completed.compareAndSet(false, true)) {
                cancellation.cancel()
                if (!sessionResetRequested.get()) preserveSession.set(true)
                onReady(AppSettings(), null, true)
            }
        }
        main.postDelayed(timeout, 2_000)
        io.execute {
            var settings = AppSettings()
            var settingsSucceeded = false
            var checkpoint: BrowserSessionCheckpoint? = null
            val manager = RecoveryManager(EpochClock(System::currentTimeMillis))
            val probes = listOf(
                object : RecoveryProbe {
                    override val kind = RecoveryObjectKind.SETTINGS
                    override fun check(operation: OperationContext): RecoveryOutcome = try {
                        settings = settingsStore.load(); settingsSucceeded = true; RecoveryOutcome(kind, RecoveryDisposition.READY)
                    } catch (_: Exception) {
                        val problem = error(ErrorCode.SETTINGS_UNAVAILABLE); errors.report(problem)
                        RecoveryOutcome(kind, RecoveryDisposition.ISOLATED, problem)
                    }
                },
                object : RecoveryProbe {
                    override val kind = RecoveryObjectKind.BROWSER_SESSION
                    override fun check(operation: OperationContext): RecoveryOutcome = try {
                        if (settings.browser.restoreTabs) checkpoint = sessionStore.load()
                        RecoveryOutcome(kind, RecoveryDisposition.READY)
                    } catch (_: Exception) {
                        val problem = error(ErrorCode.RECOVERY_FAILED); errors.report(problem)
                        RecoveryOutcome(kind, RecoveryDisposition.ISOLATED, problem)
                    }
                },
            )
            val report = manager.check(probes, operation)
            val failed = report.outcomes.any { it.disposition != RecoveryDisposition.READY }
            if (report.outcomes.any { it.kind == RecoveryObjectKind.BROWSER_SESSION && it.disposition != RecoveryDisposition.READY }
                && !sessionResetRequested.get()) preserveSession.set(true)
            main.post {
                main.removeCallbacks(timeout)
                if (!closed.get() && completed.compareAndSet(false, true)) {
                    settingsLoaded = settingsSucceeded
                    onReady(settings, checkpoint, failed)
                }
            }
        }
    }
    fun checkpoint(checkpoint: BrowserSessionCheckpoint, enabled: Boolean) {
        if (!completed.get() || closed.get() || preserveSession.get()) return
        sessionPending.set(if (enabled) checkpoint else empty); scheduleWrite()
    }
    fun saveSettings(settings: AppSettings, confirmNetworkPreference: Boolean = false) {
        if (closed.get()) return
        if (confirmNetworkPreference) networkPreferenceConfirmed.set(true)
        settingsPending.set(PendingSettings(settings, settingsLoaded || networkPreferenceConfirmed.get(), settingsLoaded, settingsLoaded))
        if (!settings.browser.restoreTabs) {
            // The settings toggle explicitly explains that disabling clears saved addresses.
            sessionResetRequested.set(true); preserveSession.set(false); sessionPending.set(empty)
        }
        scheduleWrite()
    }
    private fun scheduleWrite() {
        if (writing.compareAndSet(false, true)) io.execute {
            do {
                settingsPending.getAndSet(null)?.let { value -> runCatching { settingsStore.save(value.value, value.includeNetwork, value.includeConcurrency, value.includeVaultPolicy) }.onFailure { writeFailure(ErrorCode.SETTINGS_UNAVAILABLE) } }
                sessionPending.getAndSet(null)?.takeUnless { preserveSession.get() }?.let { value -> runCatching { sessionStore.save(value) }.onFailure { writeFailure(ErrorCode.RECOVERY_FAILED) } }
                writing.set(false)
            } while ((settingsPending.get() != null || sessionPending.get() != null) && writing.compareAndSet(false, true))
        }
    }
    private fun writeFailure(code: ErrorCode) {
        errors.report(error(code))
        if (failureNotified.compareAndSet(false, true)) main.post { if (!closed.get()) onWriteFailure() }
    }
    private fun error(code: ErrorCode) = ErrorEnvelope(code, ErrorModule.RECOVERY, true, UserAction.REVIEW_SETTINGS)
    override fun close() { closed.set(true); main.removeCallbacksAndMessages(null); io.shutdown() }
}
