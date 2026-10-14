package com.easysavevideo.app.save.normalization

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Original deterministic owned bytes, generated here without media, copied encoder output, the
 * production scanner, clock or digest helpers. I_PCM IDR and P-skip syntax is constructed explicitly;
 * these host fixtures are NOT evidence of Android decoder output or broad MOV codec support.
 */
internal object OriginalMovFixtures {
    data class Options(
        val frameRate: Int = 30,
        val count: Int = 3,
        val width: Int = 16,
        val height: Int = 16,
        val moovFirst: Boolean = false,
        val co64: Boolean = false,
        val samplesPerChunk: Int = 1,
        val quickTime: Boolean = true,
        val splitTiming: Boolean = false,
        val aud: Boolean = false,
        val inBandConfig: Boolean = false,
        val sdr: Boolean = true,
        val pocType: Int = 0,
        val audio: Boolean = false,
        val extraVideo: Boolean = false,
        val missingSync: Boolean = false,
        val allIdr: Boolean = false,
        val slices: Int = 1,
        val nonIdrSliceType: Int = 0,
        val slicePpsId: Int = 0,
        val frameStep: Int = 1,
        val pocStep: Int = 2,
        val referenceListModification: Boolean = false,
        val adaptiveReferenceMarking: Boolean = false,
        val idrDiscardPrior: Boolean = false,
        val idrLongTerm: Boolean = false,
        val maxReferences: Int = 1,
        val ppsSliceGroups: Int = 0,
        val ppsWeighted: Boolean = false,
        val ppsRedundant: Boolean = false,
        val progressive: Boolean = true,
        val colorTransfer: Int = 1,
    )

    fun input(bytes: ByteArray): MovProbeInput = object : MovProbeInput {
        override val length = bytes.size.toLong()
        override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int) {
            require(offset >= 0 && offset <= bytes.size && length >= 0 && length <= bytes.size - offset)
            bytes.copyInto(target, targetOffset, offset.toInt(), offset.toInt() + length)
        }
    }

    fun build(options: Options = Options()): ByteArray {
        val sps = sps(options)
        val pps = pps(options)
        val samples = (0 until options.count).map { index ->
            val nals = ArrayList<ByteArray>()
            if (options.aud) nals.add(byteArrayOf(9, if (index == 0 || options.allIdr) 0x10 else 0x30))
            if (options.inBandConfig) { nals.add(sps); nals.add(pps) }
            repeat(options.slices) { slice -> nals.add(slice(options, index, slice)) }
            bytes { nals.forEach { writeInt(it.size); write(it) } }
        }
        val ftyp = box("ftyp", bytes {
            writeTag(if (options.quickTime) "qt  " else "isom"); writeInt(0)
            writeTag(if (options.quickTime) "qt  " else "isom")
        })
        val payload = concat(*samples.toTypedArray())
        val chunkSizes = samples.chunked(options.samplesPerChunk).map { chunk -> chunk.sumOf { it.size } }
        fun offsets(start: Int): LongArray {
            var next = start.toLong()
            return LongArray(chunkSizes.size) { index -> next.also { next += chunkSizes[index] } }
        }
        val emptyOffsets = LongArray(chunkSizes.size)
        val prototype = movie(options, samples, sps, pps, emptyOffsets)
        val mediaStart = ftyp.size + if (options.moovFirst) prototype.size + 8 else 8
        val moov = movie(options, samples, sps, pps, offsets(mediaStart))
        check(prototype.size == moov.size)
        val mdat = box("mdat", payload)
        return if (options.moovFirst) concat(ftyp, moov, mdat) else concat(ftyp, mdat, moov)
    }

    fun sps(options: Options = Options()): ByteArray {
        val b = BitWriter()
        b.bits(66, 8); b.bits(0, 8); b.bits(30, 8); b.ue(0)
        b.ue(0); b.ue(options.pocType)
        if (options.pocType == 0) b.ue(0)
        b.ue(options.maxReferences); b.bit(false)
        b.ue(options.width / 16 - 1); b.ue(options.height / 16 - 1)
        b.bit(options.progressive); b.bit(true); b.bit(false)
        b.bit(options.sdr)
        if (options.sdr) {
            b.bit(true); b.bits(1, 8) // square pixels
            b.bit(false) // overscan
            b.bit(true); b.bits(5, 3); b.bit(false); b.bit(true)
            b.bits(1, 8); b.bits(options.colorTransfer, 8); b.bits(1, 8)
            b.bit(false); b.bit(false) // chroma location, timing
            b.bit(false); b.bit(false); b.bit(false) // HRD, pic_struct
            b.bit(true); b.bit(true) // restriction, vectors over boundaries
            b.ue(0); b.ue(0); b.ue(10); b.ue(10); b.ue(0); b.ue(1)
        }
        return nal(0x67, b.finish())
    }

    fun pps(options: Options = Options()): ByteArray {
        val b = BitWriter()
        b.ue(0); b.ue(0); b.bit(false); b.bit(false); b.ue(options.ppsSliceGroups)
        b.ue(0); b.ue(0); b.bit(options.ppsWeighted); b.bits(0, 2)
        b.se(0); b.se(0); b.se(0); b.bit(true); b.bit(false); b.bit(options.ppsRedundant)
        return nal(0x68, b.finish())
    }

    fun slice(options: Options, index: Int, slice: Int = 0): ByteArray {
        val idr = index == 0 || options.allIdr
        val b = BitWriter()
        val mbCount = options.width / 16 * (options.height / 16)
        val first = slice * mbCount / options.slices
        val last = (slice + 1) * mbCount / options.slices
        b.ue(first); b.ue(if (idr) 2 else options.nonIdrSliceType); b.ue(options.slicePpsId)
        b.bits(if (idr) 0 else (index * options.frameStep) % 16, 4)
        if (idr) b.ue(index)
        if (options.pocType == 0) b.bits(if (idr) 0 else (index * options.pocStep) % 16, 4)
        if (!idr) { b.bit(false); b.bit(options.referenceListModification) }
        if (idr) { b.bit(options.idrDiscardPrior); b.bit(options.idrLongTerm) } else b.bit(options.adaptiveReferenceMarking)
        b.se(0); b.ue(1) // disable_deblocking_filter_idc
        if (idr) {
            repeat(last - first) {
                b.ue(25) // I_PCM in an I slice.
                b.alignZero()
                repeat(256) { b.bits(16, 8) }
                repeat(128) { b.bits(128, 8) }
            }
        } else b.ue(last - first) // P-skip macroblocks referencing prior picture.
        return nal(if (idr) 0x65 else 0x41, b.finish())
    }

    fun avcc(sps: ByteArray, pps: ByteArray): ByteArray = bytes {
        writeByte(1); write(sps, 1, 3); writeByte(0xff); writeByte(0xe1)
        writeShort(sps.size); write(sps); writeByte(1); writeShort(pps.size); write(pps)
    }

    private fun movie(o: Options, samples: List<ByteArray>, sps: ByteArray, pps: ByteArray, offsets: LongArray): ByteArray {
        val delta = 90_000 / o.frameRate
        val ticks = o.count.toLong() * delta
        val halfUs = (ticks * 1_000_000L / 90_000) + if ((ticks * 1_000_000L % 90_000) * 2 >= 90_000) 1 else 0
        val header = (halfUs * 10_000L + 500_000L) / 1_000_000L
        val mvhd = box("mvhd", bytes {
            writeInt(0); writeLong(0); writeInt(10_000); writeInt(header.toInt())
            writeInt(0x10000); writeShort(256); writeShort(0); writeLong(0); matrix()
            repeat(6) { writeInt(0) }; writeInt(4)
        })
        fun track(handler: String, id: Int): ByteArray {
            val tkhd = box("tkhd", bytes {
                writeInt(7); writeLong(0); writeInt(id); writeInt(0); writeInt(header.toInt())
                writeLong(0); writeLong(0); matrix(); writeInt(o.width shl 16); writeInt(o.height shl 16)
            })
            val mdhd = box("mdhd", bytes { writeInt(0); writeLong(0); writeInt(90_000); writeInt(ticks.toInt()); writeInt(0) })
            val hdlr = box("hdlr", bytes { writeLong(0); writeTag(handler); repeat(3) { writeInt(0) }; writeByte(0) })
            val vmhd = box("vmhd", bytes { writeInt(1); writeLong(0) })
            val dinf = box("dinf", box("dref", concat(bytes { writeInt(0); writeInt(1) }, box("url ", bytes { writeInt(1) }))))
            val entry = box("avc1", concat(bytes {
                writeInt(0); writeShort(0); writeShort(1); repeat(4) { writeInt(0) }
                writeShort(o.width); writeShort(o.height); writeInt(0x480000); writeInt(0x480000); writeInt(0)
                writeShort(1); repeat(32) { writeByte(0) }; writeShort(24); writeShort(-1)
            }, box("avcC", avcc(sps, pps)), box("pasp", bytes { writeInt(1); writeInt(1) }),
                box("colr", bytes { writeTag("nclx"); writeShort(1); writeShort(1); writeShort(1); writeByte(0) })))
            val stsd = box("stsd", concat(bytes { writeInt(0); writeInt(1) }, entry))
            val stts = box("stts", bytes {
                writeInt(0); writeInt(if (o.splitTiming) o.count else 1)
                if (o.splitTiming) repeat(o.count) { writeInt(1); writeInt(delta) } else { writeInt(o.count); writeInt(delta) }
            })
            val stsz = box("stsz", bytes { writeInt(0); writeInt(0); writeInt(samples.size); samples.forEach { writeInt(it.size) } })
            val lastCount = samples.size % o.samplesPerChunk
            val splitLast = lastCount != 0 && samples.size > o.samplesPerChunk
            val stsc = box("stsc", bytes {
                writeInt(0); writeInt(if (splitLast) 2 else 1)
                writeInt(1); writeInt(minOf(o.samplesPerChunk, samples.size)); writeInt(1)
                if (splitLast) { writeInt(offsets.size); writeInt(lastCount); writeInt(1) }
            })
            val stco = box(if (o.co64) "co64" else "stco", bytes {
                writeInt(0); writeInt(offsets.size)
                offsets.forEach { if (o.co64) writeLong(it) else writeInt(it.toInt()) }
            })
            val stss = if (o.missingSync) byteArrayOf() else box("stss", bytes {
                writeInt(0); writeInt(if (o.allIdr) o.count else 1)
                if (o.allIdr) repeat(o.count) { writeInt(it + 1) } else writeInt(1)
            })
            return box("trak", concat(tkhd, box("mdia", concat(mdhd, hdlr,
                box("minf", concat(vmhd, dinf, box("stbl", concat(stsd, stts, stsz, stsc, stco, stss))))))))
        }
        return box("moov", concat(mvhd, track("vide", 1),
            if (o.audio) track("soun", 2) else byteArrayOf(),
            if (o.extraVideo) track("vide", 3) else byteArrayOf()))
    }

    fun box(type: String, payload: ByteArray): ByteArray = bytes { writeInt(payload.size + 8); writeTag(type); write(payload) }
    fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { out -> DataOutputStream(out).use(block) }.toByteArray()
    fun concat(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> values.forEach { out.write(it) } }.toByteArray()
    fun DataOutputStream.writeTag(tag: String) { require(tag.length == 4); tag.forEach { writeByte(it.code) } }
    private fun DataOutputStream.matrix() { listOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).forEach(::writeInt) }

    private fun nal(header: Int, rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); out.write(header)
        var zeros = 0
        for (byte in rbsp) {
            val value = byte.toInt() and 255
            if (zeros == 2 && value <= 3) { out.write(3); zeros = 0 }
            out.write(value); zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }
    private class BitWriter {
        val out = ByteArrayOutputStream()
        var value = 0; var count = 0
        fun bit(v: Boolean) { value = (value shl 1) or if (v) 1 else 0; if (++count == 8) { out.write(value); value = 0; count = 0 } }
        fun bits(v: Int, n: Int) { for (i in n - 1 downTo 0) bit((v ushr i) and 1 != 0) }
        fun ue(v: Int) { require(v >= 0); val value = v + 1; val width = 32 - Integer.numberOfLeadingZeros(value); repeat(width - 1) { bit(false) }; bits(value, width) }
        fun se(v: Int) = ue(if (v <= 0) -v * 2 else v * 2 - 1)
        fun alignZero() { while (count != 0) bit(false) }
        fun finish(): ByteArray { bit(true); alignZero(); return out.toByteArray() }
    }
}
