package com.easysavevideo.app.save.stream

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Synthetic transport and header checks ONLY. These bytes are not valid codec/decode fixtures. */
class CompatTsProfileProbeTest {
    @Test fun boundedSyntheticHeadersProduceOriginalTimestampEvidence() {
        val ts = SyntheticTs()
        val result = probe(ts.segment(pts = 90000, final = false), ts.segment(pts = 180000))
        assertEquals(256, result.videoPid)
        assertNull(result.audioPid)
        assertEquals(2L, result.videoPesCount)
        assertEquals(2L, result.videoAccessUnitCount)
        assertEquals(90000L, result.firstVideoPts90Khz)
        assertEquals(180000L, result.lastVideoPts90Khz)
        assertEquals(listOf(90000L, 180000L), result.segments.map { it.firstVideoPts90Khz })
        assertEquals(66, result.sps[1].toInt() and 255)
    }

    @Test fun syntheticAdtsHeadersAreCountedWithoutClaimingDecodeValidity() {
        val ts = SyntheticTs(audio = true)
        val result = probe(ts.segment(90000, audioPayload = adts() + adts()))
        assertEquals(257, result.audioPid)
        assertEquals(48000, result.audioSampleRate)
        assertEquals(2, result.audioChannelCount)
        assertEquals(1L, result.audioPesCount)
        assertEquals(2L, result.audioFrameCount)
        assertEquals(2L, result.segments.single().audioFrameCount)
    }

    @Test fun emptyAndNon188ByteInputsFailClosed() {
        rejects(CompatTsProfileFailure.EMPTY, byteArrayOf())
        rejects(CompatTsProfileFailure.PACKET, ByteArray(189))
        rejects(CompatTsProfileFailure.PACKET, SyntheticTs().segment().dropLast(1).toByteArray())
    }

    @Test fun wrongSyncTransportErrorAndScramblingAreRejected() {
        val good = SyntheticTs().segment()
        rejects(CompatTsProfileFailure.PACKET, good.clone().also { it[0] = 0 })
        rejects(CompatTsProfileFailure.PACKET, good.clone().also { it[1] = (it[1].toInt() or 0x80).toByte() })
        rejects(CompatTsProfileFailure.PROTECTED, good.clone().also { it[3] = (it[3].toInt() or 0x80).toByte() })
    }

    @Test fun adaptationDiscontinuityIsNotHiddenByCounters() {
        val bad = SyntheticTs().segment().also { it[5] = 0x80.toByte() }
        rejects(CompatTsProfileFailure.DISCONTINUITY, bad)
    }

    @Test fun patAndPmtCrcAreChecked() {
        rejects(CompatTsProfileFailure.PSI, SyntheticTs().segment(pat = pat().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }))
        rejects(CompatTsProfileFailure.PSI, SyntheticTs().segment(pmt = pmt().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }))
    }

    @Test fun multipleProgramsAndUnexpectedElementaryStreamsAreRejected() {
        rejects(CompatTsProfileFailure.PROGRAM, SyntheticTs().segment(pat = pat(multiple = true)))
        rejects(CompatTsProfileFailure.TRACKS, SyntheticTs().segment(pmt = pmt(extraType = 0x15)))
        rejects(CompatTsProfileFailure.TRACKS, SyntheticTs().segment(pmt = pmt(extraType = 0x06)))
        rejects(CompatTsProfileFailure.TRACKS, SyntheticTs().segment(pmt = pmt(extraType = 0x24)))
    }

    @Test fun unknownPidIsRejectedEvenIfItLooksLikeNullData() {
        val ts = SyntheticTs()
        rejects(CompatTsProfileFailure.TRACKS, ts.segment() + ts.packet(444, byteArrayOf(1), false))
    }

    @Test fun everySegmentMustRepeatPatAndPmt() {
        val ts = SyntheticTs()
        rejects(CompatTsProfileFailure.PROGRAM, ts.segment(90000, final = false), ts.segment(180000, tables = false))
    }

    @Test fun continuityCountersCannotResetOrSkipAtSegmentBoundary() {
        val first = SyntheticTs().segment(90000, final = false)
        val restarted = SyntheticTs().segment(180000)
        rejects(CompatTsProfileFailure.CONTINUITY, first, restarted)
        val ts = SyntheticTs()
        val a = ts.segment(90000, final = false)
        val b = ts.segment(180000).also { it[3] = (it[3].toInt() and 0xf0 or 3).toByte() }
        rejects(CompatTsProfileFailure.CONTINUITY, a, b)
    }

    @Test fun duplicatePayloadPacketsAreNotSilentlyDiscarded() {
        val ts = SyntheticTs()
        val good = ts.segment()
        rejects(CompatTsProfileFailure.CONTINUITY, good.copyOfRange(0, 188) + good)
    }

    @Test fun rawTimestampResetWrapEqualValuesAndHugeGapsAreRejected() {
        for ((first, last) in listOf(90000L to 90000L, 180000L to 90000L, 90000L to 270001L, ((1L shl 33) - 90000L) to 90000L)) {
            val ts = SyntheticTs()
            rejects(CompatTsProfileFailure.TIMESTAMP, ts.segment(first, final = false), ts.segment(last))
        }
    }

    @Test fun reorderedPtsDtsAndMissingMarkerBitsAreRejected() {
        rejects(CompatTsProfileFailure.TIMESTAMP, SyntheticTs().segment(pes = pes(90000, au(), dts = 89999)))
        val p = pes(90000, au()).also { it[9] = (it[9].toInt() and 0xfe).toByte() }
        rejects(CompatTsProfileFailure.TIMESTAMP, SyntheticTs().segment(pes = p))
    }

    @Test fun declaredPesLengthMustExactlyMatchPayload() {
        val incomplete = pes(90000, au()).also { it[5] = (it[5].toInt() + 1).toByte() }
        rejects(CompatTsProfileFailure.INCOMPLETE, SyntheticTs().segment(pes = incomplete))
        val extra = pes(90000, au()) + byteArrayOf(0xff.toByte())
        rejects(CompatTsProfileFailure.INCOMPLETE, SyntheticTs().segment(pes = extra))
    }

    @Test fun completeFinalUnboundedVideoPesMayHaveNoSourceTail() {
        val noTail = pes(90000, au(trailingAud = false, end = false), unbounded = true)
        val pending = probe(SyntheticTs().segment(pes = noTail))
        assertEquals(1L, pending.videoAccessUnitCount)
        assertTrue(pending.terminalPendingVcl)
        val complete = pes(90000, au(), unbounded = true)
        val delimited = probe(SyntheticTs().segment(pes = complete))
        assertEquals(1L, delimited.videoAccessUnitCount)
        assertFalse(delimited.terminalPendingVcl)
        val onlyEnd = pes(90000, au(trailingAud = false), unbounded = true)
        assertTrue(probe(SyntheticTs().segment(pes = onlyEnd)).terminalPendingVcl)
    }

    @Test fun intermediateUnboundedPesEndsAtNextSegmentsNewPusiAndAud() {
        val good = SyntheticTs()
        val complete = pes(90000, au(trailingAud = false, end = true), unbounded = true)
        assertEquals(2L, probe(good.segment(90000, pes = complete), good.segment(93000)).videoAccessUnitCount)
        val ordinary = SyntheticTs()
        val noTail = pes(90000, au(trailingAud = false, end = false), unbounded = true)
        val next = pes(93000, picture(listOf(nal(1, slice(0, false, frameNum = 1))),
            sps = null, pps = null), unbounded = true)
        val result = probe(ordinary.segment(90000, pes = noTail), ordinary.segment(93000, pes = next))
        assertEquals(2L, result.videoAccessUnitCount)
        assertTrue(result.terminalPendingVcl)
    }

    @Test fun intermediateUnboundedPesCannotContinueAcrossSegmentWithoutPusi() {
        val ts = SyntheticTs()
        val first = ts.segment(90000, pes = pes(90000, au(trailingAud = false, end = false), unbounded = true))
        val second = ts.segment(93000, final = false)
        // PAT and PMT consume the first two packets. Clear PUSI on the new video packet;
        // its payload is otherwise well-formed, so failure establishes the boundary gate.
        second[2 * 188 + 1] = (second[2 * 188 + 1].toInt() and 0xbf).toByte()
        rejects(CompatTsProfileFailure.INCOMPLETE, first, second)
    }

    @Test fun newPusiCannotCarryContinuedSlicesAcrossSegmentWithoutNewAud() {
        val ts = SyntheticTs()
        val first = ts.segment(90000, pes = pes(90000,
            picture(listOf(nal(5, slice()))), unbounded = true))
        val second = ts.segment(93000, pes = pes(93000,
            nal(5, slice(firstMb = 1)), unbounded = true))
        rejects(CompatTsProfileFailure.UNSUPPORTED_AU_LAYOUT, first, second)
    }

    @Test fun boundedFinalVideoPesDoesNotRequireSourceDelimiterOrInventTimestamp() {
        val result = probe(SyntheticTs().segment(pes = pes(90000, au())))
        assertEquals(1L, result.videoAccessUnitCount)
        assertEquals(90000L, result.lastVideoPts90Khz)
        val missingAud = pes(90000, au(trailingAud = false, end = false))
        val noTail = probe(SyntheticTs().segment(pes = missingAud))
        assertEquals(1L, noTail.videoAccessUnitCount)
        assertEquals(90000L, noTail.lastVideoPts90Khz)
        assertTrue(noTail.terminalPendingVcl)
        assertFalse(result.terminalPendingVcl)
    }

    @Test fun finalEmptyAudMustBeDelimitedByEndMarkerEvenForFinitePes() {
        val missingEnd = pes(90000, au(end = false))
        rejects(CompatTsProfileFailure.UNSUPPORTED_AU_LAYOUT, SyntheticTs().segment(pes = missingEnd))
    }

    @Test fun baselineOnlyActualBSlicesAndNonIdrAssetStartFail() {
        rejects(CompatTsProfileFailure.AVC, SyntheticTs().segment(pes = pes(90000, au(sps = sps(profile = 100)))))
        rejects(CompatTsProfileFailure.AVC, SyntheticTs().segment(pes = pes(90000, au(sliceType = 1))))
        rejects(CompatTsProfileFailure.AVC, SyntheticTs().segment(pes = pes(90000, au(idr = false, sliceType = 0))))
    }

    @Test fun multisliceAudPicturesCountOneRealAccessUnitEach() {
        val ts = SyntheticTs()
        val first = picture(listOf(nal(5, slice(firstMb = 0)), nal(5, slice(firstMb = 1))))
        val second = picture(listOf(nal(1, slice(0, false, firstMb = 0, frameNum = 1)),
            nal(1, slice(0, false, firstMb = 1, frameNum = 1))), sps = null, pps = null)
        val result = probe(ts.segment(90000, pes = pes(90000, first)),
            ts.segment(93000, pes = pes(93000, second, unbounded = true)))
        assertEquals(2L, result.videoAccessUnitCount)
        assertEquals(2L, result.videoPesCount)
        assertEquals(listOf(1L, 1L), result.segments.map { it.videoAccessUnitCount })
        assertTrue(result.terminalPendingVcl)
        assertEquals(93000L, result.lastVideoPts90Khz)
    }

    @Test fun inheritedSpsPpsAndNonIdrSecondSegmentAreAccepted() {
        val ts = SyntheticTs()
        val subsequent = picture(listOf(nal(1, slice(0, false, frameNum = 1))),
            sps = null, pps = null, trailingAud = true, end = true)
        val result = probe(ts.segment(90000, final = false),
            ts.segment(93000, pes = pes(93000, subsequent)))
        assertEquals(2L, result.videoAccessUnitCount)
        assertFalse(result.terminalPendingVcl)
    }

    @Test fun ppsMayBeRepeatedAgainstInheritedSps() {
        val ts = SyntheticTs()
        val subsequent = picture(listOf(nal(1, slice(0, false, frameNum = 1))), sps = null)
        val result = probe(ts.segment(90000, final = false),
            ts.segment(93000, pes = pes(93000, subsequent)))
        assertEquals(2L, result.videoAccessUnitCount)
    }

    @Test fun explicitEndMarkerStillRequiresNewIdrInsteadOfDependentPicture() {
        val ts = SyntheticTs()
        val ended = au(trailingAud = false, end = true)
        val dependent = picture(listOf(nal(1, slice(0, false, frameNum = 1))), sps = null, pps = null)
        rejects(CompatTsProfileFailure.AVC, ts.segment(90000, pes = pes(90000, ended)),
            ts.segment(93000, pes = pes(93000, dependent)))
    }

    @Test fun sliceMacroblocksMustStartAtZeroIncreaseAndFitPicture() {
        val badStarts = listOf(listOf(1), listOf(0, 0), listOf(0, 2), listOf(0, 1, 1))
        for (starts in badStarts) {
            val body = picture(starts.map { nal(5, slice(firstMb = it)) })
            rejects(CompatTsProfileFailure.AVC, SyntheticTs().segment(pes = pes(90000, body)))
        }
        val decreasing = picture(listOf(0, 2, 1).map { nal(5, slice(firstMb = it)) }, sps = sps(widthMbs = 3))
        rejects(CompatTsProfileFailure.AVC, SyntheticTs().segment(pes = pes(90000, decreasing)))
    }

    @Test fun multislicePicturesMustHaveIdenticalFrameAndIdrIdentity() {
        val first = nal(5, slice())
        for (other in listOf(
            nal(5, slice(firstMb = 1, frameNum = 1)),
            nal(5, slice(firstMb = 1, idrPicId = 1)),
            nal(1, slice(0, false, firstMb = 1)),
            nal(5, slice(firstMb = 1), refIdc = 1),
            nal(5, slice(firstMb = 1, ppsId = 1)),
        )) {
            rejects(CompatTsProfileFailure.AVC,
                SyntheticTs().segment(pes = pes(90000, picture(listOf(first, other)))))
        }
    }

    @Test fun multislicePicturesMustHaveSamePocLsbAndDeltaPoc() {
        for (pocType in listOf(0, 1)) {
            val first = nal(5, slice(pocType = pocType))
            val changed = nal(5, slice(firstMb = 1, pocType = pocType, poc = 1))
            rejects(CompatTsProfileFailure.AVC, SyntheticTs().segment(pes = pes(90000,
                picture(listOf(first, changed), sps = sps(widthMbs = 2, pocType = pocType)))))
            val same = nal(5, slice(firstMb = 1, pocType = pocType))
            assertEquals(1L, probe(SyntheticTs().segment(pes = pes(90000,
                picture(listOf(first, same), sps = sps(widthMbs = 2, pocType = pocType))))).videoAccessUnitCount)
        }
    }

    @Test fun mixedSliceTypesWithinNonIdrPictureRemainUnsupported() {
        val ts = SyntheticTs()
        val mixed = picture(listOf(nal(1, slice(0, false, frameNum = 1)),
            nal(1, slice(2, false, firstMb = 1, frameNum = 1))), sps = null, pps = null)
        rejects(CompatTsProfileFailure.AVC,
            ts.segment(90000, pes = pes(90000, picture(listOf(nal(5, slice()))))),
            ts.segment(93000, pes = pes(93000, mixed)))
    }

    @Test fun noAudAndAuCrossingPesRemainExplicitlyUnsupported() {
        val noAud = nal(7, sps()) + nal(8, pps()) + nal(5, slice())
        rejects(CompatTsProfileFailure.UNSUPPORTED_AU_LAYOUT,
            SyntheticTs().segment(pes = pes(90000, noAud)))
        val ts = SyntheticTs()
        val firstSlice = picture(listOf(nal(5, slice())))
        val continued = nal(5, slice(firstMb = 1))
        rejects(CompatTsProfileFailure.UNSUPPORTED_AU_LAYOUT,
            ts.segment(90000, pes = pes(90000, firstSlice)) + ts.packets(256, pes(90000, continued)))
    }

    @Test fun parameterSetsCannotBeChangedOrInsertedBetweenSlices() {
        val body = picture(listOf(nal(5, slice()), nal(7, sps(widthMbs = 2)), nal(5, slice(firstMb = 1))))
        rejects(CompatTsProfileFailure.AVC, SyntheticTs().segment(pes = pes(90000, body)))
    }

    @Test fun boundedFinalWholePacketLossStillFailsDeclaredLength() {
        val body = au(trailingAud = false, end = false) + nal(12,
            ByteArray(512) { 0xff.toByte() } + byteArrayOf(0x80.toByte()))
        val bytes = SyntheticTs().segment(pes = pes(90000, body))
        rejects(CompatTsProfileFailure.INCOMPLETE, bytes.copyOf(bytes.size - 188))
    }

    @Test fun finalPartialNalHeaderAndStartCodeAreNotAcceptedAsTail() {
        val body = au(trailingAud = false, end = false)
        rejects(CompatTsProfileFailure.INCOMPLETE,
            SyntheticTs().segment(pes = pes(90000, body + byteArrayOf(0, 0, 1), unbounded = true)))
        rejects(CompatTsProfileFailure.INCOMPLETE,
            SyntheticTs().segment(pes = pes(90000, body + nal(5, byteArrayOf()), unbounded = true)))
    }

    @Test fun everySpsAndPpsChangeIsRejectedIncludingLateChanges() {
        val ts = SyntheticTs()
        rejects(CompatTsProfileFailure.FORMAT_CHANGE, ts.segment(90000, final = false), ts.segment(180000, pes = pes(180000, au(sps = sps(level = 31)))))
        val other = SyntheticTs()
        rejects(CompatTsProfileFailure.FORMAT_CHANGE, other.segment(90000, final = false), other.segment(180000, pes = pes(180000, au(pps = pps(constrained = true)))))
    }

    @Test fun noAvcParameterSetsOrMultipleAccessUnitsInPesFailClosed() {
        rejects(CompatTsProfileFailure.AVC, SyntheticTs().segment(pes = pes(90000, nal(9, byteArrayOf(0xf0.toByte())) + nal(5, slice()) + nal(9, byteArrayOf(0xf0.toByte())))))
        rejects(CompatTsProfileFailure.UNSUPPORTED_AU_LAYOUT, SyntheticTs().segment(pes = pes(90000, au(trailingAud = false, end = false) + au())))
    }

    @Test fun malformedExpGolombAndTruncatedHeadersAreBounded() {
        val excessiveGolomb = byteArrayOf(66, 0, 30) + ByteArray(8) + byteArrayOf(0x80.toByte())
        val excessivePes = pes(90000, au(sps = excessiveGolomb))
        rejects(CompatTsProfileFailure.LIMIT, SyntheticTs().segment(pes = excessivePes))
        val truncatedPes = pes(90000, au(sps = byteArrayOf(66)))
        rejects(CompatTsProfileFailure.INCOMPLETE, SyntheticTs().segment(pes = truncatedPes))
    }

    @Test fun adtsConfigurationChangesAndTruncatedFramesFailClosed() {
        val ts = SyntheticTs(audio = true)
        rejects(CompatTsProfileFailure.FORMAT_CHANGE, ts.segment(90000, final = false), ts.segment(180000, audioPayload = adts(rate = 4)))
        val other = SyntheticTs(audio = true)
        rejects(CompatTsProfileFailure.FORMAT_CHANGE, other.segment(90000, final = false), other.segment(180000, audioPayload = adts(channels = 1)))
        rejects(CompatTsProfileFailure.INCOMPLETE, SyntheticTs(audio = true).segment(audioPayload = adts().dropLast(1).toByteArray()))
        rejects(CompatTsProfileFailure.AAC, SyntheticTs(audio = true).segment(audioPayload = adts(profile = 0)))
    }

    @Test fun missingWholeAacFrameBetweenPesFailsEvenWithinTwoSecondGapLimit() {
        val ts = SyntheticTs(audio = true)
        rejects(CompatTsProfileFailure.TIMESTAMP, ts.segment(90000, final = false), ts.segment(93840))
    }

    @Test fun audioPesCadenceUsesParsedMultiFrameDurationWithOneTickRounding() {
        val ts = SyntheticTs(audio = true)
        val twoFrames = adts(rate = 4) + adts(rate = 4)
        val threeFrames = twoFrames + adts(rate = 4)
        // At 44.1 kHz, 2 AAC frames = 4179.5918 ticks and 3 frames = 6269.3878 ticks.
        val result = probe(
            ts.segment(90000, final = false, audioPayload = twoFrames),
            ts.segment(94180, final = false, audioPayload = threeFrames),
            ts.segment(100449, audioPayload = adts(rate = 4)),
        )
        assertEquals(6L, result.audioFrameCount)
        assertEquals(listOf(90000L, 94180L, 100449L), result.segments.map { it.firstAudioPts90Khz })
    }

    @Test fun audioCadenceToleranceIsOneObservedTickRatherThanFrameRepair() {
        for (delta in listOf(1919L, 1920L, 1921L)) {
            val ts = SyntheticTs(audio = true)
            assertEquals(2L, probe(ts.segment(90000, final = false), ts.segment(90000 + delta)).audioFrameCount)
        }
        for (delta in listOf(1918L, 1922L)) {
            val ts = SyntheticTs(audio = true)
            rejects(CompatTsProfileFailure.TIMESTAMP, ts.segment(90000, final = false), ts.segment(90000 + delta))
        }
    }

    @Test fun optionalAudioCannotDisappearInAnySegment() {
        val ts = SyntheticTs(audio = true)
        rejects(CompatTsProfileFailure.INCOMPLETE, ts.segment(90000, final = false), ts.segment(180000, omitAudio = true))
    }

    @Test fun payloadLossInsideMultiPacketPesIsRejectedBeforeCodecParsing() {
        val input = SyntheticTs().segment(pes = pes(90000, au() + ByteArray(512) { 0xff.toByte() }))
        val dropped = input.copyOfRange(0, 188 * 3) + input.copyOfRange(188 * 4, input.size)
        rejects(CompatTsProfileFailure.CONTINUITY, dropped)
    }

    @Test fun oversizedPesCannotAllocateAnUnboundedAccessUnit() {
        val tooLarge = ByteArray(4 * 1024 * 1024 + 1)
        rejects(CompatTsProfileFailure.LIMIT, SyntheticTs().segment(pes = tooLarge))
    }

    @Test fun intermediateEmptyAudWouldCreateGhostSampleAndIsRejected() {
        val ts = SyntheticTs()
        rejects(CompatTsProfileFailure.INCOMPLETE, ts.segment(90000, pes = pes(90000, au(trailingAud = true))), ts.segment(180000))
    }

    @Test fun approvedInputCapabilityIsOpenedAndClosedExactlyOnce() {
        val bytes = SyntheticTs().segment()
        var opened = 0
        var closed = 0
        val source = CompatTsInput(bytes.size.toLong()) {
            opened++
            object : ByteArrayInputStream(bytes) {
                override fun close() { closed++; super.close() }
            }
        }
        assertEquals(1L, CompatTsProfileProbe.inspectSources(listOf(source)).videoAccessUnitCount)
        assertEquals(1, opened)
        assertEquals(1, closed)
    }

    @Test fun capabilityByteCountMustMatchActualReadInBothDirections() {
        val bytes = SyntheticTs().segment()
        for (declared in listOf(bytes.size.toLong() - 188, bytes.size.toLong() + 188)) {
            val source = CompatTsInput(declared) { ByteArrayInputStream(bytes) }
            try { CompatTsProfileProbe.inspectSources(listOf(source)); fail("Expected incomplete source") }
            catch (failure: CompatTsProfileException) { assertEquals(CompatTsProfileFailure.INCOMPLETE, failure.reason) }
        }
    }

    @Test fun releaseFailureOverridesParserFailureWithoutExposingUnderlyingMessage() {
        val bytes = SyntheticTs().segment().also { it[0] = 0 }
        var closed = false
        val source = CompatTsInput(bytes.size.toLong()) {
            object : ByteArrayInputStream(bytes) {
                override fun close() { closed = true; throw IOException("not-for-user-output") }
            }
        }
        try { CompatTsProfileProbe.inspectSources(listOf(source)); fail("Expected unconfirmed release") }
        catch (failure: CompatTsProfileException) {
            assertEquals(CompatTsProfileFailure.RELEASE_UNCONFIRMED, failure.reason)
            assertEquals("RELEASE_UNCONFIRMED", failure.message)
            assertNull(failure.cause)
        }
        assertTrue(closed)
    }

    @Test fun cancellationAfterCapabilityOpenStillClosesReader() {
        val bytes = SyntheticTs().segment()
        var closed = false
        var checkpoints = 0
        val cancellation = IllegalStateException("technical-test-cancellation")
        val source = CompatTsInput(bytes.size.toLong()) {
            object : ByteArrayInputStream(bytes) {
                override fun close() { closed = true; super.close() }
            }
        }
        try {
            CompatTsProfileProbe.inspectSources(listOf(source)) { if (++checkpoints == 2) throw cancellation }
            fail("Expected cancellation")
        } catch (failure: IllegalStateException) { assertSame(cancellation, failure) }
        assertTrue(closed)
    }

    @Test fun checkpointPropagatesCancellationWithoutConvertingItToMediaFailure() {
        val file = File.createTempFile("synthetic-ts-", ".bin")
        try {
            file.writeBytes(SyntheticTs().segment())
            val cancellation = IllegalStateException("technical-test-cancellation")
            try { CompatTsProfileProbe.inspect(listOf(file)) { throw cancellation }; fail("Expected cancellation") }
            catch (actual: IllegalStateException) { assertSame(cancellation, actual) }
        } finally { file.delete() }
    }

    private fun probe(vararg bytes: ByteArray): CompatTsProfileEvidence {
        val files = bytes.map { body -> File.createTempFile("synthetic-ts-", ".bin").also { it.writeBytes(body) } }
        return try { CompatTsProfileProbe.inspect(files) } finally { files.forEach { it.delete() } }
    }
    private fun rejects(reason: CompatTsProfileFailure, vararg bytes: ByteArray) {
        try { probe(*bytes); fail("Expected ${reason.name}") }
        catch (failure: CompatTsProfileException) { assertEquals(reason, failure.reason) }
    }

    private class SyntheticTs(private val audio: Boolean = false) {
        private val counters = mutableMapOf<Int, Int>()
        fun segment(
            pts: Long = 90000,
            pat: ByteArray = pat(),
            pmt: ByteArray = pmt(audio),
            final: Boolean = true,
            pes: ByteArray = pes(pts, au(trailingAud = final, end = final)),
            audioPayload: ByteArray = adts(),
            tables: Boolean = true,
            omitAudio: Boolean = false,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            if (tables) { out.write(packet(0, byteArrayOf(0) + pat, true)); out.write(packet(4096, byteArrayOf(0) + pmt, true)) }
            out.write(packets(256, pes))
            if (audio && !omitAudio) out.write(packets(257, pes(pts, audioPayload, streamId = 0xc0)))
            return out.toByteArray()
        }
        fun packets(pid: Int, bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var at = 0
            while (at < bytes.size) {
                val end = minOf(at + 184, bytes.size)
                out.write(packet(pid, bytes.copyOfRange(at, end), at == 0))
                at = end
            }
            return out.toByteArray()
        }
        fun packet(pid: Int, payload: ByteArray, start: Boolean): ByteArray {
            require(payload.size in 1..184)
            val counter = counters[pid] ?: 0
            counters[pid] = (counter + 1) and 15
            val b = ByteArray(188) { 0xff.toByte() }
            b[0] = 0x47
            b[1] = ((pid ushr 8) or if (start) 0x40 else 0).toByte()
            b[2] = pid.toByte()
            b[3] = ((if (payload.size == 184) 0x10 else 0x30) or counter).toByte()
            if (payload.size < 184) { b[4] = (183 - payload.size).toByte(); if (payload.size < 183) b[5] = 0 }
            payload.copyInto(b, 188 - payload.size)
            return b
        }
    }

    private class BitWriter {
        private val bits = StringBuilder()
        fun bit(value: Boolean) { bits.append(if (value) '1' else '0') }
        fun bits(value: Int, count: Int) { for (i in count - 1 downTo 0) bit(value ushr i and 1 != 0) }
        fun ue(value: Int) { val s = (value + 1).toString(2); repeat(s.length - 1) { bit(false) }; bits.append(s) }
        fun se(value: Int) = ue(if (value <= 0) -2 * value else 2 * value - 1)
        fun finish(): ByteArray {
            bit(true)
            while (bits.length % 8 != 0) bit(false)
            return bits.toString().chunked(8).map { it.toInt(2).toByte() }.toByteArray()
        }
    }

    companion object {
        private fun crc(body: ByteArray): ByteArray {
            var crc = -1
            for (b in body) {
                crc = crc xor ((b.toInt() and 255) shl 24)
                repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
            }
            return body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        }
        private fun pat(multiple: Boolean = false): ByteArray {
            val entries = byteArrayOf(0, 1, 0xf0.toByte(), 0) + if (multiple) byteArrayOf(0, 2, 0xf0.toByte(), 1) else byteArrayOf()
            return crc(byteArrayOf(0, 0xb0.toByte(), (9 + entries.size).toByte(), 0, 1, 0xc1.toByte(), 0, 0) + entries)
        }
        private fun pmt(audio: Boolean = false, extraType: Int? = null): ByteArray {
            var streams = byteArrayOf(0x1b, 0xe1.toByte(), 0, 0xf0.toByte(), 0)
            if (audio) streams += byteArrayOf(0x0f, 0xe1.toByte(), 1, 0xf0.toByte(), 0)
            if (extraType != null) streams += byteArrayOf(extraType.toByte(), 0xe1.toByte(), 2, 0xf0.toByte(), 0)
            return crc(byteArrayOf(2, 0xb0.toByte(), (13 + streams.size).toByte(), 0, 1, 0xc1.toByte(), 0, 0, 0xe1.toByte(), 0, 0xf0.toByte(), 0) + streams)
        }
        private fun pts(value: Long, prefix: Int): ByteArray = byteArrayOf(
            ((prefix shl 4) or (((value ushr 30).toInt() and 7) shl 1) or 1).toByte(),
            (value ushr 22).toByte(), ((((value ushr 15).toInt() and 127) shl 1) or 1).toByte(),
            (value ushr 7).toByte(), (((value.toInt() and 127) shl 1) or 1).toByte(),
        )
        private fun pes(value: Long, payload: ByteArray, dts: Long? = null, unbounded: Boolean = false, streamId: Int = 0xe0): ByteArray {
            val timing = if (dts == null) pts(value, 2) else pts(value, 3) + pts(dts, 1)
            val body = byteArrayOf(0x80.toByte(), if (dts == null) 0x80.toByte() else 0xc0.toByte(), timing.size.toByte()) + timing + payload
            val length = if (unbounded) 0 else body.size
            return byteArrayOf(0, 0, 1, streamId.toByte(), (length ushr 8).toByte(), length.toByte()) + body
        }
        private fun sps(profile: Int = 66, level: Int = 30, widthMbs: Int = 1, pocType: Int = 2): ByteArray = BitWriter().apply {
            bits(profile, 8); bits(0, 8); bits(level, 8)
            ue(0); ue(0); ue(pocType)
            if (pocType == 0) ue(0)
            if (pocType == 1) { bit(false); se(0); se(0); ue(0) }
            ue(1); bit(false); ue(widthMbs - 1); ue(0)
            bit(true); bit(true); bit(false); bit(false)
        }.finish()
        private fun pps(constrained: Boolean = false): ByteArray = BitWriter().apply {
            ue(0); ue(0); bit(false); bit(false); ue(0); ue(0); ue(0)
            bit(false); bits(0, 2); se(0); se(0); se(0); bit(true); bit(constrained); bit(false)
        }.finish()
        private fun slice(type: Int = 2, idr: Boolean = true, firstMb: Int = 0,
            frameNum: Int = 0, idrPicId: Int = 0, ppsId: Int = 0, pocType: Int = 2, poc: Int = 0,
        ): ByteArray = BitWriter().apply {
            ue(firstMb); ue(type); ue(ppsId); bits(frameNum, 4); if (idr) ue(idrPicId)
            if (pocType == 0) bits(poc, 4)
            if (pocType == 1) se(poc)
            bits(0xaa, 8)
        }.finish()
        private fun nal(type: Int, payload: ByteArray,
            refIdc: Int = if (type in setOf(1, 5, 7, 8)) 3 else 0,
        ): ByteArray = byteArrayOf(0, 0, 1, (type or (refIdc shl 5)).toByte()) + escaped(payload)
        private fun escaped(payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var zeros = 0
            for (b in payload) {
                val v = b.toInt() and 255
                if (zeros >= 2 && v <= 3) { out.write(3); zeros = 0 }
                out.write(v)
                zeros = if (v == 0) zeros + 1 else 0
            }
            return out.toByteArray()
        }
        private fun au(sps: ByteArray = sps(), pps: ByteArray = pps(), sliceType: Int = 2, idr: Boolean = true, trailingAud: Boolean = true, end: Boolean = true): ByteArray =
            nal(9, byteArrayOf(0xf0.toByte())) + nal(7, sps) + nal(8, pps) + nal(if (idr) 5 else 1, slice(sliceType, idr)) +
                (if (trailingAud) nal(9, byteArrayOf(0xf0.toByte())) else byteArrayOf()) + (if (end) nal(10, byteArrayOf()) else byteArrayOf())
        private fun picture(slices: List<ByteArray>, sps: ByteArray? = sps(widthMbs = 2),
            pps: ByteArray? = pps(), trailingAud: Boolean = false, end: Boolean = false,
        ): ByteArray = nal(9, byteArrayOf(0xf0.toByte())) +
            (sps?.let { nal(7, it) } ?: byteArrayOf()) + (pps?.let { nal(8, it) } ?: byteArrayOf()) +
            slices.fold(byteArrayOf()) { all, slice -> all + slice } +
            (if (trailingAud) nal(9, byteArrayOf(0xf0.toByte())) else byteArrayOf()) +
            (if (end) nal(10, byteArrayOf()) else byteArrayOf())
        private fun adts(rate: Int = 3, channels: Int = 2, profile: Int = 1): ByteArray {
            val length = 9
            return byteArrayOf(0xff.toByte(), 0xf1.toByte(), ((profile shl 6) or (rate shl 2) or (channels ushr 2)).toByte(),
                ((channels shl 6) or (length ushr 11)).toByte(), (length ushr 3).toByte(), (((length and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte(), 0x21, 0x10)
        }
    }
}
