package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.normalization.MovProbeInput
import com.easysavevideo.app.save.normalization.MovProfileException
import com.easysavevideo.app.save.normalization.MovProfileFailure
import com.easysavevideo.app.save.normalization.MovProfileProbe
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.save.*

/** Data comparison only. A matching tuple does not issue permission or prove a process restart. */
internal object DirectRawRecoveryPolicy {
    fun complete(record: SaveRecord): Boolean {
        val artifact = record.artifact ?: return false
        val transfer = record.transfer ?: return false
        return record.request.method == TaskMethod.DIRECT && record.task.method == TaskMethod.DIRECT &&
            record.request.networkSourceMetadata != null && artifact.commitId == record.commitId &&
            transfer.artifactId == artifact.artifactId && transfer.bytesOnDisk > 0 &&
            transfer.expectedTotalBytes?.let { it == transfer.bytesOnDisk } != false &&
            transfer.streamExpectation == null && transfer.recordingExpectation == null &&
            !record.hasDirectOutputProof() && record.receipt == null
    }

    /** Explicit resume may change operation/control fields, never the completed raw value graph. */
    fun sameRawTuple(prior: SaveRecord, current: SaveRecord): Boolean =
        complete(prior) && complete(current) && prior.request == current.request &&
            prior.task.taskId == current.task.taskId && prior.task.method == current.task.method &&
            prior.commitId == current.commitId && prior.artifact == current.artifact &&
            prior.transfer == current.transfer && prior.bytesTransferred == current.bytesTransferred &&
            prior.checkpoint == current.checkpoint && prior.schemaVersion == current.schemaVersion

    fun preLocal(record: SaveRecord): Boolean = record.phase in setOf(SavePhase.EXECUTING, SavePhase.NORMALIZING) &&
        record.verification == null

    fun permitsFreshMov(origin: SaveRecord, beforeResume: SaveRecord?, current: SaveRecord,
        enteredLegacy: Boolean): Boolean = !enteredLegacy && sameRawTuple(origin, current) &&
        preLocal(origin) && preLocal(current) &&
        (beforeResume == null || (sameRawTuple(origin, beforeResume) && preLocal(beforeResume)))
}

/** Readonly branch selection. Ordinary MP4 retains the existing container preflight acceptance. */
internal object DirectRawFormatSelector {
    private val mp4Brands = setOf("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1")
    fun select(input: MovProbeInput, expectedAudio: Boolean, checkpoint: () -> Unit = {}): DirectRawKind {
        checkpoint()
        // Four bounded signature bytes select only the existing owned WebM attempt. Its paired
        // probe still decides the complete profile, including audio; this is no second parser.
        if (input.length >= 4 && u32(read(input, 0, 4), 0) == 0x1A45DFA3L)
            return DirectRawKind.WEBM_CANDIDATE
        if (input.length < 32) unsupported()
        var offset = 0L
        var boxes = 0
        var brand: String? = null
        var quickTime = false
        var moov = false
        var mdat = false
        while (offset < input.length) {
            checkpoint()
            if (++boxes > MovProfileProbe.MAX_BOXES || input.length - offset < 8) unsupported()
            val header = read(input, offset, 8)
            val short = u32(header, 0)
            val type = tag(header, 4)
            val headerSize = if (short == 1L) 16L else 8L
            val size = when (short) {
                0L -> input.length - offset
                1L -> {
                    if (input.length - offset < 16) unsupported()
                    val wide = read(input, offset + 8, 8)
                    if (wide[0].toInt() < 0) unsupported()
                    (u32(wide, 0) shl 32) or u32(wide, 4)
                }
                else -> short
            }
            if (size < headerSize || size > input.length - offset) unsupported()
            when (type) {
                "ftyp" -> {
                    val count = size - headerSize
                    if (brand != null || offset > 4096 || count !in 8L..4096L || count % 4 != 0L) unsupported()
                    val bytes = read(input, offset + headerSize, count.toInt())
                    brand = tag(bytes, 0)
                    quickTime = brand == "qt  "
                    var i = 8
                    while (i < bytes.size) { if (tag(bytes, i) == "qt  ") quickTime = true; i += 4 }
                }
                "moov" -> { if (moov || size <= headerSize) unsupported(); moov = true }
                "mdat" -> { if (size <= headerSize) unsupported(); mdat = true }
            }
            offset += size
        }
        if (brand == null || !moov || !mdat || offset != input.length) unsupported()
        if (!quickTime) {
            if (brand !in mp4Brands || boxes > 4096) unsupported()
            // Decoder/native inspection remains the acceptance authority for ordinary MP4,
            // including its established audio/track handling. Do not apply MOV's strict profile.
            return DirectRawKind.ALREADY_MP4
        }
        try {
            val profile = MovProfileProbe.probeQuickTime(input, checkpoint)
            if (expectedAudio && profile.conservation.audio == null) unsupported()
        } catch (failure: MovProfileException) {
            throw SaveFailure(if (failure.failure == MovProfileFailure.PROTECTED_MEDIA)
                SaveError.PROTECTED_MEDIA else SaveError.UNSUPPORTED_MEDIA)
        }
        return DirectRawKind.STRICT_MOV
    }
    private fun read(input: MovProbeInput, offset: Long, count: Int): ByteArray = ByteArray(count).also {
        input.readAt(offset, it, 0, count)
    }
    private fun u32(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { value, index ->
        (value shl 8) or (bytes[at + index].toLong() and 255)
    }
    private fun tag(bytes: ByteArray, at: Int): String = CharArray(4) { (bytes[at + it].toInt() and 255).toChar() }.concatToString()
    private fun unsupported(): Nothing = throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
}

/** Once transfer is durably complete, no local byte failure may reopen HTTP retry. */
internal fun completeRawFailure(failure: SaveFailure): SaveFailure =
    if (failure.code == SaveError.INCOMPLETE_TRANSFER) SaveFailure(SaveError.RESOURCE_CHANGED) else failure
