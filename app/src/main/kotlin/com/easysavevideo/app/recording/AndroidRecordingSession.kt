package com.easysavevideo.app.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.easysavevideo.recording.*
import com.easysavevideo.app.recording.nativeregion.*
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Original platform adapter. Call run once on the application's single admitted native worker,
 * after foreground promotion and per-session user consent. This object owns the supplied projection.
 * No path, permission Intent, Activity, microphone, network source, or second display is used here.
 *
 * Control methods never call native APIs. All codec, audio, mux and descriptor operations belong to
 * run's owner. The caller must bound waiting separately: a native call can ignore thread interruption.
 * The application-lifetime lease remains occupied until cleanup actually confirms release.
 */
class AndroidRecordingSession(
    context: Context,
    private val projection: MediaProjection,
    descriptor: CaptureDescriptor,
    private val limits: RecordingLimits,
    private val lease: RecordingNativeLease,
    private val openWriter: () -> FileOutputStream,
    private val retainNativeWriter: () -> AutoCloseable,
    private val availableBytes: () -> Long,
    private val onProgress: (RecordingCaptureProgress) -> Unit = {},
    private val segmentOutputs: NativeRecordingSegmentOutputs? = null,
    private val previewPort: NativeRegionPreviewPort? = null,
) {
    private val context = context.applicationContext
    @Volatile private var descriptor = descriptor
    @Volatile private var previewAdmissionComplete = false
    private val capturedContentVisible = AtomicReference<Boolean?>()
    private var previewSnapshot: RegionPreviewSnapshot? = null
    private var firstRecordingFrameUs = 0L
    private val previewStopSignal = RegionPreviewStopSignal()
    private val used = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val stopReason = AtomicReference<RecordingStopReason?>()
    private val controlFailure = AtomicReference<RecordingError?>()
    private val controls = RecordingControlMailbox()
    private val capturedSize = AtomicReference<Pair<Int, Int>?>()
    private val sourceConfirmedAtNs = AtomicLong(0)
    private var regionObservation: CaptureGeometryObservation? = null
    private var observedDisplayGeometry: CaptureDisplayGeometry? = null
    private var regionGl: RegionGlCapture? = null
    private var currentOutput: NativeRecordingOutput? = null
    private var segmentIndex = 0
    private var segmentOpen = false
    private var segmentClosed = false
    private var pausedBetweenSegments = false
    private var cumulativeDurationUs = 0L
    private var cumulativeBytes = 0L
    private var effectiveResumes = 0
    private var segmentStartedUs = 0L
    private var segmentResumePending = false
    private var lastClosedExpectation: RecordingArtifactExpectation? = null
    private val observedVideoTimes = ArrayList<Long>()
    private val measuredVideoTail = MeasuredVideoTail<RecordingPendingSample>(limits.maxPendingBytes)
    private val measuredAudioTail = MeasuredAudioTail<RecordingPendingSample>(limits.maxPendingBytes, limits.maxPendingSamples)
    private var cumulativeVideoFrames = 0L
    private var nativeReleaseUnconfirmed = false
    @Volatile private var ended = false
    @Volatile private var state = RecordingState.PREPARING
    private var callbackRegistered = false
    private var writer: FileOutputStream? = null
    private var descriptorFence: AutoCloseable? = null
    private var muxer: MediaMuxer? = null
    private var muxerConstructionUnconfirmed = false
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var videoStarted = false
    private var audioCodecStarted = false
    private var audioRecordStarted = false
    private var muxStarted = false
    private var muxStopped = false
    private var videoEos = false
    private var audioEos = false
    private var audioInputEos = false
    private var finalizing = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var videoProfile: CodecProfileSelector.Video? = null
    private var audioProfile: CodecProfileSelector.Audio? = null
    private var actualProfile: RecordingProfile? = null
    private var audioStatus = if (descriptor.audioChoice == RecordingAudioChoice.NONE)
        RecordingAudioStatus.NOT_REQUESTED else RecordingAudioStatus.INITIALIZING
    private var timeline: RecordingTimeline? = null
    private var videoStats = RecordingMuxTrack(RecordingTrack.VIDEO, limits.maxResumeBoundaries)
    private var audioStats = RecordingMuxTrack(RecordingTrack.AUDIO)
    private val pending = ArrayDeque<RecordingPendingSample>()
    private var pendingBytes = 0L
    private var pendingResumeBoundaries = 0
    private var payloadBytes = 0L
    private var finalBytes = 0L
    private var captureStartedUs = 0L
    private var pausedAtUs: Long? = null
    private var audioInterrupted = false
    private var nonSilentPcm = false
    private var pcmFramesRead = 0L
    private var pcmFilled = 0
    private var pcm = ShortArray(0)
    private var pcmStartFrame = 0L
    private var pcmWaitingSinceUs = 0L
    private var audioClockBaseUs: Long? = null
    private var lastAudioInputUs = -1L
    private var audioBufferFrames = 0
    private var audioTimestampMissingSinceUs = 0L
    private var lastPcmReadUs = 0L
    private val audioTimestamp = AudioTimestamp()
    private var initialWidth = 0
    private var initialHeight = 0
    private var initialRotation = 0
    private var sourceDisplay: Display? = null
    private var windowContext: Context? = null
    private var lastEnvironmentCheckUs = 0L
    private var lastProgressUs = 0L
    private var finalizeDeadlineUs = Long.MAX_VALUE

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { if (!ended) stop(RecordingStopReason.SYSTEM_STOPPED) }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            capturedContentVisible.set(isVisible)
            if (!ended && previewPort != null && !previewAdmissionComplete && !isVisible)
                stop(RecordingStopReason.SYSTEM_STOPPED)
        }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (ended) return
            if (width <= 0 || height <= 0) { stop(RecordingStopReason.SIZE_CHANGED); return }
            val next = width to height
            descriptor.regionCaptureRecipe?.let { recipe ->
                if (next != (recipe.source.width to recipe.source.height)) {
                    stop(RecordingStopReason.SIZE_CHANGED)
                    return
                }
                sourceConfirmedAtNs.compareAndSet(0, System.nanoTime())
            }
            // API 34's first callback initializes the system-selected app/full-screen bounds. Its
            // fixed SDR canvas may be letterboxed by the system. Later changes always stop safely.
            val prior = capturedSize.getAndSet(next)
            if (prior != null && prior != next) stop(RecordingStopReason.SIZE_CHANGED)
        }
    }

    fun pause(): Boolean = command(pause = true)
    fun resume(): Boolean = command(pause = false)
    private fun command(pause: Boolean): Boolean {
        if (ended || stopReason.get() != null || cancelled.get() ||
            state !in setOf(RecordingState.RECORDING, RecordingState.PAUSED, RecordingState.RESUMING)) return false
        if (!controls.offer(pause)) {
            controlFailure.compareAndSet(null, RecordingError.BUFFER_LIMIT)
            stop(RecordingStopReason.ENCODER_FAILED)
            return false
        }
        return true
    }
    fun stop(reason: RecordingStopReason = RecordingStopReason.USER_STOP) {
        if (!ended) {
            stopReason.compareAndSet(null, reason)
            revokePendingPreview(reason)
        }
    }
    fun cancel() {
        if (!ended) {
            cancelled.set(true)
            stopReason.set(RecordingStopReason.USER_CANCELLED)
            revokePendingPreview(RecordingStopReason.USER_CANCELLED)
        }
    }

    private fun revokePendingPreview(reason: RecordingStopReason) {
        val port = previewPort ?: return
        try {
            // Projection callbacks must revoke publication before returning, rather than waiting
            // for this worker's finally. Only metadata changes here; native cleanup stays owned.
            previewStopSignal.signal(!previewAdmissionComplete, reason, port::invalidate)
        } catch (_: RuntimeException) {
            // Runtime invalidates its gate first. An observer failure cannot escape a callback or
            // transfer/release native ownership; the owner still performs the final cleanup path.
        }
    }

    /** Returns only after actual close; failure to prove release is a permanently fenced outcome. */
    fun run(): RecordingOutcome {
        check(used.compareAndSet(false, true))
        if (!lease.started()) {
            val released = try { projection.stop(); true } catch (_: RuntimeException) { false }
            if (!released) lease.keepAlive(this)
            if (lease.occupied) lease.released(released)
            ended = true
            return RecordingOutcome.Failed(if (released) RecordingError.CANCELLED else RecordingError.RESOURCE_RELEASE_UNCONFIRMED)
        }
        lease.keepAlive(this)
        var failure: RecordingError? = null
        var finalized = false
        try {
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            callbackRegistered = true
            progress(RecordingState.PREPARING)
            prepare()
            captureLoop()
            if (!cancelled.get()) {
                if (!pausedBetweenSegments) finalizeCapture()
                finalized = true
            }
        } catch (error: RecordingFailure) {
            failure = error.code
        } catch (_: SecurityException) {
            failure = if (descriptor.audioChoice == RecordingAudioChoice.INTERNAL && audioRecord == null)
                RecordingError.AUDIO_FAILED else RecordingError.CAPTURE_UNAVAILABLE
        } catch (_: java.io.IOException) {
            failure = if (finalizing) RecordingError.FINALIZATION_FAILED else RecordingError.CAPTURE_UNAVAILABLE
        } catch (_: RuntimeException) {
            failure = if (finalizing) RecordingError.FINALIZATION_FAILED else RecordingError.ENCODER_FAILED
        } finally {
            // An interrupted Java waiter does not skip native cleanup and never closes its FD early.
            Thread.interrupted()
            try { previewPort?.invalidate(failure ?: RecordingError.CANCELLED) }
            catch (_: RuntimeException) { /* UI retirement cannot skip native cleanup. */ }
            val released = releaseResources(finalized)
            if (!released) failure = RecordingError.RESOURCE_RELEASE_UNCONFIRMED
            if (segmentOutputs != null && segmentOpen && !segmentClosed) {
                if (released && finalized && !cancelled.get()) {
                    try { reportClosedSegment() } catch (error: RuntimeException) {
                        failure = (error as? RecordingFailure)?.code ?: RecordingError.FINALIZATION_FAILED
                        if (!segmentClosed) abandonCurrentSegment(requireNotNull(failure))
                    }
                } else {
                    abandonCurrentSegment(failure ?: RecordingError.CANCELLED)
                }
            }
            ended = true
            lease.released(released)
        }
        val outcome = when {
            failure == RecordingError.RESOURCE_RELEASE_UNCONFIRMED -> RecordingOutcome.Failed(failure)
            cancelled.get() -> RecordingOutcome.Cancelled(RecordingStopReason.USER_CANCELLED)
            failure != null -> RecordingOutcome.Failed(failure)
            !finalized -> RecordingOutcome.Failed(RecordingError.FINALIZATION_FAILED)
            else -> if (segmentOutputs != null) lastClosedExpectation?.let(RecordingOutcome::Completed)
                ?: RecordingOutcome.Failed(RecordingError.CAPTURE_EMPTY) else completedOutcome()
        }
        progress(when (outcome) {
            is RecordingOutcome.Completed -> RecordingState.CAPTURE_FINISHED
            is RecordingOutcome.Cancelled -> RecordingState.CANCELLED
            is RecordingOutcome.Failed -> if (outcome.error == RecordingError.RESOURCE_RELEASE_UNCONFIRMED)
                RecordingState.RECOVERY_REQUIRED else RecordingState.FAILED
        }, (outcome as? RecordingOutcome.Failed)?.error)
        return outcome
    }

    private fun prepare() {
        var deadline = nowUs() + limits.prepareTimeoutUs
        fun checkpoint() {
            checkCancellation()
            if (stopReason.get() != null) fail(RecordingError.CAPTURE_EMPTY)
            if (nowUs() >= deadline) fail(RecordingError.DEADLINE_EXCEEDED)
        }
        checkpoint()
        if (segmentOutputs == null && availableBytes() < limits.minFreeBytes) fail(RecordingError.STORAGE_LOW)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: fail(RecordingError.CAPTURE_UNAVAILABLE)
        sourceDisplay = display
        val visualContext = context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
        windowContext = visualContext
        val bounds = visualContext.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        initialWidth = bounds.width(); initialHeight = bounds.height(); initialRotation = display.rotation
        if (initialWidth !in 1..8192 || initialHeight !in 1..8192) fail(RecordingError.PROFILE_UNSUPPORTED)
        var recipe = descriptor.regionCaptureRecipe
        if (segmentOutputs != null && recipe == null) fail(RecordingError.INVALID_REQUEST)
        if (recipe != null && (recipe.scopeEvidence != DefaultDisplayCaptureGeometry.scopeEvidence() ||
                DefaultDisplayCaptureGeometry.read(display, visualContext) != recipe.source)) fail(RecordingError.FORMAT_CHANGED)
        if (descriptor.requestedProfile.frameRate !in 15..60) fail(RecordingError.PROFILE_UNSUPPORTED)
        val density = visualContext.resources.configuration.densityDpi
        if (density <= 0) fail(RecordingError.CAPTURE_UNAVAILABLE)
        if (previewPort != null) {
            if (recipe == null || segmentOutputs == null) fail(RecordingError.INVALID_REQUEST)
            regionGl = RegionGlCapture(recipe, descriptor.requestedProfile.frameRate)
            requireNotNull(regionGl).initialize()
            createCaptureDisplay(initialWidth, initialHeight, density, requireNotNull(regionGl).sourceSurface)
            awaitRegionPreview()
            // User selection has its own bounded lifetime. It consumes no encoder preparation time.
            deadline = nowUs() + limits.prepareTimeoutUs
            recipe = requireNotNull(descriptor.regionCaptureRecipe)
            requireNotNull(regionGl).selectRecipe(recipe)
        }
        val desired = descriptor.requestedProfile
        if (recipe == null && maxOf(desired.width, desired.height) < 256) fail(RecordingError.PROFILE_UNSUPPORTED)
        val video = (if (recipe == null) CodecProfileSelector.video(initialWidth, initialHeight,
            minOf(maxOf(desired.width, desired.height), 3840), desired.frameRate)
            else CodecProfileSelector.exactVideo(recipe.pixelCrop.width, recipe.pixelCrop.height, desired.frameRate))
            ?: fail(RecordingError.PROFILE_UNSUPPORTED)
        videoProfile = video
        checkpoint()
        val audio = if (descriptor.audioChoice == RecordingAudioChoice.INTERNAL) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                fail(RecordingError.AUDIO_PERMISSION_DENIED)
            CodecProfileSelector.audio() ?: fail(RecordingError.PROFILE_UNSUPPORTED)
        } else null
        audioProfile = audio
        actualProfile = RecordingProfile(video.width, video.height, video.frameRate, video.bitrate,
            audio?.sampleRate ?: desired.audioSampleRate, audio?.channels ?: desired.audioChannels,
            audio?.bitrate ?: desired.audioBitrate)
        if (previewPort != null) {
            checkPreviewEnvironment()
            if (!previewPort.confirmed(descriptor)) fail(RecordingError.CANCELLED)
            checkPreviewEnvironment()
            previewAdmissionComplete = true
        }
        // A slow confirmed journal must not carry an expired setup budget into file/audio start.
        checkpoint()
        prepareSegmentOutput(::checkpoint)
        captureStartedUs = segmentStartedUs
        if (previewPort != null) {
            requireNotNull(regionGl).setEncoderSurface(requireNotNull(surface))
        } else {
            if (recipe != null) {
                regionGl = RegionGlCapture(recipe, video.frameRate)
                requireNotNull(regionGl).initialize(requireNotNull(surface))
            }
            createCaptureDisplay(if (recipe == null) video.width else initialWidth,
                if (recipe == null) video.height else initialHeight, density,
                regionGl?.sourceSurface ?: requireNotNull(surface))
        }
        checkpoint()
        requestKeyframe()
        checkpoint()
    }

    private fun createCaptureDisplay(width: Int, height: Int, density: Int, target: Surface) {
        check(virtualDisplay == null)
        // Sole display per grant. Preview and every later crop share this exact OES producer.
        virtualDisplay = projection.createVirtualDisplay("EasySaveVideo capture", width, height,
            density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, target, null, null)
            ?: fail(RecordingError.CAPTURE_UNAVAILABLE)
    }

    /** No file/output provider, codec or audio allocation is reachable inside this phase. */
    private fun awaitRegionPreview() {
        val port = requireNotNull(previewPort)
        val gl = requireNotNull(regionGl)
        val firstFrameDeadline = nowUs() + RegionPreviewLimits.FIRST_FRAME_TIMEOUT_US
        var offered = false
        var selection: NativeRegionPreviewSelection? = null
        while (true) {
            checkPreviewEnvironment()
            val now = nowUs()
            val snapshot = previewSnapshot
            if ((!offered && now >= firstFrameDeadline) ||
                (snapshot != null && now >= snapshot.expiresAtMonotonicUs)) fail(RecordingError.DEADLINE_EXCEEDED)
            if (offered && selection == null) selection = port.takeConfirmedSelection(now)
            val chosen = selection
            if (chosen != null) {
                val confirmation = chosen.confirmation
                if (confirmation.snapshot != snapshot || confirmation.snapshot.session != descriptor.session ||
                    chosen.earliestRecordingFrameMonotonicUs <= confirmation.captureReadyAtMonotonicUs ||
                    chosen.earliestRecordingFrameMonotonicUs > nowUs() ||
                    confirmation.recipe.source != snapshot?.observation?.geometry ||
                    confirmation.recipe.scopeEvidence != snapshot?.observation?.scopeEvidence)
                    fail(RecordingError.STALE_SESSION)
                if (!RegionPreviewHandoffPolicy.allows(confirmation, nowUs()))
                    fail(RecordingError.DEADLINE_EXCEEDED)
                if (gl.observeFrame(chosen.earliestRecordingFrameMonotonicUs * 1000, ::confirmRegionFrame)) {
                    checkPreviewEnvironment()
                    // updateTexImage/driver calls can block. Gate admission does not extend the
                    // frozen selection's lifetime or the bounded post-retirement handoff window.
                    if (!RegionPreviewHandoffPolicy.allows(confirmation, nowUs()))
                        fail(RecordingError.DEADLINE_EXCEEDED)
                    val selected = confirmation.recipe
                    descriptor = descriptor.copy(regionCaptureRecipe = selected,
                        requestedProfile = RegionCapturePolicy.outputProfile(selected, descriptor.requestedProfile))
                    firstRecordingFrameUs = chosen.earliestRecordingFrameMonotonicUs
                    return
                }
            } else if (!offered) {
                val eligibility = port.eligibility()
                if (eligibility is NativeRegionPreviewEligibility.Eligible && regionSourceReady() &&
                    (Build.VERSION.SDK_INT < 34 || capturedContentVisible.get() == true)) {
                    val readback = gl.readPreview(maxOf(eligibility.earliestFrameMonotonicUs * 1000,
                        sourceConfirmedAtNs.get())) { transform ->
                        checkPreviewEnvironment()
                        port.eligibility() == eligibility && confirmRegionFrame(transform)
                    }
                    if (readback != null) {
                        var frame: NativeRegionPreviewFrame? = null
                        var transferred = false
                        var publicationAttempted = false
                        try {
                            checkPreviewEnvironment()
                            val capturedAt = nowUs()
                            val captured = RegionPreviewSnapshot(descriptor.session, port.geometryGeneration, 1,
                                requireNotNull(regionObservation), readback.bitmap.width, readback.bitmap.height,
                                RegionPreviewTransform(readback.transform), readback.timestampNs, capturedAt,
                                capturedAt + RegionPreviewLimits.SNAPSHOT_LIFETIME_US)
                            previewSnapshot = captured
                            frame = NativeRegionPreviewFrame(captured, readback.bitmap)
                            if (port.eligibility() != eligibility) fail(RecordingError.CANCELLED)
                            publicationAttempted = true
                            if (!port.offer(frame)) fail(RecordingError.CANCELLED)
                            transferred = true
                            offered = true
                        } catch (_: OutOfMemoryError) {
                            fail(RecordingError.BUFFER_LIMIT)
                        } finally {
                            if (!transferred) {
                                frame?.close()
                                // An offer that threw may have handed pixels to UI. Never recycle it.
                                if (!publicationAttempted) readback.bitmap.recycle()
                            }
                        }
                    }
                } else gl.pump(false, 0, ::confirmRegionFrame)
            } else gl.pump(false, 0, ::confirmRegionFrame)
            idleBriefly()
        }
    }

    private fun checkPreviewEnvironment() {
        checkCancellation()
        if (stopReason.get() != null) fail(RecordingError.CANCELLED)
        if (previewPort?.eligibility() == NativeRegionPreviewEligibility.Cancelled) fail(RecordingError.CANCELLED)
        if (!context.getSystemService(PowerManager::class.java).isInteractive ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            fail(RecordingError.CANCELLED)
        if (!regionSourceReady() && stopReason.get() != null) fail(RecordingError.FORMAT_CHANGED)
        if (Build.VERSION.SDK_INT >= 34 && capturedContentVisible.get() == false) fail(RecordingError.CANCELLED)
    }

    private fun prepareSegmentOutput(checkpoint: () -> Unit) {
        val video = requireNotNull(videoProfile)
        currentOutput = segmentOutputs?.open(segmentIndex)
        segmentOpen = segmentOutputs != null
        segmentClosed = false
        if (freeBytes() < limits.minFreeBytes) fail(RecordingError.STORAGE_LOW)
        writer = currentOutput?.openWriter?.invoke() ?: openWriter()
        descriptorFence = currentOutput?.retainNativeWriter?.invoke() ?: retainNativeWriter()
        checkpoint()
        muxerConstructionUnconfirmed = true
        muxer = MediaMuxer(requireNotNull(writer).fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerConstructionUnconfirmed = false
        if (descriptor.regionCaptureRecipe != null) requireNotNull(muxer).setOrientationHint(0)
        checkpoint()
        videoCodec = MediaCodec.createByCodecName(video.encoderName)
        checkpoint()
        requireNotNull(videoCodec).configure(video.format(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        checkpoint()
        surface = requireNotNull(videoCodec).createInputSurface()
        checkpoint()
        requireNotNull(videoCodec).start(); videoStarted = true
        checkpoint()
        audioProfile?.let { audio ->
            prepareAudio(audio, checkpoint)
            checkpoint()
        }
        segmentStartedUs = nowUs()
        timeline = RecordingTimeline(segmentStartedUs, limits.keyframeTimeoutUs)
    }

    @SuppressLint("MissingPermission") // Checked immediately above; no microphone source is ever configured.
    private fun prepareAudio(profile: CodecProfileSelector.Audio, checkpoint: () -> Unit) {
        val channelMask = if (profile.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minimum = AudioRecord.getMinBufferSize(profile.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0 || minimum > 1024 * 1024) fail(RecordingError.AUDIO_FAILED)
        val bytes = maxOf(minimum, 1024 * profile.channels * 2 * 8)
        audioBufferFrames = bytes / (profile.channels * 2)
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        audioRecord = AudioRecord.Builder().setAudioFormat(AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(profile.sampleRate)
            .setChannelMask(channelMask).build()).setBufferSizeInBytes(bytes)
            .setAudioPlaybackCaptureConfig(capture).build()
        checkpoint()
        if (requireNotNull(audioRecord).state != AudioRecord.STATE_INITIALIZED) fail(RecordingError.AUDIO_FAILED)
        audioCodec = MediaCodec.createByCodecName(profile.encoderName)
        checkpoint()
        requireNotNull(audioCodec).configure(profile.format(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        checkpoint()
        requireNotNull(audioCodec).start(); audioCodecStarted = true
        checkpoint()
        pcm = ShortArray(1024 * profile.channels)
        requireNotNull(audioRecord).startRecording()
        audioRecordStarted = true
        checkpoint()
        if (requireNotNull(audioRecord).recordingState != AudioRecord.RECORDSTATE_RECORDING) fail(RecordingError.AUDIO_FAILED)
        audioTimestampMissingSinceUs = nowUs()
        lastPcmReadUs = audioTimestampMissingSinceUs
    }

    private fun captureLoop() {
        while (stopReason.get() == null && !cancelled.get()) {
            checkCancellation()
            controlFailure.get()?.let(::fail)
            applyControls()
            val now = nowUs()
            if (!pausedBetweenSegments) requireNotNull(timeline).checkDeadline(now)
            val wallLimit = if (segmentOutputs == null) limits.maxSessionDurationUs else 2L * 60 * 60 * 1_000_000
            if (now - captureStartedUs >= wallLimit) stop(RecordingStopReason.SESSION_LIMIT)
            pausedAtUs?.let { if (now - it >= limits.maxPausedDurationUs) stop(RecordingStopReason.PAUSE_LIMIT) }
            checkEnvironment(now)
            if (stopReason.get() != null) break
            var worked = regionGl?.pump(!pausedBetweenSegments && regionSourceReady(),
                maxOf(segmentStartedUs * 1000, sourceConfirmedAtNs.get(), firstRecordingFrameUs * 1000), ::confirmRegionFrame) ?: false
            if (pausedBetweenSegments) {
                if (now - lastProgressUs >= 250_000) { progress(RecordingState.PAUSED); lastProgressUs = now }
                if (!worked) idleBriefly()
                continue
            }
            feedAudio()
            worked = drainCodec(requireNotNull(videoCodec), RecordingTrack.VIDEO) || worked
            audioCodec?.let { worked = drainCodec(it, RecordingTrack.AUDIO) || worked }
            startMuxerIfReady()
            if (!muxStarted && nowUs() - segmentStartedUs >= limits.formatTimeoutUs) fail(RecordingError.FORMAT_TIMEOUT)
            if (now - lastProgressUs >= 250_000) { progress(state); lastProgressUs = now }
            if (!worked) idleBriefly()
        }
        controlFailure.get()?.let(::fail)
    }

    private fun applyControls() {
        for (pause in controls.drain()) {
            val now = nowUs()
            if (segmentOutputs != null) {
                applySegmentControl(pause, now)
                if (stopReason.get() != null) return
                continue
            }
            if (pause) {
                if (requireNotNull(timeline).pause(now)) {
                    pausedAtUs = now
                    progress(RecordingState.PAUSED)
                }
            } else {
                if (requireNotNull(timeline).state == RecordingTimelineState.PAUSED &&
                    !videoStats.canAdmitResumeBoundary(pendingResumeBoundaries)) {
                    stop(RecordingStopReason.RESUME_LIMIT)
                    return
                }
                if (requireNotNull(timeline).resume(now)) {
                    pausedAtUs = null
                    progress(RecordingState.RESUMING)
                    requestKeyframe()
                }
            }
        }
    }

    private fun applySegmentControl(pause: Boolean, now: Long) {
        if (pause) {
            if (pausedBetweenSegments) return
            // An empty/tiny epoch safely ends capture; it cannot create a missing ordinal or be
            // represented as a closed segment. Already closed segments remain recoverable.
            finalizeCapture(segmentPause = true)
            if (!releaseOutputResources(finalized = true)) fail(RecordingError.RESOURCE_RELEASE_UNCONFIRMED)
            reportClosedSegment()
            pausedBetweenSegments = true
            pausedAtUs = nowUs()
            progress(RecordingState.PAUSED)
        } else {
            if (!pausedBetweenSegments) return
            if (effectiveResumes >= limits.maxResumeBoundaries) { stop(RecordingStopReason.RESUME_LIMIT); return }
            if (!regionSourceReady()) { stop(RecordingStopReason.SIZE_CHANGED); return }
            resetSegmentState()
            segmentIndex++
            segmentResumePending = true
            val deadline = now + limits.prepareTimeoutUs
            prepareSegmentOutput {
                checkCancellation()
                if (stopReason.get() != null || !regionSourceReady()) fail(RecordingError.FORMAT_CHANGED)
                if (nowUs() >= deadline) fail(RecordingError.DEADLINE_EXCEEDED)
            }
            requireNotNull(regionGl).setEncoderSurface(requireNotNull(surface))
            pausedBetweenSegments = false
            pausedAtUs = null
            progress(RecordingState.RESUMING)
            requestKeyframe()
        }
    }

    private fun regionSourceReady(): Boolean {
        val recipe = descriptor.regionCaptureRecipe ?: return stopReason.get() == null && !cancelled.get()
        if (stopReason.get() != null || cancelled.get()) return false
        observedDisplayGeometry = DefaultDisplayCaptureGeometry.read(requireNotNull(sourceDisplay), requireNotNull(windowContext))
        if (observedDisplayGeometry != recipe.source) {
            stop(RecordingStopReason.SIZE_CHANGED); return false
        }
        return RegionSourceAdmission.readyForFrame(recipe, Build.VERSION.SDK_INT, observedDisplayGeometry, capturedSize.get()) &&
            (Build.VERSION.SDK_INT < 34 || sourceConfirmedAtNs.get() > 0)
    }

    private fun freeBytes(): Long = currentOutput?.availableBytes?.invoke() ?: availableBytes()

    /** Invoked only after an actual OES frame was latched and its unscaled transform checked. */
    private fun confirmRegionFrame(latchedTransform: FloatArray): Boolean {
        if (!regionSourceReady()) return false
        val recipe = descriptor.regionCaptureRecipe ?: return false
        if (!RegionSourceAdmission.canEncode(recipe, Build.VERSION.SDK_INT, observedDisplayGeometry,
                capturedSize.get(), latchedTransform)) return false
        val observation = CaptureGeometryObservation(requireNotNull(observedDisplayGeometry), recipe.scopeEvidence,
            if (Build.VERSION.SDK_INT >= 34) CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK
            else CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_SURFACE, nowUs())
        previewSnapshot?.let { snapshot ->
            // Incoming video keeps advancing. Match the source binding, never the frozen frame's
            // sequence/timestamp or the newer observation time, and never replace the UI snapshot.
            if (!snapshot.matchesSource(descriptor.session, requireNotNull(previewPort).geometryGeneration,
                    observation, latchedTransform)) fail(RecordingError.FORMAT_CHANGED)
        }
        regionObservation = RegionCapturePolicy.confirm(recipe, observation).observation
        return true
    }

    private fun requestKeyframe() {
        requireNotNull(videoCodec).setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    private fun checkEnvironment(now: Long) {
        if (now - lastEnvironmentCheckUs < 250_000) return
        lastEnvironmentCheckUs = now
        if (!context.getSystemService(PowerManager::class.java).isInteractive ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) stop(RecordingStopReason.SYSTEM_STOPPED)
        if (sourceDisplay?.rotation != initialRotation) stop(RecordingStopReason.SIZE_CHANGED)
        if (descriptor.regionCaptureRecipe != null) regionSourceReady()
        if (Build.VERSION.SDK_INT < 34) {
            val bounds = requireNotNull(windowContext).getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            if (bounds.width() != initialWidth || bounds.height() != initialHeight) stop(RecordingStopReason.SIZE_CHANGED)
        }
        if (freeBytes() < limits.minFreeBytes) stop(RecordingStopReason.STORAGE_LOW)
        val reserve = minOf(16L * 1024 * 1024, limits.maxFileBytes / 8)
        val activePayload = if (segmentOutputs != null && segmentClosed) 0 else
            payloadBytes + pendingBytes + measuredAudioTail.retainedBytes + measuredVideoTail.retainedBytes
        if (cumulativeBytes + activePayload >= limits.maxFileBytes - reserve ||
            cumulativeBytes + (writer?.channel?.size() ?: 0) >= limits.maxFileBytes - reserve) stop(RecordingStopReason.BYTE_LIMIT)
    }

    /** Reads exactly one bounded AAC-LC packet without blocking; the same recorder runs while paused. */
    private fun feedAudio() {
        val profile = audioProfile ?: return
        val recorder = requireNotNull(audioRecord)
        if (audioInterrupted || !audioRecordStarted) return
        try {
            if (nowUs() - lastPcmReadUs > 2_000_000) { interruptAudio(); return }
            if (pcmFilled < pcm.size) {
                if (pcmFilled == 0) { pcmStartFrame = pcmFramesRead; pcmWaitingSinceUs = nowUs() }
                val read = recorder.read(pcm, pcmFilled, pcm.size - pcmFilled, AudioRecord.READ_NON_BLOCKING)
                if (read < 0 || read % profile.channels != 0) { interruptAudio(); return }
                if (read > 0) {
                    lastPcmReadUs = nowUs()
                    for (index in pcmFilled until pcmFilled + read) if (abs(pcm[index].toInt()) > 32) nonSilentPcm = true
                    pcmFilled += read; pcmFramesRead += read / profile.channels
                    audioStatus = if (nonSilentPcm) RecordingAudioStatus.PCM_OBSERVED else RecordingAudioStatus.SILENCE_OR_UNKNOWN
                }
            }
            val timestampResult = recorder.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
            if (timestampResult == AudioRecord.SUCCESS) {
                val stampUs = audioTimestamp.nanoTime / 1000
                if (audioTimestamp.framePosition < 0 || stampUs <= 0 ||
                    abs(nowUs() - stampUs) > 2_000_000 ||
                    audioTimestamp.framePosition - pcmFramesRead > audioBufferFrames.toLong()) { interruptAudio(); return }
                val observedBase = stampUs - audioTimestamp.framePosition * 1_000_000L / profile.sampleRate
                val priorBase = audioClockBaseUs
                if (priorBase == null) audioClockBaseUs = observedBase
                else if (abs(priorBase - observedBase) > limits.audioBoundaryToleranceUs.coerceAtLeast(1_000)) {
                    interruptAudio(); return
                }
            } else if (audioClockBaseUs == null && nowUs() - audioTimestampMissingSinceUs > 2_000_000) {
                interruptAudio(); return
            }
            if (pcmFilled < pcm.size || audioClockBaseUs == null) return
            if (nowUs() - pcmWaitingSinceUs > 250_000) { interruptAudio(); return }
            val encoder = requireNotNull(audioCodec)
            val input = encoder.dequeueInputBuffer(0)
            if (input < 0) return
            val buffer = encoder.getInputBuffer(input) ?: fail(RecordingError.AUDIO_FAILED)
            val countBytes = pcm.size * 2
            if (buffer.capacity() < countBytes) fail(RecordingError.AUDIO_FAILED)
            buffer.clear(); buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm)
            val pts = requireNotNull(audioClockBaseUs) + pcmStartFrame * 1_000_000L / profile.sampleRate
            if (pts <= lastAudioInputUs || pts < 0) fail(RecordingError.TIMESTAMP_INVALID)
            encoder.queueInputBuffer(input, 0, countBytes, pts, 0)
            lastAudioInputUs = pts; pcmFilled = 0
        } catch (error: RecordingFailure) { throw error }
        catch (_: RuntimeException) { interruptAudio() }
    }

    private fun interruptAudio() {
        audioInterrupted = true; audioStatus = RecordingAudioStatus.INTERRUPTED
        stop(RecordingStopReason.AUDIO_LOST)
    }

    private fun drainCodec(codec: MediaCodec, track: RecordingTrack): Boolean {
        var worked = false
        val info = MediaCodec.BufferInfo()
        // One busy track cannot starve commands, the other codec, or bounded deadlines.
        repeat(16) {
            if (if (track == RecordingTrack.VIDEO) videoEos else audioEos) return worked
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return worked
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    acceptFormat(track, codec.outputFormat); worked = true
                }
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                index >= 0 -> {
                    try {
                        if (info.size < 0 || info.offset < 0 || info.size > limits.maxSampleBytes || info.flags and 7.inv() != 0)
                            fail(RecordingError.SAMPLE_INVALID)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (config && eos) fail(RecordingError.SAMPLE_INVALID)
                        if (info.size > 0 && !config) {
                            val buffer = codec.getOutputBuffer(index) ?: fail(RecordingError.SAMPLE_INVALID)
                            if (info.offset.toLong() + info.size > buffer.capacity()) fail(RecordingError.SAMPLE_INVALID)
                            val keyframe = track == RecordingTrack.VIDEO && info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val duration = if (track == RecordingTrack.VIDEO)
                                if (segmentOutputs != null) 1L else 1_000_000L / requireNotNull(videoProfile).frameRate
                                else requireNotNull(audioProfile).packetDurationUs
                            val now = nowUs()
                            if (info.presentationTimeUs < segmentStartedUs - 2_000_000 || info.presentationTimeUs > now ||
                                now - info.presentationTimeUs > 5_000_000) fail(RecordingError.TIMESTAMP_INVALID)
                            val resuming = requireNotNull(timeline).state == RecordingTimelineState.RESUMING
                            when (val mapping = requireNotNull(timeline).mapSample(track, info.presentationTimeUs, duration, keyframe)) {
                                is RecordingSampleMapping.Accepted -> {
                                    if (mapping.ptsUs > limits.maxSessionDurationUs - cumulativeDurationUs - mapping.durationUs) {
                                        stop(RecordingStopReason.SESSION_LIMIT)
                                    } else {
                                        val resumeBoundary = segmentOutputs == null && resuming && track == RecordingTrack.VIDEO && mapping.ptsUs > 0
                                        if (resumeBoundary && !videoStats.canAdmitResumeBoundary(pendingResumeBoundaries)) {
                                            // This epoch has no admitted sample. Close it before draining, so a
                                            // 33rd keyframe or later dependent samples can never enter the mux.
                                            requireNotNull(timeline).pause(now)
                                            stop(RecordingStopReason.RESUME_LIMIT)
                                        } else {
                                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                            val copy = ByteArray(info.size); buffer.get(copy)
                                            val sample = RecordingPendingSample(track, copy, mapping.ptsUs, mapping.durationUs, keyframe, resumeBoundary)
                                            if (segmentOutputs != null && track == RecordingTrack.VIDEO) holdMeasuredVideo(sample)
                                            else enqueue(sample)
                                        }
                                    }
                                }
                                is RecordingSampleMapping.Dropped -> Unit
                            }
                        }
                        if (eos) {
                            if (!finalizing) fail(RecordingError.ENCODER_FAILED)
                            if (track == RecordingTrack.VIDEO) videoEos = true else audioEos = true
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                    worked = true
                }
                else -> fail(RecordingError.ENCODER_FAILED)
            }
        }
        return worked
    }

    private fun acceptFormat(track: RecordingTrack, format: MediaFormat) {
        if (format.keys.size > 128 || (0..2).any { (format.getByteBuffer("csd-$it")?.remaining() ?: 0) > 65_536 })
            fail(RecordingError.BUFFER_LIMIT)
        if (track == RecordingTrack.VIDEO) {
            if (videoFormat != null || muxStarted) fail(RecordingError.FORMAT_CHANGED)
            val profile = requireNotNull(videoProfile)
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_VIDEO_AVC ||
                format.getInteger(MediaFormat.KEY_WIDTH) != profile.width || format.getInteger(MediaFormat.KEY_HEIGHT) != profile.height ||
                !baselineCsd(format.getByteBuffer("csd-0"))) fail(RecordingError.PROFILE_UNSUPPORTED)
            videoFormat = format
        } else {
            if (audioFormat != null || muxStarted) fail(RecordingError.FORMAT_CHANGED)
            val profile = requireNotNull(audioProfile)
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC ||
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != profile.sampleRate ||
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != profile.channels ||
                !aacLcCsd(format.getByteBuffer("csd-0"), profile.sampleRate, profile.channels)) fail(RecordingError.PROFILE_UNSUPPORTED)
            audioFormat = format
        }
        startMuxerIfReady()
    }

    private fun enqueue(sample: RecordingPendingSample) {
        if (muxStarted) { writeSample(sample); return }
        if (pending.size + measuredAudioTail.retainedSamples + (if (measuredVideoTail.retainedBytes > 0) 1 else 0) >= limits.maxPendingSamples ||
            sample.bytes.size > limits.maxPendingBytes - pendingBytes - measuredVideoTail.retainedBytes - measuredAudioTail.retainedBytes)
            fail(RecordingError.BUFFER_LIMIT)
        pending.addLast(sample); pendingBytes += sample.bytes.size
        if (sample.resumeBoundary) pendingResumeBoundaries++
    }

    private fun holdMeasuredVideo(sample: RecordingPendingSample) {
        if (pending.size + measuredAudioTail.retainedSamples + (if (measuredVideoTail.retainedBytes > 0) 1 else 0) >= limits.maxPendingSamples)
            fail(RecordingError.BUFFER_LIMIT)
        measuredVideoTail.advance(sample, sample.ptsUs, sample.bytes.size, pendingBytes + measuredAudioTail.retainedBytes)?.let { completed ->
            val prior = completed.sample
            enqueue(RecordingPendingSample(prior.track, prior.bytes, prior.ptsUs, completed.durationUs, prior.keyframe))
        }
    }

    private fun startMuxerIfReady() {
        if (muxStarted || videoFormat == null || (audioProfile != null && audioFormat == null)) return
        val output = requireNotNull(muxer)
        videoTrack = output.addTrack(requireNotNull(videoFormat))
        if (audioProfile != null) audioTrack = output.addTrack(requireNotNull(audioFormat))
        output.start(); muxStarted = true
        while (pending.isNotEmpty()) {
            val sample = pending.removeFirst(); pendingBytes -= sample.bytes.size
            if (sample.resumeBoundary) pendingResumeBoundaries--
            writeSample(sample)
        }
    }

    private fun writeSample(sample: RecordingPendingSample, fromAudioTail: Boolean = false) {
        if (segmentOutputs != null && sample.track == RecordingTrack.AUDIO && !fromAudioTail &&
            (measuredAudioTail.retainedSamples > 0 || sample.ptsUs + sample.durationUs > videoStats.endUs)) {
            measuredAudioTail.offer(sample, sample.ptsUs, sample.durationUs, sample.bytes.size,
                pendingBytes + measuredVideoTail.retainedBytes,
                pending.size + (if (measuredVideoTail.retainedBytes > 0) 1 else 0))
            flushMeasuredAudio()
            return
        }
        if (sample.bytes.size > limits.maxFileBytes - cumulativeBytes - payloadBytes) fail(RecordingError.BYTE_LIMIT)
        if (segmentOutputs != null && sample.track == RecordingTrack.VIDEO &&
            cumulativeVideoFrames + observedVideoTimes.size >= RecordingSegmentLimits.MAX_VIDEO_FRAMES) fail(RecordingError.BUFFER_LIMIT)
        val stats = if (sample.track == RecordingTrack.VIDEO) videoStats else audioStats
        stats.validate(sample.ptsUs, sample.durationUs, sample.keyframe, sample.resumeBoundary)
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.bytes.size, sample.ptsUs, if (sample.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        requireNotNull(muxer).writeSampleData(if (sample.track == RecordingTrack.VIDEO) videoTrack else audioTrack,
            ByteBuffer.wrap(sample.bytes), info)
        stats.accepted(sample.ptsUs, sample.durationUs, sample.keyframe, sample.resumeBoundary)
        if (segmentOutputs != null && sample.track == RecordingTrack.VIDEO) {
            observedVideoTimes.add(sample.ptsUs)
            if (segmentResumePending) {
                effectiveResumes++
                segmentResumePending = false
            }
        }
        payloadBytes += sample.bytes.size
        if (segmentOutputs != null && sample.track == RecordingTrack.VIDEO) flushMeasuredAudio()
        if (!finalizing && requireNotNull(timeline).state == RecordingTimelineState.ACTIVE &&
            state != RecordingState.RECORDING) progress(RecordingState.RECORDING)
    }

    private fun flushMeasuredAudio() {
        while (true) {
            val packet = measuredAudioTail.pollReady(videoStats.endUs) ?: return
            writeSample(packet, fromAudioTail = true)
        }
    }

    private fun finalizeCapture(segmentPause: Boolean = false) {
        finalizing = true
        finalizeDeadlineUs = nowUs() + limits.finalizationTimeoutUs
        if (!segmentPause) progress(RecordingState.STOPPING)
        requireNotNull(timeline).stop(nowUs())
        // Detach producer before surface EOS. The already-created display is never replaced.
        if (segmentOutputs == null) virtualDisplay?.surface = null
        if (audioRecordStarted) {
            try { requireNotNull(audioRecord).stop() } catch (_: RuntimeException) { audioInterrupted = true; audioStatus = RecordingAudioStatus.INTERRUPTED }
            audioRecordStarted = false
        }
        requireNotNull(videoCodec).signalEndOfInputStream()
        while (!videoEos || (audioCodec != null && !audioEos)) {
            checkFinalizeDeadline()
            if (cancelled.get()) fail(RecordingError.CANCELLED)
            if (audioCodec != null && !audioInputEos) {
                val encoder = requireNotNull(audioCodec)
                val input = encoder.dequeueInputBuffer(0)
                if (input >= 0) {
                    encoder.queueInputBuffer(input, 0, 0, maxOf(0, lastAudioInputUs + requireNotNull(audioProfile).packetDurationUs),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    audioInputEos = true
                }
            }
            var worked = drainCodec(requireNotNull(videoCodec), RecordingTrack.VIDEO)
            audioCodec?.let { worked = drainCodec(it, RecordingTrack.AUDIO) || worked }
            startMuxerIfReady()
            if (!worked) idleBriefly()
        }
        if (segmentOutputs != null) {
            flushMeasuredAudio()
            measuredAudioTail.discard().forEach { it.bytes.fill(0) }
        }
        if (!muxStarted || videoStats.count < (if (segmentOutputs == null) 1L else 2L) ||
            (audioProfile != null && audioStats.count == 0L)) fail(RecordingError.CAPTURE_EMPTY)
        if (maxOf(videoStats.endUs, audioStats.endUs) < limits.minCaptureDurationUs) fail(RecordingError.CAPTURE_TOO_SHORT)
        if (!segmentPause) progress(RecordingState.FINALIZING)
        // Android's MP4 muxer accepts a zero-byte EOS sample to specify the final sample duration.
        // This is not counted as a real sample or as independent decode/tail verification evidence.
        writeDurationEnd(videoTrack, videoStats.endUs)
        if (audioProfile != null) writeDurationEnd(audioTrack, audioStats.endUs)
        checkFinalizeDeadline()
        requireNotNull(muxer).stop(); muxStopped = true
        checkFinalizeDeadline()
    }

    private fun writeDurationEnd(track: Int, endUs: Long) {
        val info = MediaCodec.BufferInfo().apply { set(0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
        requireNotNull(muxer).writeSampleData(track, ByteBuffer.allocate(0), info)
    }

    private fun checkFinalizeDeadline() {
        if (nowUs() >= finalizeDeadlineUs) fail(RecordingError.FINALIZATION_FAILED)
    }

    /** Descriptor ownership is released only after native muxer release, even if stop failed. */
    private fun releaseResources(finalized: Boolean): Boolean {
        var confirmed = true
        fun release(action: () -> Unit) { try { action() } catch (_: Exception) { confirmed = false } }
        if (callbackRegistered) release { projection.unregisterCallback(projectionCallback); callbackRegistered = false }
        release { virtualDisplay?.release(); virtualDisplay = null }
        if (!releaseOutputResources(finalized && !pausedBetweenSegments)) confirmed = false
        regionGl?.let { gl -> release { if (gl.release()) regionGl = null else confirmed = false } }
        release { projection.stop() }
        if (!confirmed) nativeReleaseUnconfirmed = true
        if (nativeReleaseUnconfirmed) lease.keepAlive(this)
        return confirmed && !nativeReleaseUnconfirmed
    }

    /** Per-file resources close while the single projection/VD/OES capture scope remains owned. */
    private fun releaseOutputResources(finalized: Boolean): Boolean {
        var confirmed = true
        fun release(action: () -> Unit) { try { action() } catch (_: Exception) { confirmed = false } }
        regionGl?.let { gl -> release { gl.clearEncoderSurface() } }
        audioRecord?.let { recorder ->
            if (audioRecordStarted) try { recorder.stop() } catch (_: RuntimeException) { /* release is authoritative */ }
            release { recorder.release(); audioRecord = null; audioRecordStarted = false }
        }
        videoCodec?.let { encoder ->
            if (videoStarted) try { encoder.stop() } catch (_: RuntimeException) { /* release is authoritative */ }
            release { encoder.release(); videoCodec = null; videoStarted = false }
        }
        audioCodec?.let { encoder ->
            if (audioCodecStarted) try { encoder.stop() } catch (_: RuntimeException) { /* release is authoritative */ }
            release { encoder.release(); audioCodec = null; audioCodecStarted = false }
        }
        release { surface?.release(); surface = null }
        // If this throws, native dup ownership is unknown; retain BOTH the fence and writer.
        release { muxer?.release(); muxer = null }
        if (muxerConstructionUnconfirmed) confirmed = false
        if (muxer == null && !muxerConstructionUnconfirmed) {
            if (finalized && muxStopped) {
                try {
                    val stream = requireNotNull(writer)
                    finalBytes = stream.channel.size()
                    if (finalBytes !in 1..limits.maxFileBytes) { finalBytes = 0 }
                    stream.flush(); stream.fd.sync()
                } catch (_: Exception) { finalBytes = 0 }
            }
            // Keep a failed fence reachable. Storage decides whether actual FD closure is proven.
            release { writer?.close(); writer = null }
            release { descriptorFence?.close(); descriptorFence = null }
        }
        pending.forEach { it.bytes.fill(0) }
        pending.clear(); pendingBytes = 0; pendingResumeBoundaries = 0
        measuredVideoTail.discardEndpoint()?.bytes?.fill(0)
        measuredAudioTail.discard().forEach { it.bytes.fill(0) }
        pcm.fill(0)
        if (!confirmed) { nativeReleaseUnconfirmed = true; lease.keepAlive(this) }
        return confirmed
    }

    private fun reportClosedSegment() {
        check(segmentOutputs != null && segmentOpen && !segmentClosed)
        if (cumulativeDurationUs + maxOf(videoStats.endUs, audioStats.endUs) > limits.maxSessionDurationUs ||
            cumulativeBytes + finalBytes > limits.maxFileBytes) fail(RecordingError.BYTE_LIMIT)
        val outcome = completedOutcome()
        if (outcome !is RecordingOutcome.Completed) fail((outcome as? RecordingOutcome.Failed)?.error ?: RecordingError.CANCELLED)
        val timing = RecordingFrameTimeline(observedVideoTimes.toLongArray(), outcome.expectation.video.endPtsUs)
        val observation = requireNotNull(regionObservation)
        // Do not retry an uncertain durable callback. The already closed private capability stays
        // with its storage owner even if journaling/independent verification fails afterwards.
        segmentClosed = true
        segmentOutputs.closed(segmentIndex, outcome.expectation, timing, observation)
        lastClosedExpectation = outcome.expectation
        cumulativeDurationUs += outcome.expectation.effectiveDurationUs
        cumulativeBytes += outcome.expectation.bytesWritten
        cumulativeVideoFrames += outcome.expectation.video.sampleCount
    }

    private fun abandonCurrentSegment(error: RecordingError) {
        segmentClosed = true
        try { segmentOutputs?.abandoned(segmentIndex, error) }
        catch (_: RuntimeException) { /* Retention/recovery remains the storage owner's job. */ }
    }

    private fun resetSegmentState() {
        check(segmentClosed && muxer == null && writer == null && descriptorFence == null && videoCodec == null &&
            audioCodec == null && audioRecord == null && surface == null)
        finalizing = false; muxStarted = false; muxStopped = false
        videoEos = false; audioEos = false; audioInputEos = false
        videoTrack = -1; audioTrack = -1; videoFormat = null; audioFormat = null
        videoStats = RecordingMuxTrack(RecordingTrack.VIDEO, limits.maxResumeBoundaries)
        audioStats = RecordingMuxTrack(RecordingTrack.AUDIO)
        observedVideoTimes.clear()
        payloadBytes = 0; finalBytes = 0
        audioInterrupted = false; nonSilentPcm = false; pcmFramesRead = 0; pcmFilled = 0
        pcmStartFrame = 0; pcmWaitingSinceUs = 0; audioClockBaseUs = null; lastAudioInputUs = -1
        audioTimestampMissingSinceUs = 0; lastPcmReadUs = 0
        audioStatus = if (audioProfile == null) RecordingAudioStatus.NOT_REQUESTED else RecordingAudioStatus.INITIALIZING
    }

    private fun completedOutcome(): RecordingOutcome {
        if (finalBytes <= 0 || !muxStopped || !videoEos || (audioProfile != null && !audioEos))
            return RecordingOutcome.Failed(RecordingError.FINALIZATION_FAILED)
        val video = videoStats.evidence()
        val audio = if (audioProfile != null) audioStats.evidence() else null
        val audioOutcome = when {
            audio == null -> RecordingAudioOutcome.NO_AUDIO_REQUESTED
            audioInterrupted -> RecordingAudioOutcome.INTERNAL_AUDIO_INTERRUPTED
            nonSilentPcm -> RecordingAudioOutcome.INTERNAL_AUDIO_CAPTURED
            else -> RecordingAudioOutcome.INTERNAL_AUDIO_SILENT_OR_UNKNOWN
        }
        val reason = stopReason.get() ?: RecordingStopReason.USER_STOP
        if (reason in setOf(RecordingStopReason.USER_CANCELLED, RecordingStopReason.CONSENT_DENIED,
                RecordingStopReason.AUDIO_PERMISSION_DENIED, RecordingStopReason.ENTITLEMENT_REVOKED,
                RecordingStopReason.PROCESS_INTERRUPTED)) return RecordingOutcome.Cancelled(reason)
        return RecordingOutcome.Completed(RecordingArtifactExpectation(descriptor, requireNotNull(actualProfile), audioOutcome,
            reason, video, audio, maxOf(video.endPtsUs, audio?.endPtsUs ?: 0L), finalBytes,
            resumeBoundaries = videoStats.resumeBoundaries()))
    }

    private fun progress(next: RecordingState, error: RecordingError? = null) {
        state = next
        val currentDuration = if (segmentOutputs != null && segmentClosed) 0 else maxOf(videoStats.endUs, audioStats.endUs)
        try { onProgress(RecordingCaptureProgress(next, audioStatus, cumulativeDurationUs + currentDuration,
            actualProfile, stopReason.get(), error, if (segmentOutputs != null) effectiveResumes else videoStats.effectiveResumeCount)) }
        catch (_: RuntimeException) { /* A UI observer cannot take native ownership or break cleanup. */ }
    }
    private fun checkCancellation() {
        if (cancelled.get() || Thread.currentThread().isInterrupted || lease.releaseUnconfirmed) {
            cancelled.set(true)
            fail(RecordingError.CANCELLED)
        }
    }
    private fun idleBriefly() {
        try { Thread.sleep(4) } catch (_: InterruptedException) { cancelled.set(true); fail(RecordingError.CANCELLED) }
    }
    private fun nowUs() = System.nanoTime() / 1000
    private fun fail(code: RecordingError): Nothing = throw RecordingFailure(code)

    private fun baselineCsd(source: ByteBuffer?): Boolean {
        val buffer = source?.duplicate() ?: return false
        if (buffer.remaining() !in 5..65_536) return false
        val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
        // AVCDecoderConfigurationRecord or Annex B SPS; no heuristic acceptance of unknown CSD.
        if (bytes[0].toInt() and 0xff == 1) return bytes[1].toInt() and 0xff == 66
        for (index in 0 until bytes.size - 4) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                val nal = when {
                    bytes[index + 2] == 1.toByte() -> index + 3
                    bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> index + 4
                    else -> continue
                }
                if (nal + 1 < bytes.size && bytes[nal].toInt() and 0x1f == 7) return bytes[nal + 1].toInt() and 0xff == 66
            }
        }
        return false
    }

    private fun aacLcCsd(source: ByteBuffer?, sampleRate: Int, channels: Int): Boolean {
        val buffer = source?.duplicate() ?: return false
        if (buffer.remaining() !in 2..256) return false
        val first = buffer.get().toInt() and 0xff
        val second = buffer.get().toInt() and 0xff
        val rateIndex = ((first and 7) shl 1) or (second shr 7)
        return first shr 3 == 2 && rateIndex in 3..4 &&
            (if (rateIndex == 3) 48_000 else 44_100) == sampleRate && ((second shr 3) and 15) == channels
    }
}

/** Bounded/coalesced commands; a flood cannot allocate an unbounded handler or executor queue. */
internal class RecordingControlMailbox {
    private val commands = ArrayDeque<Boolean>()
    private var desiredPaused = false
    @Synchronized fun offer(paused: Boolean): Boolean {
        if (paused == desiredPaused) return true
        if (commands.size >= 16) return false
        desiredPaused = paused; commands.addLast(paused)
        return true
    }
    @Synchronized fun drain(): List<Boolean> {
        val result = commands.toList(); commands.clear(); return result
    }
}

/** Exactly the successfully muxed samples, not codec buffers merely dequeued or discarded. */
internal class RecordingMuxTrack(private val track: RecordingTrack, private val maxResumeBoundaries: Int = MAX_RECORDING_RESUME_BOUNDARIES) {
    init { require(maxResumeBoundaries in 1..MAX_RECORDING_RESUME_BOUNDARIES) }
    private val boundaries = ArrayList<RecordingResumeBoundary>(maxResumeBoundaries)
    val effectiveResumeCount: Int get() = boundaries.size
    fun canAdmitResumeBoundary(pending: Int = 0): Boolean {
        require(pending in 0..maxResumeBoundaries)
        return track == RecordingTrack.VIDEO && boundaries.size + pending < maxResumeBoundaries
    }
    fun resumeBoundaries(): List<RecordingResumeBoundary> =
        requireNotNull(snapshotRecordingResumeBoundaries(boundaries, evidence()))
    var count = 0L; private set
    private var first = -1L
    private var last = -1L
    private var duration = 0L
    private var keys = 0L
    private var firstKey = false
    val endUs: Long get() = if (count == 0L) 0 else last + duration
    fun validate(ptsUs: Long, durationUs: Long, keyframe: Boolean, resumeBoundary: Boolean = false) {
        if (ptsUs < 0 || ptsUs <= last || durationUs !in 1..1_000_000 || ptsUs > Long.MAX_VALUE - durationUs ||
            count >= 10_000_000 || (track == RecordingTrack.AUDIO && keyframe) ||
            (resumeBoundary && (!keyframe || count == 0L || !canAdmitResumeBoundary())) ||
            (track == RecordingTrack.VIDEO && count == 0L && (!keyframe || ptsUs != 0L)))
            throw RecordingFailure(RecordingError.SAMPLE_INVALID)
    }
    fun accepted(ptsUs: Long, durationUs: Long, keyframe: Boolean, resumeBoundary: Boolean = false) {
        validate(ptsUs, durationUs, keyframe, resumeBoundary)
        if (count == 0L) { first = ptsUs; firstKey = keyframe }
        if (resumeBoundary) boundaries.add(RecordingResumeBoundary(count, ptsUs))
        count++; last = ptsUs; duration = durationUs
        if (keyframe) keys++
    }
    fun evidence() = RecordingTrackEvidence(count, first, last, duration, keys, firstKey)
}

/** Lightweight observations, with explicit properties to keep the Android lint UAST path stable. */
class RecordingCaptureProgress(
    state: RecordingState,
    audioStatus: RecordingAudioStatus,
    effectiveDurationUs: Long,
    actualProfile: RecordingProfile?,
    stopReason: RecordingStopReason?,
    error: RecordingError? = null,
    effectiveResumeCount: Int = 0,
) {
    val effectiveResumeCount = effectiveResumeCount
    val state = state
    val audioStatus = audioStatus
    val effectiveDurationUs = effectiveDurationUs
    val actualProfile = actualProfile
    val stopReason = stopReason
    val error = error
}

/** A copied codec buffer is owned only until its bounded mux write completes. */
private class RecordingPendingSample(
    track: RecordingTrack,
    bytes: ByteArray,
    ptsUs: Long,
    durationUs: Long,
    keyframe: Boolean,
    resumeBoundary: Boolean = false,
) {
    val resumeBoundary = resumeBoundary
    val track = track
    val bytes = bytes
    val ptsUs = ptsUs
    val durationUs = durationUs
    val keyframe = keyframe
}
