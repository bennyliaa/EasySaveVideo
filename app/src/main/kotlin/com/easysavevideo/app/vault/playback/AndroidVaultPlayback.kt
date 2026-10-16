package com.easysavevideo.app.vault.playback

import android.content.Context
import android.graphics.Color
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoTimeoutException
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import com.easysavevideo.app.vault.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** No Activity, auth/store/root creation, URI authority, disk cache or system media session. */
internal class AndroidVaultPlaybackFactory(context: Context) : VaultPlaybackPlatformFactory {
    private val context = context.applicationContext
    override fun create(ticket: VaultPlaybackTicket, read: VaultPlaybackReadPort, isCurrent: () -> Boolean,
        changed: (VaultPlaybackView) -> Unit, progressed: () -> Unit): VaultPreparedPlayback =
        AndroidVaultPlayback(context, ticket, read, isCurrent, changed, progressed).also { it.start() }
}

private class AndroidBorrowedVaultSurface(val ticket: VaultPlaybackTicket, val holder: SurfaceHolder,
    val surface: Surface, val generation: Long) : VaultPlaybackSurface {
    // The holder owns the Surface. This adapter never calls Surface.release().
    val destroyed = AtomicBoolean(false)
}

/** A stable Main-owned child of the existing protected root, retained across route/status changes. */
internal class AndroidVaultPlaybackSurfaceSlot(context: Context, private val ticket: VaultPlaybackTicket,
    private val prepared: VaultPreparedPlayback, private val current: () -> Boolean,
    private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    val view = FrameLayout(context)
    private val surfaceView = SurfaceView(context)
    private val opaque = View(context)
    private val changePosted = AtomicBoolean(false)
    private var generation = 0L
    private var surface: AndroidBorrowedVaultSurface? = null
    private var retired = false
    private var detachRequested = false
    private var detachReturned = false
    private var holderDestroyed = false
    private var removed = false
    private var failed = false
    init {
        check(Looper.myLooper() === Looper.getMainLooper())
        surfaceView.setSecure(true) // Before attachment / holder creation; never top or media overlay.
        view.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        opaque.setBackgroundColor(Color.BLACK) // Security barrier, not a business visual theme.
        opaque.isClickable = true
        opaque.visibility = View.GONE
        view.addView(opaque, FrameLayout.LayoutParams(-1, -1))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val exact = AndroidBorrowedVaultSurface(ticket, holder, holder.surface, ++generation)
                if (surface != null) failed = true
                surface = exact
                holderDestroyed = false
                if (retired || !current()) {
                    cover()
                    requestRetirement()
                } else prepared.attachSurface(exact)
                notifyChanged()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cover() // Every system destruction revokes publication before any asynchronous progress.
                val exact = surface
                if (exact == null || exact.holder !== holder || exact.surface !== holder.surface) {
                    failed = true
                } else {
                    exact.destroyed.set(true)
                    holderDestroyed = true
                    // System destruction can precede our detach; it never proves player detach.
                }
                requestRetirement()
                notifyChanged()
            }
        })
        if (!current()) { cover(); requestRetirement() }
    }
    private fun notifyChanged() {
        if (changePosted.compareAndSet(false, true)) main.post {
            changePosted.set(false)
            try { changed() } catch (_: Throwable) { failed = true }
        }
    }
    fun cover() {
        check(Looper.myLooper() === Looper.getMainLooper())
        ticket.host.revoke()
        retired = true
        opaque.visibility = View.VISIBLE
        opaque.bringToFront()
        prepared.revoke()
        // Deliberately leave SurfaceView visibility and parent untouched until real detach.
    }
    fun requestRetirement() {
        check(Looper.myLooper() === Looper.getMainLooper())
        cover()
        if (detachRequested) return
        val exact = surface
        if (exact == null) {
            // No holder was created. Prevent later creation by removing before first attach.
            if (!surfaceView.holder.surface.isValid && !surfaceView.isAttachedToWindow) {
                view.removeView(surfaceView)
                removed = true
                holderDestroyed = true
                detachReturned = true
                notifyChanged()
                return
            }
            // Attached view without callback: retain it until an exact holder arrives.
            return
        }
        detachRequested = true
        prepared.detachSurface(exact) { ok ->
            main.post {
                if (surface !== exact) { failed = true; notifyChanged(); return@post }
                detachReturned = ok
                if (!ok) { failed = true; notifyChanged(); return@post }
                // Main never synchronously calls Player.clear/release or joins a thread.
                surfaceView.visibility = View.GONE
                view.removeView(surfaceView)
                removed = surfaceView.parent == null
                notifyChanged()
            }
        }
    }
    fun observeDrain(): VaultPlaybackDrain {
        check(Looper.myLooper() === Looper.getMainLooper())
        return when {
            failed -> VaultPlaybackDrain.UNCONFIRMED
            !retired -> VaultPlaybackDrain.LIVE
            detachReturned && removed && holderDestroyed -> VaultPlaybackDrain.RELEASED
            else -> VaultPlaybackDrain.RETIRING
        }
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private class AndroidVaultPlayback(private val context: Context, private val ticket: VaultPlaybackTicket,
    private val read: VaultPlaybackReadPort, private val isCurrent: () -> Boolean,
    private val changed: (VaultPlaybackView) -> Unit, private val progressed: () -> Unit) : VaultPreparedPlayback {
    private val ledger = VaultPlaybackRetirement()
    private val revoked = AtomicBoolean(false)
    private val progressPosted = AtomicBoolean(false)
    private val retainedOutputs = mutableListOf<AudioOutput>()
    private val retainedSinks = mutableListOf<AudioSink>()
    private val retainedRenderers = mutableListOf<Renderer>()
    private val trackedRenderers = mutableListOf<TrackingRenderer>()
    private var retainedSelector: DefaultTrackSelector? = null
    private val mailbox = Any()
    private val retirementSignal = Object()
    private val main = Handler(Looper.getMainLooper())
    private var commandThread: HandlerThread? = null
    private var playbackThread: HandlerThread? = null
    private var command: Handler? = null
    private var playback: Handler? = null
    private var player: ExoPlayer? = null // Only accessed on command looper.
    private var provider: TrackingAudioProvider? = null
    private val loaders = mutableListOf<OwnedLoader>()
    private var desiredPlaying: Boolean? = null
    private var desiredSeek: Long? = null
    private var desiredSurface: AndroidBorrowedVaultSurface? = null
    private var boundSurface: AndroidBorrowedVaultSurface? = null
    private var detachSurface: AndroidBorrowedVaultSurface? = null
    private var detachCallback: ((Boolean) -> Unit)? = null
    private var detachAttempted = false
    private var workPosted = false
    private var releaseStarted = false
    private var observerStarted = false
    private var detachTimeout = false
    private var lastPositionUpdate = Long.MIN_VALUE
    private val commandWork = Runnable { drainCommands() }
    private val positionUpdate = object : Runnable {
        override fun run() {
            if (!current()) { revoke(); return }
            publishPosition()
            command?.postDelayed(this, VaultPlaybackLimits.POSITION_UPDATE_MILLIS)
        }
    }
    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val timeout = error.cause as? ExoTimeoutException
            when (timeout?.timeoutOperation) {
                ExoTimeoutException.TIMEOUT_OPERATION_DETACH_SURFACE -> { detachTimeout = true; ledger.detachFailure() }
                ExoTimeoutException.TIMEOUT_OPERATION_RELEASE -> ledger.releaseTimeout()
                else -> ledger.playbackError() // A playback/read error is not a resource-release failure.
            }
            // Keep only a technical status; never retain/forward raw exception text or cause.
            revoke()
            signal()
        }
        override fun onPlaybackStateChanged(playbackState: Int) { publishPosition() }
        override fun onIsPlayingChanged(isPlaying: Boolean) { publishPosition() }
    }
    fun start() {
        // Every factory has an obligation before execution; failures keep the same owner.
        try {
            val commandAcquiring = ledger.acquire()
            val thread = HandlerThread("esv-vault-command")
            commandThread = thread
            thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> fail() }
            thread.start()
            val handler = Handler(thread.looper)
            synchronized(mailbox) { command = handler }
            ledger.adopted(commandAcquiring)
            val playbackAcquiring = ledger.acquire()
            val rendererThread = HandlerThread("esv-vault-playback")
            playbackThread = rendererThread
            rendererThread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> fail() }
            rendererThread.start()
            playback = Handler(rendererThread.looper)
            ledger.adopted(playbackAcquiring)
            if (!handler.post { construct() }) fail()
        } catch (_: Throwable) { fail() }
    }
    private fun current(): Boolean = !revoked.get() && isCurrent() && ticket.host.isCurrent()
    private fun assertCommand() { check(Looper.myLooper() === command?.looper && Looper.myLooper() !== Looper.getMainLooper()) }
    private fun construct() {
        assertCommand()
        if (!current()) { revoke(); return }
        val acquiring = ledger.acquire()
        try {
            val providerAcquiring = ledger.acquire()
            val actualProvider = AudioTrackAudioOutputProvider.Builder(context).build()
            val audio = TrackingAudioProvider(actualProvider)
            provider = audio
            ledger.addOwned(audio)
            ledger.adopted(providerAcquiring)
            val factory = TrackingRenderers(context, audio)
            val sourceFactory = ProgressiveMediaSource.Factory(VaultMedia3DataSource.Factory(read),
                ExtractorsFactory { arrayOf(Mp4Extractor()) })
                .setContinueLoadingCheckIntervalBytes(64 * 1024)
                .setLoadOnlySelectedTracks(true)
                .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(0) {
                    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
                    override fun getFallbackSelectionFor(options: LoadErrorHandlingPolicy.FallbackOptions,
                        info: LoadErrorHandlingPolicy.LoadErrorInfo): LoadErrorHandlingPolicy.FallbackSelection? = null
                })
                .setDownloadExecutor({ newLoader() }, { it.retire() })
            val selectorAcquiring = ledger.acquire()
            val selector = DefaultTrackSelector(context)
            retainedSelector = selector
            ledger.adopted(selectorAcquiring)
            selector.parameters = selector.buildUponParameters()
                .setTunnelingEnabled(false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_IMAGE, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_CAMERA_MOTION, true)
                .setAudioOffloadPreferences(TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build())
                .build()
            val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE).build()
            val actual = ExoPlayer.Builder(context, factory, sourceFactory)
                .setLooper(requireNotNull(command).looper)
                .setPlaybackLooper(requireNotNull(playback).looper)
                .setUsePlatformDiagnostics(false)
                .setTrackSelector(selector)
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1000, 5000, 250, 500)
                    .setTargetBufferBytes(8 * 1024 * 1024).setBackBuffer(0, false)
                    .setPrioritizeTimeOverSizeThresholds(false).build())
                .setAudioAttributes(attributes, false)
                .setHandleAudioBecomingNoisy(false)
                .setWakeMode(C.WAKE_MODE_NONE)
                .setPauseAtEndOfMediaItems(true)
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                .setName("vault-controlled-playback")
                .build()
            player = actual
            ledger.adopted(acquiring)
            actual.addListener(listener) // Retained through synchronous release/timeout delivery.
            actual.playWhenReady = false
            if (!current()) { revoke(); return }
            actual.setMediaSource(sourceFactory.createMediaSource(MediaItem.Builder().setUri(Uri.EMPTY)
                .setMediaId("vault-controlled-item").setMimeType(MimeTypes.VIDEO_MP4).build()))
            actual.prepare()
            command?.post(positionUpdate)
            synchronized(mailbox) { postWorkLocked() }
        } catch (_: Throwable) { fail() }
    }
    override fun play() = intent(true)
    override fun pause() = intent(false)
    private fun intent(value: Boolean) {
        synchronized(mailbox) { if (revoked.get()) return; desiredPlaying = value; postWorkLocked() }
    }
    override fun seekTo(positionMillis: Long) {
        if (positionMillis < 0) return
        synchronized(mailbox) { if (revoked.get()) return; desiredSeek = positionMillis; postWorkLocked() }
    }
    override fun attachSurface(surface: VaultPlaybackSurface) {
        val exact = surface as? AndroidBorrowedVaultSurface ?: run { fail(); return }
        if (exact.ticket !== ticket) { fail(); return }
        synchronized(mailbox) {
            if (revoked.get() || exact.destroyed.get()) return
            if (desiredSurface != null && desiredSurface !== exact || boundSurface != null && boundSurface !== exact) { fail(); return }
            desiredSurface = exact
            postWorkLocked()
        }
    }
    override fun detachSurface(surface: VaultPlaybackSurface, detached: (Boolean) -> Unit) {
        val exact = surface as? AndroidBorrowedVaultSurface
        if (exact == null || exact.ticket !== ticket) { ledger.detachFailure(); detached(false); return }
        synchronized(mailbox) {
            if (detachSurface != null && detachSurface !== exact) { ledger.detachFailure(); detached(false); return }
            detachSurface = exact
            if (detachAttempted) { detached(ledger.detachConfirmed()); return }
            if (detachCallback != null) { detached(false); return }
            detachCallback = detached
        }
        revoke()
    }
    override fun revoke() {
        ticket.host.revoke() // Synchronous read/publication fence, including native error paths.
        revoked.set(true)
        ledger.revoke()
        synchronized(mailbox) {
            desiredPlaying = null; desiredSeek = null; desiredSurface = null
            val handler = command
            if (handler != null && !releaseStarted) {
                handler.removeCallbacks(commandWork)
                workPosted = true
                if (!handler.postAtFrontOfQueue(commandWork)) ledger.failed()
            }
        }
        signal()
    }
    override fun readyForReadRetirement(): Boolean = ledger.resourcesReturned()
    override fun readOwnerRetired() { ledger.readOwnerRetired(); signal() }
    override fun observeDrain(): VaultPlaybackDrain = ledger.observe()
    private fun postWorkLocked() {
        if (workPosted || releaseStarted) return
        val handler = command ?: return
        workPosted = true
        if (!handler.post(commandWork)) { workPosted = false; fail() }
    }
    private fun drainCommands() {
        assertCommand()
        synchronized(mailbox) { workPosted = false }
        if (!current()) { retireOnCommand(); return }
        val actual = player ?: return
        val intent: Boolean?; val seek: Long?; val surface: AndroidBorrowedVaultSurface?
        synchronized(mailbox) {
            intent = desiredPlaying; desiredPlaying = null
            seek = desiredSeek; desiredSeek = null
            surface = desiredSurface; desiredSurface = null
        }
        try {
            // Each actual public call is fenced at execution, not just enqueue.
            if (surface != null && current() && !surface.destroyed.get() && surface.surface.isValid) {
                actual.setVideoSurface(surface.surface)
                synchronized(mailbox) { boundSurface = surface }
            }
            if (seek != null && current() && actual.isCurrentMediaItemSeekable && actual.duration >= 0) {
                actual.seekTo(seek.coerceAtMost(actual.duration))
            }
            if (intent != null && current()) {
                if (intent) {
                    // ENDED replay remains an explicit user command.
                    if (actual.playbackState == Player.STATE_ENDED) actual.seekTo(0)
                    if (current()) actual.play()
                } else actual.pause()
            }
        } catch (_: Throwable) { fail() }
    }
    private fun publishPosition() {
        assertCommand()
        if (!current()) return
        val now = SystemClock.elapsedRealtime()
        if (lastPositionUpdate != Long.MIN_VALUE && now - lastPositionUpdate < VaultPlaybackLimits.POSITION_UPDATE_MILLIS) return
        lastPositionUpdate = now
        val actual = player ?: return
        val status = when (actual.playbackState) {
            Player.STATE_ENDED -> VaultPlaybackStatus.ENDED
            Player.STATE_READY -> if (actual.isPlaying) VaultPlaybackStatus.PLAYING else VaultPlaybackStatus.PAUSED
            else -> VaultPlaybackStatus.PREPARING
        }
        changed(VaultPlaybackView(ticket, status, actual.currentPosition.coerceAtLeast(0),
            actual.duration.takeIf { it >= 0 }, actual.isCurrentMediaItemSeekable && actual.duration >= 0))
    }
    private fun retireOnCommand() {
        assertCommand()
        synchronized(mailbox) { if (releaseStarted) return; releaseStarted = true }
        revoked.set(true); ledger.revoke()
        command?.removeCallbacks(positionUpdate)
        val actual = player
        if (actual != null) {
            safely { actual.pause() }
            safely { actual.volume = 0f }
            safely { actual.stop() }
            try {
                actual.clearVideoSurface()
                if (!detachTimeout) ledger.detachReturned()
            } catch (_: Throwable) { ledger.detachFailure() }
            synchronized(mailbox) { detachAttempted = true; detachCallback?.invoke(ledger.detachConfirmed()); detachCallback = null }
            try { actual.release(); ledger.playerReleaseReturned() } catch (_: Throwable) { ledger.failed() }
            // Do not detach the listener until after release and its synchronous error notifications.
            safely { actual.removeListener(listener) }
        } else {
            // A skipped creation has no player; unresolved factory obligations still prevent drain.
            ledger.detachReturned(); ledger.playerReleaseReturned()
            synchronized(mailbox) { detachAttempted = true; detachCallback?.invoke(true); detachCallback = null }
            // A failed Player factory may still have returned sinks/selector to our hooks.
            for (renderer in trackedRenderers.toList()) {
                safely { if (renderer.state == Renderer.STATE_STARTED) renderer.stop() }
                safely { if (renderer.state != Renderer.STATE_DISABLED) renderer.disable() }
                safely { renderer.reset() }
                safely { renderer.release() }
            }
            for (sink in retainedSinks.toList()) { safely { sink.reset() }; safely { sink.release() } }
            retainedSelector?.let { safely { it.release() } }
            provider?.let { safely { it.release() } }
        }
        val handler = playback
        if (handler == null || !handler.post { ledger.playbackBarrierReturned(); signal() }) ledger.failed()
        main.postDelayed({ ledger.deadline(); signal() }, VaultPlaybackLimits.RETIREMENT_MILLIS)
        startRetirementObserver()
        signal()
    }
    private fun safely(action: () -> Unit) { try { action() } catch (_: Throwable) { ledger.failed() } }
    private fun fail() { ledger.failed(); revoke() }
    private fun signal() {
        synchronized(retirementSignal) { retirementSignal.notifyAll() }
        if (progressPosted.compareAndSet(false, true)) {
            if (!main.post { progressPosted.set(false); try { progressed() } catch (_: Throwable) { ledger.failed() } }) {
                progressPosted.set(false)
                ledger.failed()
            }
        }
    }
    private fun startRetirementObserver() {
        synchronized(mailbox) { if (observerStarted) return; observerStarted = true }
        try {
            Thread({
                try {
                    // First barrier guarantees no future period/output acquisition is admitted.
                    synchronized(retirementSignal) {
                        while (!ledger.controlBarrierConfirmed()) retirementSignal.wait()
                    }
                    val exactLoaders = synchronized(loaders) { loaders.toList() }
                    for (loader in exactLoaders) {
                        // Media3's releaser owns orderly shutdown; cancellation alone does not do it.
                        while (!loader.awaitTermination(1, TimeUnit.DAYS)) Unit
                        ledger.loaderTerminated(loader, !loader.taskFailure.get())
                        signal()
                    }
                    synchronized(retirementSignal) {
                        while (!ledger.mayJoinLoopers()) retirementSignal.wait()
                    }
                    // Successful controls + exact audio handoff + loader returns precede quit/join.
                    val playbackOwner = playbackThread
                    val commandOwner = commandThread
                    if (playbackOwner == null || commandOwner == null) { ledger.failed(); signal(); return@Thread }
                    if (!playbackOwner.quitSafely() || !commandOwner.quitSafely()) { ledger.failed(); signal(); return@Thread }
                    playbackOwner.join()
                    commandOwner.join()
                    if (!playbackOwner.isAlive && !commandOwner.isAlive) ledger.loopersJoined() else ledger.failed()
                    signal()
                } catch (_: Throwable) { ledger.failed(); signal() }
            }, "esv-vault-retirement").apply { isDaemon = true }.start()
        } catch (_: Throwable) { ledger.failed(); signal() }
    }
    private fun newLoader(): OwnedLoader {
        if (!current() || !ledger.canAcquireLoader()) throw IllegalStateException("vault_playback_loader_denied")
        val acquiring = ledger.acquire()
        try {
            val executor = OwnedLoader()
            synchronized(loaders) { loaders += executor }
            ledger.addLoader(executor)
            ledger.adopted(acquiring)
            if (!current()) executor.retire()
            return executor
        } catch (_: Throwable) { fail(); throw IllegalStateException("vault_playback_loader_failed") }
    }
    private inner class OwnedLoader(val taskFailure: AtomicBoolean = AtomicBoolean(false)) : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(8), ThreadFactory { task -> Thread(task, "esv-vault-loader").apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> taskFailure.set(true); fail() }
        } }) {
        fun retire() { shutdown(); signal() }
        override fun afterExecute(task: Runnable, error: Throwable?) {
            super.afterExecute(task, error)
            var failed = error != null
            if (task is Future<*> && task.isDone && !task.isCancelled) {
                try { task.get() } catch (_: Throwable) { failed = true }
            }
            if (failed) { taskFailure.set(true); fail() }
        }
        override fun execute(command: Runnable) {
            try { super.execute(command) } catch (_: Throwable) { taskFailure.set(true); fail(); throw IllegalStateException("vault_playback_loader_rejected") }
        }
    }
    private inner class TrackingAudioProvider(delegate: AudioOutputProvider) : ForwardingAudioOutputProvider(delegate) {
        private val releaseOnce = AtomicBoolean(false)
        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
            if (!current()) throw AudioOutputProvider.InitializationException()
            if (!ledger.canAcquireAudio() || config.isOffload || config.isTunneling ||
                config.audioAttributes.allowedCapturePolicy != C.ALLOW_CAPTURE_BY_NONE) {
                ledger.playbackError(); revoke(); throw AudioOutputProvider.InitializationException()
            }
            val acquiring = ledger.acquire()
            var output: AudioOutput? = null
            var releaseAttempted = false
            try {
                output = super.getAudioOutput(config)
                synchronized(retainedOutputs) { retainedOutputs += output }
                val exact = output as? AudioTrackAudioOutput ?: run {
                    // Retain the unresolved obligation; unsupported types never become a fallback.
                    releaseAttempted = true
                    safely { output.release() }
                    throw AudioOutputProvider.InitializationException()
                }
                val track = exact.audioTrack
                val initialized = track.state == AudioTrack.STATE_INITIALIZED
                ledger.addAudio(exact, track, initialized)
                ledger.adopted(acquiring)
                exact.addListener(object : AudioOutput.Listener {
                    override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) = Unit
                    override fun onOffloadDataRequest() = Unit
                    override fun onOffloadPresentationEnded() = Unit
                    override fun onUnderrun() = Unit
                    override fun onReleased() {
                        // Listener delivery is the same-output Handler handoff after async release.
                        val observe = Runnable {
                            try { ledger.audioReleased(exact, track, track.state == AudioTrack.STATE_UNINITIALIZED) }
                            catch (_: Throwable) { ledger.failed() }
                            signal()
                        }
                        val handler = playback
                        if (Looper.myLooper() === handler?.looper) observe.run()
                        else if (handler?.post(observe) != true) { ledger.failed(); signal() }
                    }
                })
                if (!initialized || !current()) {
                    // Late result belongs to this ticket; it is never handed to a new/live sink.
                    releaseAttempted = true
                    exact.release()
                    throw AudioOutputProvider.InitializationException()
                }
                return exact
            } catch (_: Throwable) {
                if (output != null && !releaseAttempted) safely { output.release() }
                fail()
                throw AudioOutputProvider.InitializationException() // No private/raw cause.
            }
        }
        override fun release() {
            if (!releaseOnce.compareAndSet(false, true)) return
            try { super.release(); ledger.ownedReleased(this) } catch (_: Throwable) {
                ledger.failed(); throw IllegalStateException("vault_playback_audio_provider_failed")
            } finally { signal() }
        }
    }
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.ExperimentalApi::class])
    private inner class TrackingRenderers(context: Context, private val audio: AudioOutputProvider) : DefaultRenderersFactory(context) {
        init {
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
            setEnableDecoderFallback(false)
            experimentalSetEnableMediaCodecVideoRendererPrewarming(false)
        }
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enablePlaybackParams: Boolean): AudioSink {
            val acquiring = ledger.acquire()
            try {
                val sink = DefaultAudioSink.Builder(context).setAudioOutputProvider(audio)
                    .setEnableFloatOutput(false).setEnableAudioOutputPlaybackParameters(false).build()
                // Retain before adoption: the later renderer constructor is permitted to fail.
                retainedSinks += sink
                ledger.adopted(acquiring)
                return sink
            } catch (_: Throwable) { fail(); throw IllegalStateException("vault_playback_sink_failed") }
        }
        override fun createRenderers(handler: Handler, video: VideoRendererEventListener, audio: AudioRendererEventListener,
            text: TextOutput, metadata: MetadataOutput): Array<Renderer> {
            val acquiring = ledger.acquire()
            try {
                val exact = super.createRenderers(handler, video, audio, text, metadata)
                retainedRenderers.addAll(exact)
                val tracked = exact.map { renderer ->
                    ledger.addRenderer(renderer)
                    TrackingRenderer(renderer).also { trackedRenderers += it }
                }.toTypedArray<Renderer>()
                ledger.adopted(acquiring)
                return tracked
            } catch (_: Throwable) { fail(); throw IllegalStateException("vault_playback_renderer_factory_failed") }
        }
        override fun createSecondaryRenderer(renderer: Renderer, handler: Handler, video: VideoRendererEventListener,
            audio: AudioRendererEventListener, text: TextOutput, metadata: MetadataOutput): Renderer? = null
        override fun buildTextRenderers(context: Context, output: TextOutput, looper: Looper, mode: Int, renderers: ArrayList<Renderer>) = Unit
        override fun buildMetadataRenderers(context: Context, output: MetadataOutput, looper: Looper, mode: Int, renderers: ArrayList<Renderer>) = Unit
        override fun buildCameraMotionRenderers(context: Context, mode: Int, renderers: ArrayList<Renderer>) = Unit
        override fun buildImageRenderers(context: Context, renderers: ArrayList<Renderer>) = Unit
    }
    private inner class TrackingRenderer(private val exact: Renderer) : ForwardingRenderer(exact) {
        override fun enable(configuration: RendererConfiguration, formats: Array<Format>, stream: SampleStream,
            positionUs: Long, joining: Boolean, mayRenderStartOfStream: Boolean, startPositionUs: Long,
            offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
            ledger.rendererEnabled(exact)
            try { super.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId) }
            catch (_: Throwable) { fail(); throw IllegalStateException("vault_playback_renderer_enable_failed") }
        }
        override fun disable() = rendererCall({ super.disable() }, { ledger.rendererDisabled(exact) })
        override fun reset() = rendererCall({ super.reset() }, { ledger.rendererReset(exact) })
        override fun release() = rendererCall({ super.release() }, { ledger.rendererReleased(exact) })
        private fun rendererCall(action: () -> Unit, returned: () -> Unit) {
            try { action(); returned() } catch (_: Throwable) { fail(); throw IllegalStateException("vault_playback_renderer_retirement_failed") }
            finally { signal() }
        }
    }
}
