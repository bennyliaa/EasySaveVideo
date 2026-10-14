package com.easysavevideo.app.save.normalization

import com.easysavevideo.save.DirectMovAvcConfiguration

/**
 * Original bounded Baseline syntax policy. This deliberately narrower first profile accepts only
 * one short-term reference, reference I/P pictures, sequential frame_num, POC type 0 (+2 modulo)
 * or 2, no reference-list modifications, no MMCO, weighted prediction, FMO, ASO, redundant slices,
 * HRD, interlace or non-BT709/limited SDR. All slice headers are parsed, but CAVLC entropy/macroblock
 * data is NOT decoded: complete decoder validity and actual frame output remain the Android gate.
 * No HLS/AUD prerequisite is imported; an optional AUD is checked, never injected or removed.
 */
internal class MovAvcProfile(
    config: DirectMovAvcConfiguration,
    expectedWidth: Int,
    expectedHeight: Int,
    private val frameRate: Int,
    private val checkpoint: () -> Unit,
) {
    private val spsBytes = config.sps
    private val ppsBytes = config.pps
    private val sps = readSps(spsBytes, expectedWidth, expectedHeight)
    private val pps = readPps(ppsBytes)
    private var previousFrame = -1
    private var previousPoc = -1

    private data class Sps(val id: Int, val frameBits: Int, val pocType: Int, val pocBits: Int,
        val macroblocks: Int)
    private data class Pps(val id: Int, val initQp: Int, val deblock: Boolean)
    private data class Picture(val frame: Int, val idr: Boolean, val idrId: Int?, val poc: Int?,
        val ref: Int, val type: Int)
    private data class Slice(val firstMb: Int, val picture: Picture)

    fun sample(nals: List<ByteArray>, index: Int, sourceSync: Boolean) {
        checkpoint()
        requireAvc(nals.isNotEmpty() && nals.size <= 1024)
        var picture: Picture? = null
        var previousMb = -1
        var aud: Int? = null
        var sawVcl = false
        for ((nalIndex, nal) in nals.withIndex()) {
            checkpoint()
            requireAvc(nal.size >= 2)
            val header = nal[0].toInt() and 255
            requireAvc(header and 0x80 == 0)
            val type = header and 31
            val ref = (header ushr 5) and 3
            when (type) {
                7 -> requireAvc(!sawVcl && nal.contentEquals(spsBytes))
                8 -> requireAvc(!sawVcl && nal.contentEquals(ppsBytes))
                9 -> {
                    requireAvc(nalIndex == 0 && aud == null && ref == 0)
                    val b = Bits(rbsp(nal))
                    aud = b.bits(3)
                    requireAvc(aud == 0 || aud == 1) // Only I or I/P primary pictures.
                    b.trailing()
                }
                1, 5 -> {
                    requireAvc(ref != 0) // First profile has reference pictures only.
                    val slice = readSlice(nal, type == 5, ref)
                    if (picture == null) {
                        requireAvc(slice.firstMb == 0)
                        picture = slice.picture
                    } else requireAvc(slice.picture == picture && slice.firstMb > previousMb)
                    previousMb = slice.firstMb
                    sawVcl = true
                }
                else -> throw MovProfileException(MovProfileFailure.AVC_PROFILE)
            }
        }
        val p = picture ?: throw MovProfileException(MovProfileFailure.AVC_PROFILE)
        requireAvc(sourceSync == p.idr && (index != 0 || p.idr))
        if (aud == 0) requireAvc(p.type == 2)
        if (p.idr) {
            requireAvc(p.frame == 0 && (p.poc == null || p.poc == 0))
        } else {
            requireAvc(previousFrame >= 0 && p.frame == (previousFrame + 1) % (1 shl sps.frameBits))
            if (p.poc != null) requireAvc(previousPoc >= 0 && p.poc == (previousPoc + 2) % (1 shl sps.pocBits))
        }
        previousFrame = p.frame
        previousPoc = p.poc ?: -1
    }

    private fun readSps(nal: ByteArray, width: Int, height: Int): Sps {
        requireAvc(nal.size >= 5 && (nal[0].toInt() and 0x9f) == 7 && nal[0].toInt() and 0x60 != 0)
        val b = Bits(rbsp(nal))
        requireAvc(b.bits(8) == 66)
        requireAvc(b.bits(8) and 3 == 0)
        requireAvc(b.bits(8) in setOf(10, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51, 52))
        val id = b.ue(31)
        val frameBits = b.ue(12) + 4
        val pocType = b.ue(2)
        requireAvc(pocType == 0 || pocType == 2) // Type 1 cycles outside first bounded profile.
        val pocBits = if (pocType == 0) b.ue(12) + 4 else 0
        requireAvc(b.ue(16) == 1 && !b.bit()) // One reference, no frame_num gaps.
        val mbWidth = b.ue(119) + 1
        val mbHeight = b.ue(119) + 1
        requireAvc(b.bit()) // frame_mbs_only_flag
        b.bit() // direct_8x8_inference_flag does not enable B pictures.
        var left = 0; var right = 0; var top = 0; var bottom = 0
        if (b.bit()) { left = b.ue(959); right = b.ue(959); top = b.ue(959); bottom = b.ue(959) }
        requireAvc(mbWidth * 16 - 2 * (left + right) == width && mbHeight * 16 - 2 * (top + bottom) == height)
        requireAvc(width in 2..1920 && height in 2..1920 && width % 2 == 0 && height % 2 == 0 && width.toLong() * height <= 1920L * 1080)
        requireAvc(b.bit()) // VUI required: unknown color never silently becomes SDR.
        readVui(b)
        b.trailing()
        return Sps(id, frameBits, pocType, pocBits, mbWidth * mbHeight)
    }

    private fun readVui(b: Bits) {
        if (b.bit()) {
            when (b.bits(8)) {
                1 -> Unit
                255 -> { val sar = b.bits(16); requireAvc(sar > 0 && b.bits(16) == sar) }
                else -> throw MovProfileException(MovProfileFailure.AVC_PROFILE)
            }
        }
        requireAvc(!b.bit()) // Overscan/cropping display semantics outside profile.
        requireAvc(b.bit()) // video_signal_type_present_flag
        requireAvc(b.bits(3) == 5 && !b.bit() && b.bit()) // Unspecified video format, limited, color description.
        requireAvc(b.bits(8) == 1 && b.bits(8) == 1 && b.bits(8) == 1) // BT709 SDR.
        if (b.bit()) requireAvc(b.ue(5) == 0 && b.ue(5) == 0) // Default 4:2:0 chroma location only.
        if (b.bit()) {
            val units = b.longBits(32)
            val scale = b.longBits(32)
            requireAvc(units > 0 && scale > 0 && units * 2 * frameRate == scale && b.bit())
        }
        requireAvc(!b.bit() && !b.bit()) // No NAL/VCL HRD buffering model/SEI timing dependency.
        requireAvc(!b.bit()) // pic_struct_present_flag; no SEI picture-structure dependency.
        if (b.bit()) {
            b.bit() // motion_vectors_over_pic_boundaries_flag
            b.ue(16); b.ue(16); b.ue(16); b.ue(16)
            requireAvc(b.ue(16) == 0 && b.ue(16) == 1)
        }
    }

    private fun readPps(nal: ByteArray): Pps {
        requireAvc(nal.size >= 2 && (nal[0].toInt() and 0x9f) == 8 && nal[0].toInt() and 0x60 != 0)
        val b = Bits(rbsp(nal))
        val id = b.ue(255)
        requireAvc(b.ue(31) == sps.id)
        requireAvc(!b.bit() && !b.bit()) // CAVLC; no bottom-field POC.
        requireAvc(b.ue(7) == 0) // No FMO/slice groups.
        requireAvc(b.ue(31) == 0 && b.ue(31) == 0) // One active reference for each default list.
        requireAvc(!b.bit() && b.bits(2) == 0) // No weighted predictions.
        val qp = b.se()
        requireAvc(qp in -26..25 && b.se() in -26..25 && b.se() in -12..12)
        val deblock = b.bit()
        b.bit() // constrained_intra_pred_flag is safe and remains in exact PPS.
        requireAvc(!b.bit()) // No redundant pictures.
        b.trailing() // No extension/8x8/scaling matrix syntax.
        return Pps(id, qp, deblock)
    }

    private fun readSlice(nal: ByteArray, idr: Boolean, ref: Int): Slice {
        val rbsp = rbsp(nal)
        val b = Bits(rbsp)
        val first = b.ue(sps.macroblocks - 1)
        val type = b.ue(9) % 5
        requireAvc(type == 0 || type == 2)
        requireAvc(!idr || type == 2)
        requireAvc(b.ue(255) == pps.id)
        val frame = b.bits(sps.frameBits)
        val idrId = if (idr) b.ue(65535) else null
        val poc = if (sps.pocType == 0) b.bits(sps.pocBits) else null
        if (type == 0) {
            if (b.bit()) requireAvc(b.ue(31) == 0) // One reference even for override.
            requireAvc(!b.bit()) // No ref_pic_list_modification.
        }
        if (idr) requireAvc(!b.bit() && !b.bit()) // No prior output suppression or long-term reference.
        else requireAvc(!b.bit()) // No adaptive reference picture marking/MMCO.
        val qpDelta = b.se()
        requireAvc(26L + pps.initQp + qpDelta in 0..51)
        if (pps.deblock) {
            val disable = b.ue(2)
            if (disable != 1) requireAvc(b.se() in -6..6 && b.se() in -6..6)
        }
        // We reach the actual slice_data boundary. Require at least payload plus a stop bit, but do
        // not mistake this bounded header scan for proof of valid entropy or macroblock coverage.
        requireAvc(b.remaining >= 2 && rbsp.last() != 0.toByte())
        return Slice(first, Picture(frame, idr, idrId, poc, ref, type))
    }

    private fun rbsp(nal: ByteArray): ByteArray {
        // Canonical helper already proves unambiguous EBSP; retain original bytes for digests.
        val result = ByteArray(nal.size - 1)
        var count = 0
        var zeros = 0
        var at = 1
        while (at < nal.size) {
            if (at and 4095 == 0) checkpoint()
            val value = nal[at++].toInt() and 255
            if (zeros == 2 && value == 3) {
                requireAvc(at < nal.size && (nal[at].toInt() and 255) <= 3)
                zeros = 0
                continue
            }
            requireAvc(zeros < 2 || value > 2)
            result[count++] = value.toByte()
            zeros = if (value == 0) zeros + 1 else 0
        }
        return result.copyOf(count)
    }

    private class Bits(private val data: ByteArray) {
        private var at = 0
        val remaining get() = data.size * 8 - at
        fun bit(): Boolean = bits(1) != 0
        fun bits(count: Int): Int = longBits(count).toInt()
        fun longBits(count: Int): Long {
            requireAvc(count in 0..32 && remaining >= count)
            var value = 0L
            repeat(count) {
                value = (value shl 1) or ((data[at / 8].toInt() ushr (7 - at % 8)) and 1).toLong()
                at++
            }
            return value
        }
        fun ue(max: Int = Int.MAX_VALUE - 1): Int {
            var leading = 0
            while (!bit()) { requireAvc(++leading <= 30) }
            val value = (1L shl leading) - 1 + longBits(leading)
            requireAvc(value <= max)
            return value.toInt()
        }
        fun se(): Int { val value = ue(); return if (value and 1 == 0) -(value / 2) else (value + 1) / 2 }
        fun trailing() {
            requireAvc(remaining in 1..8 && bit())
            while (remaining > 0) requireAvc(!bit())
        }
    }

    private companion object {
        fun requireAvc(ok: Boolean) = requireMov(ok, MovProfileFailure.AVC_PROFILE)
    }
}
