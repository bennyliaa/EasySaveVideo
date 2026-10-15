package com.easysavevideo.app.recording

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring only. Does not execute Android, VirtualDisplay, scaling, codec or output checks. */
class BasicCaptureGeometryWiringTest {
    private val session get() = File("src/main/kotlin/com/easysavevideo/app/recording/AndroidRecordingSession.kt").readText()

    @Test fun callbackOnlyPublishesBasicSizeAndKeepsRegionFrozenSourceAdmission() {
        val callback = session.substringAfter("override fun onCapturedContentResize(").substringBefore("    fun pause()")
        val basic = callback.substringAfter("basicGeometry?.let").substringBefore("// Region/preview")
        assertTrue(basic.contains("geometry.observe(width, height, captureDensityDpi)"))
        for (forbidden in listOf(".resize(", "createVirtualDisplay", "SIZE_CHANGED", "requireStartAllowed"))
            assertFalse(forbidden, basic.contains(forbidden))
        assertTrue(callback.contains("next != (recipe.source.width to recipe.source.height)"))
        assertTrue(callback.contains("sourceConfirmedAtNs.compareAndSet(0, System.nanoTime())"))
        assertTrue(callback.contains("prior != null && prior != next) stop(RecordingStopReason.SIZE_CHANGED)"))
    }

    @Test fun selectedAppSizeIsNeverOverwrittenByDefaultDisplayPollingOnApi34() {
        val observation = session.substringAfter("private fun observeBasicGeometry()").substringBefore("private fun applyBasicGeometry()")
        val legacy = observation.substringAfter("if (Build.VERSION.SDK_INT < 34)").substringBefore("} else {")
        val selected = observation.substringAfter("} else {")
        assertTrue(legacy.contains("maximumWindowMetrics.bounds"))
        assertTrue(legacy.contains("geometry.observe(bounds.width(), bounds.height(), density)"))
        assertTrue(selected.contains("geometry.observeDensity(density)"))
        assertFalse(selected.contains("bounds"))
        assertFalse(selected.contains("geometry.observe("))
        val prepare = session.substringAfter("private fun prepare()").substringBefore("private fun createCaptureDisplay(")
        assertTrue(prepare.contains("Build.VERSION.SDK_INT < 34 && basicGeometry?.observe(initialWidth, initialHeight, density)"))
    }

    @Test fun oneDisplayAndOneEncoderCanvasWithOwnerAdjacentStopAndLeaseChecks() {
        assertEquals(1, Regex("projection\\.createVirtualDisplay\\(").findAll(session).count())
        assertEquals(1, Regex("createInputSurface\\(").findAll(session).count())
        val apply = session.substringAfter("private fun applyBasicGeometry()").substringBefore("private fun feedAudio()")
        for (gate in listOf("!ended", "!finalizing", "!cancelled.get()", "stopReason.get() == null",
            "!Thread.currentThread().isInterrupted", "lease.occupied", "!lease.releaseUnconfirmed",
            "privateCapture?.isStopRequested != true")) assertTrue(gate, apply.contains(gate))
        assertTrue(apply.contains("requireNotNull(virtualDisplay).resize(next.width, next.height, next.densityDpi)"))
        for (forbidden in listOf("createVirtualDisplay", "createInputSurface", ".configure(", "setOrientationHint", "addTrack"))
            assertFalse(forbidden, apply.contains(forbidden))
        assertTrue(apply.contains("catch (_: OutOfMemoryError)"))
        assertTrue(apply.contains("fail(RecordingError.CAPTURE_UNAVAILABLE)"))
    }

    @Test fun initialAndOngoingApplyStayOnNativeOwnerAndRetireBeforeCleanup() {
        val run = session.substringAfter("fun run()").substringBefore("private fun prepare()")
        assertTrue(run.indexOf("basicGeometry?.bindOwner()") < run.indexOf("projection.registerCallback"))
        val create = session.substringAfter("private fun createCaptureDisplay(").substringBefore("private fun awaitRegionPreview()")
        assertTrue(create.indexOf("projection.createVirtualDisplay(") < create.indexOf("basicGeometry?.displayCreated("))
        assertTrue(create.indexOf("basicGeometry?.displayCreated(") < create.indexOf("applyBasicGeometry()"))
        val loop = session.substringAfter("private fun captureLoop()").substringBefore("private fun applyControls()")
        assertTrue(loop.indexOf("checkCancellation()") < loop.indexOf("applyBasicGeometry()"))
        assertTrue(loop.indexOf("checkEnvironment(now)") < loop.indexOf("applyBasicGeometry()"))
        for ((start, end) in listOf("fun stop(" to "fun cancel()", "fun cancel()" to "private fun revokePendingPreview(",
            "private fun finalizeCapture(" to "private fun writeDurationEnd(",
            "private fun releaseResources(" to "private fun releaseOutputResources(")) {
            val body = session.substringAfter(start).substringBefore(end)
            assertTrue(start, body.contains("basicGeometry?.retire()"))
        }
        val cleanup = session.substringAfter("private fun releaseResources(").substringBefore("private fun releaseOutputResources(")
        assertTrue(cleanup.indexOf("basicGeometry?.retire()") < cleanup.indexOf("virtualDisplay?.release()"))
    }

    @Test fun regionAndPreviewStillRejectFrozenSourceChangesWhileBasicDoesNotStopForRotation() {
        assertTrue(session.contains("if (descriptor.regionCaptureRecipe == null) BasicCaptureGeometry() else null"))
        val environment = session.substringAfter("private fun checkEnvironment(").substringBefore("private fun observeBasicGeometry()")
        val basic = environment.substringAfter("if (basicGeometry != null)").substringBefore("} else {")
        assertTrue(basic.contains("observeBasicGeometry()"))
        assertFalse(basic.contains("SIZE_CHANGED"))
        assertTrue(environment.contains("sourceDisplay?.rotation != initialRotation) stop(RecordingStopReason.SIZE_CHANGED)"))
        val region = session.substringAfter("private fun regionSourceReady()").substringBefore("private fun freeBytes()")
        assertTrue(region.contains("observedDisplayGeometry != recipe.source"))
        assertTrue(region.contains("stop(RecordingStopReason.SIZE_CHANGED); return false"))
        assertTrue(region.contains("RegionSourceAdmission.readyForFrame"))
        val frame = session.substringAfter("private fun confirmRegionFrame(").substringBefore("private fun requestKeyframe()")
        assertTrue(frame.contains("RegionSourceAdmission.canEncode"))
        assertTrue(frame.contains("snapshot.matchesSource"))
    }

    @Test fun visibilityIsNotBasicStopAndRealSystemBudgetsAndCleanupRemain() {
        val visible = session.substringAfter("override fun onCapturedContentVisibilityChanged(").substringBefore("override fun onCapturedContentResize(")
        assertTrue(visible.contains("previewPort != null && !previewAdmissionComplete && !isVisible"))
        assertTrue(session.contains("override fun onStop() { if (!ended) stop(RecordingStopReason.SYSTEM_STOPPED) }"))
        for (required in listOf("limits.maxSessionDurationUs", "limits.maxPausedDurationUs", "limits.minFreeBytes",
            "limits.maxFileBytes", "limits.finalizationTimeoutUs", "projection.stop()", "lease.released(released)"))
            assertTrue(required, session.contains(required))
    }

    @Test fun stopObservationIsMetadataOnlyOutsideLocksAndDoesNotClaimNativeRelease() {
        assertTrue(session.contains("private val onStopRequested: (RecordingStopReason) -> Unit = {}"))
        val notify = session.substringAfter("private fun notifyStopRequested(").substringBefore("private fun terminalStopReason(")
        assertTrue(notify.contains("reportedStop.compareAndSet(prior, reason)"))
        assertTrue(notify.indexOf("reportedStop.compareAndSet") < notify.indexOf("onStopRequested(reason)"))
        assertTrue(notify.contains("catch (_: Throwable)"))
        assertTrue(notify.contains("prior == RecordingStopReason.USER_CANCELLED"))
        for (forbidden in listOf("synchronized", "progress(", "videoStats", "audioStats", "releaseResources(",
            "lease.released(", "projection.", "virtualDisplay", "videoCodec", "audioRecord"))
            assertFalse(forbidden, notify.contains(forbidden))
    }

    @Test fun firstStopAndCancellationNotifyBeforeReturningWithoutReadingNativeStats() {
        val stop = session.substringAfter("fun stop(reason:").substringBefore("fun cancel()")
        assertTrue(stop.indexOf("privateCapture?.requestStop(") < stop.indexOf("notifyStopRequested("))
        assertTrue(stop.contains("if (stopReason.compareAndSet(null, reason)) notifyStopRequested(stopReason.get() ?: reason)"))
        val cancel = session.substringAfter("fun cancel()").substringBefore("private fun notifyStopRequested(")
        assertTrue(cancel.contains("val firstCancellation = cancelled.compareAndSet(false, true)"))
        assertTrue(cancel.contains("if (firstCancellation) notifyStopRequested(RecordingStopReason.USER_CANCELLED)"))
        assertFalse(stop.contains("progress("))
        assertFalse(cancel.contains("progress("))
    }

    @Test fun realFailureNotifiesBeforeCleanupWithoutOverwritingFailureOrOutcome() {
        val run = session.substringAfter("fun run()").substringBefore("private fun prepare()")
        val rejectedLease = run.substringAfter("if (!lease.started())").substringBefore("lease.keepAlive(this)")
        assertTrue(rejectedLease.indexOf("notifyStopRequested(") < rejectedLease.indexOf("projection.stop()"))
        val cleanup = run.substringAfter("} finally {")
        assertTrue(cleanup.indexOf("notifyStopRequested(terminalStopReason(failure))") < cleanup.indexOf("releaseResources(finalized)"))
        val reason = session.substringAfter("private fun terminalStopReason(").substringBefore("private fun revokePendingPreview(")
        assertTrue(reason.contains("stopReason.get()?.let { return it }"))
        for (name in listOf("DEADLINE_EXCEEDED", "BYTE_LIMIT", "STORAGE_LOW"))
            assertTrue(name, reason.contains("RecordingError.$name -> RecordingStopReason.$name"))
        assertTrue(reason.contains("RecordingError.CANCELLED -> RecordingStopReason.USER_CANCELLED"))
        assertTrue(reason.contains("RecordingStopReason.AUDIO_LOST"))
        assertTrue(reason.contains("RecordingError.FORMAT_CHANGED -> RecordingStopReason.SIZE_CHANGED"))
        assertTrue(reason.contains("else -> RecordingStopReason.ENCODER_FAILED"))
        for (forbidden in listOf("stopReason.set", "stopReason.compareAndSet", "cancelled.set", "failure =", "progress("))
            assertFalse(forbidden, reason.contains(forbidden))
    }

    @Test fun basicScreenStateDoesNotSimulateProjectionRevocationWhilePreviewAndRegionKeepTheirPolicy() {
        val body = session.substringAfter("private fun checkEnvironment(").substringBefore("private fun observeBasicGeometry(")
        val guarded = body.indexOf("if (basicGeometry == null || previewPort != null || segmentOutputs != null)")
        val screen = body.indexOf("PowerManager::class.java")
        assertTrue(guarded >= 0); assertTrue(screen > guarded)
        assertTrue(body.contains("isKeyguardLocked"))
        assertTrue(body.contains("if (freeBytes() < limits.minFreeBytes)"))
        val preview = session.substringAfter("private fun checkPreviewEnvironment()").substringBefore("private fun prepareSegmentOutput")
        assertTrue(preview.contains("isInteractive")); assertTrue(preview.contains("isKeyguardLocked"))
        assertTrue(session.contains("override fun onStop() { if (!ended) stop(RecordingStopReason.SYSTEM_STOPPED) }"))
    }
}
