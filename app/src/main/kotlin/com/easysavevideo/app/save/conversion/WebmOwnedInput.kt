package com.easysavevideo.app.save.conversion

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractorInput
import java.io.IOException

/**
 * Borrowed, caller-owned, completed fixed extent. This interface neither grants storage authority
 * nor opens paths/URIs nor closes a descriptor. The caller must already own the exact artifact,
 * enforce revocation/identity here, and retain its normal close/hash/publication obligations.
 */
internal interface WebmOwnedInput {
    val length: Long
    fun validateIdentity()
    /** Return positive bytes, or throw. A zero-progress/short-to-EOF read is never completion. */
    fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int): Int
}

/** Accounting only, never an input/storage capability. The registered attempt owns this budget. */
internal interface WebmAttemptBudget {
    fun request(bytes: Int)
    fun event()
}

/** Closed construction port. The paired loader's registered single-use token is the sole subtype. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal sealed class WebmSequentialInput {
    internal abstract fun claim(binding: WebmInputBinding): DataReader
}

internal enum class WebmProbeFailure {
    INPUT_EXTENT, IDENTITY, IO_BUDGET, READ_REQUEST, TRUNCATED, ZERO_PROGRESS, FOREIGN_INPUT,
    HEADER_CANDIDATES, HEADER_CALLBACK, UNKNOWN_SIZE, MASTER_EXTENT, MASTER_DEPTH,
    UNKNOWN_NUMERIC_END, ELEMENT_LIMIT, UNSUPPORTED_ELEMENT, ELEMENT_CONTEXT, FIELD_VALUE,
    DUPLICATE_FIELD, IDENTIFIER, CONFIG_LIMIT, METADATA_LIMIT, TRACK_INVENTORY, TRACK_LIMIT,
    TRACK_ID, TRACK_PROFILE, PROTECTED_MEDIA, SAMPLE_LIMIT, SAMPLE_BOUNDARY, INCOMPLETE,
    SEEK_UNSUPPORTED, CANCELLED, DEADLINE, MALFORMED,
}
internal class WebmProbeException(val failure: WebmProbeFailure) : IOException("WEBM_${failure.name}")
internal fun requireWebm(value: Boolean, failure: WebmProbeFailure) {
    if (!value) throw WebmProbeException(failure)
}

/** Preserve caller-owned stop/identity/IO failures separately from trusted-parser failures. */
internal class WebmCallerFailure(val original: Exception) : RuntimeException(null, original, false, false)
internal fun <T> webmCaller(block: () -> T): T = try {
    block()
} catch (e: WebmProbeException) {
    throw e
} catch (e: WebmCallerFailure) {
    throw e
} catch (e: Exception) {
    throw WebmCallerFailure(e)
}

internal data class WebmProbeLimits(
    val maxInputBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxIoBytes: Long,
    val maxElementsAndSamples: Long = 2_000_000,
    val maxOutstandingMasters: Int = 16,
    val maxSampleBytes: Int = 4 * 1024 * 1024,
    val maxConfigurationBytes: Int = 64 * 1024,
    val maxAllConfigurationBytes: Int = 256 * 1024,
    val maxMetadataBytes: Int = 1024 * 1024,
    val maxIdentifierBytes: Int = 128,
    val maxPeekWindowBytes: Int = 4 * 1024 * 1024,
) {
    init {
        require(maxInputBytes in 1..2L * 1024 * 1024 * 1024 && maxIoBytes > 0)
        require(maxElementsAndSamples in 1..2_000_000 && maxOutstandingMasters in 1..16)
        require(maxSampleBytes in 1..4 * 1024 * 1024 && maxConfigurationBytes in 1..64 * 1024)
        require(maxAllConfigurationBytes in maxConfigurationBytes..256 * 1024)
        require(maxMetadataBytes in 1..1024 * 1024 && maxIdentifierBytes in 1..128)
        require(maxPeekWindowBytes in 1..4 * 1024 * 1024)
    }
}

/** One shared budget/binding, not one replenished allowance per extractor or reader. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class WebmInputBinding(
    private val borrowed: WebmOwnedInput,
    val limits: WebmProbeLimits,
    private val attemptBudget: WebmAttemptBudget?,
    private val checkpoint: () -> Unit,
) {
    constructor(borrowed: WebmOwnedInput, limits: WebmProbeLimits, checkpoint: () -> Unit) :
        this(borrowed, limits, null, checkpoint)
    val extent: Long = webmCaller { borrowed.length }
    private val budgetLock = Any()
    @Volatile var chargedBytes: Long = 0; private set
    @Volatile var elementsAndSamples: Long = 0; private set
    var headerChecks: Long = 0; internal set
    var payloadDelegations: Long = 0; internal set

    init { requireWebm(extent in 1..limits.maxInputBytes, WebmProbeFailure.INPUT_EXTENT); check() }
    fun check() {
        webmCaller { checkpoint() } // Real caller stop reasons survive as their original exception.
        webmCaller { borrowed.validateIdentity() }
        requireWebm(webmCaller { borrowed.length } == extent, WebmProbeFailure.IDENTITY)
    }
    fun charge(bytes: Int) {
        check()
        synchronized(budgetLock) {
            requireWebm(bytes >= 0 && bytes.toLong() <= limits.maxIoBytes - chargedBytes, WebmProbeFailure.IO_BUDGET)
            chargedBytes += bytes
        }
        webmCaller { attemptBudget?.request(bytes) }
    }
    fun event() {
        check()
        synchronized(budgetLock) {
            requireWebm(elementsAndSamples < limits.maxElementsAndSamples, WebmProbeFailure.ELEMENT_LIMIT)
            elementsAndSamples++
        }
        webmCaller { attemptBudget?.event() }
    }
    fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int) {
        requireWebm(length >= 0 && length <= limits.maxSampleBytes && offset >= 0 && offset <= extent &&
            length.toLong() <= extent - offset && targetOffset >= 0 && targetOffset <= target.size &&
            length <= target.size - targetOffset, WebmProbeFailure.READ_REQUEST)
        var done = 0
        while (done < length) {
            charge(length - done)
            val count = webmCaller { borrowed.readAt(offset + done, target, targetOffset + done, length - done) }
            requireWebm(count > 0, if (count == 0) WebmProbeFailure.ZERO_PROGRESS else WebmProbeFailure.TRUNCATED)
            requireWebm(count <= length - done, WebmProbeFailure.READ_REQUEST)
            done += count
        }
        check()
    }
    fun suffix(contentPosition: Long): ByteArray {
        requireWebm(contentPosition in 1..extent, WebmProbeFailure.HEADER_CALLBACK)
        val count = minOf(12L, contentPosition).toInt()
        return ByteArray(count).also { readAt(contentPosition - count, it, 0, count) }
    }
    fun newInput(boundary: () -> Long, owned: WebmSequentialInput): WebmGuardedInput {
        check()
        val reader = owned.claim(this)
        return WebmGuardedInput(DefaultExtractorInput(reader, 0, extent), this, boundary)
    }
    fun newInput(boundary: () -> Long): WebmGuardedInput {
        check()
        val reader = object : DataReader {
            var position = 0L
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                check()
                requireWebm(length >= 0 && length <= limits.maxSampleBytes, WebmProbeFailure.READ_REQUEST)
                if (length == 0) return 0
                if (position == extent) return C.RESULT_END_OF_INPUT
                val count = minOf(length.toLong(), extent - position).toInt()
                readAt(position, buffer, offset, count); position += count
                return count
            }
        }
        return WebmGuardedInput(DefaultExtractorInput(reader, 0, extent), this, boundary)
    }
}

/** Validate requested ranges before the pinned delegate can allocate its peek buffer. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class WebmGuardedInput(
    delegate: ExtractorInput,
    val binding: WebmInputBinding,
    private val boundary: () -> Long,
) : ForwardingExtractorInput(delegate) {
    private fun range(position: Long, length: Int, fully: Boolean, allowEof: Boolean): Int {
        binding.check()
        requireWebm(length >= 0 && length <= binding.limits.maxSampleBytes, WebmProbeFailure.READ_REQUEST)
        val end = minOf(binding.extent, boundary())
        requireWebm(position >= 0 && position <= end, WebmProbeFailure.MASTER_EXTENT)
        val remaining = end - position
        if (fully && length.toLong() > remaining) {
            if (allowEof && remaining == 0L && position == binding.extent) return -1
            throw WebmProbeException(WebmProbeFailure.TRUNCATED)
        }
        val count = minOf(length.toLong(), remaining).toInt()
        binding.charge(count)
        return count
    }
    private fun peekRange(length: Int, fully: Boolean, allowEof: Boolean): Int {
        binding.check()
        val held = peekPosition - position
        requireWebm(held >= 0 && held <= binding.limits.maxPeekWindowBytes && length >= 0 &&
            length.toLong() <= binding.limits.maxPeekWindowBytes - held, WebmProbeFailure.READ_REQUEST)
        return range(peekPosition, length, fully, allowEof)
    }
    private fun checkedCount(count: Int, requested: Int): Int {
        binding.check()
        requireWebm(count > 0 || requested == 0 || (count == C.RESULT_END_OF_INPUT && position == binding.extent),
            if (count == 0) WebmProbeFailure.ZERO_PROGRESS else WebmProbeFailure.TRUNCATED)
        requireWebm(count <= requested, WebmProbeFailure.READ_REQUEST)
        return count
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = range(position, length, false, true)
        if (count == 0 && length > 0) return if (position == binding.extent) C.RESULT_END_OF_INPUT
            else throw WebmProbeException(WebmProbeFailure.TRUNCATED)
        return checkedCount(super.read(buffer, offset, count), count)
    }
    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        if (range(position, length, true, allowEndOfInput) == -1) return false
        val result = super.readFully(target, offset, length, allowEndOfInput)
        binding.check(); requireWebm(result || position == binding.extent, WebmProbeFailure.TRUNCATED)
        return result
    }
    override fun readFully(target: ByteArray, offset: Int, length: Int) { readFully(target, offset, length, false) }
    override fun skip(length: Int): Int {
        val count = range(position, length, false, true)
        if (count == 0 && length > 0) return if (position == binding.extent) C.RESULT_END_OF_INPUT
            else throw WebmProbeException(WebmProbeFailure.TRUNCATED)
        return checkedCount(super.skip(count), count)
    }
    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        if (range(position, length, true, allowEndOfInput) == -1) return false
        val result = super.skipFully(length, allowEndOfInput)
        binding.check(); requireWebm(result || position == binding.extent, WebmProbeFailure.TRUNCATED)
        return result
    }
    override fun skipFully(length: Int) { skipFully(length, false) }
    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        val count = peekRange(length, false, true)
        if (count == 0 && length > 0) return if (peekPosition == binding.extent) C.RESULT_END_OF_INPUT
            else throw WebmProbeException(WebmProbeFailure.TRUNCATED)
        val result = super.peek(target, offset, count)
        binding.check(); requireWebm(result > 0 || count == 0, WebmProbeFailure.ZERO_PROGRESS)
        return result
    }
    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        if (peekRange(length, true, allowEndOfInput) == -1) return false
        val result = super.peekFully(target, offset, length, allowEndOfInput)
        binding.check(); requireWebm(result || peekPosition == binding.extent, WebmProbeFailure.TRUNCATED)
        return result
    }
    override fun peekFully(target: ByteArray, offset: Int, length: Int) { peekFully(target, offset, length, false) }
    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        if (peekRange(length, true, allowEndOfInput) == -1) return false
        val result = super.advancePeekPosition(length, allowEndOfInput)
        binding.check(); requireWebm(result || peekPosition == binding.extent, WebmProbeFailure.TRUNCATED)
        return result
    }
    override fun advancePeekPosition(length: Int) { advancePeekPosition(length, false) }
    override fun resetPeekPosition() { binding.check(); super.resetPeekPosition() }
    override fun <E : Throwable?> setRetryPosition(position: Long, e: E): Nothing {
        throw WebmProbeException(WebmProbeFailure.SEEK_UNSUPPORTED)
    }
}
