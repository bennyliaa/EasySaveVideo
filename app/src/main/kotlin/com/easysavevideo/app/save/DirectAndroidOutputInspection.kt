package com.easysavevideo.app.save

import com.easysavevideo.app.save.normalization.MovProbeInput
import com.easysavevideo.app.save.normalization.MovProfile
import com.easysavevideo.app.save.normalization.MovProfileException
import com.easysavevideo.app.save.normalization.MovProfileFailure
import com.easysavevideo.app.save.normalization.MovProfileProbe
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.*
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Integration port, intentionally absent from production until adopted-generation ownership is
 * implemented. acquire must validate record.selectedOutputDescriptor(), the caller's existing exact
 * execution/local claim and exclusive mutation/publication fence, then open the adopted generation
 * exactly once in read-only mode. It must not acquire/wait for Direct, serial or native lanes again.
 * The owner validates task/operation, full proof, exact ADOPTED generation and directory ownership.
 * A terminal task, including SUCCEEDED, cannot yield this capability. Failed acquisition closes all
 * acquired resources independently and retains its own recovery fence if release is unconfirmed.
 * Passing a caller boolean or an arbitrary pathname is not an implementation of this contract.
 */
interface DirectNormalizedOutputOwner {
    /** Includes an acquire failure that could not confirm release of a not-yet-returned resource. */
    fun requiresProcessRecovery(): Boolean
    fun acquire(artifact: StagingArtifact, output: ArtifactOutputDescriptor.NormalizedDirect,
        context: OperationContext): DirectNormalizedOutputLease
}

/**
 * readOnlyInput remains the same open descriptor for this lease. revalidate checks the authoritative
 * record and adopted generation without reopening/replacing that descriptor. close must actually
 * close the descriptor before releasing its ownership fence; a failed close retains the owner's
 * recovery fence. These are storage integration obligations, not claims made by the native parser.
 */
interface DirectNormalizedOutputLease {
    val artifact: StagingArtifact
    val output: ArtifactOutputDescriptor.NormalizedDirect
    val readOnlyInput: RandomAccessFile
    fun revalidate()
    /** One-way, idempotent, nonthrowing memory-only latch; no reverse locks. Cannot be cleared by close. */
    fun retainOnNativeReleaseUncertainty()
    /** True only after all lease release obligations are confirmed and its ownership fence can retire. */
    val releaseConfirmed: Boolean
    /** Always attempts the owned descriptor close; a latched native uncertainty retains the fence. */
    fun close()
}

internal object DirectNormalizedPreflight {
    fun check(artifact: StagingArtifact, output: ArtifactOutputDescriptor.NormalizedDirect,
        context: OperationContext) {
        val proof = output.normalizationProof
        if (proof.artifactId != artifact.artifactId || proof.commitId != artifact.commitId ||
            !proof.matchesTransfer(output.sourceTransfer)) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        if (proof.schemaVersion != 1 || proof.engineRevision != 1 || proof.policyRevision != 1 ||
            !proof.noEncoding || proof.profile != DirectNormalizationProfile.QT_MOV_AVC_BASELINE_AAC_LC_V1 ||
            proof.conservation.audio != null || proof.conservation.audioSampleRate != null ||
            proof.conservation.audioChannelCount != null) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        if (output.sizeBytes !in 1..DirectNormalizationLimits.MAX_BYTES ||
            context.resourceBudget.maxBytes?.let { output.sizeBytes > it } == true)
            throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
    }

    fun acquire(owner: DirectNormalizedOutputOwner, artifact: StagingArtifact,
        output: ArtifactOutputDescriptor.NormalizedDirect, context: OperationContext,
        markReleaseUnconfirmed: () -> Unit): DirectNormalizedOutputLease {
        check(artifact, output, context)
        if (owner.requiresProcessRecovery()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        return try { directLocalIo { owner.acquire(artifact, output, context) } }
        catch (failure: Exception) {
            if (owner.requiresProcessRecovery()) {
                markReleaseUnconfirmed()
                if (failure is SaveFailure && failure.code in setOf(SaveError.CANCELLED,
                        SaveError.DEADLINE_EXCEEDED, SaveError.RETRY_EXHAUSTED)) throw failure
                throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            }
            throw (failure as? SaveFailure ?: SaveFailure(SaveError.RECOVERY_REQUIRED))
        }
    }

    fun checkLease(lease: DirectNormalizedOutputLease, artifact: StagingArtifact,
        output: ArtifactOutputDescriptor.NormalizedDirect) {
        if (lease.artifact != artifact || lease.output !== output) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        try { lease.revalidate() }
        catch (failure: SaveFailure) { throw failure }
        catch (_: Exception) { throw SaveFailure(SaveError.RECOVERY_REQUIRED) }
    }
}

/** FD facts, never a filename. A fixed dev/inode/extent is checked around every bounded read. */
internal data class DirectFileIdentity(val device: Long, val inode: Long, val size: Long)

/** Caller owns this descriptor. This adapter never closes it and never obtains a new live extent. */
internal class FixedDirectOutputInput(
    private val input: RandomAccessFile,
    private val captured: DirectFileIdentity,
    private val identity: () -> DirectFileIdentity,
    private val checkpoint: () -> Unit,
) : MovProbeInput {
    override val length: Long = captured.size
    init { if (length !in 1..DirectNormalizationLimits.MAX_BYTES) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED) }

    fun checkIdentity() {
        checkpoint()
        val actual = directLocalIo { identity() }
        if (actual != captured || directLocalIo { input.length() } != length)
            throw SaveFailure(SaveError.RESOURCE_CHANGED)
    }

    override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > this.length || length > this.length - offset ||
            targetOffset < 0 || targetOffset > target.size || length > target.size - targetOffset)
            throw SaveFailure(SaveError.INVALID_MEDIA)
        checkIdentity()
        try { directLocalIo { input.seek(offset); input.readFully(target, targetOffset, length) } }
        catch (failure: SaveFailure) {
            // A concurrent truncation is RESOURCE_CHANGED; stable local I/O is STORAGE_FAILURE.
            checkIdentity()
            throw failure
        }
        checkIdentity()
    }

    fun requireHash(expected: String): String = sha256().also {
        if (it != expected) throw SaveFailure(SaveError.RESOURCE_CHANGED)
    }

    fun sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(64 * 1024)
        var offset = 0L
        checkIdentity()
        while (offset < length) {
            val count = minOf(bytes.size.toLong(), length - offset).toInt()
            readAt(offset, bytes, 0, count)
            digest.update(bytes, 0, count)
            offset += count
        }
        checkIdentity()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Android-shaped facts are copied through bounded duplicate buffers, preserving caller positions. */
internal data class DirectNativeTrackFormat(
    val trackCount: Int, val mime: String, val width: Int, val height: Int, val durationUs: Long,
    val initializationData: List<ByteArray>, val hasExtraInitializationData: Boolean,
    val rotation: Int? = null, val cropLeft: Int? = null, val cropRight: Int? = null,
    val cropTop: Int? = null, val cropBottom: Int? = null, val sarWidth: Int? = null,
    val sarHeight: Int? = null, val colorStandard: Int? = null, val colorTransfer: Int? = null,
    val colorRange: Int? = null, val hasProtection: Boolean = false, val hdrMetadata: Boolean = false,
)

internal fun directInitializationData(buffers: List<ByteBuffer>): List<ByteArray> {
    if (buffers.size != 2 || buffers.any { it.remaining() <= 0 } ||
        buffers.sumOf { it.remaining().toLong() } > DirectNormalizationLimits.MAX_CONFIGURATION_BYTES)
        throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
    return buffers.map { source -> ByteArray(source.remaining()).also { source.duplicate().get(it) } }
}

internal fun inspectDirectFormat(actual: DirectNativeTrackFormat, profile: MovProfile,
    checkpoint: () -> Unit): DirectMovAvcConfiguration {
    checkpoint()
    if (actual.hasProtection) throw SaveFailure(SaveError.PROTECTED_MEDIA)
    if (actual.trackCount != 1 || actual.mime != "video/avc" ||
        actual.width != profile.width || actual.height != profile.height)
        throw SaveFailure(SaveError.INVALID_MEDIA)
    val endpoint = profile.clock.endpoint
    // Metadata only: never used for samples, indexed PTS, mdhd/stts or tail evidence.
    if (actual.durationUs <= 0 || actual.durationUs < endpoint.minimumUs - DIRECT_METADATA_TOLERANCE_US ||
        actual.durationUs > endpoint.maximumUs + DIRECT_METADATA_TOLERANCE_US)
        throw SaveFailure(SaveError.INVALID_MEDIA)
    if (actual.hdrMetadata || actual.rotation?.let { it != 0 } == true ||
        actual.cropLeft?.let { it != 0 } == true || actual.cropTop?.let { it != 0 } == true ||
        actual.cropRight?.let { it != profile.width - 1 } == true ||
        actual.cropBottom?.let { it != profile.height - 1 } == true ||
        ((actual.sarWidth == null) != (actual.sarHeight == null)) ||
        actual.sarWidth?.let { it <= 0 || it != actual.sarHeight } == true ||
        actual.colorStandard?.let { it != 1 } == true || // COLOR_STANDARD_BT709
        actual.colorTransfer?.let { it != 3 } == true || // COLOR_TRANSFER_SDR_VIDEO
        actual.colorRange?.let { it != 2 } == true) // COLOR_RANGE_LIMITED
        throw SaveFailure(SaveError.INVALID_MEDIA)
    if (actual.hasExtraInitializationData) throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
    val configuration = directNativeRepresentation {
        DirectMovCanonical.avcConfigurationFromInitializationData(actual.initializationData, checkpoint)
    }
    if (configuration.sha256 != profile.configuration.sha256 ||
        !configuration.sps.contentEquals(profile.configuration.sps) ||
        !configuration.pps.contentEquals(profile.configuration.pps)) throw SaveFailure(SaveError.INVALID_MEDIA)
    return configuration
}

/** Minimal native-observation seam, shared by the real extractor adapter and host policy tests. */
internal interface DirectNativeSampleSource {
    val timeUs: Long
    val trackIndex: Int
    val size: Long
    val flags: Int
    fun seekPreviousSync(timeUs: Long)
    fun read(target: ByteBuffer): Int
    fun advance()
}

/** An explicitly clear flag is not protection evidence; unreadable native facts are unavailable. */
internal fun directExplicitEncryptionFlag(present: Boolean, readInteger: () -> Int): Boolean =
    present && directNativeCall { readInteger() != 0 }

internal fun directAndroidSync(flags: Int): Int {
    if (flags and 2 != 0) throw SaveFailure(SaveError.PROTECTED_MEDIA) // SAMPLE_FLAG_ENCRYPTED
    // Android flags are NOT Media3 callback flags: partial frames and LAST_SAMPLE are not admitted.
    if (flags and 1.inv() != 0) throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
    return flags and 1
}

internal data class DirectInspectedSamples(val conservation: DirectRemuxConservation, val tail: DirectVideoTailPlan)

internal fun inspectDirectSamples(source: DirectNativeSampleSource, track: Int, input: MovProbeInput,
    profile: MovProfile, configuration: DirectMovAvcConfiguration, checkpoint: () -> Unit): DirectInspectedSamples {
    val expected = profile.conservation
    val tailStart = expected.precedingVideoSync.index
    var tailBytes = 0L
    // Bound compressed work before retaining even the first compact identity; earlier GOPs do not count.
    for (sample in profile.samples) if (sample.index >= tailStart) {
        checkpoint()
        if (sample.size.toLong() > DIRECT_TAIL_BYTES - tailBytes) throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
        tailBytes += sample.size
    }
    val builder = DirectVideoTailPlan.Builder(profile.clock, expected, checkpoint)
    val accumulator = DirectMovSampleAccumulator(profile.clock, configuration.sha256, checkpoint)
    val buffer = ByteBuffer.allocateDirect(DirectNormalizationLimits.MAX_SAMPLE_BYTES)
    source.seekPreviousSync(0)
    for (sample in profile.samples) {
        checkpoint()
        val observed = readDirectNativeSample(source, track, sample.index.toLong(), profile.clock, buffer, checkpoint)
        if (observed.identity.flags != if (sample.sync) 1 else 0) throw SaveFailure(SaveError.INVALID_MEDIA)
        val raw = directRawScan { MovProfileProbe.readCanonicalSample(input, profile, sample.index, checkpoint) }
        if (!observed.canonical.contentEquals(raw)) throw SaveFailure(SaveError.INVALID_MEDIA)
        val compact = directRawScan { accumulator.append(observed.canonical, observed.identity.presentationTimeUs,
            observed.identity.flags) }
        if (compact != observed.identity) throw SaveFailure(SaveError.INVALID_MEDIA)
        if (sample.index >= tailStart) builder.append(DirectNativeSample(compact, observed.nativeBytes))
        source.advance()
    }
    requireDirectEof(source, buffer, checkpoint)
    val result = directRawScan { accumulator.finish() }
    val actual = DirectRemuxConservation(profile.width, profile.height, profile.frameRate,
        result.track, null, null, null, result.precedingVideoSync ?: throw SaveFailure(SaveError.INVALID_MEDIA))
    if (actual != expected) throw SaveFailure(SaveError.INVALID_MEDIA)
    return DirectInspectedSamples(actual, builder.finish())
}

internal data class DirectReadSample(val identity: DirectSampleIdentity, val nativeBytes: Int, val canonical: ByteArray)

internal fun readDirectNativeSample(source: DirectNativeSampleSource, track: Int, index: Long,
    clock: DirectMovClock, buffer: ByteBuffer, checkpoint: () -> Unit): DirectReadSample {
    checkpoint()
    if (index !in 0 until clock.sampleCount || source.trackIndex != track ||
        source.timeUs != clock.presentationTimeUs(index)) throw SaveFailure(SaveError.INVALID_MEDIA)
    val flags = directAndroidSync(source.flags)
    val time = source.timeUs
    val size = source.size
    if (size !in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES.toLong()) throw SaveFailure(SaveError.INVALID_MEDIA)
    if (size > buffer.capacity()) throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
    buffer.clear()
    val count = source.read(buffer)
    if (count.toLong() != size) throw SaveFailure(SaveError.INVALID_MEDIA)
    buffer.position(0); buffer.limit(count)
    val bytes = ByteArray(count).also { buffer.duplicate().get(it) }
    val canonical = directNativeRepresentation { DirectMovCanonical.avcSample(bytes, DirectMovNalFraming.ANNEX_B, checkpoint) }
    val identity = DirectSampleIdentity(index, time, canonical.size,
        DirectMovCanonical.sampleSha256(canonical, checkpoint), flags)
    checkpoint()
    return DirectReadSample(identity, count, canonical)
}

internal fun requireDirectEof(source: DirectNativeSampleSource, buffer: ByteBuffer, checkpoint: () -> Unit) {
    checkpoint()
    if (source.timeUs != -1L || source.trackIndex != -1 || source.size != -1L)
        throw SaveFailure(SaveError.INVALID_MEDIA)
    buffer.clear()
    if (source.read(buffer) != -1) throw SaveFailure(SaveError.INVALID_MEDIA)
    checkpoint()
}

/** Cleanup calls are independent. Only a genuinely unconfirmed release latches process recovery. */
internal fun finishDirectCleanup(primary: Throwable?, markReleaseUnconfirmed: () -> Unit,
    stop: (() -> Unit)? = null, releases: List<() -> Unit>, after: (() -> Unit)? = null) {
    var ordinaryFailure: Throwable? = null
    var unconfirmed = false
    try { stop?.invoke() } catch (failure: Throwable) { ordinaryFailure = failure }
    for (release in releases) try { release() } catch (_: Throwable) {
        unconfirmed = true
        // The supplied latch is nonthrowing; still do not let a broken integration skip cleanup.
        try { markReleaseUnconfirmed() } catch (_: Throwable) { /* release remains unconfirmed */ }
    }
    try { after?.invoke() } catch (failure: Throwable) { if (ordinaryFailure == null) ordinaryFailure = failure }
    // Caller stop reason remains authoritative while the independent recovery latch still blocks.
    if (primary is SaveFailure && primary.code in setOf(SaveError.CANCELLED, SaveError.DEADLINE_EXCEEDED, SaveError.RETRY_EXHAUSTED)) return
    if (unconfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    if (primary == null && ordinaryFailure != null) throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
}

internal inline fun <T> directLocalIo(action: () -> T): T = try { action() }
catch (failure: SaveFailure) { throw failure }
catch (_: IOException) { throw SaveFailure(SaveError.STORAGE_FAILURE) }
catch (_: SecurityException) { throw SaveFailure(SaveError.STORAGE_FAILURE) }

internal inline fun <T> directNativeCall(action: () -> T): T = try { action() }
catch (failure: SaveFailure) { throw failure }
catch (_: Exception) { throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE) }

internal inline fun <T> directNativeRepresentation(action: () -> T): T = try { action() }
catch (failure: SaveFailure) { throw failure }
catch (_: IllegalArgumentException) { throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE) }

internal inline fun <T> directRawScan(action: () -> T): T = try { action() }
catch (failure: SaveFailure) { throw failure }
catch (failure: MovProfileException) { throw SaveFailure(when (failure.failure) {
    MovProfileFailure.PROTECTED_MEDIA -> SaveError.PROTECTED_MEDIA
    MovProfileFailure.AUDIO_NOT_ADMITTED -> SaveError.UNSUPPORTED_MEDIA
    MovProfileFailure.LIMIT -> SaveError.BYTE_LIMIT_EXCEEDED
    else -> SaveError.INVALID_MEDIA
}) }
catch (_: IOException) { throw SaveFailure(SaveError.STORAGE_FAILURE) }
catch (_: IllegalArgumentException) { throw SaveFailure(SaveError.INVALID_MEDIA) }

private const val DIRECT_METADATA_TOLERANCE_US = 1_000L
internal const val DIRECT_TAIL_BYTES = 64L * 1024 * 1024
