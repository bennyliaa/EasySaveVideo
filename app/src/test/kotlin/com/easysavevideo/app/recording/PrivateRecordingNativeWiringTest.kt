package com.easysavevideo.app.recording

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source ordering/contracts only. These do not execute Android, codecs, projection or WindowManager. */
class PrivateRecordingNativeWiringTest {
    private fun source(name: String) = File("src/main/kotlin/com/easysavevideo/app/recording", name).readText()
    private val port get() = source("AndroidRecordSavePort.kt")
    private val session get() = source("AndroidRecordingSession.kt")
    private val binding get() = source("PrivateRecordingNativeBinding.kt")

    @Test fun sameBindingIsFrozenAtPrepareAndUsedAtRealQueueBoundary() {
        val prepare = port.substringAfter("fun prepare(").substringBefore("fun admitForeground(")
        assertTrue(prepare.contains("privateBinding.descriptor != descriptor"))
        assertTrue(prepare.contains("this.privateBinding = privateBinding"))
        val record = port.substringAfter("override fun record(")
        assertTrue(record.contains("privateCapture.descriptor != descriptor"))
        val register = record.indexOf("privateCapture.registerQueued(lease)")
        val submit = record.indexOf("native.submit<RecordingOutcome>")
        assertTrue(register >= 0 && register < submit)
        assertTrue(binding.contains("authority.consumeDeferred(ownerIdentity)"))
        assertFalse(binding.contains("PrivateWorkRegistration"))
        assertFalse(binding.contains("registerPrivateCaptureOwner"))
        assertTrue(binding.contains("check(lease.tryAcquire())"))
        assertTrue(record.contains("privateCapture = privateCapture"))
    }

    @Test fun nativeWorkerRechecksBeforeGrantProjectionAndSessionRun() {
        val native = port.substringAfter("native.submit<RecordingOutcome>").substringBefore("} catch (_: java.util.concurrent.RejectedExecutionException)")
        for (call in listOf("ProjectionGrantHandoff.take(requestId)", "manager.getMediaProjection", "capture.run()")) {
            val index = native.indexOf(call)
            assertTrue(call, index >= 0)
            assertTrue(call, native.substring(0, index).lastIndexOf("privateCapture?.requireStartAllowed(descriptor)") >= 0)
        }
        assertTrue(native.indexOf("privateCapture?.ownerStarted()") < native.indexOf("ProjectionGrantHandoff.take"))
        assertTrue(native.contains("projectionAcquisitionUnconfirmed = privateCapture != null"))
        assertTrue(native.contains("var released = !projectionAcquisitionUnconfirmed"))
    }

    @Test fun delayedOutputAndCodecPreparationCannotBypassTheAcquisitionGate() {
        val prepare = session.substringAfter("private fun prepare()").substringBefore("private fun createCaptureDisplay(")
        val checkpoint = prepare.substringAfter("fun checkpoint()").substringBefore("checkpoint()\n")
        assertTrue(checkpoint.contains("privateCapture?.requireStartAllowed(descriptor)"))
        assertTrue(prepare.contains("prepareSegmentOutput(::checkpoint)\n        privateCapture?.requireStartAllowed(descriptor)"))
        val audio = session.substringAfter("private fun prepareAudio(").substringBefore("private fun captureLoop()")
        val build = audio.indexOf("audioRecord = AudioRecord.Builder()")
        val beforeBuild = audio.lastIndexOf("privateCapture?.requireStartAllowed(descriptor)", build)
        assertTrue(beforeBuild >= 0 && beforeBuild < build)
        val start = audio.indexOf("requireNotNull(audioRecord).startRecording()")
        val beforeStart = audio.lastIndexOf("privateCapture?.requireStartAllowed(descriptor)", start)
        assertTrue(beforeStart > build && beforeStart < start)
        assertTrue(audio.indexOf("privateCapture?.nativeCaptureAcquired()") > start)
    }

    @Test fun everyVirtualDisplayPassesSamePreAcquisitionGateAndPostAcquisitionObserver() {
        assertEquals(1, Regex("projection\\.createVirtualDisplay\\(").findAll(session).count())
        val create = session.substringAfter("private fun createCaptureDisplay(").substringBefore("private fun awaitRegionPreview()")
        val gate = create.indexOf("privateCapture?.requireStartAllowed(descriptor)")
        val acquire = create.indexOf("projection.createVirtualDisplay(")
        val observed = create.indexOf("privateCapture?.nativeCaptureAcquired()")
        assertTrue(gate >= 0 && gate < acquire && acquire < observed)
        assertTrue(binding.contains("require(descriptor.regionCaptureRecipe == null)"))
        assertTrue(binding.contains("value.descriptor.mode != PrivateCaptureMode.BASIC"))
    }

    @Test fun actualCleanupIsTheOnlyNativePositiveDrainBoundary() {
        val cleanup = port.substringAfter("                } finally {").substringBefore("} catch (_: java.util.concurrent.RejectedExecutionException)")
        val projectionStop = cleanup.indexOf("projection?.stop()")
        val leaseReleased = cleanup.indexOf("lease.released(released)")
        val observed = cleanup.indexOf("privateCapture?.ownerReleased(!lease.occupied)")
        assertTrue(projectionStop >= 0 && projectionStop < leaseReleased && leaseReleased < observed)
        assertTrue(observed < cleanup.indexOf("session = null"))
        assertEquals(1, Regex("privateCapture\\?\\.ownerReleased\\(").findAll(port).count())
        val releaseBody = session.substringAfter("private fun releaseResources(").substringBefore("private fun releaseOutputResources(")
        for (resource in listOf("projection.unregisterCallback", "virtualDisplay?.release()", "releaseOutputResources(",
            "gl.release()", "projection.stop()")) assertTrue(resource, releaseBody.contains(resource))
        assertTrue(session.indexOf("val released = releaseResources(finalized)") < session.lastIndexOf("lease.released(released)"))
        assertTrue(binding.contains("confirmed && nativeLease?.occupied == false"))
    }

    @Test fun keepPathNeverCallsCancelOrDenialAndDiscardIsSeparate() {
        val stop = port.substringAfter("fun stop(requestId:").substringBefore("override fun cancel(")
        assertTrue(stop.contains("requestStop(PrivateCaptureDisposition.KEEP_VALID_OUTPUT)"))
        assertTrue(stop.contains("controller.stop(")); assertTrue(stop.contains("session?.stop(reason)"))
        for (forbidden in listOf("cancelToken", "controller.cancel", "session?.cancel", "denied("))
            assertFalse(forbidden, stop.contains(forbidden))
        val cancel = port.substringAfter("override fun cancel(").substringBefore("override fun record(")
        assertTrue(cancel.contains("requestStop(PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT)"))
        assertTrue(cancel.contains("controller.cancel("))
        assertFalse(binding.contains("OperationContext"))
        assertFalse(binding.contains("cancelToken"))
    }

    @Test fun ordinaryDefaultsAndExistingLeaseExecutorOutputChecksRemain() {
        assertTrue(port.contains("privateBinding: PrivateRecordingNativeBinding? = null"))
        assertTrue(session.contains("privateCapture: PrivateRecordingNativeBinding? = null"))
        assertTrue(port.contains("if (privateCapture == null) lease.tryAcquire() else privateCapture.registerQueued(lease)"))
        assertEquals(1, Regex("ThreadPoolExecutor\\(1, 1").findAll(port).count())
        assertTrue(port.contains("context.cancelToken.isCancelled || !controller.snapshot().publicationAllowed"))
        assertTrue(port.contains("bytes != outcome.expectation.bytesWritten"))
    }
    @Test fun nativeStopPublishesMetadataBeforeReleaseWithoutSyntheticDrain() {
        val callback = port.substringAfter("onStopRequested = { reason ->").substringBefore("onProgress =")
        assertTrue(callback.contains("controller.stop(descriptor.session, now(), reason)"))
        assertTrue(callback.contains("controller.cancel(descriptor.session, now())"))
        assertTrue(callback.contains("publish()"))
        for (forbidden in listOf("lease.released", "releaseConfirmed", "ownerReleased", "session?.stop", "session?.cancel"))
            assertFalse(forbidden, callback.contains(forbidden))
    }

}
