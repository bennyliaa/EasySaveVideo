package com.easysavevideo.app.media.verification

import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale

/**
 * One already-open caller-owned read. The caller must derive FD/channel/extent from the same owned
 * read and keep its identity/storage fence live. This view grants no acquisition or completion
 * authority, creates no stream, and has deliberately no close operation. The caller alone closes.
 */
internal class BorrowedAvcAacRead(
    val originalFd: FileDescriptor,
    val channel: FileChannel,
    val extentBytes: Long,
) {
    fun sha256(checkpoint: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64 * 1024)
        var position = 0L
        while (position < extentBytes) {
            checkpoint(); buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(), extentBytes - position).toInt())
            val count = channel.read(buffer, position)
            if (count <= 0) invalidAvcAacOutput()
            buffer.flip(); digest.update(buffer); position += count
        }
        if (channel.size() != extentBytes) invalidAvcAacOutput()
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    /** Caller invokes only after actual native release, while its original descriptor is still open. */
    fun requireUnchanged(initialHash: String, checkpoint: () -> Unit) {
        if (channel.size() != extentBytes || sha256(checkpoint) != initialHash) invalidAvcAacOutput()
    }
}

/** Technical observations only. Neither these nor a successful native release issue product proof. */
internal data class AvcAacOutputObservations(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val firstVideoPtsUs: Long,
    val lastVideoPtsUs: Long,
    val videoSamples: Long,
    val audioSamples: Long,
    val decodedVideoFrames: Long,
    val decodedAudioFrames: Long,
    /** Maximum over every independently scanned and exactly decoded video PTS, fixed zero origin. */
    val maximumFixedVideoDeviationUs: Long,
    /** Null until the independent decoder's native AAC/priming coordinate profile is accepted. */
    val presentedAudio: AvcAacPresentedAudio? = null,
)

internal data class AvcAacInspectionResult(
    val observations: AvcAacOutputObservations?,
    val failure: Throwable?,
    val nativeReleaseConfirmed: Boolean,
) {
    fun observationsOrThrow(): AvcAacOutputObservations {
        failure?.let { throw it }
        if (!nativeReleaseConfirmed) throw AvcAacVerificationFailure(AvcAacVerificationError.RELEASE_UNCONFIRMED)
        return observations ?: invalidAvcAacOutput()
    }
}

/**
 * Production cleanup owner shared by the Android body and host lifecycle tests. Resources are only
 * codecs/extractors, never the input FD/channel/storage fence. A failed release is attempted once,
 * remains strongly held here, and is sticky even if a later cleanup call is made.
 */
internal class AvcAacNativeResources {
    private data class Resource(val owner: Any, val stop: (() -> Unit)?, val release: () -> Unit,
        var started: Boolean = false, var attempted: Boolean = false)
    private val resources = ArrayList<Resource>()
    var cleanupFinished = false; private set
    private var releaseConfirmed = true
    var cleanupFailure: AvcAacVerificationFailure? = null; private set
    val nativeReleaseConfirmed: Boolean get() = cleanupFinished && releaseConfirmed

    fun add(owner: Any, stop: (() -> Unit)? = null, release: () -> Unit) {
        check(!cleanupFinished)
        resources.add(Resource(owner, stop, release))
    }
    fun started(owner: Any) { resources.single { it.owner === owner }.started = true }
    fun release(owner: Any) {
        val resource = resources.singleOrNull { it.owner === owner } ?: return
        if (resource.attempted) return
        resource.attempted = true
        var stopped = true
        if (resource.started) try { resource.stop?.invoke() } catch (_: Throwable) { stopped = false }
        try {
            resource.release()
            resources.remove(resource)
        } catch (_: Throwable) {
            releaseConfirmed = false
            throw AvcAacVerificationFailure(AvcAacVerificationError.RELEASE_UNCONFIRMED)
        }
        if (!stopped) {
            val failure = AvcAacVerificationFailure(AvcAacVerificationError.VERIFICATION_FAILED)
            if (cleanupFailure == null) cleanupFailure = failure
            throw failure
        }
    }
    fun releaseAll(): Boolean {
        if (cleanupFinished) return releaseConfirmed
        resources.toList().asReversed().forEach {
            try { release(it.owner) } catch (_: Throwable) { /* Actual release status is kept separately. */ }
        }
        if (resources.isNotEmpty()) releaseConfirmed = false
        cleanupFinished = true
        return releaseConfirmed
    }
}

/**
 * Synchronous one-shot borrowed inspection. It enters no lane/gate. All body exits release native
 * resources, including cancellation. The owning caller must retain this object on uncertainty and
 * query its sticky state even on failure; a returned snapshot does not replace that ownership.
 * Injection is the exact native body seam for host cleanup tests, not a native-success substitute.
 */
internal class BorrowedAvcAacInspection(
    private val read: BorrowedAvcAacRead,
    private val inspectBody: (BorrowedAvcAacRead, AvcAacNativeResources, AvcAacOutputExpectation, () -> Unit) -> AvcAacOutputObservations =
        AndroidAvcAacOutputInspection::inspect,
) {
    private val resources = AvcAacNativeResources()
    private var inspectionStarted = false
    private var completedResult: AvcAacInspectionResult? = null
    val nativeReleaseConfirmed: Boolean get() = resources.nativeReleaseConfirmed
    val isPristine: Boolean get() = !inspectionStarted && !resources.cleanupFinished
    val successfulInspection: Boolean get() = completedResult?.let {
        it.observations != null && it.failure == null && it.nativeReleaseConfirmed && nativeReleaseConfirmed
    } == true
    /** Identity only; grants no IO, close, success or durable authority. */
    fun borrows(descriptor: FileDescriptor, channel: FileChannel, extent: Long): Boolean =
        read.originalFd === descriptor && read.channel === channel && read.extentBytes == extent

    fun inspect(expected: AvcAacOutputExpectation, checkpoint: () -> Unit): AvcAacInspectionResult {
        check(!inspectionStarted && !resources.cleanupFinished)
        inspectionStarted = true
        var observations: AvcAacOutputObservations? = null
        var failure: Throwable? = null
        try {
            checkpoint()
            observations = inspectBody(read, resources, expected, checkpoint)
        } catch (caught: Throwable) { failure = caught }
        finally { resources.releaseAll() }
        // A failed stop with a successful release is an ordinary inspection failure, not uncertain
        // ownership. Preserve it even when the shared finally is the first place cleanup was run.
        if (failure == null) failure = resources.cleanupFailure
        return AvcAacInspectionResult(observations, failure, nativeReleaseConfirmed).also { completedResult = it }
    }

    /** Idempotent actual cleanup; it never closes or releases the caller's borrowed read. */
    fun releaseNative(): Boolean = resources.releaseAll()
}

/** Preserve the body's selected primary while attempting every independent cleanup obligation. */
internal inline fun <T> withAvcAacCleanup(releases: List<() -> Unit>, body: () -> T): T {
    var primary: Throwable? = null
    try { return body() }
    catch (failure: Throwable) { primary = failure; throw failure }
    finally {
        var cleanupFailure: Throwable? = null
        for (release in releases) {
            try { release() }
            catch (failure: Throwable) {
                val selected = primary ?: cleanupFailure
                if (selected == null) cleanupFailure = failure
                else if (selected !== failure) selected.addSuppressed(failure)
            }
        }
        if (primary == null) cleanupFailure?.let { throw it }
    }
}
