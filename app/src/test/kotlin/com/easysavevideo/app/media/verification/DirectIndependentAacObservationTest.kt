package com.easysavevideo.app.media.verification

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Production probe and PCM ledger with synthetic bytes only; no MediaCodec/native acceptance. */
class DirectIndependentAacObservationTest {
    private val expected = AvcAacOutputExpectation(120_000, 16, 16, 25,
        AvcAacAudioProfile.Aac(48_000, 2), 40_000, 42_667, 4_000_000, 3,
        AvcAacIndependentAudioExpectation(6592))
    private val accepted = AvcAacNativeAudioMapping.PCM_FOLLOWS_EXTRACTOR_WITH_VERIFIED_PRIMING

    private fun reject(block: () -> Unit) = assertThrows(AvcAacVerificationFailure::class.java, block)
    private fun inspect(bytes: ByteArray, expectation: AvcAacOutputExpectation = expected): AvcAacMp4Track {
        val file = File.createTempFile("esv-aac-synthetic", ".bin")
        try {
            file.writeBytes(bytes)
            return FileInputStream(file).use { AvcAacMp4Probe.read(it.channel, file.length(), expectation) }
                .single { it.kind == "soun" }
        } finally { file.delete() }
    }
    private fun scan(timing: AvcAacMp4Track, presentationCoordinates: Boolean,
        expectation: AvcAacOutputExpectation = expected): AvcAacScannedTrack {
        val ledger = AvcAacSampleLedger(false, expectation, timing)
        val media = if (presentationCoordinates) timing.edits.singleOrNull()?.mediaTimeTicks ?: 0L else 0L
        repeat(timing.sampleCount) { index ->
            val pts = Math.floorDiv((timing.sampleStartTicks!![index] - media) * 1_000_000, timing.timescale)
            ledger.sample(pts, timing.sampleSizes[index].toLong(), 1)
        }
        return ledger.finish()
    }
    private fun decode(track: AvcAacScannedTrack, expectation: AvcAacOutputExpectation = expected,
        mapping: AvcAacNativeAudioMapping = accepted, chunkFrames: Int = 1024): AvcAacAudioDecodeLedger {
        val ledger = AvcAacAudioDecodeLedger(expectation, track, mapping)
        track.samples.forEach(ledger::input); ledger.inputEnd()
        var frames = 0L
        while (frames < 8192) {
            val n = minOf(chunkFrames.toLong(), 8192 - frames).toInt()
            val pts = track.firstPtsUs + frames * 1_000_000 / 48_000
            ledger.output(pts, n * 4, false, false)
            frames += n
        }
        ledger.output(0, 0, false, true)
        return ledger
    }

    @Test fun probeKeepsRawEditTicksAndActualTrackTimeline() {
        val timing = inspect(mp4(audioPrimingSamples = 1600))
        assertEquals(1_000_000L, timing.movieTimescale)
        assertEquals(48_000L, timing.timescale)
        assertEquals(137_333L, timing.trackDurationTicks)
        assertTrue(timing.editPresent)
        assertEquals(listOf(AvcAacMp4Edit(137_333, 1600)), timing.edits)
        assertArrayEquals(LongArray(8) { it * 1024L }, timing.sampleStartTicks)
    }

    @Test fun realScanSelectsMediaOrAlreadyPresentedWithoutDoubleEdit() {
        val timing = inspect(mp4(audioPrimingSamples = 1600))
        val media = scan(timing, false)
        val presented = scan(timing, true)
        assertEquals(AvcAacAudioCoordinates.MEDIA, media.audioPresentation!!.coordinates)
        assertEquals(AvcAacAudioCoordinates.PRESENTATION, presented.audioPresentation!!.coordinates)
        val a = decode(media).presentedAudio!!
        val b = decode(presented).presentedAudio!!
        assertEquals(a, b)
        assertEquals(6592L, a.frames)
        assertEquals(0L, a.startUs)
        assertEquals(137_333L, a.endUs)
        requireIndependentAacMatches(expected.independentAudio!!, a)
    }

    @Test fun expectedEncoderFramesCannotChangeContainerWindowOrPcmObservation() {
        val timing = inspect(mp4(audioPrimingSamples = 1600))
        val changed = expected.copy(independentAudio = AvcAacIndependentAudioExpectation(48_000))
        val a = decode(scan(timing, true)).presentedAudio!!
        val b = decode(scan(timing, true, changed), changed).presentedAudio!!
        assertEquals(a, b)
        reject { requireIndependentAacMatches(changed.independentAudio!!, b) }
    }

    @Test fun noEditUsesOnlyThisAudioTrackAndDoesNotEstablishNativePriming() {
        val timing = inspect(mp4(audioPrimingSamples = 0, audioEditPresent = false))
        assertFalse(timing.editPresent); assertTrue(timing.edits.isEmpty())
        val track = scan(timing, false)
        assertEquals(8192L, track.audioPresentation!!.endFrame)
        assertEquals(170_666L, track.audioPresentation!!.durationUs) // video is only 120,000us.
        val actualUnverified = decode(track, mapping = AvcAacNativeAudioMapping.UNVERIFIED)
        assertTrue(actualUnverified.outputEos); assertEquals(8192L, actualUnverified.decodedFrames)
        assertNull(actualUnverified.presentedAudio)
    }

    @Test fun unknownNativeProfileCannotTurnTrimmedRawDecodeIntoEffectiveEvidence() {
        val ledger = decode(scan(inspect(mp4(audioPrimingSamples = 1600)), true),
            mapping = AvcAacNativeAudioMapping.UNVERIFIED)
        assertTrue(ledger.outputEos); assertEquals(8192L, ledger.decodedFrames)
        assertNull(ledger.presentedAudio)
    }

    @Test fun nonIntegralEndpointsUseSameHalfOpenFrameRoundingAcrossOddChunks() {
        val timing = inspect(mp4(audioPrimingSamples = 1600, audioMediaScale = 96_000,
            audioMediaTimeTicks = 3201, audioEditDurationUs = 137_320))
        val track = scan(timing, true)
        assertEquals(1601L, track.audioPresentation!!.startFrame)
        assertEquals(8192L, track.audioPresentation!!.endFrame)
        val observed = decode(track, chunkFrames = 137).presentedAudio!!
        assertEquals(6591L, observed.frames)
        assertEquals(10L, observed.startUs)
        assertEquals(137_322L, observed.endUs)
        requireIndependentAacMatches(expected.independentAudio!!, observed)
    }

    @Test fun gapOrOverlapInsidePcmFailsDespiteMatchingFirstAndLastEndpoints() {
        val track = scan(inspect(mp4(audioPrimingSamples = 1600)), true)
        for (offset in listOf(-21L, 21L, 1000L)) {
            val ledger = AvcAacAudioDecodeLedger(expected, track, accepted)
            track.samples.forEach(ledger::input); ledger.inputEnd()
            ledger.output(track.firstPtsUs, 1024 * 4, false, false)
            reject { ledger.output(track.firstPtsUs + 1024_000_000L / 48_000 + offset, 1024 * 4, false, false) }
            reject { ledger.output(track.firstPtsUs + 1024_000_000L / 48_000, 1024 * 4, false, false) }
        }
    }

    @Test fun scanRejectsMiddleGapAndMissingAuEvenWithCorrectLastTime() {
        val timing = inspect(mp4(audioPrimingSamples = 1600))
        val ledger = AvcAacSampleLedger(false, expected, timing)
        ledger.sample(-33_334, 8, 1)
        reject { ledger.sample(-33_334 + 1024_000_000L / 48_000 + 21, 8, 1) }
        val short = AvcAacSampleLedger(false, expected, timing)
        repeat(7) { short.sample(Math.floorDiv((it * 1024L - 1600) * 1_000_000, 48_000), 8, 1) }
        reject { short.finish() }
    }

    @Test fun emptyMultiAndNonUnitEditsStayOutsideTheDirectSubset() {
        reject { inspect(mp4(audioPrimingSamples = 0, audioEmptyEditUs = 10_000)) }
        reject { inspect(mp4(audioPrimingSamples = -1)) }
        reject { inspect(mp4(audioPrimingSamples = 1600, audioEditRate = 0)) }
    }

    @Test fun videoToleranceCannotWidenIndependentAudioOrHideOutsidePcm() {
        val timing = inspect(mp4(audioPrimingSamples = 1600))
        val changed = expected.copy(durationToleranceUs = 400_000,
            independentAudio = AvcAacIndependentAudioExpectation(10_000))
        val observed = decode(scan(timing, true, changed), changed).presentedAudio!!
        reject { requireIndependentAacMatches(changed.independentAudio!!, observed) }
        val track = scan(timing, true)
        val ledger = AvcAacAudioDecodeLedger(expected, track, accepted)
        track.samples.forEach(ledger::input); ledger.inputEnd()
        reject { ledger.output(track.firstPtsUs - 42_688, 4, false, false) }
    }

    @Test fun decodedPcmCannotSpendInputCreditBeforeActualQueuedAus() {
        val track = scan(inspect(mp4(audioPrimingSamples = 1600)), true)
        val ledger = AvcAacAudioDecodeLedger(expected, track, accepted)
        ledger.input(track.samples.first())
        reject { ledger.output(track.firstPtsUs, 2048 * 4, false, false) }
        reject { ledger.input(track.samples[1]) }
    }

    @Test fun actualInputCreditAllowsSplitAndCoalescedPcmWithoutPerAuOutputAssumption() {
        val track = scan(inspect(mp4(audioPrimingSamples = 1600)), true)
        val ledger = AvcAacAudioDecodeLedger(expected, track, accepted)
        ledger.input(track.samples[0]); ledger.input(track.samples[1])
        ledger.output(track.firstPtsUs, 1536 * 4, false, false)
        ledger.output(track.firstPtsUs + 1536_000_000L / 48_000, 512 * 4, false, false)
        track.samples.drop(2).forEach(ledger::input); ledger.inputEnd()
        ledger.output(track.firstPtsUs + 2048_000_000L / 48_000, 6144 * 4, false, false)
        ledger.output(0, 0, false, true)
        assertEquals(8192L, ledger.decodedFrames)
        assertEquals(6592L, ledger.presentedAudio!!.frames)
    }

    @Test fun actualAndroidBodyIsExplicitlyUnverifiedAndKeepsActualInspectionWiring() {
        val android = File("src/main/kotlin/com/easysavevideo/app/media/verification/AndroidAvcAacOutputInspection.kt").readText()
        assertTrue(android.contains("AvcAacNativeAudioMapping.UNVERIFIED"))
        assertFalse(android.contains("PCM_FOLLOWS_EXTRACTOR_WITH_VERIFIED_PRIMING"))
        assertTrue(android.contains("AvcAacMp4Probe.read(read.channel, size, expected, checkpoint)"))
        assertTrue(android.contains("value.setDataSource(read.originalFd, 0, size)"))
        assertTrue(android.contains("activeCodec.queueInputBuffer"))
        assertTrue(android.contains("activeCodec.releaseOutputBuffer"))
        val queue = android.indexOf("activeCodec.queueInputBuffer(index, 0, sizeBytes.toInt(), sample.ptsUs, 0)")
        val credit = android.indexOf("video?.input(sample); audio?.input(sample)")
        assertTrue(queue >= 0); assertTrue(credit >= 0); assertTrue(queue < credit)
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
        audioEditPresent: Boolean = true,
        audioEditDurationUs: Long? = null,
        audioMediaScale: Int = 48_000,
        audioMediaTimeTicks: Long? = null,
        audioEmptyEditUs: Long? = null,
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
            val audioDuration = audioEditDurationUs ?: (8192 - audioPrimingSamples) * 1_000_000L / 48_000
            val audioTkhd = tkhd.array().copyOf().also {
                ByteBuffer.wrap(it).putInt(12, 2).putInt(20, audioDuration.toInt()).putInt(76, 0).putInt(80, 0)
            }
            val audioEntry = ByteBuffer.allocate(28).also {
                it.putShort(6, 1); it.putShort(16, 2); it.putShort(18, 16); it.putInt(24, 48_000 shl 16)
            }.array()
            val audioStsd = atom("stsd", ints(0, 1) + atom("mp4a", audioEntry + atom("esds", ints(0) + byteArrayOf(3, 0))))
            val audioStbl = atom("stbl", audioStsd + atom("stts", ints(0, 1, 8, 1024 * (audioMediaScale / 48_000))) +
                atom("stsz", ints(0, 8, 8)) + atom("stsc", ints(0, 1, 1, 8, 1)) +
                atom("co64", ints(0, 1) + ByteBuffer.allocate(8).putLong((ftyp.size + 8 + 12).toLong()).array()))
            val audioMdia = atom("mdia", atom("mdhd", ints(0, 0, 0, audioMediaScale, 8192 * (audioMediaScale / 48_000), 0)) +
                atom("hdlr", ints(0, 0) + "soun".toByteArray() + ints(0, 0, 0)) + atom("minf", data + audioStbl))
            val entries = (audioEmptyEditUs?.let { ByteBuffer.allocate(20)
                .putLong(it).putLong(-1).putShort(1).putShort(0).array() } ?: byteArrayOf()) +
                ByteBuffer.allocate(20).putLong(audioDuration)
                    .putLong(audioMediaTimeTicks ?: audioPrimingSamples.toLong()).putShort(audioEditRate.toShort()).putShort(0).array()
            val audioEdit = if (!audioEditPresent) byteArrayOf() else atom("edts", atom("elst",
                ints(1 shl 24, if (audioEmptyEditUs == null) 1 else 2) + entries))
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
