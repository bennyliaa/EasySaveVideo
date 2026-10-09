package com.easysavevideo.app.editor.verification

import com.easysavevideo.library.edit.EditAudioProfile
import com.easysavevideo.library.edit.EditFailure
import com.easysavevideo.library.edit.EditOutputExpectation
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Synthetic boxes exercise host policies only. These are deliberately not playable-media fixtures. */
class EditMp4ProbeTest {
    private val expected = EditOutputExpectation(120_000, 16, 16, 25, EditAudioProfile.Silent, 40_000, 0)
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected edit MP4 rejection") } catch (_: EditFailure) { }
    }
    private fun inspect(bytes: ByteArray, declaredBytes: Long = bytes.size.toLong(),
        expectation: EditOutputExpectation = expected): List<EditMp4Track> {
        val file = File.createTempFile("esv-edit-synthetic", ".bin")
        try {
            file.writeBytes(bytes)
            return FileInputStream(file).use { EditMp4Probe.read(it.channel, declaredBytes, expectation) }
        } finally { file.delete() }
    }

    @Test fun exactExtentAndSampleTablesReconcile() {
        val track = inspect(mp4()).single()
        assertEquals("vide", track.kind); assertEquals(3, track.sampleCount)
        assertArrayEquals(intArrayOf(4, 4, 4), track.sampleSizes)
        assertEquals(12L, track.sampleBytes); assertEquals(120_000L, track.durationUs)
        assertEquals(80_000L, track.spanUs); assertEquals(40_000L, track.lastDurationUs)
        assertEquals(66, track.avcProfile)
    }
    @Test fun truncatedLastMdatOrMoovFails() {
        val complete = mp4()
        reject { inspect(complete.copyOf(complete.size - 1)) }
        reject { inspect(complete, complete.size.toLong() - 1) }
    }
    @Test fun trailingUnboxedBytesCannotHideAfterCompleteMoov() {
        reject { inspect(mp4() + byteArrayOf(0, 0, 0)) }
    }
    @Test fun chunkOutsideMdatAndMetadataPointingIntoMoovFail() {
        reject { inspect(mp4(chunkOffset = 0)) }
        reject { inspect(mp4(chunkOffset = 44)) }
        reject { inspect(mp4(chunkOffset = Long.MAX_VALUE)) }
    }
    @Test fun chunkExtentPastMdatTailFails() {
        reject { inspect(mp4(sampleSizes = intArrayOf(4, 4, 5))) }
    }
    @Test fun sampleCountsMustMatchBothTimingAndChunkMapping() {
        reject { inspect(mp4(timingCount = 2)) }
        reject { inspect(mp4(samplesPerChunk = 2)) }
        reject { inspect(mp4(samplesPerChunk = 4)) }
    }
    @Test fun declaredTimingCannotInventMissingTailDuration() {
        reject { inspect(mp4(mdhdDuration = 160_000)) }
        reject { inspect(mp4(delta = 80_000, mdhdDuration = 240_000)) }
    }
    @Test fun movieHeaderCannotAdvertiseADifferentDuration() {
        reject { inspect(mp4(movieDuration = 5_000_000)) }
    }
    @Test fun zeroTimingAndOversizedSampleAreRejected() {
        reject { inspect(mp4(delta = 0)) }
        reject { inspect(mp4(sampleSizes = intArrayOf(4, 4, EditVerificationBounds.MAX_SAMPLE_BYTES + 1))) }
    }
    @Test fun geometryAndRotationMustMatchPhysicalOutput() {
        reject { inspect(mp4(tkhdWidth = 32)) }
        reject { inspect(mp4(sampleWidth = 32)) }
        reject { inspect(mp4(rotated = true)) }
    }
    @Test fun fragmentedOrExternalDataIsOutsideThisOutputProfile() {
        reject { inspect(mp4() + atom("moof", byteArrayOf(0, 0, 0, 0))) }
        reject { inspect(mp4(externalData = true)) }
    }
    @Test fun compositionReorderingAndNonSquarePixelsFail() {
        reject { inspect(mp4(compositionOffset = 40_000)) }
        reject { inspect(mp4(sarWidth = 2)) }
    }
    @Test fun hdrTransferAndHighBitDepthAreRejectedIndependently() {
        reject { inspect(mp4(transfer = 16)) }
        reject { inspect(mp4(config = avcc(profile = 100, bitDepthMinus8 = 2))) }
    }
    @Test fun validMainAndEightBitHighSpsAreSupported() {
        assertEquals(77, inspect(mp4(config = avcc(profile = 77))).single().avcProfile)
        assertEquals(100, inspect(mp4(config = avcc(profile = 100))).single().avcProfile)
    }
    @Test fun spsDimensionsAreNotInferredFromContainerLabels() {
        reject { inspect(mp4(config = avcc(width = 32))) }
    }
    @Test fun videoEditListCannotIntroduceHiddenTrimOrNonUnitRate() {
        assertEquals(0L, inspect(mp4(editMediaTime = 0)).single().presentationOffsetUs)
        reject { inspect(mp4(editMediaTime = 40_000)) }
        reject { inspect(mp4(editMediaTime = 0, editRate = 0)) }
        reject { inspect(mp4(editMediaTime = -1)) }
    }
    @Test fun productionShapeNegativeAacEditListHasBoundedExplicitMapping() {
        // Pinned Media3 c2.android.aac.encoder delay1600 produces about-33333us at48kHz.
        // Boxes.elst uses track ticks:1600frames is represented exactly here. Payload is synthetic.
        val audioExpected = expected.copy(audio = EditAudioProfile.Aac(48_000, 2), audioPrimingToleranceUs = 43_000)
        val track = inspect(mp4(audioPrimingSamples = 1600), expectation = audioExpected).single { it.kind == "soun" }
        assertEquals(-33_333L, track.presentationOffsetUs)
        assertEquals(8, track.sampleCount); assertEquals(64L, track.sampleBytes)
        val ledger = EditSampleLedger(false, audioExpected, track)
        repeat(8) { ledger.sample(track.presentationOffsetUs + it * 1_024_000_000L / 48_000, 8, 1) }
        assertTrue(ledger.finish().endUs in 137_000..138_000)
    }
    @Test fun excessiveAacPrimingAndNonUnitAudioEditsAreRejected() {
        val audioExpected = expected.copy(audio = EditAudioProfile.Aac(48_000, 2), audioPrimingToleranceUs = 43_000)
        reject { inspect(mp4(audioPrimingSamples = 4096), expectation = audioExpected) }
        reject { inspect(mp4(audioPrimingSamples = 1600, audioEditRate = 0), expectation = audioExpected) }
    }
    @Test fun extractorCannotSilentlyHideAacPrimingPacketsFromCompleteWalk() {
        val audioExpected = expected.copy(audio = EditAudioProfile.Aac(48_000, 2), audioPrimingToleranceUs = 43_000)
        val track = inspect(mp4(audioPrimingSamples = 1600), expectation = audioExpected).single { it.kind == "soun" }
        val ledger = EditSampleLedger(false, audioExpected, track)
        // Seven instead of all eight declared samples cannot receive complete demux evidence.
        repeat(7) { ledger.sample(track.presentationOffsetUs + it * 1_024_000_000L / 48_000, 8, 1) }
        reject { ledger.finish() }
    }
    @Test fun pqInSpsCannotHideBehindMissingContainerColorTag() {
        reject { inspect(mp4(config = avcc(vuiTransfer = 16))) }
    }
    @Test fun cancellationCheckpointIsUsedDuringContainerWalk() {
        val file = File.createTempFile("esv-edit-synthetic", ".bin")
        try {
            file.writeBytes(mp4())
            FileInputStream(file).use { input ->
                var checks = 0
                try {
                    EditMp4Probe.read(input.channel, file.length(), expected) { if (++checks == 8) throw InterruptedException() }
                    fail("Checkpoint must interrupt the probe")
                } catch (_: InterruptedException) { assertEquals(8, checks) }
            }
        } finally { file.delete() }
    }

    private fun mp4(
        sampleSizes: IntArray = intArrayOf(4, 4, 4),
        timingCount: Int = 3,
        delta: Int = 40_000,
        mdhdDuration: Int = 120_000,
        movieDuration: Int = 120_000,
        samplesPerChunk: Int = 3,
        chunkOffset: Long? = null,
        tkhdWidth: Int = 16,
        sampleWidth: Int = 16,
        rotated: Boolean = false,
        externalData: Boolean = false,
        compositionOffset: Int? = null,
        sarWidth: Int? = null,
        transfer: Int? = null,
        editMediaTime: Long? = null,
        editRate: Int = 1,
        audioPrimingSamples: Int? = null,
        audioEditRate: Int = 1,
        config: ByteArray = avcc(),
    ): ByteArray {
        val ftyp = atom("ftyp", "isom".toByteArray() + ints(0) + "isom".toByteArray())
        val mdat = atom("mdat", ByteArray(12 + if (audioPrimingSamples == null) 0 else 64) { (it + 1).toByte() })
        val data = atom("dinf", atom("dref", ints(0, 1) + atom("url ", ints(if (externalData) 0 else 1))))
        val tkhd = ByteBuffer.allocate(84)
        tkhd.putInt(7).putInt(0).putInt(0).putInt(1).putInt(0).putInt(mdhdDuration)
        tkhd.putLong(0).putShort(0).putShort(0).putShort(0).putShort(0)
        val matrix = if (rotated) intArrayOf(0, 65536, 0, -65536, 0, 0, 0, 0, 1 shl 30)
            else intArrayOf(65536, 0, 0, 0, 65536, 0, 0, 0, 1 shl 30)
        matrix.forEach(tkhd::putInt); tkhd.putInt(tkhdWidth shl 16).putInt(16 shl 16)
        val sampleHeader = ByteBuffer.allocate(78)
        sampleHeader.position(6); sampleHeader.putShort(1)
        sampleHeader.position(24); sampleHeader.putShort(sampleWidth.toShort()).putShort(16)
        var configuration = atom("avcC", config)
        if (sarWidth != null) configuration += atom("pasp", ints(sarWidth, 1))
        if (transfer != null) configuration += atom("colr", "nclx".toByteArray() + shorts(1, transfer, 1) + byteArrayOf(0))
        val stsd = atom("stsd", ints(0, 1) + atom("avc1", sampleHeader.array() + configuration))
        val stts = atom("stts", ints(0, 1, timingCount, delta))
        val stsz = atom("stsz", ints(0, 0, sampleSizes.size) + ints(*sampleSizes))
        val stsc = atom("stsc", ints(0, 1, 1, samplesPerChunk, 1))
        val offset = chunkOffset ?: (ftyp.size + 8).toLong()
        val co64 = atom("co64", ints(0, 1) + ByteBuffer.allocate(8).putLong(offset).array())
        val ctts = if (compositionOffset == null) byteArrayOf() else atom("ctts", ints(0, 1, 3, compositionOffset))
        val stbl = atom("stbl", stsd + stts + stsz + stsc + co64 + atom("stss", ints(0, 1, 1)) + ctts)
        val mdhd = atom("mdhd", ints(0, 0, 0, 1_000_000, mdhdDuration, 0))
        val handler = atom("hdlr", ints(0, 0) + "vide".toByteArray() + ints(0, 0, 0))
        val mdia = atom("mdia", mdhd + handler + atom("minf", data + stbl))
        val mvhd = atom("mvhd", ints(0, 0, 0, 1_000_000, movieDuration) + ByteArray(80))
        val edit = if (editMediaTime == null) byteArrayOf() else atom("edts", atom("elst",
            ints(1 shl 24, 1) + ByteBuffer.allocate(20).putLong(120_000).putLong(editMediaTime).putShort(editRate.toShort()).putShort(0).array()))
        val audioTrack = if (audioPrimingSamples == null) byteArrayOf() else {
            val audioDuration = (8192 - audioPrimingSamples) * 1_000_000L / 48_000
            val audioTkhd = tkhd.array().copyOf().also {
                ByteBuffer.wrap(it).putInt(12, 2).putInt(20, audioDuration.toInt()).putInt(76, 0).putInt(80, 0)
            }
            val audioEntry = ByteBuffer.allocate(28).also {
                it.putShort(6, 1); it.putShort(16, 2); it.putShort(18, 16); it.putInt(24, 48_000 shl 16)
            }.array()
            val audioStsd = atom("stsd", ints(0, 1) + atom("mp4a", audioEntry + atom("esds", ints(0) + byteArrayOf(3, 0))))
            val audioStbl = atom("stbl", audioStsd + atom("stts", ints(0, 1, 8, 1024)) +
                atom("stsz", ints(0, 8, 8)) + atom("stsc", ints(0, 1, 1, 8, 1)) +
                atom("co64", ints(0, 1) + ByteBuffer.allocate(8).putLong((ftyp.size + 8 + 12).toLong()).array()))
            val audioMdia = atom("mdia", atom("mdhd", ints(0, 0, 0, 48_000, 8192, 0)) +
                atom("hdlr", ints(0, 0) + "soun".toByteArray() + ints(0, 0, 0)) + atom("minf", data + audioStbl))
            val audioEdit = atom("edts", atom("elst", ints(1 shl 24, 1) + ByteBuffer.allocate(20)
                .putLong(audioDuration).putLong(audioPrimingSamples.toLong()).putShort(audioEditRate.toShort()).putShort(0).array()))
            atom("trak", atom("tkhd", audioTkhd) + audioEdit + audioMdia)
        }
        return ftyp + mdat + atom("moov", mvhd + atom("trak", atom("tkhd", tkhd.array()) + edit + mdia) + audioTrack)
    }

    private fun atom(type: String, body: ByteArray) = ints(body.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + body
    private fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).also { out -> values.forEach(out::putInt) }.array()
    private fun shorts(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 2).also { out -> values.forEach { out.putShort(it.toShort()) } }.array()

    private fun avcc(profile: Int = 66, bitDepthMinus8: Int = 0, width: Int = 16, vuiTransfer: Int? = null): ByteArray {
        val bits = Bits()
        bits.write(profile, 8); bits.write(0, 8); bits.write(30, 8); bits.ue(0)
        if (profile == 100) { bits.ue(1); bits.ue(bitDepthMinus8); bits.ue(bitDepthMinus8); bits.write(0, 1); bits.write(0, 1) }
        bits.ue(0); bits.ue(0); bits.ue(0); bits.ue(1); bits.write(0, 1)
        bits.ue(width / 16 - 1); bits.ue(0); bits.write(1, 1); bits.write(1, 1); bits.write(0, 1)
        bits.write(if (vuiTransfer == null) 0 else 1, 1)
        if (vuiTransfer != null) {
            bits.write(0, 1); bits.write(0, 1); bits.write(1, 1)
            bits.write(5, 3); bits.write(0, 1); bits.write(1, 1)
            bits.write(1, 8); bits.write(vuiTransfer, 8); bits.write(1, 8)
        }
        bits.write(1, 1) // rbsp_stop_one_bit
        val sps = byteArrayOf(0x67) + bits.bytes()
        val pps = byteArrayOf(0x68, 0x80.toByte())
        return byteArrayOf(1, profile.toByte(), 0, 30, 0xff.toByte(), 0xe1.toByte()) + shorts(sps.size) + sps + byteArrayOf(1) + shorts(pps.size) + pps
    }
    private class Bits {
        private val bits = ArrayList<Int>()
        fun write(value: Int, size: Int) { for (i in size - 1 downTo 0) bits.add((value ushr i) and 1) }
        fun ue(value: Int) { val code = value + 1; val length = 32 - Integer.numberOfLeadingZeros(code); repeat(length - 1) { bits.add(0) }; write(code, length) }
        fun bytes(): ByteArray {
            val out = ByteArray((bits.size + 7) / 8)
            bits.forEachIndexed { index, bit -> out[index / 8] = (out[index / 8].toInt() or (bit shl (7 - index % 8))).toByte() }
            return out
        }
    }
}
