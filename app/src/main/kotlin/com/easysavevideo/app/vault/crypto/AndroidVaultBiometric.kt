package com.easysavevideo.app.vault.crypto

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.easysavevideo.app.vault.*
import com.easysavevideo.library.export.ExportRelease
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.KeysetHandle
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher

/** Optional platform leaf. Construction and prepare never touch KeyStore or show a prompt. */
internal class AndroidVaultBiometric(
    private val androidContext: Context,
    private val keystore: AndroidVaultKeystore,
    private val executor: Executor,
    private val scheduler: ScheduledExecutorService,
    private val clock: VaultMonotonicClock,
    platformExecutor: Executor = Executor { action ->
        if (!Handler(Looper.getMainLooper()).post(action)) throw RejectedExecutionException("biometric-dispatch")
    },
) : VaultBiometricCryptoLeaf {
    enum class Failure { CANCELLED, TIMEOUT, TEMPORARY_LOCKOUT, PERMANENT_LOCKOUT, SLOT_UNAVAILABLE, REJECTED }
    sealed interface Outcome<out T> {
        class Completed<T>(val value: T) : Outcome<T>
        class Failed(val failure: Failure) : Outcome<Nothing>
    }
    enum class Phase { PREPARED, SUBMITTING, SUBMITTED, TERMINAL_OBSERVED }
    enum class Submission { NOT_STARTED, IN_FLIGHT, CONFIRMED, UNCONFIRMED }

    /** The owner registers this handle before start. A cancel request is never terminal evidence. */
    class Pending internal constructor(private val operation: VaultBiometricPromptOperation<*>) : VaultBiometricPending {
        override fun start() = operation.start()
        fun phase(): Phase = operation.phase()
        fun submission(): Submission = operation.submission()
        fun isPlatformTerminal(): Boolean = operation.isPlatformTerminal()
        override fun observeDrain(): ExportRelease = operation.observeDrain()
        override fun onDrain(listener: () -> Unit) = operation.onDrain(listener)
        override fun ownsSystemPromptFocus(): Boolean = operation.ownsSystemPromptFocus()
        override fun close() = operation.close()
    }

    private val dispatcher = VaultBiometricSerialDispatcher(platformExecutor)

    /** Display-only capability query; this never creates a key, root, or authentication result. */
    override fun availability(): VaultBiometricAvailability = try {
        if (androidContext.getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS)
            VaultBiometricAvailability.AVAILABLE else VaultBiometricAvailability.UNAVAILABLE
    } catch (_: Exception) { VaultBiometricAvailability.UNAVAILABLE }

    override fun prepareWrapRoot(identity: VaultPersistentIdentity, slotId: String, generation: Long,
        root: KeysetHandle, title: CharSequence, cancelText: CharSequence, context: VaultOperationContext,
        binding: VaultBiometricBinding, completed: (Outcome<VaultCiphertext>) -> Unit): Pending =
        prepare(identity, slotId, generation, context, binding, completed) { callbacks, callbackExecutor ->
            require(binding.purpose == VaultBiometricPurpose.ENROLL_WRAP) { "biometric-purpose" }
            val aad = VaultCryptoWire.aad(identity, VaultAadPurpose.BIOMETRIC_ROOT,
                VaultSlotKind.BIOMETRIC, slotId, generation)
            val cipher = keystore.biometricEncryptCipher(identity.biometricKeyAlias)
            cipher.updateAAD(aad) // Frozen before the actual platform submission; never changed afterward.
            platform(cipher, title, cancelText, callbacks, callbackExecutor) {
                val aead = VaultBiometricCipherBinding.encrypt(cipher, aad)
                VaultCiphertext(VaultTinkKeysets.wrap(root, aead, aad))
            }
        }

    override fun prepareUnwrapRoot(identity: VaultPersistentIdentity, slotId: String, generation: Long,
        persistedEnvelope: VaultCiphertext, title: CharSequence, cancelText: CharSequence,
        context: VaultOperationContext, binding: VaultBiometricBinding,
        completed: (Outcome<KeysetHandle>) -> Unit): Pending {
        // Snapshot bytes are copied at prepare, before any callback or alias access can happen.
        val envelope = persistedEnvelope.copyBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(envelope)
        return prepare(identity, slotId, generation, context, binding, completed) { callbacks, callbackExecutor ->
            require(binding.purpose == VaultBiometricPurpose.ENROLL_VERIFY ||
                binding.purpose == VaultBiometricPurpose.UNLOCK) { "biometric-purpose" }
            require(envelope.size <= 16_384 && MessageDigest.isEqual(digest,
                MessageDigest.getInstance("SHA-256").digest(envelope))) { "envelope-binding" }
            VaultTinkKeysets.requireCanonicalEncrypted(envelope)
            val aad = VaultCryptoWire.aad(identity, VaultAadPurpose.BIOMETRIC_ROOT,
                VaultSlotKind.BIOMETRIC, slotId, generation)
            // Bounded outer parse provides untrusted IV only. The same immutable envelope goes to Tink.
            val ciphertext = BinaryKeysetReader.withBytes(envelope).readEncrypted().encryptedKeyset.toByteArray()
            require(ciphertext.size in 28..16_384) { "envelope-size" }
            val cipher = keystore.biometricDecryptCipher(identity.biometricKeyAlias, ciphertext)
            cipher.updateAAD(aad)
            platform(cipher, title, cancelText, callbacks, callbackExecutor) {
                val aead = VaultBiometricCipherBinding.decrypt(cipher, aad, ciphertext)
                VaultTinkKeysets.unwrap(envelope, aead, aad)
            }
        }
    }

    /** Legacy entry points are intentionally unusable without the current same-owner binding. */
    fun wrapRoot(identity: VaultPersistentIdentity, slotId: String, generation: Long, root: KeysetHandle,
        title: CharSequence, cancelText: CharSequence, context: VaultOperationContext,
        completed: (Outcome<VaultCiphertext>) -> Unit, binding: VaultBiometricBinding? = null): Pending =
        prepareWrapRoot(identity, slotId, generation, root, title, cancelText, context,
            binding ?: throw VaultOperationException(VaultFailure.REJECTED), completed).also { it.start() }

    fun unwrapRoot(identity: VaultPersistentIdentity, slotId: String, generation: Long,
        persistedEnvelope: VaultCiphertext, title: CharSequence, cancelText: CharSequence,
        context: VaultOperationContext, completed: (Outcome<KeysetHandle>) -> Unit,
        binding: VaultBiometricBinding? = null): Pending =
        prepareUnwrapRoot(identity, slotId, generation, persistedEnvelope, title, cancelText, context,
            binding ?: throw VaultOperationException(VaultFailure.REJECTED), completed).also { it.start() }

    private fun <T> prepare(identity: VaultPersistentIdentity, slotId: String, generation: Long,
        context: VaultOperationContext, binding: VaultBiometricBinding, completed: (Outcome<T>) -> Unit,
        factory: (VaultBiometricPromptCallbacks, Executor) -> VaultBiometricPlatformSession<T>): Pending {
        // Value validation only. The existing authentication owner validates snapshot/attempt/host.
        VaultCryptoWire.requireIdentity(identity)
        require(slotId.length in 1..128 && generation > 0) { "slot-binding" }
        return Pending(VaultBiometricPromptOperation(context, clock, dispatcher, executor, scheduler,
            { binding.requireCurrent() }, completed, factory))
    }

    private fun <T> platform(cipher: Cipher, title: CharSequence, cancelText: CharSequence,
        callbacks: VaultBiometricPromptCallbacks, callbackExecutor: Executor,
        complete: () -> T): VaultBiometricPlatformSession<T> {
        val cancellation = CancellationSignal()
        val submitted = BiometricPrompt.CryptoObject(cipher)
        val prompt = BiometricPrompt.Builder(androidContext).setTitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton(cancelText, callbackExecutor) { _, _ -> callbacks.negativeDismissed() }.build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                callbacks.succeeded(result.cryptoObject, result.cryptoObject?.cipher,
                    result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = callbacks.error(when (errorCode) {
                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT -> Failure.TEMPORARY_LOCKOUT
                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> Failure.PERMANENT_LOCKOUT
                BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED -> Failure.CANCELLED
                BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> Failure.TIMEOUT
                else -> Failure.SLOT_UNAVAILABLE
            }) // Never retain or log platform error text.
            override fun onAuthenticationFailed() = callbacks.scanFailed()
        }
        return object : VaultBiometricPlatformSession<T> {
            override val cryptoObject: Any = submitted
            override val preparedCipher: Any = cipher
            override fun start() = prompt.authenticate(submitted, cancellation, callbackExecutor, callback)
            override fun cancel() = cancellation.cancel()
            override fun finish(): T = complete()
        }
    }
}

/** One-direction operation AEAD. No key extraction, fallback serialization or repeated doFinal.
 * Copies bind the actual Tink call to the exact AAD and bounded ciphertext prepared before prompt.
 */
internal object VaultBiometricCipherBinding {
    fun encrypt(cipher: Cipher, associatedData: ByteArray): Aead {
        val aad = associatedData.copyOf()
        val once = AtomicBoolean(false)
        return object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                require(plaintext.size <= 16_384 - 28 && MessageDigest.isEqual(aad, associatedData) &&
                    once.compareAndSet(false, true)) { "biometric-operation-binding" }
                return cipher.iv + cipher.doFinal(plaintext)
            }
            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
                throw GeneralSecurityException("biometric-direction")
        }
    }
    fun decrypt(cipher: Cipher, associatedData: ByteArray, boundedCiphertext: ByteArray): Aead {
        require(boundedCiphertext.size in 28..16_384) { "envelope-size" }
        val aad = associatedData.copyOf()
        val ciphertext = boundedCiphertext.copyOf()
        val once = AtomicBoolean(false)
        return object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
                throw GeneralSecurityException("biometric-direction")
            override fun decrypt(input: ByteArray, associatedData: ByteArray): ByteArray {
                require(MessageDigest.isEqual(ciphertext, input) && MessageDigest.isEqual(aad, associatedData) &&
                    once.compareAndSet(false, true)) { "biometric-operation-binding" }
                return cipher.doFinal(input, 12, input.size - 12)
            }
        }
    }
}

/** The only fake boundary: production supplies the real prompt, signal and current Cipher. */
internal interface VaultBiometricPlatformSession<T> {
    val cryptoObject: Any
    val preparedCipher: Any
    fun start()
    fun cancel()
    fun finish(): T
}
internal interface VaultBiometricPromptCallbacks {
    fun succeeded(cryptoObject: Any?, cipher: Any?, biometric: Boolean)
    fun error(failure: AndroidVaultBiometric.Failure)
    fun negativeDismissed()
    fun scanFailed()
}

/** Serializes actual authenticate and signal.cancel even with an inline/reentrant executor. */
internal class VaultBiometricSerialDispatcher(private val executor: Executor) {
    private class Task(val run: () -> Unit, val rejected: () -> Unit)
    private val lock = Any()
    private val queue = ArrayDeque<Task>()
    private var running = false
    fun dispatch(run: () -> Unit, rejected: () -> Unit) {
        val launch = synchronized(lock) {
            queue.addLast(Task(run, rejected))
            if (running) false else { running = true; true }
        }
        if (!launch) return
        try { executor.execute { drain() } }
        catch (_: Exception) {
            val lost = synchronized(lock) { queue.toList().also { queue.clear(); running = false } }
            lost.forEach { it.rejected() }
        }
    }
    private fun drain() {
        while (true) {
            val task = synchronized(lock) {
                if (queue.isEmpty()) { running = false; null } else queue.removeFirst()
            } ?: return
            try { task.run() } catch (_: Exception) { /* Already entered: never report a scheduling rejection twice. */ }
        }
    }
}

/** Actual production lifecycle, exercised with fixed callbacks in JVM tests. No fake authority.
 * Drain means observed platform termination plus app-owned work retirement, never HAL erasure.
 */
internal class VaultBiometricPromptOperation<T>(
    private val context: VaultOperationContext,
    private val clock: VaultMonotonicClock,
    private val dispatcher: VaultBiometricSerialDispatcher,
    private val continuationExecutor: Executor,
    private val scheduler: ScheduledExecutorService,
    private val requireCurrent: () -> Unit,
    private val completed: (AndroidVaultBiometric.Outcome<T>) -> Unit,
    private val factory: (VaultBiometricPromptCallbacks, Executor) -> VaultBiometricPlatformSession<T>,
) : AutoCloseable {
    private val lock = Any()
    private var phase = AndroidVaultBiometric.Phase.PREPARED
    private var submission = AndroidVaultBiometric.Submission.NOT_STARTED
    private var started = false
    private var queuedStart = false
    private var inSubmission = false
    private var enteredPlatform = false
    private var terminal = false
    private var revoked = false
    private var resultClaimed = false
    private var ownedWork = 0
    private var session: VaultBiometricPlatformSession<T>? = null
    private var bounded: VaultOperationContext? = null
    private var timer: ScheduledFuture<*>? = null
    private val drainListeners = mutableListOf<() -> Unit>()

    fun phase() = synchronized(lock) { phase }
    fun submission() = synchronized(lock) { submission }
    fun isPlatformTerminal() = synchronized(lock) { terminal }
    /** A narrow display-focus exception only. Never authentication or cleanup authority. */
    fun ownsSystemPromptFocus(): Boolean = synchronized(lock) {
        !revoked && !terminal && phase in listOf(AndroidVaultBiometric.Phase.SUBMITTING,
            AndroidVaultBiometric.Phase.SUBMITTED) && submission in listOf(AndroidVaultBiometric.Submission.IN_FLIGHT,
            AndroidVaultBiometric.Submission.CONFIRMED)
    }
    fun observeDrain(): ExportRelease = synchronized(lock) {
        if (drained()) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
    }
    private fun drained() = started && !queuedStart && !inSubmission && ownedWork == 0 &&
        (terminal || !enteredPlatform) && submission != AndroidVaultBiometric.Submission.UNCONFIRMED

    fun onDrain(listener: () -> Unit) {
        val ready = synchronized(lock) { if (drained()) true else { drainListeners += listener; false } }
        if (ready) notifyListener(listener)
    }
    private fun notifyListener(listener: () -> Unit) {
        try { continuationExecutor.execute { try { listener() } catch (_: Exception) { /* Owner polls too. */ } } }
        catch (_: Exception) { /* No success is fabricated if the owner's queue has closed. */ }
    }
    private fun changed() {
        val listeners = synchronized(lock) {
            if (drained()) drainListeners.toList().also { drainListeners.clear(); session = null }
            else emptyList()
        }
        listeners.forEach(::notifyListener)
    }
    private fun active() {
        context.checkActive()
        bounded?.checkActive()
        requireCurrent()
        if (synchronized(lock) { revoked }) throw VaultOperationException(VaultFailure.CANCELLED)
    }
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true; queuedStart = true
        }
        try {
            active()
            bounded = context.bounded(VaultRuntimeLimits.PROMPT_MILLIS)
            val now = clock.elapsedMillis()
            val end = checkNotNull(bounded).deadlineElapsedMillis
            if (now < 0 || now >= end) throw VaultOperationException(VaultFailure.DEADLINE)
            val scheduled = scheduler.schedule({ revoke(AndroidVaultBiometric.Failure.TIMEOUT) }, end - now, TimeUnit.MILLISECONDS)
            synchronized(lock) { timer = scheduled; if (terminal || revoked) scheduled.cancel(false) }
        } catch (error: Exception) {
            revoke(classify(error))
            synchronized(lock) { queuedStart = false }
            changed(); return
        }
        dispatcher.dispatch({ submit() }, {
            revoke(AndroidVaultBiometric.Failure.SLOT_UNAVAILABLE)
            synchronized(lock) { queuedStart = false }
            changed()
        })
    }
    private fun submit() {
        try {
            active()
            val prepared = factory(callbacks, callbackExecutor)
            active()
            synchronized(lock) {
                session = prepared
                if (revoked) return@synchronized
                enteredPlatform = true; inSubmission = true
                submission = AndroidVaultBiometric.Submission.IN_FLIGHT
                phase = AndroidVaultBiometric.Phase.SUBMITTING
            }
            if (!synchronized(lock) { inSubmission }) return
            // No signal cancellation can execute until this call has exited on the same dispatcher.
            try {
                prepared.start()
                synchronized(lock) { submission = AndroidVaultBiometric.Submission.CONFIRMED }
            } catch (_: Exception) {
                synchronized(lock) { submission = if (terminal) AndroidVaultBiometric.Submission.CONFIRMED
                    else AndroidVaultBiometric.Submission.UNCONFIRMED }
                revoke(AndroidVaultBiometric.Failure.SLOT_UNAVAILABLE)
            } finally {
                synchronized(lock) {
                    inSubmission = false
                    phase = if (terminal) AndroidVaultBiometric.Phase.TERMINAL_OBSERVED else AndroidVaultBiometric.Phase.SUBMITTED
                }
            }
        } catch (error: Exception) { revoke(classify(error)) }
        finally {
            synchronized(lock) { queuedStart = false }
            changed()
        }
    }
    override fun close() = revoke(AndroidVaultBiometric.Failure.CANCELLED)
    private fun revoke(failure: AndroidVaultBiometric.Failure) {
        // Hold a work reservation through failure/cancel enqueue, so another thread cannot observe
        // a false empty window between revocation and the owned continuations it schedules.
        synchronized(lock) { ownedWork++ }
        try { revokeAndEnqueue(failure) } finally { retireWork() }
    }
    private fun revokeAndEnqueue(failure: AndroidVaultBiometric.Failure) {
        val cancel = synchronized(lock) {
            val first = !revoked
            revoked = true; timer?.cancel(false)
            if (!started) started = true // Explicit close of PREPARED confirms no platform call is needed.
            first && enteredPlatform && !terminal
        }
        fail(failure)
        if (cancel) {
            synchronized(lock) { ownedWork++ }
            dispatcher.dispatch({
                try { synchronized(lock) { session }?.cancel() }
                catch (_: Exception) { /* Cancellation failure cannot invent terminal evidence. */ }
                finally { retireWork() }
            }, { retireWork() })
        }
        changed()
    }
    private fun fail(failure: AndroidVaultBiometric.Failure) {
        val send = synchronized(lock) { if (resultClaimed) false else { resultClaimed = true; true } }
        if (send) continuation { completed(AndroidVaultBiometric.Outcome.Failed(failure)) }
    }
    private fun continuation(onRejected: () -> Unit = {}, work: () -> Unit) {
        synchronized(lock) { ownedWork++ }
        try { continuationExecutor.execute {
            try { work() } catch (_: Exception) { /* Owner rejection is not successful authentication. */ }
            finally { retireWork() }
        } } catch (_: Exception) { try { onRejected() } finally { retireWork() } }
    }
    private fun retireWork() { synchronized(lock) { ownedWork-- }; changed() }
    private val callbackExecutor = Executor { task ->
        synchronized(lock) { ownedWork++ }
        dispatcher.dispatch({
            try { task.run() } catch (_: Exception) { revoke(AndroidVaultBiometric.Failure.SLOT_UNAVAILABLE) }
            finally { retireWork() }
        }, {
            revoke(AndroidVaultBiometric.Failure.SLOT_UNAVAILABLE); retireWork()
        })
    }
    private fun callback(action: () -> Unit) {
        synchronized(lock) { ownedWork++ }
        try { action() } finally { retireWork() }
    }
    private fun terminal(): Boolean = synchronized(lock) {
        if (!enteredPlatform || terminal) false else {
            terminal = true; phase = AndroidVaultBiometric.Phase.TERMINAL_OBSERVED
            if (!inSubmission) submission = AndroidVaultBiometric.Submission.CONFIRMED
            timer?.cancel(false); true
        }
    }
    private val callbacks = object : VaultBiometricPromptCallbacks {
        override fun succeeded(cryptoObject: Any?, cipher: Any?, biometric: Boolean) = callback {
            if (!terminal()) return@callback
            val prepared = synchronized(lock) { session }
            if (prepared == null || cryptoObject !== prepared.cryptoObject || cipher !== prepared.preparedCipher || !biometric) {
                revoke(AndroidVaultBiometric.Failure.REJECTED); return@callback
            }
            continuation(onRejected = { revoke(AndroidVaultBiometric.Failure.SLOT_UNAVAILABLE) }) {
                try {
                    active()
                    val value = prepared.finish() // Actual Cipher.doFinal and Tink profile validation.
                    active() // A cancellation during crypto discards the unregistered value.
                    val accept = synchronized(lock) {
                        if (revoked || resultClaimed) false else { resultClaimed = true; true }
                    }
                    if (accept) completed(AndroidVaultBiometric.Outcome.Completed(value))
                    // KeysetHandle has no destroy API. Dropping a rejected reference is not secure erasure.
                } catch (error: Exception) { revoke(classify(error)) }
            }
        }
        override fun error(failure: AndroidVaultBiometric.Failure) = callback {
            if (terminal()) revoke(failure)
        }
        override fun negativeDismissed() = callback {
            // API31's actual negative listener is dialog-dismissal evidence, even with no error callback.
            if (terminal()) revoke(AndroidVaultBiometric.Failure.CANCELLED)
        }
        override fun scanFailed() = callback { /* Nonterminal scan: same prompt and reservation. */ }
    }
    private fun classify(error: Exception): AndroidVaultBiometric.Failure = when (error) {
        is KeyPermanentlyInvalidatedException -> AndroidVaultBiometric.Failure.SLOT_UNAVAILABLE
        is VaultOperationException -> when (error.category) {
            VaultFailure.DEADLINE -> AndroidVaultBiometric.Failure.TIMEOUT
            VaultFailure.CANCELLED -> AndroidVaultBiometric.Failure.CANCELLED
            VaultFailure.UNAVAILABLE, VaultFailure.UNSUPPORTED -> AndroidVaultBiometric.Failure.SLOT_UNAVAILABLE
            else -> AndroidVaultBiometric.Failure.REJECTED
        }
        is GeneralSecurityException, is IllegalArgumentException -> AndroidVaultBiometric.Failure.REJECTED
        else -> AndroidVaultBiometric.Failure.SLOT_UNAVAILABLE
    }
}
