package com.easysavevideo.app.editor.verification

import com.easysavevideo.app.media.verification.*
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.library.edit.EditAudioProfile
import com.easysavevideo.library.edit.EditError
import com.easysavevideo.library.edit.EditFailure
import com.easysavevideo.library.edit.EditLimits
import com.easysavevideo.library.edit.EditOutputExpectation
import com.easysavevideo.library.edit.EditOutputRef
import com.easysavevideo.library.edit.EditVerificationEvidence
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Independent edit-only verifier. A Transformer callback is not its input evidence. Only a private
 * immutable staging read capability reaches Android native parsers. Full demux and complete decode
 * are engineering-budgeted, not a claim of real-device performance or visual/quality acceptance.
 */
class AndroidEditOutputVerifier(
    private val openOwnedRead: (EditOutputRef) -> EditOwnedOutputRead,
    private val timeoutMillis: Long = EditLimits.MAX_VERIFY_TIMEOUT_MILLIS,
) {
    init { require(timeoutMillis in 1..EditLimits.MAX_VERIFY_TIMEOUT_MILLIS) }
    fun requiresProcessRecovery(): Boolean = nativeGate.recoveryRequired

    fun verify(output: EditOutputRef, expected: EditOutputExpectation, context: OperationContext): EditVerificationEvidence {
        checkActive(context)
        if (context.operationId != output.operationId) throw EditFailure(EditError.INVALID_REQUEST)
        val owner = EditVerificationReadOwner(openOwnedRead)
        if (!nativeGate.acquire(owner)) throw EditFailure(if (nativeGate.recoveryRequired) EditError.RELEASE_UNCONFIRMED else EditError.CAPACITY)
        val allottedMillis = minOf(timeoutMillis, EditLimits.verificationTimeoutMillis(expected.durationUs),
            (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1))
        val deadline = System.nanoTime() + allottedMillis * 1_000_000
        val future: Future<EditVerificationEvidence>
        try {
            future = nativeLane.submit<EditVerificationEvidence> {
                var confirmed = false
                try {
                    if (!nativeGate.start(owner)) throw EditFailure(EditError.CANCELLED)
                    val evidence = owner.inspect(output, expected) { checkpoint(context, deadline) }
                    // Evidence is withheld until the actual native cleanup and descriptor close.
                    confirmed = owner.releaseAll()
                    if (!confirmed) throw EditFailure(EditError.RELEASE_UNCONFIRMED)
                    checkpoint(context, deadline)
                    evidence.copy(nativeReleaseConfirmed = true).also {
                        if (!it.validFor(output, expected)) invalidEditOutput()
                    }
                } finally {
                    if (!owner.cleanupFinished) confirmed = owner.releaseAll()
                    nativeGate.finish(owner, confirmed)
                    owner.cleanup.finished(confirmed)
                }
            }
        } catch (_: RejectedExecutionException) {
            nativeGate.rejectedBeforeScheduling(owner)
            throw EditFailure(EditError.CAPACITY)
        } catch (_: Throwable) {
            // An unexpected scheduling failure does not prove the task was never admitted.
            nativeGate.abandon(owner); owner.abandoned = true
            owner.cleanup.awaitConfirmed(EditLimits.RELEASE_TIMEOUT_MILLIS)
            throw EditFailure(EditError.RELEASE_UNCONFIRMED)
        }
        var received = false
        try {
            while (true) {
                checkpoint(context, deadline)
                try {
                    val result = future.get(minOf(50_000_000L, (deadline - System.nanoTime()).coerceAtLeast(1)), TimeUnit.NANOSECONDS)
                    received = true
                    checkpoint(context, deadline)
                    return result
                } catch (_: TimeoutException) { /* Poll caller cancellation without opening another native lane. */ }
            }
        } catch (failure: ExecutionException) {
            received = true
            throw (failure.cause as? EditFailure ?: EditFailure(EditError.VERIFICATION_FAILED))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EditFailure(EditError.CANCELLED)
        } finally {
            if (!received) {
                nativeGate.abandon(owner)
                // Do not cancel a queued Future: its worker must acknowledge and release its gate.
                // The cooperative checkpoint fences late results; an interrupt is not closure.
                owner.abandoned = true
                if (!owner.cleanup.awaitConfirmed(EditLimits.RELEASE_TIMEOUT_MILLIS))
                    throw EditFailure(EditError.RELEASE_UNCONFIRMED)
            }
        }
    }

    companion object {
        private val nativeGate = EditVerificationNativeGate()
        private val nativeLane = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { runnable ->
            Thread(runnable, "esv-edit-output-check").apply { isDaemon = true }
        }.apply { rejectedExecutionHandler = ThreadPoolExecutor.AbortPolicy() }
        private fun checkActive(context: OperationContext) {
            if (context.cancelToken.isCancelled || Thread.currentThread().isInterrupted) throw EditFailure(EditError.CANCELLED)
            if (System.currentTimeMillis() >= context.deadlineEpochMillis) throw EditFailure(EditError.TIMEOUT)
        }
        private fun checkpoint(context: OperationContext, deadline: Long) {
            checkActive(context)
            if (System.nanoTime() >= deadline) throw EditFailure(EditError.TIMEOUT)
        }
    }
}


/**
 * Editor's sole read/fence closer, retained by its existing process gate through worker cleanup.
 * The neutral inspection has no owning stream and cannot close this read. Kept separate from the
 * Android executor so host tests exercise this production ordering with a controlled native body.
 */
internal class EditVerificationReadOwner(
    private val openOwnedRead: (EditOutputRef) -> EditOwnedOutputRead,
    private val newInspection: (BorrowedAvcAacRead) -> BorrowedAvcAacInspection = { BorrowedAvcAacInspection(it) },
) {
    val cleanup = EditVerificationCleanup()
    private var read: EditOwnedOutputRead? = null
    private var inspection: BorrowedAvcAacInspection? = null
    @Volatile var abandoned = false
    var cleanupFinished = false; private set
    private var releaseConfirmed = true

    fun inspect(output: EditOutputRef, expected: EditOutputExpectation,
        checkpoint: () -> Unit): EditVerificationEvidence = editVerification {
        fun check() { if (abandoned) throw EditFailure(EditError.CANCELLED); checkpoint() }
        check()
        val owned = openOwnedRead(output).also { read = it }
        if (owned.output != output) invalidEditOutput()
        val channel = owned.input.channel
        val size = channel.size()
        if (size !in 1..expected.maxBytes || size != owned.expectedBytes) invalidEditOutput()
        val borrowed = BorrowedAvcAacRead(owned.input.fd, channel, size)
        val initialHash = borrowed.sha256(::check)
        val native = newInspection(borrowed).also { inspection = it }
        val actual = native.inspect(expected.inspectionExpectation(), ::check).observationsOrThrow()
        if (!native.nativeReleaseConfirmed) throw EditFailure(EditError.RELEASE_UNCONFIRMED)
        // Native release precedes this final hash; the caller's original FD is still open.
        check()
        borrowed.requireUnchanged(initialHash, ::check)
        val audio = expected.audio as? EditAudioProfile.Aac
        EditVerificationEvidence(output, expected, size, initialHash, actual.durationUs,
            actual.width, actual.height, actual.rotationDegrees, "video/avc",
            audio?.let { "audio/mp4a-latm" }, audio?.sampleRate, audio?.channels,
            actual.firstVideoPtsUs, actual.lastVideoPtsUs, actual.videoSamples, actual.audioSamples,
            actual.decodedVideoFrames, actual.decodedAudioFrames, true, audio != null, false, true)
    }

    fun releaseAll(): Boolean {
        if (cleanupFinished) return releaseConfirmed
        releaseConfirmed = inspection?.releaseNative() ?: true
        if (releaseConfirmed) {
            try { read?.closeAfterNativeRelease() } catch (_: Throwable) { releaseConfirmed = false }
        }
        if (!releaseConfirmed) try { read?.retainForProcessRecovery() } catch (_: Throwable) { /* Still retained here. */ }
        if (releaseConfirmed) { read = null; inspection = null }
        cleanupFinished = true
        return releaseConfirmed
    }
}
