package com.easysavevideo.save

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic byte-protocol fixtures only; short NAL bytes assert no decoder-valid AVC or AAC. */
class DirectMovCanonicalTest {
    private val sps = bytes("6742001e80")
    private val pps = bytes("68ce06e2")
    private val configuration get() = DirectMovCanonical.avcConfiguration(sps, pps)

    @Test fun threeContractGoldenHashesMatchExactBytes() {
        assertEquals("d7043c95da89ec697ef3d135685964aef547739e1b254a2e81384e6865d56389", configuration.sha256)
        assertEquals("9ec82d69968492e81640faa1381a5e106d6750c9f0cfdb8cd4cedfca9ca7d4c1",
            DirectMovCanonical.aacConfiguration(bytes("1210")).sha256)
        val canonical = bytes("0000000265aa0000000761bb00000301cc")
        assertEquals("ba1df6b884c3c490701d39b361014828eb974d167d64e54a90b7201eef9dae74",
            DirectMovCanonical.sampleSha256(canonical))
        assertArrayEquals(canonical, DirectMovCanonical.avcSample(canonical, DirectMovNalFraming.AVCC4))
    }

    @Test fun exactOrderedNalsSurviveBothFramingsWithoutDroppingAudConfigOrEpb() {
        val nals = listOf(bytes("09f0"), sps, pps, sps, bytes("65aa00000300cc"), bytes("61bb00000303dd"))
        val expected = avcc(nals)
        for (annex in listOf(annex(nals, 3), annex(nals, 4), bytes("000001") + nals[0] + annex(nals.drop(1), 4))) {
            assertArrayEquals(expected, DirectMovCanonical.avcSample(annex, DirectMovNalFraming.ANNEX_B))
        }
        assertArrayEquals(expected, DirectMovCanonical.avcSample(expected, DirectMovNalFraming.AVCC4))
        val copied = DirectMovCanonical.avcNals(expected, DirectMovNalFraming.AVCC4)
        assertEquals(nals.size, copied.size)
        nals.indices.forEach { assertArrayEquals(nals[it], copied[it]) }
        assertNotEquals(DirectMovCanonical.sampleSha256(expected),
            DirectMovCanonical.sampleSha256(avcc(nals.reversed())))
        copied[0][0] = 1
        assertArrayEquals(nals[0], DirectMovCanonical.avcNals(expected, DirectMovNalFraming.AVCC4)[0])
    }

    @Test fun malformedAvccLengthsAndAnnexBoundariesCannotBeRepaired() {
        for (bad in listOf("", "00", "000000", "00000000", "0000000265", "ffffffff65", "8000000065",
            "000000016500", "000000016500000000", "00000003650000")) {
            reject { DirectMovCanonical.avcSample(bytes(bad), DirectMovNalFraming.AVCC4) }
        }
        for (bad in listOf("", "65aa", "0001aa", "000000000165aa", "000001", "00000001",
            "00000165aa000001", "00000100000165aa", "0000000165aa00", "0000000165aa0000",
            "0000000165aa000000")) {
            reject { DirectMovCanonical.avcSample(bytes(bad), DirectMovNalFraming.ANNEX_B) }
        }
        reject { DirectMovCanonical.avcSample(annex(listOf(bytes("65aa")), 4), DirectMovNalFraming.AVCC4) }
        reject { DirectMovCanonical.avcSample(avcc(listOf(bytes("65aa"))), DirectMovNalFraming.ANNEX_B) }
    }

    @Test fun forbiddenTerminalZerosUnescapedBytesMalformedEpbAndUnsupportedNalKindsReject() {
        val badNals = listOf("6500", "650000", "65000000", "6500000001aa", "65000001aa", "65000002aa",
            "65000003", "6500000304aa", "65000003ffaa", "e5aa", "06aa", "0aaa", "0baa", "0caa", "14aa", "00aa")
        for (nal in badNals) {
            reject { DirectMovCanonical.avcSample(avcc(listOf(bytes(nal))), DirectMovNalFraming.AVCC4) }
        }
        for (nal in listOf("6500000300aa", "6500000301aa", "6500000302aa", "6500000303aa", "65000004aa")) {
            val encoded = avcc(listOf(bytes(nal)))
            assertArrayEquals(encoded, DirectMovCanonical.avcSample(encoded, DirectMovNalFraming.AVCC4))
            assertArrayEquals(encoded, DirectMovCanonical.avcSample(annex(listOf(bytes(nal)), 4), DirectMovNalFraming.ANNEX_B))
        }
    }

    @Test fun sampleAndNalCountBoundsAreCheckedBeforeCanonicalAllocation() {
        val nals = List(1_024) { bytes("61aa") }
        assertEquals(1_024, DirectMovCanonical.avcNals(avcc(nals), DirectMovNalFraming.AVCC4).size)
        reject { DirectMovCanonical.avcSample(avcc(nals + bytes("61aa")), DirectMovNalFraming.AVCC4) }
        reject { DirectMovCanonical.avcSample(annex(nals + bytes("61aa"), 3), DirectMovNalFraming.ANNEX_B) }
        val exact = ByteArray(DirectNormalizationLimits.MAX_SAMPLE_BYTES - 4) { 0x55 }.also { it[0] = 0x65 }
        assertEquals(DirectNormalizationLimits.MAX_SAMPLE_BYTES,
            DirectMovCanonical.avcSample(avcc(listOf(exact)), DirectMovNalFraming.AVCC4).size)
        reject { DirectMovCanonical.avcSample(avcc(listOf(exact + 0x55.toByte())), DirectMovNalFraming.AVCC4) }
        // Source still fits 4 MiB; canonical four-byte prefix would exceed it by one.
        reject { DirectMovCanonical.avcSample(annex(listOf(exact + 0x55.toByte()), 3), DirectMovNalFraming.ANNEX_B) }
    }

    @Test fun configParsingChecksExactTupleReservedBitsHeadersAndNoTrailingData() {
        val avcc = avcConfig()
        assertEquals(configuration.sha256, DirectMovCanonical.avcConfigurationFromAvcc(avcc).sha256)
        assertEquals(configuration.sha256, DirectMovCanonical.avcConfigurationFromInitializationData(
            listOf(annex(listOf(sps), 4), annex(listOf(pps), 4))).sha256)
        for ((position, value) in listOf(0 to 2, 1 to 0x43, 2 to 1, 3 to 0x1f, 4 to 0xfe,
            4 to 0x7f, 5 to 0x21, 5 to 0xe0, 5 to 0xe2, 6 to 0xff, 7 to 0)) {
            reject { DirectMovCanonical.avcConfigurationFromAvcc(avcc.copyOf().also { it[position] = value.toByte() }) }
        }
        reject { DirectMovCanonical.avcConfigurationFromAvcc(avcc + 0.toByte()) }
        reject { DirectMovCanonical.avcConfigurationFromAvcc(avcc.copyOf(avcc.size - 1)) }
        val ppsCountPosition = 8 + sps.size
        reject { DirectMovCanonical.avcConfigurationFromAvcc(avcc.copyOf().also { it[ppsCountPosition] = 2 }) }
        reject { DirectMovCanonical.avcConfiguration(pps, sps) }
        reject { DirectMovCanonical.avcConfiguration(sps, pps + 0.toByte()) }
        reject { DirectMovCanonical.avcConfigurationFromInitializationData(listOf(annex(listOf(sps, sps), 4), annex(listOf(pps), 4))) }
        reject { DirectMovCanonical.avcConfigurationFromInitializationData(listOf(annex(listOf(sps), 4), annex(listOf(pps), 4), byteArrayOf(1))) }
        assertNotEquals(configuration.sha256, DirectMovCanonical.avcConfiguration(sps, pps.copyOf().also { it[3] = 0xe3.toByte() }).sha256)
        val config = configuration
        config.sps[0] = 0; config.pps[0] = 0
        assertArrayEquals(sps, config.sps); assertArrayEquals(pps, config.pps)
    }

    @Test fun configurationEncodingItselfMustFit64KiB() {
        val largeSps = ByteArray(65_495) { 0x55 }.also { it[0] = 0x67 }
        val smallPps = byteArrayOf(0x68, 0x55, 0x55, 0x55)
        // 23-byte domain + count(4) + two tags/lengths(10) + 65,499 bytes = 65,536.
        DirectMovCanonical.avcConfiguration(largeSps, smallPps)
        reject { DirectMovCanonical.avcConfiguration(largeSps + 0x55.toByte(), smallPps) }
        reject { DirectMovCanonical.avcConfiguration(ByteArray(65_536) { 0x67 }, pps) }
    }

    @Test fun allSixExactAscsHaveStableRateChannelsAndByteMutationCannotMasquerade() {
        for ((hex, rate, channels) in listOf(Triple("1288", 32_000, 1), Triple("1290", 32_000, 2),
            Triple("1208", 44_100, 1), Triple("1210", 44_100, 2), Triple("1188", 48_000, 1), Triple("1190", 48_000, 2))) {
            val asc = bytes(hex)
            val config = DirectMovCanonical.aacConfiguration(asc)
            assertEquals(rate, config.sampleRate); assertEquals(channels, config.channelCount)
            assertArrayEquals(asc, config.asc)
            assertEquals(referenceConfig(2, listOf(5 to asc)), config.sha256)
            val copied = config.asc; copied[0] = 0
            assertArrayEquals(asc, config.asc)
            reject { DirectMovCanonical.aacConfiguration(asc + 0.toByte()) }
            reject { DirectMovCanonical.aacConfiguration(asc.copyOf().also { it[1] = (it[1].toInt() or 1).toByte() }) }
        }
        reject { DirectMovCanonical.aacConfiguration(bytes("2b92")) }
        reject { DirectMovCanonical.aacConfiguration(bytes("1214")) }
        reject { DirectMovCanonical.aacConfigurationFromInitializationData(listOf(bytes("1210"), bytes("1210"))) }
        assertEquals(44_100, DirectMovCanonical.aacConfigurationFromInitializationData(listOf(bytes("1210"))).sampleRate)
    }

    @Test fun orderedAccumulationMatchesIndependentProtocolAndLiteralSequenceGoldens() {
        val clock = DirectMovClock.video(30, 3)
        val samples = listOf(bytes("0000000265aa"), bytes("0000000261bb"), bytes("0000000261cc"))
        val accumulator = DirectMovSampleAccumulator(clock, configuration.sha256)
        val identities = samples.mapIndexed { i, sample -> accumulator.append(sample, clock.presentationTimeUs(i.toLong()), if (i == 0) 1 else 0) }
        val result = accumulator.finish()
        assertEquals(3L, result.track.sampleCount); assertEquals(18L, result.track.canonicalBytes)
        assertEquals(identities.last(), result.track.lastSample)
        assertEquals(identities.first(), result.precedingVideoSync)
        assertEquals("22b3904162080efb8fce1dc83dce2dff6d7acd109b96461f39ee70bb5aae39c4", result.track.payloadSha256)
        assertEquals("232b7b2ae0694dadfe4f615ef2c4b7305fa3243ad68b9e9e4aed5d6e6a572cdc", result.track.sampleFlagsSha256)
        assertEquals("05687ac3f96e30bd5aedca41652236f5cc4b5e42561de8fdd500714d31a9b774", result.track.timingSha256)
        assertEquals(referenceSequence(2, 1, clock, samples, listOf(1, 0, 0)), result.track.payloadSha256)
        assertEquals(referenceSequence(3, 1, clock, samples, listOf(1, 0, 0)), result.track.sampleFlagsSha256)
        assertEquals(referenceSequence(4, 1, clock, samples, listOf(1, 0, 0)), result.track.timingSha256)
        reject { accumulator.append(samples[0], 100_000, 1) }; reject { accumulator.finish() }
        DirectRemuxConservation(16, 16, 30, result.track, null, null, null, requireNotNull(result.precedingVideoSync))
    }

    @Test fun accumulatorRejectsBadCountsPtsFlagsAndDistinguishesPayloadOrderAndSync() {
        val clock = DirectMovClock.video(30, 3)
        val sample = bytes("0000000265aa")
        val accumulator = DirectMovSampleAccumulator(clock, configuration.sha256)
        reject { accumulator.finish() }; reject { accumulator.append(sample, 0, 0) }
        reject { accumulator.append(sample, 1, 1) }; reject { accumulator.append(sample, 0, 2) }
        reject { accumulator.append(byteArrayOf(), 0, 1) }
        val first = accumulator.append(sample, 0, 1)
        reject { accumulator.append(sample, 33_334, 1) }
        accumulator.append(sample, 33_333, 0)
        val last = accumulator.append(sample, 66_666, 1)
        reject { accumulator.append(sample, 100_000, 1) }
        val result = accumulator.finish()
        assertEquals(last, result.precedingVideoSync); assertNotEquals(first, result.precedingVideoSync)
        fun proof(samples: List<ByteArray>, sync: List<Int>, rate: Int = 30): DirectTrackConservation {
            val c = DirectMovClock.video(rate, 3)
            val a = DirectMovSampleAccumulator(c, configuration.sha256)
            samples.forEachIndexed { i, bytes -> a.append(bytes, c.presentationTimeUs(i.toLong()), sync[i]) }
            return a.finish().track
        }
        val middle = bytes("0000000261bb"); val tail = bytes("0000000261cc")
        val base = proof(listOf(sample, middle, tail), listOf(1, 0, 0))
        assertNotEquals(base.payloadSha256, proof(listOf(sample, tail, middle), listOf(1, 0, 0)).payloadSha256)
        assertNotEquals(base.payloadSha256, proof(listOf(sample, middle, middle), listOf(1, 0, 0)).payloadSha256)
        assertNotEquals(base.sampleFlagsSha256, proof(listOf(sample, middle, tail), listOf(1, 1, 0)).sampleFlagsSha256)
        assertNotEquals(base.timingSha256, proof(listOf(sample, middle, tail), listOf(1, 0, 0), 24).timingSha256)
        reject { DirectMovSampleAccumulator(clock, "A".repeat(64)) }
    }

    @Test fun syntheticAacAccumulatorHashesRawBytesUnchangedAndRequiresAllSync() {
        val clock = DirectMovClock.audio(44_100, 2)
        val config = DirectMovCanonical.aacConfiguration(bytes("1210"))
        val samples = listOf(bytes("000001fe"), bytes("ffee00")) // Transport bytes, not valid AAC evidence.
        val a = DirectMovSampleAccumulator(clock, config.sha256)
        reject { a.append(samples[0], 0, 0) }
        a.append(samples[0], 0, 1); a.append(samples[1], 23_219, 1)
        val result = a.finish()
        assertNull(result.precedingVideoSync)
        assertEquals(7L, result.track.canonicalBytes)
        assertEquals(referenceSequence(2, 2, clock, samples, listOf(1, 1)), result.track.payloadSha256)
        assertEquals(referenceSequence(3, 2, clock, samples, listOf(1, 1)), result.track.sampleFlagsSha256)
        assertEquals(referenceSequence(4, 2, clock, samples, listOf(1, 1)), result.track.timingSha256)
    }

    @Test fun canonicalAndConfigurationScanningPropagateCancellationAtEveryCheckpoint() {
        val largeNal = ByteArray(32 * 1024) { 0x55 }.also { it[0] = 0x65 }
        val largeSps = largeNal.copyOf().also { it[0] = 0x67; it[1] = 0x42; it[2] = 0; it[3] = 0x1e }
        val raw = avcc(listOf(largeNal))
        val annexBytes = annex(listOf(largeNal), 4)
        val configBytes = encode {
            write(bytes("0142001effe1")); writeShort(largeSps.size); write(largeSps)
            writeByte(1); writeShort(pps.size); write(pps)
        }
        val operations = listOf<(() -> Unit) -> Unit>(
            { check -> DirectMovCanonical.avcNals(raw, DirectMovNalFraming.AVCC4, check) },
            { check -> DirectMovCanonical.avcNals(annexBytes, DirectMovNalFraming.ANNEX_B, check) },
            { check -> DirectMovCanonical.avcSample(raw, DirectMovNalFraming.AVCC4, check) },
            { check -> DirectMovCanonical.avcSample(annexBytes, DirectMovNalFraming.ANNEX_B, check) },
            { check -> DirectMovCanonical.avcConfiguration(largeSps, pps, check) },
            { check -> DirectMovCanonical.avcConfigurationFromAvcc(configBytes, check) },
            { check -> DirectMovCanonical.avcConfigurationFromInitializationData(
                listOf(annex(listOf(largeSps), 4), annex(listOf(pps), 4)), check) },
            { check -> DirectMovCanonical.sampleSha256(raw, check) },
        )
        for (operation in operations) {
            var completeChecks = 0
            operation { completeChecks++ }
            assertTrue(completeChecks >= 8) // Bounded progress, rather than entry/exit-only checks.
            for (stopAt in 1..completeChecks) {
                val cancellation = InterruptedException("owned test cancellation")
                var calls = 0
                val thrown = assertThrows(InterruptedException::class.java) {
                    operation { if (++calls == stopAt) throw cancellation }
                }
                assertSame(cancellation, thrown)
                assertEquals(stopAt, calls)
            }
        }
        assertArrayEquals(avcc(listOf(largeNal)), raw)
        assertArrayEquals(annex(listOf(largeNal), 4), annexBytes)
    }

    @Test fun cancelledPartialAccumulatorRecordCannotProduceAProofOrResume() {
        val clock = DirectMovClock.audio(48_000, 2)
        val payload = ByteArray(16 * 1024) { 0x55 }
        var hashChecks = 0
        DirectMovCanonical.sampleSha256(payload) { hashChecks++ }
        // append-entry + complete sample hash + pre-record + first payload chunk, then interrupt.
        val cancelAt = hashChecks + 4
        var calls = 0
        val cancellation = InterruptedException("owned test cancellation")
        val accumulator = DirectMovSampleAccumulator(clock, DirectMovCanonical.aacConfiguration(bytes("1190")).sha256) {
            if (++calls == cancelAt) throw cancellation
        }
        assertSame(cancellation, assertThrows(InterruptedException::class.java) { accumulator.append(payload, 0, 1) })
        assertEquals(cancelAt, calls)
        reject { accumulator.append(payload, 0, 1) }
        reject { accumulator.finish() }
    }

    @Test fun checkpointEnabledPathsKeepIdenticalDigestsAndFinalizationChecksBeforeMutation() {
        val clock = DirectMovClock.video(30, 2)
        var calls = 0
        var cancel = false
        val cancellation = InterruptedException("owned test cancellation")
        val check: () -> Unit = { calls++; if (cancel) throw cancellation }
        val config = DirectMovCanonical.avcConfigurationFromAvcc(avcConfig(), check)
        assertEquals(configuration.sha256, config.sha256)
        assertEquals(DirectMovCanonical.aacConfiguration(bytes("1210")).sha256,
            DirectMovCanonical.aacConfigurationFromInitializationData(listOf(bytes("1210")), check).sha256)
        val samples = listOf(bytes("0000000265aa"), bytes("0000000261bb"))
        val checked = DirectMovSampleAccumulator(clock, config.sha256, check)
        val ordinary = DirectMovSampleAccumulator(clock, config.sha256)
        samples.forEachIndexed { index, sample ->
            val pts = clock.presentationTimeUs(index.toLong())
            val sync = if (index == 0) 1 else 0
            assertArrayEquals(sample, DirectMovCanonical.avcSample(sample, DirectMovNalFraming.AVCC4, check))
            assertEquals(ordinary.append(sample, pts, sync), checked.append(sample, pts, sync))
        }
        assertTrue(calls > 10)
        cancel = true
        assertSame(cancellation, assertThrows(InterruptedException::class.java) { checked.finish() })
        cancel = false
        assertEquals(ordinary.finish(), checked.finish())
    }

    private fun avcConfig(): ByteArray = bytes("0142001effe1") + byteArrayOf(0, sps.size.toByte()) + sps +
        byteArrayOf(1, 0, pps.size.toByte()) + pps
    private fun avcc(nals: List<ByteArray>): ByteArray = encode { for (nal in nals) { writeInt(nal.size); write(nal) } }
    private fun annex(nals: List<ByteArray>, prefix: Int): ByteArray = encode {
        for (nal in nals) { repeat(prefix - 1) { writeByte(0) }; writeByte(1); write(nal) }
    }
    private fun bytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun reject(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }
    private fun encode(body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream(); DataOutputStream(bytes).use { it.body() }; return bytes.toByteArray()
    }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun DataOutputStream.header(domain: Int, kind: Int) {
        writeBytes("ESV-DIRECT-REMUX"); writeByte(0); writeInt(1); writeByte(domain); writeByte(kind)
    }
    private fun referenceConfig(kind: Int, records: List<Pair<Int, ByteArray>>): String = digest(encode {
        header(1, kind); writeInt(records.size)
        for ((tag, bytes) in records) { writeByte(tag); writeInt(bytes.size); write(bytes) }
    })
    private fun referenceSequence(domain: Int, kind: Int, clock: DirectMovClock,
        samples: List<ByteArray>, flags: List<Int>): String = digest(encode {
        header(domain, kind)
        if (domain == 4) { writeInt(clock.timescale); writeInt(clock.sampleDeltaTicks.toInt()) }
        samples.forEachIndexed { i, sample ->
            writeByte(1); writeLong(i.toLong())
            when (domain) {
                2 -> { writeInt(sample.size); write(sample) }
                3 -> writeByte(flags[i])
                4 -> writeLong(i * clock.sampleDeltaTicks * 1_000_000 / clock.timescale)
            }
        }
        writeByte(255); writeLong(samples.size.toLong())
        if (domain == 2) writeLong(samples.sumOf { it.size.toLong() })
        if (domain == 4) {
            val ticks = samples.size * clock.sampleDeltaTicks
            val minimum = ticks * 1_000_000 / clock.timescale
            writeLong(ticks); writeLong(minimum)
            writeLong(minimum + if (ticks * 1_000_000 % clock.timescale == 0L) 0 else 1)
        }
    })
}
