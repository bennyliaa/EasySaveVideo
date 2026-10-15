package com.easysavevideo.app.save.normalization

import com.easysavevideo.save.DirectMovCanonical
import com.easysavevideo.save.DirectMovNalFraming
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MovProfileProbeTest {
    private fun fixture(options: OriginalMovFixtures.Options = OriginalMovFixtures.Options()) = OriginalMovFixtures.build(options)
    private fun scan(bytes: ByteArray) = MovProfileProbe.probeQuickTime(OriginalMovFixtures.input(bytes))

    @Test fun ownedSilentQuickTimeGetsExactImmutableSampleEvidence() {
        val bytes = fixture()
        val profile = scan(bytes)
        assertEquals(16, profile.width); assertEquals(16, profile.height); assertEquals(30, profile.frameRate)
        assertEquals(listOf(0L, 33_333L, 66_666L), profile.samples.map { it.presentationTimeUs })
        assertEquals(listOf(true, false, false), profile.samples.map { it.sync })
        assertEquals(100_000L, profile.clock.endpoint.minimumUs)
        assertEquals(100_000L, profile.clock.endpoint.maximumUs)
        assertEquals(1_000L, profile.clock.headerDurationTicks)
        assertNull(profile.conservation.audio)
        assertEquals(3L, profile.conservation.video.sampleCount)
        assertEquals(0L, profile.conservation.precedingVideoSync.index)
        assertThrows(UnsupportedOperationException::class.java) { (profile.samples as MutableList<MovSampleReference>).clear() }
        val sps = profile.configuration.sps
        sps[1] = 0
        assertEquals(66, profile.configuration.sps[1].toInt())
        profile.samples.indices.forEach { index ->
            val sample = profile.samples[index]
            val raw = bytes.copyOfRange(sample.offset.toInt(), sample.offset.toInt() + sample.size)
            assertArrayEquals(DirectMovCanonical.avcSample(raw, DirectMovNalFraming.AVCC4),
                MovProfileProbe.readCanonicalSample(OriginalMovFixtures.input(bytes), profile, index))
        }
    }

    @Test fun allFiniteRatesBothMetadataOrdersBothOffsetWidthsAndChunkLayoutsAgree() {
        val rates = listOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 12, 15, 16, 18, 20, 24, 25, 30, 36, 40, 45, 48, 50, 60)
        for (rate in rates) {
            val baseline = scan(fixture(OriginalMovFixtures.Options(frameRate = rate, count = 5))).conservation
            for (first in listOf(false, true)) for (wide in listOf(false, true)) for (chunk in listOf(1, 2, 5)) {
                val actual = scan(fixture(OriginalMovFixtures.Options(frameRate = rate, count = 5,
                    moovFirst = first, co64 = wide, samplesPerChunk = chunk, splitTiming = true)))
                assertEquals("rate=$rate first=$first wide=$wide chunk=$chunk", baseline, actual.conservation)
            }
        }
    }

    @Test fun multiSliceAudMatchingInBandConfigAndPocTypeTwoRemainByteConserved() {
        val bytes = fixture(OriginalMovFixtures.Options(width = 32, slices = 2, aud = true, inBandConfig = true, pocType = 2))
        val profile = scan(bytes)
        assertEquals(32, profile.width)
        assertEquals(3, profile.samples.size)
        val raw = MovProfileProbe.readCanonicalSample(OriginalMovFixtures.input(bytes), profile, 0)
        val nals = DirectMovCanonical.avcNals(raw, DirectMovNalFraming.AVCC4)
        assertEquals(listOf(9, 7, 8, 5, 5), nals.map { it[0].toInt() and 31 })
    }

    @Test fun twoSamplesRequireExactFinalDurationAndMovieRounding() {
        val profile = scan(fixture(OriginalMovFixtures.Options(count = 2)))
        assertEquals(66_666L, profile.clock.endpoint.minimumUs)
        assertEquals(66_667L, profile.clock.endpoint.maximumUs)
        assertEquals(667L, profile.clock.headerDurationTicks)
    }

    @Test fun canonicalOutputScanHasExplicitDistinctContainerAdmission() {
        val mov = fixture()
        val mp4 = fixture(OriginalMovFixtures.Options(quickTime = false))
        reject(mp4)
        assertThrows(IOException::class.java) { MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(mov)) }
        assertEquals(scan(mov).conservation, MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(mp4)).conservation)
        val mixed = mp4.clone().also { writeTag(it, offset(it, "ftyp") + 8, "qt  ") }
        assertThrows(IOException::class.java) { MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(mixed)) }
    }

    @Test fun everyExtraOrUnknownTrackRejectsEntireFile() {
        reject(fixture(OriginalMovFixtures.Options(audio = true, extraAudio = true)))
        reject(fixture(OriginalMovFixtures.Options(audio = true, extraVideo = true)))
        reject(fixture(OriginalMovFixtures.Options(extraVideo = true)))
        reject(patch(fixture(), "hdlr", 8) { b, at -> writeTag(b, at, "tmcd") })
        reject(patch(fixture(), "tkhd", 0) { b, at -> put32(b, at, 0) })
    }

    @Test fun allSixExactAudioConfigsConserveIndependentClockAndImmutableRawBytes() {
        for (rate in listOf(32_000, 44_100, 48_000)) for (channels in 1..2) for (count in listOf(2, 3, 7)) {
            val options = OriginalMovFixtures.Options(audio = true, audioSampleRate = rate,
                audioChannelCount = channels, audioCount = count)
            val baseline = scan(fixture(options))
            for (first in listOf(false, true)) for (wide in listOf(false, true)) {
                val bytes = fixture(options.copy(moovFirst = first, audioFirst = first, co64 = wide,
                    samplesPerChunk = 2, splitTiming = true))
                val profile = scan(bytes)
                assertEquals(baseline.conservation, profile.conservation)
                val audio = requireNotNull(profile.audio)
                assertEquals(rate, audio.configuration.sampleRate); assertEquals(channels, audio.configuration.channelCount)
                assertArrayEquals(OriginalMovFixtures.audioAsc(options), audio.configuration.asc)
                assertEquals(count.toLong(), profile.conservation.audio!!.sampleCount)
                assertEquals(count * 1024L, audio.clock.endpointTicks)
                assertEquals((0 until count).map { it * 1024L * 1_000_000 / rate }, audio.samples.map { it.presentationTimeUs })
                assertTrue(audio.samples.all { it.sync })
                assertEquals(maxOf(profile.clock.roundedDurationUs, audio.clock.roundedDurationUs), profile.durationUs)
                assertNotEquals(profile.clock.endpoint, audio.clock.endpoint)
                OriginalMovFixtures.audioSamples(options).forEachIndexed { index, raw ->
                    assertArrayEquals(raw, MovProfileProbe.readAudioSample(OriginalMovFixtures.input(bytes), profile, index))
                }
                val asc = audio.configuration.asc; asc[0] = 0
                assertArrayEquals(OriginalMovFixtures.audioAsc(options), audio.configuration.asc)
                assertThrows(UnsupportedOperationException::class.java) { (audio.samples as MutableList<MovSampleReference>).clear() }
            }
        }
    }

    @Test fun audioProfileDefensivelyCopiesCallerListAndReadUsesFixedExtentAndCheckpoint() {
        val bytes = fixture(OriginalMovFixtures.Options(audio = true))
        val profile = scan(bytes); val audio = requireNotNull(profile.audio)
        val original = audio.samples.toMutableList()
        val copy = MovAudioProfile(audio.clock, audio.configuration, original)
        original.clear(); assertEquals(audio.samples, copy.samples)
        assertThrows(IOException::class.java) {
            MovProfileProbe.readAudioSample(OriginalMovFixtures.input(bytes + byteArrayOf(0)), profile, 0)
        }
        assertThrows(IOException::class.java) { MovProfileProbe.readAudioSample(OriginalMovFixtures.input(bytes), profile, -1) }
        assertThrows(IOException::class.java) { MovProfileProbe.readAudioSample(OriginalMovFixtures.input(bytes), profile, audio.samples.size) }
        assertThrows(IOException::class.java) { MovProfileProbe.readAudioSample(OriginalMovFixtures.input(fixture()), scan(fixture()), 0) }
        val stop = IllegalStateException("AUDIO_CANCEL")
        assertSame(stop, assertThrows(IllegalStateException::class.java) {
            MovProfileProbe.readAudioSample(OriginalMovFixtures.input(bytes), profile, 0) { throw stop }
        })
    }

    @Test fun audioSourceFieldsMustAgreeBeforeExtractorCanOverrideThem() {
        val bytes = fixture(OriginalMovFixtures.Options(audio = true))
        reject(patch(bytes, "mp4a", 16) { b, at -> b[at + 1] = 1 })
        reject(patch(bytes, "mp4a", 24) { b, at -> put32(b, at, 44_100 shl 16) })
        reject(patch(bytes, "mp4a", 24) { b, at -> b[at + 3] = 1 })
        listOf(0, 3, 6, 255).forEach { channels -> reject(patch(bytes, "mp4a", 16) { b, at -> b[at + 1] = channels.toByte() }) }
        for (field in listOf(0, 6, 8, 10, 12, 18, 20, 22)) {
            reject(patch(bytes, "mp4a", field) { b, at -> b[at] = 1 })
        }
        for ((field, value) in listOf(0 to 1, 4 to 4, 5 to 24, 8 to 0x80, 8 to 0x40, 8 to 0x20,
            9 to 5, 10 to 16, 11 to 0x66, 12 to 0x14, 12 to 0x17, 24 to 6, 25 to 3,
            26 to 0x2b, 27 to 0x94, 28 to 5, 29 to 2, 30 to 1)) {
            reject(patch(bytes, "esds", field) { b, at -> b[at] = value.toByte() })
        }
        reject(rename(bytes, "esds", "wave"))
        reject(rename(bytes, "mp4a", "alac"))
        val protected = assertThrows(MovProfileException::class.java) { scan(rename(bytes, "mp4a", "enca")) }
        assertEquals(MovProfileFailure.PROTECTED_MEDIA, protected.failure)
    }

    @Test fun audioTablesOwnTheirDurationExtentFlagsAndNoTrimSemantics() {
        val bytes = fixture(OriginalMovFixtures.Options(audio = true))
        for (delta in listOf(960, 1023, 1025, 2048)) {
            reject(patchNth(bytes, "stts", 1, 12) { b, at -> put32(b, at, delta) })
        }
        reject(patchNth(bytes, "mdhd", 1, 12) { b, at -> put32(b, at, 44_100) })
        reject(patchNth(bytes, "mdhd", 1, 16) { b, at -> put32(b, at, 3071) })
        reject(patchNth(bytes, "tkhd", 1, 20) { b, at -> put32(b, at, 1000) })
        reject(patchNth(bytes, "tkhd", 1, 12) { b, at -> put32(b, at, 1) })
        reject(patchNth(bytes, "tkhd", 1, 76) { b, at -> put32(b, at, 16 shl 16) })
        reject(patchNth(bytes, "url ", 1, 0) { b, at -> put32(b, at, 0) })
        reject(patchNth(bytes, "stsc", 1, 16) { b, at -> put32(b, at, 2) })
        reject(rename(bytes, "smhd", "edts"))
        reject(rename(bytes, "esds", "sinf"))
        val videoOffset = scan(bytes).samples.first().offset
        reject(patchNth(bytes, "stco", 1, 8) { b, at -> put32(b, at, videoOffset.toInt()) })
        reject(patchNth(bytes, "stco", 1, 8) { b, at -> put32(b, at, videoOffset.toInt() + 1) })
        reject(patchNth(bytes, "stco", 1, 8) { b, at -> put32(b, at, bytes.size - 1) })
        assertEquals(scan(bytes).conservation, scan(fixture(OriginalMovFixtures.Options(audio = true, audioSyncCount = 3))).conservation)
        reject(fixture(OriginalMovFixtures.Options(audio = true, audioSyncCount = 2)))
        // A declared extra sample with no bytes cannot become a zero-size padding packet.
        reject(patchNth(bytes, "stts", 1, 8) { b, at -> put32(b, at, 2) })
        reject(patchNth(bytes, "stsz", 1, 8) { b, at -> put32(b, at, 84_376) })
    }

    @Test fun longerAudioUsesMaximumMovieDurationButNeverChangesVideoClock() {
        val short = scan(fixture(OriginalMovFixtures.Options(audio = true, audioCount = 2)))
        val longBytes = fixture(OriginalMovFixtures.Options(audio = true, audioCount = 7, audioSampleRate = 44_100))
        val long = scan(longBytes)
        assertEquals(short.conservation.video, long.conservation.video)
        assertEquals(short.conservation.precedingVideoSync, long.conservation.precedingVideoSync)
        assertTrue(short.conservation.endpoint.maximumUs == short.clock.endpoint.maximumUs)
        assertEquals(long.audio!!.clock.endpoint, long.conservation.endpoint)
        reject(patch(longBytes, "mvhd", 16) { b, at -> put32(b, at, 1000) })
        val mp4 = fixture(OriginalMovFixtures.Options(audio = true, audioCount = 7, audioSampleRate = 44_100, quickTime = false))
        assertEquals(long.conservation, MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(mp4)).conservation)
    }

    @Test fun unknownColorRotationEditsCompositionAndFragmentationAreNeverGuessed() {
        reject(fixture(OriginalMovFixtures.Options(sdr = false)))
        reject(patch(fixture(), "colr", 6) { b, at -> b[at + 1] = 16 }) // PQ transfer.
        reject(patch(fixture(), "colr", 10) { b, at -> b[at] = 0x80.toByte() })
        reject(patch(fixture(), "pasp", 4) { b, at -> put32(b, at, 2) })
        reject(patch(fixture(), "tkhd", 40) { b, at -> put32(b, at, 0) })
        reject(patch(fixture(), "mvhd", 36) { b, at -> put32(b, at, 0) })
        reject(rename(fixture(), "stss", "ctts")) // Even all-zero ctts are never admitted.
        reject(rename(fixture(), "stss", "edts"))
        reject(fixture() + OriginalMovFixtures.box("moof", ByteArray(8)))
        reject(rename(fixture(), "moov", "cmov"))
    }

    @Test fun strictHeadersClocksAndAllTableCountsAreIndependentlyChecked() {
        listOf("mvhd" to 12, "mdhd" to 12).forEach { (tag, field) -> reject(patch(fixture(), tag, field) { b, at -> put32(b, at, 1000) }) }
        listOf("mvhd" to 16, "tkhd" to 20, "mdhd" to 16).forEach { (tag, field) -> reject(patch(fixture(), tag, field) { b, at -> put32(b, at, 1) }) }
        reject(patch(fixture(), "stts", 12) { b, at -> put32(b, at, 2812) }) // 32 fps is not admitted.
        reject(patch(fixture(), "stts", 8) { b, at -> put32(b, at, 2) })
        reject(patch(fixture(OriginalMovFixtures.Options(splitTiming = true)), "stts", 28) { b, at -> put32(b, at, 3001) })
        reject(patch(fixture(), "stsz", 8) { b, at -> put32(b, at, 108_001) })
        reject(patch(fixture(), "stsz", 12) { b, at -> put32(b, at, 4 * 1024 * 1024 + 1) })
        reject(patch(fixture(), "stco", 4) { b, at -> put32(b, at, -1) })
        reject(patch(fixture(), "stsc", 4) { b, at -> put32(b, at, -1) })
        reject(patch(fixture(), "stts", 4) { b, at -> put32(b, at, -1) })
        reject(patch(fixture(), "stsd", 4) { b, at -> put32(b, at, 2) })
        reject(patch(fixture(), "stsc", 16) { b, at -> put32(b, at, 2) })
    }

    @Test fun externalReferencesProtectionUnknownBoxesAndDescriptionChangesFailClosed() {
        reject(patch(fixture(), "url ", 0) { b, at -> put32(b, at, 0) })
        reject(rename(fixture(), "url ", "urn "))
        reject(patch(fixture(), "avc1", 6) { b, at -> b[at + 1] = 2 })
        reject(rename(fixture(), "avc1", "avc3"))
        val encrypted = assertThrows(MovProfileException::class.java) { scan(rename(fixture(), "avc1", "encv")) }
        assertEquals(MovProfileFailure.PROTECTED_MEDIA, encrypted.failure)
        val pssh = assertThrows(MovProfileException::class.java) { scan(fixture() + OriginalMovFixtures.box("pssh", ByteArray(8))) }
        assertEquals(MovProfileFailure.PROTECTED_MEDIA, pssh.failure)
        reject(rename(fixture(), "stss", "sdtp"))
        reject(rename(fixture(), "stss", "zzzz"))
        reject(patch(fixture(), "avcC", 4) { b, at -> b[at] = 0xfe.toByte() })
        reject(patch(fixture(), "avcC", 5) { b, at -> b[at] = 0xe2.toByte() })
    }

    @Test fun sourceSyncAgreesWithIdrAndNoPreKeyframeOrInventedSyncIsAllowed() {
        reject(fixture(OriginalMovFixtures.Options(missingSync = true))) // Missing stss means ALL sync.
        val all = scan(fixture(OriginalMovFixtures.Options(missingSync = true, allIdr = true)))
        assertEquals(listOf(true, true, true), all.samples.map { it.sync })
        reject(patch(fixture(), "stss", 8) { b, at -> put32(b, at, 2) })
        val bytes = fixture()
        val first = scan(bytes).samples.first()
        val nonIdrFirst = bytes.clone().also { it[first.offset.toInt() + 4] = 0x41 }
        reject(nonIdrFirst)
        val second = scan(bytes).samples[1]
        reject(bytes.clone().also { it[second.offset.toInt() + 4] = 0x65 })
    }

    @Test fun sampleRangesCannotEscapeAliasOverlapOrOverflow() {
        val bytes = fixture()
        val first = scan(bytes).samples.first()
        reject(patch(bytes, "stco", 8) { b, at -> put32(b, at, 0) })
        reject(patch(bytes, "stco", 12) { b, at -> put32(b, at, first.offset.toInt()) })
        reject(patch(bytes, "stco", 12) { b, at -> put32(b, at, first.offset.toInt() + 1) })
        reject(patch(bytes, "stco", 16) { b, at -> put32(b, at, bytes.size - 1) })
        reject(patch(fixture(OriginalMovFixtures.Options(co64 = true)), "co64", 8) { b, at -> b[at] = 0x80.toByte() })
        reject(bytes.copyOf(bytes.size - 1))
        reject(patch(bytes, "mdat", -8) { b, at -> put32(b, at, 8) })
        reject(patch(bytes, "mdat", -8) { b, at -> put32(b, at, 0) })
    }

    @Test fun nalFramingChangesConfigUnsupportedNalsAndTrailingZerosReject() {
        val bytes = fixture(OriginalMovFixtures.Options(inBandConfig = true))
        val profile = scan(bytes)
        val sample = profile.samples[1]
        val start = sample.offset.toInt()
        reject(bytes.clone().also { put32(it, start, -1) })
        reject(bytes.clone().also { put32(it, start, 0) })
        reject(bytes.clone().also { it[start + 4] = 0x06 })
        reject(bytes.clone().also { it[start + 4 + 3] = 0x1f }) // Config change without changing tuple.
        reject(bytes.clone().also { it[start + sample.size - 1] = 0 })
        reject(bytes.clone().also { it[start + 4] = 0xe7.toByte() })
    }

    @Test fun everySliceHeaderRejectsBFramesUnstableIdsReorderingAndUnsupportedPrediction() {
        val defaults = OriginalMovFixtures.Options()
        listOf(
            defaults.copy(nonIdrSliceType = 1), defaults.copy(nonIdrSliceType = 3),
            defaults.copy(slicePpsId = 1), defaults.copy(frameStep = 2), defaults.copy(pocStep = 4),
            defaults.copy(referenceListModification = true), defaults.copy(adaptiveReferenceMarking = true),
            defaults.copy(idrDiscardPrior = true), defaults.copy(idrLongTerm = true),
            defaults.copy(maxReferences = 2), defaults.copy(ppsSliceGroups = 1),
            defaults.copy(ppsWeighted = true), defaults.copy(ppsRedundant = true),
            defaults.copy(progressive = false), defaults.copy(colorTransfer = 16),
        ).forEach { reject(fixture(it)) }
        val bytes = fixture()
        val second = scan(bytes).samples[1]
        reject(bytes.clone().also { it[second.offset.toInt() + 4] = 1 }) // Non-reference picture.
    }

    @Test fun samplePictureCannotStartAtNonzeroMbOrMixPictureSliceIdentities() {
        val bytes = fixture(OriginalMovFixtures.Options(width = 32, slices = 2))
        val sample = scan(bytes).samples[1]
        val start = sample.offset.toInt()
        val firstLength = ((bytes[start].toInt() and 255) shl 24) or ((bytes[start + 1].toInt() and 255) shl 16) or
            ((bytes[start + 2].toInt() and 255) shl 8) or (bytes[start + 3].toInt() and 255)
        val secondNal = start + 4 + firstLength + 4
        reject(bytes.clone().also { it[secondNal] = 0x61 }) // nal_ref_idc differs inside one AU.
        reject(bytes.clone().also { it[start + 5] = 0x7f }) // first_mb no longer zero / malformed header.
    }

    @Test fun fixedExtentAndCancellationAreCheckedDuringReadsAndSampleLoops() {
        val bytes = fixture()
        var reads = 0
        val changing = object : MovProbeInput {
            override val length get() = bytes.size.toLong() + if (reads > 2) 1 else 0
            override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int) {
                reads++; bytes.copyInto(target, targetOffset, offset.toInt(), offset.toInt() + length)
            }
        }
        assertThrows(IOException::class.java) { MovProfileProbe.probeQuickTime(changing) }
        val sentinel = IllegalStateException("TEST_CANCEL")
        var checks = 0
        val actual = assertThrows(IllegalStateException::class.java) {
            MovProfileProbe.probeQuickTime(OriginalMovFixtures.input(bytes)) { if (++checks == 20) throw sentinel }
        }
        assertSame(sentinel, actual)
        assertTrue(checks >= 20)
    }

    @Test fun fileMoovAndBoxBoundsRejectBeforeOversizedReadsOrAllocations() {
        val tooBig = object : MovProbeInput {
            override val length = 2L * 1024 * 1024 * 1024 + 1
            override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int) { fail("Must reject length before read") }
        }
        assertThrows(IOException::class.java) { MovProfileProbe.probeQuickTime(tooBig) }
        var largestRead = 0
        val moovHead = OriginalMovFixtures.bytes { writeInt(8 * 1024 * 1024 + 1); writeBytes("moov") }
        val hugeMoov = object : MovProbeInput {
            override val length = 8L * 1024 * 1024 + 1
            override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int) {
                largestRead = maxOf(largestRead, length)
                require(offset == 0L && length == 8)
                moovHead.copyInto(target, targetOffset)
            }
        }
        assertThrows(IOException::class.java) { MovProfileProbe.probeQuickTime(hugeMoov) }
        assertEquals(8, largestRead)
        val free = OriginalMovFixtures.box("free", ByteArray(0))
        val tooMany = fixture() + ByteArrayOutputStreamCompat.repeat(free, MovProfileProbe.MAX_BOXES)
        reject(tooMany)
    }

    private fun reject(bytes: ByteArray) {
        // Canonical/clock helpers intentionally use IllegalArgumentException; raw policy uses IO.
        val failure = assertThrows(Exception::class.java) { scan(bytes) }
        assertTrue("Unexpected failure type: ${failure.javaClass.name}", failure is IOException || failure is IllegalArgumentException)
    }
    private fun rename(bytes: ByteArray, from: String, to: String): ByteArray = bytes.clone().also { writeTag(it, offset(it, from) - 4, to) }
    private fun patch(bytes: ByteArray, type: String, field: Int, operation: (ByteArray, Int) -> Unit): ByteArray =
        bytes.clone().also { operation(it, offset(it, type) + field) }
    private fun patchNth(bytes: ByteArray, type: String, occurrence: Int, field: Int,
        operation: (ByteArray, Int) -> Unit): ByteArray = bytes.clone().also { result ->
        val needle = type.toByteArray(Charsets.US_ASCII)
        val found = (4 until result.size - 3).filter { at -> (0..3).all { result[at + it] == needle[it] } }
        operation(result, found[occurrence] + 4 + field)
    }
    private fun offset(bytes: ByteArray, tag: String): Int {
        val needle = tag.map { it.code.toByte() }.toByteArray()
        for (at in 4 until bytes.size - 3) if ((0..3).all { bytes[at + it] == needle[it] }) return at + 4
        error("Missing fixture box")
    }
    private fun writeTag(bytes: ByteArray, at: Int, tag: String) { repeat(4) { bytes[at + it] = tag[it].code.toByte() } }
    private fun put32(bytes: ByteArray, at: Int, value: Int) { repeat(4) { bytes[at + it] = (value ushr (24 - 8 * it)).toByte() } }
    private object ByteArrayOutputStreamCompat {
        fun repeat(bytes: ByteArray, count: Int): ByteArray = java.io.ByteArrayOutputStream().also { out -> repeat(count) { out.write(bytes) } }.toByteArray()
    }
}
