package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.Mp4ContainerProbe
import com.easysavevideo.app.save.normalization.OriginalMovFixtures
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

class DirectRawSelectionPolicyTest {
    private fun select(bytes: ByteArray, expectedAudio: Boolean = false) =
        DirectRawFormatSelector.select(OriginalMovFixtures.input(bytes), expectedAudio)
    private fun box(type: String, body: ByteArray) = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { it.writeInt(8 + body.size); it.writeBytes(type); it.write(body) }
    }.toByteArray()
    private fun mp4(brand: String = "mp42", compatibility: String = "isom") =
        box("ftyp", brand.toByteArray(Charsets.ISO_8859_1) + ByteArray(4) + compatibility.toByteArray(Charsets.ISO_8859_1)) +
            box("mdat", byteArrayOf(1, 2, 3, 4)) + box("moov", byteArrayOf(5, 6, 7, 8))
    private fun legacyPreflight(bytes: ByteArray): Boolean {
        val file = File.createTempFile("owned-selector-fixture-", ".bin")
        return try { file.writeBytes(bytes); RandomAccessFile(file, "r").use { Mp4ContainerProbe.isCompleteMp4(it) } }
        finally { file.delete() }
    }
    private fun rejected(bytes: ByteArray, expectedAudio: Boolean = false): SaveError =
        assertThrows(SaveFailure::class.java) { select(bytes, expectedAudio) }.code

    @Test fun rawQuickTimeRequiresTheWholeStrictProfileBeforeSelectingMov() {
        assertEquals(DirectRawKind.STRICT_MOV, select(OriginalMovFixtures.build()))
        assertEquals(SaveError.UNSUPPORTED_MEDIA, rejected(OriginalMovFixtures.build(), expectedAudio = true))
        assertEquals(SaveError.UNSUPPORTED_MEDIA,
            rejected(OriginalMovFixtures.build(OriginalMovFixtures.Options(audio = true, extraAudio = true))))
        assertEquals(SaveError.UNSUPPORTED_MEDIA,
            rejected(OriginalMovFixtures.build(OriginalMovFixtures.Options(extraVideo = true))))
        assertEquals(SaveError.UNSUPPORTED_MEDIA,
            rejected(OriginalMovFixtures.build(OriginalMovFixtures.Options(sdr = false))))
    }
    @Test fun validAudioIsPreservedWhetherPageExpectsItOrNot() {
        // Opaque synthetic packets test raw structure/selection, not native AAC decoding.
        for (rate in listOf(32_000, 44_100, 48_000)) for (channels in 1..2) {
            val bytes = OriginalMovFixtures.build(OriginalMovFixtures.Options(audio = true,
                audioSampleRate = rate, audioChannelCount = channels))
            for (expected in listOf(false, true)) assertEquals(DirectRawKind.STRICT_MOV, select(bytes, expected))
        }
    }

    @Test fun ordinaryMp4DoesNotInheritSilentMovOrCanonicalOutputRestrictions() {
        val audio = OriginalMovFixtures.build(OriginalMovFixtures.Options(quickTime = false, audio = true))
        assertTrue(legacyPreflight(audio))
        assertEquals(DirectRawKind.ALREADY_MP4, select(audio, expectedAudio = true))
        val extraVideo = OriginalMovFixtures.build(OriginalMovFixtures.Options(quickTime = false, extraVideo = true))
        assertEquals(DirectRawKind.ALREADY_MP4, select(extraVideo))
        for (brand in listOf("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1")) {
            val input = mp4(brand)
            assertTrue(legacyPreflight(input)); assertEquals(DirectRawKind.ALREADY_MP4, select(input))
        }
    }
    @Test fun ordinaryStructuralPreflightMatchesOriginalAcceptance() {
        val cases = listOf(mp4(), mp4() + box("free", byteArrayOf()),
            mp4() + box("xxxx", byteArrayOf(1)), mp4("xxxx"), mp4().dropLast(1).toByteArray(),
            box("ftyp", "mp42".toByteArray() + ByteArray(4)) + box("mdat", ByteArray(8)),
            byteArrayOf(-1, -1, -1, -1) + "ftyp".toByteArray() + ByteArray(40),
            byteArrayOf(0, 0, 0, 1) + "ftyp".toByteArray() + ByteArray(40),
            "<html>no media</html>".toByteArray())
        for (input in cases) {
            val selected = runCatching { select(input) == DirectRawKind.ALREADY_MP4 }.getOrDefault(false)
            assertEquals(legacyPreflight(input), selected)
        }
    }
    @Test fun mimeIsNotAnInputAndMixedOrUnknownContainersCannotFallBackToMp4() {
        assertEquals(SaveError.UNSUPPORTED_MEDIA, rejected(mp4("mp42", "qt  ")))
        assertEquals(SaveError.UNSUPPORTED_MEDIA, rejected(mp4("qt  ", "isom")))
        assertEquals(SaveError.UNSUPPORTED_MEDIA, rejected(mp4("webm")))
        val raw = OriginalMovFixtures.build()
        assertEquals(SaveError.PROTECTED_MEDIA, rejected(raw + box("pssh", ByteArray(8))))
        assertEquals(SaveError.UNSUPPORTED_MEDIA, rejected(raw + box("xxxx", ByteArray(8))))
    }
    @Test fun interruptedReadStopsSelectionWithoutCreatingAnOutputCapability() {
        var calls = 0
        assertEquals(SaveError.CANCELLED, assertThrows(SaveFailure::class.java) {
            DirectRawFormatSelector.select(OriginalMovFixtures.input(OriginalMovFixtures.build()), false) {
                if (++calls == 2) throw SaveFailure(SaveError.CANCELLED)
            }
        }.code)
    }
    @Test fun completedRawLocalFailuresCannotUseNetworkRetryClassification() {
        assertEquals(SaveError.RESOURCE_CHANGED, completeRawFailure(SaveFailure(SaveError.INCOMPLETE_TRANSFER)).code)
        for (error in listOf(SaveError.CANCELLED, SaveError.DEADLINE_EXCEEDED, SaveError.PROTECTED_MEDIA,
            SaveError.UNSUPPORTED_MEDIA, SaveError.RESOURCE_RELEASE_UNCONFIRMED, SaveError.STORAGE_FAILURE)) {
            val failure = SaveFailure(error)
            assertSame(failure, completeRawFailure(failure))
        }
    }
    @Test fun resumeTupleComparisonKeepsEverySourceAndCompletedByteField() {
        val prior = NormalizationDescriptorFixture().record!!.copy(phase = SavePhase.EXECUTING)
        val resumed = prior.copy(task = prior.task.copy(revision = 8, state = TaskState.RUNNING, progressPercent = 80),
            operationId = "resumed-operation", deadlineEpochMillis = 200_000, retryBudget = 1, attempt = 1)
        assertTrue(DirectRawRecoveryPolicy.sameRawTuple(prior, resumed))
        assertFalse(DirectRawRecoveryPolicy.sameRawTuple(prior, resumed.copy(bytesTransferred = resumed.bytesTransferred + 1)))
        assertFalse(DirectRawRecoveryPolicy.sameRawTuple(prior, resumed.copy(transfer = resumed.transfer!!.copy(responseMimeType = "video/mp4"))))
        assertFalse(DirectRawRecoveryPolicy.sameRawTuple(prior, resumed.copy(request = resumed.request.copy(idempotencyKey = "changed"))))
        assertFalse(DirectRawRecoveryPolicy.sameRawTuple(prior, resumed.copy(schemaVersion = resumed.schemaVersion + 1)))
        assertFalse(DirectRawRecoveryPolicy.sameRawTuple(prior, resumed.copy(transfer = resumed.transfer!!.copy(
            bytesOnDisk = resumed.transfer!!.bytesOnDisk - 1, expectedTotalBytes = resumed.transfer!!.bytesOnDisk))))
    }
    @Test fun oldLocalPhasesAndActualLegacyEntryNeverAuthorizeFreshMov() {
        val origin = NormalizationDescriptorFixture().record!!.copy(phase = SavePhase.EXECUTING)
        val current = origin.copy(task = origin.task.copy(revision = 2), operationId = "new-operation")
        assertTrue(DirectRawRecoveryPolicy.permitsFreshMov(origin, origin, current, false))
        assertFalse(DirectRawRecoveryPolicy.permitsFreshMov(origin, origin, current, true))
        for (phase in listOf(SavePhase.PREPARING, SavePhase.VERIFYING, SavePhase.COMMITTING, SavePhase.ADOPTING)) {
            assertFalse(DirectRawRecoveryPolicy.permitsFreshMov(origin, origin.copy(phase = phase), current, false))
            assertFalse(DirectRawRecoveryPolicy.permitsFreshMov(origin.copy(phase = phase), null, current, false))
            assertFalse(DirectRawRecoveryPolicy.permitsFreshMov(origin, null, current.copy(phase = phase), false))
        }
        assertTrue(DirectRawRecoveryPolicy.permitsFreshMov(origin.copy(phase = SavePhase.NORMALIZING),
            origin.copy(phase = SavePhase.NORMALIZING), current.copy(phase = SavePhase.NORMALIZING), false))
    }
    @Test fun completedConversionNeverLooksLikeFreshRawWithMovProofAbsent() {
        val converted = conversionTestRecord(verified = false, terminal = false).copy(phase = SavePhase.EXECUTING)
        assertNull(converted.directNormalization)
        assertFalse(DirectRawRecoveryPolicy.complete(converted))
        assertFalse(DirectRawRecoveryPolicy.sameRawTuple(converted, converted))
        assertFalse(DirectRawRecoveryPolicy.permitsFreshMov(converted, null, converted, false))
        val raw = converted.copy(directConversion = null)
        assertTrue(DirectRawRecoveryPolicy.complete(raw))
        assertFalse(DirectRawRecoveryPolicy.sameRawTuple(raw, converted))
    }

}
