package com.easysavevideo.app.media.verification

import com.easysavevideo.app.save.mapConvertedVerificationFailure
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** The exact helper used by real native-body finally paths, with synthetic cleanup actions. */
class AvcAacPrimaryCleanupTest {
    @Test fun allCleanupRunsWithoutReplacingOriginalBodyFailureOrItsIdentity() {
        val selected = SaveFailure(SaveError.CANCELLED)
        val first = IOException("SYNTHETIC_CODEC_RELEASE")
        val second = IllegalStateException("SYNTHETIC_EXTRACTOR_RELEASE")
        val events = arrayListOf<String>()
        assertSame(selected, assertThrows(SaveFailure::class.java) {
            withAvcAacCleanup(listOf({ events += "codec"; throw first }, { events += "extractor"; throw second })) {
                events += "body"; throw selected
            }
        })
        assertEquals(listOf("body", "codec", "extractor"), events)
        assertArrayEquals(arrayOf(first, second), selected.suppressed)
    }

    @Test fun successfulBodyCannotSwallowCleanupFailureAndFirstCleanupFailureWins() {
        val first = IOException("SYNTHETIC_CODEC_RELEASE")
        val second = IllegalStateException("SYNTHETIC_EXTRACTOR_RELEASE")
        val events = arrayListOf<String>()
        assertSame(first, assertThrows(IOException::class.java) {
            withAvcAacCleanup(listOf({ events += "codec"; throw first }, { events += "extractor"; throw second })) { 7 }
        })
        assertEquals(listOf("codec", "extractor"), events)
        assertArrayEquals(arrayOf(second), first.suppressed)
        assertEquals(7, withAvcAacCleanup(listOf({ events += "ok" })) { 7 })
    }

    @Test fun outputBufferFailureDoesNotReplacePrimaryOrSkipCodecAndExtractorRelease() {
        val selected = SaveFailure(SaveError.DEADLINE_EXCEEDED)
        val events = arrayListOf<String>()
        val resources = AvcAacNativeResources()
        val codec = Any(); val extractor = Any()
        resources.add(codec, release = { events += "codec"; throw IOException("SYNTHETIC_NATIVE_RELEASE") })
        resources.add(extractor, release = { events += "extractor" })
        assertSame(selected, assertThrows(SaveFailure::class.java) {
            withAvcAacCleanup(listOf({ resources.release(codec) }, { resources.release(extractor) })) {
                withAvcAacCleanup(listOf({ events += "buffer"; throw IllegalStateException("SYNTHETIC_BUFFER_RETURN") })) {
                    throw selected
                }
            }
        })
        assertEquals(listOf("buffer", "codec", "extractor"), events)
        assertFalse(resources.releaseAll()); assertFalse(resources.releaseAll())
        assertEquals(1, events.count { it == "codec" }); assertEquals(2, selected.suppressed.size)
    }

    @Test fun originalIoRemainsPrimaryEvenWhenCleanupTriggersLaterStop() {
        val selected = IOException("SYNTHETIC_BODY_IO")
        var cancelled = false
        val actual = assertThrows(IOException::class.java) {
            withAvcAacCleanup(listOf({ cancelled = true; throw SaveFailure(SaveError.CANCELLED) })) { throw selected }
        }
        assertTrue(cancelled); assertSame(selected, actual)
        assertEquals(SaveError.STORAGE_FAILURE, mapConvertedVerificationFailure(actual).code)
    }

    @Test fun sharedEditorBodyNowPreservesItsAlreadySelectedPolicyFailure() {
        val selected = AvcAacVerificationFailure(AvcAacVerificationError.UNSUPPORTED_PROFILE)
        assertSame(selected, assertThrows(AvcAacVerificationFailure::class.java) {
            withAvcAacCleanup(listOf({ throw AvcAacVerificationFailure(AvcAacVerificationError.RELEASE_UNCONFIRMED) })) {
                throw selected
            }
        })
        assertEquals(AvcAacVerificationError.RELEASE_UNCONFIRMED,
            (selected.suppressed.single() as AvcAacVerificationFailure).code)
    }

    @Test fun allFourRealNativeCleanupSitesUseTheBehaviorTestedHelper() {
        val source = java.io.File("src/main/kotlin/com/easysavevideo/app/media/verification/AndroidAvcAacOutputInspection.kt").readText()
        assertEquals(4, Regex("withAvcAacCleanup\\(").findAll(source).count())
        assertTrue(source.contains("listOf({ codec?.let(owner::release) }, { owner.release(extractor) })"))
        assertTrue(source.contains("listOf({ activeCodec.releaseOutputBuffer(index, false) })"))
        assertFalse(source.contains("finally { owner.release(extractor) }"))
    }
}
