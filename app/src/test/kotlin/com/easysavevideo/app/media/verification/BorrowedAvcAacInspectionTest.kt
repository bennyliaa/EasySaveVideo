package com.easysavevideo.app.media.verification

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test

/**
 * Uses the production borrowed/read/native-cleanup seam and real RAF identity. Native actions are
 * controlled host callbacks. The caller below is a lifecycle harness, not a Direct storage adapter
 * or evidence of Android decode, real adopted leases, native release, or WebM acceptance.
 */
class BorrowedAvcAacInspectionTest {
    private val expected = AvcAacOutputExpectation(120_000, 16, 16, 25, AvcAacAudioProfile.Silent, 40_000, 0, 1024, 3)
    private val observed = AvcAacOutputObservations(120_000, 16, 16, 0, 0, 80_000, 3, 0, 3, 0, 0)
    private class Cancelled : RuntimeException()

    private class Caller(val file: File) {
        val input = RandomAccessFile(file, "rw")
        val borrowed = BorrowedAvcAacRead(input.fd, input.channel, input.length())
        val events = arrayListOf<String>()
        var closes = 0
        var retained = false
        var closeFailure = false
        var inspection: BorrowedAvcAacInspection? = null
        var completed: AvcAacOutputObservations? = null

        fun verify(expected: AvcAacOutputExpectation,
            checkpoint: () -> Unit = {}, revalidate: () -> Unit = {},
            body: (BorrowedAvcAacRead, AvcAacNativeResources, AvcAacOutputExpectation, () -> Unit) -> AvcAacOutputObservations) {
            val native = BorrowedAvcAacInspection(borrowed, body).also { inspection = it }
            var result: AvcAacOutputObservations? = null
            try {
                val hash = borrowed.sha256(checkpoint); events.add("initial-hash")
                val inspected = native.inspect(expected, checkpoint)
                if (!native.nativeReleaseConfirmed) { retained = true; events.add("retain") }
                result = inspected.observationsOrThrow()
                assertTrue(native.nativeReleaseConfirmed)
                assertSame(input.fd, borrowed.originalFd); assertSame(input.channel, borrowed.channel)
                assertTrue(input.fd.valid())
                borrowed.requireUnchanged(hash, checkpoint); events.add("final-hash")
                revalidate(); events.add("revalidate")
            } finally {
                if (!native.releaseNative()) retained = true
                closes++; events.add("close")
                if (closeFailure) throw IOException("test close failure")
                input.close()
            }
            check(!retained)
            completed = result
        }
    }
    private fun fixture(action: (Caller) -> Unit) {
        val file = File.createTempFile("esv-borrowed-raf", ".bin")
        file.writeBytes(ByteArray(128) { it.toByte() })
        val caller = Caller(file)
        try { action(caller) }
        finally { caller.input.close(); file.delete() } // Test-only disposal after ownership assertions.
    }
    private fun failure(code: AvcAacVerificationError, action: () -> Unit) {
        try { action(); fail("Expected neutral rejection") }
        catch (actual: AvcAacVerificationFailure) { assertEquals(code, actual.code) }
    }

    @Test fun originalRafDescriptorSurvivesNativeReleaseFinalHashAndRevalidateUntilSoleCallerClose() = fixture { caller ->
        caller.verify(expected, revalidate = {
            assertTrue(caller.input.fd.valid())
            assertSame(caller.input.fd, caller.borrowed.originalFd)
            assertSame(caller.input.channel, caller.borrowed.channel)
        }) { read, resources, profile, _ ->
            assertSame(caller.borrowed, read); assertSame(caller.input.fd, read.originalFd)
            assertSame(caller.input.channel, read.channel); assertEquals(expected, profile)
            val codec = Any()
            resources.add(codec, stop = { caller.events.add("stop") }, release = {
                assertTrue(read.originalFd.valid()); caller.events.add("native-release")
            })
            resources.started(codec)
            observed
        }
        assertEquals(listOf("initial-hash", "stop", "native-release", "final-hash", "revalidate", "close"), caller.events)
        assertEquals(observed, caller.completed); assertEquals(1, caller.closes)
        assertFalse(caller.input.fd.valid()); assertFalse(caller.retained)
    }

    @Test fun nativeReleaseFailureSkipsFinalHashAndCallerStillAttemptsOriginalCloseExactlyOnce() = fixture { caller ->
        failure(AvcAacVerificationError.RELEASE_UNCONFIRMED) {
            caller.verify(expected) { _, resources, _, _ ->
                val codec = Any()
                resources.add(codec, stop = { caller.events.add("stop") }, release = {
                    caller.events.add("native-release"); throw IOException("test release failure")
                })
                resources.started(codec); observed
            }
        }
        assertEquals(listOf("initial-hash", "stop", "native-release", "retain", "close"), caller.events)
        assertTrue(caller.retained); assertNull(caller.completed); assertEquals(1, caller.closes)
        assertFalse(caller.inspection!!.nativeReleaseConfirmed)
        assertFalse(caller.inspection!!.releaseNative())
        assertEquals(1, caller.events.count { it == "native-release" })
    }

    @Test fun bodyFailureAndReleaseFailureKeepOriginalFailureAndIndependentStickyUncertainty() = fixture { caller ->
        failure(AvcAacVerificationError.UNSUPPORTED_PROFILE) {
            caller.verify(expected) { _, resources, _, _ ->
                resources.add(Any(), release = { caller.events.add("native-release"); throw IOException("test release failure") })
                unsupportedAvcAacOutput()
            }
        }
        assertTrue(caller.retained); assertFalse(caller.inspection!!.nativeReleaseConfirmed)
        assertEquals(listOf("initial-hash", "native-release", "retain", "close"), caller.events)
        assertNull(caller.completed); assertEquals(1, caller.closes)
    }

    @Test fun mutationInReleaseCannotPassTheFinalHashOfTheSameRaf() = fixture { caller ->
        failure(AvcAacVerificationError.VERIFICATION_FAILED) {
            caller.verify(expected) { _, resources, _, _ ->
                resources.add(Any(), release = {
                    caller.events.add("native-release"); caller.input.seek(0); caller.input.write(99)
                })
                observed
            }
        }
        assertTrue(caller.inspection!!.nativeReleaseConfirmed)
        assertEquals(listOf("initial-hash", "native-release", "close"), caller.events)
        assertNull(caller.completed); assertEquals(1, caller.closes); assertFalse(caller.retained)
    }

    @Test fun revalidationFailureAfterFinalHashClosesAndWithholdsResult() = fixture { caller ->
        try {
            caller.verify(expected, revalidate = { throw IOException("test identity changed") }) { _, resources, _, _ ->
                resources.add(Any(), release = { caller.events.add("native-release") }); observed
            }
            fail("Expected revalidation failure")
        } catch (_: IOException) { }
        assertEquals(listOf("initial-hash", "native-release", "final-hash", "close"), caller.events)
        assertNull(caller.completed); assertEquals(1, caller.closes)
        assertTrue(caller.inspection!!.nativeReleaseConfirmed)
    }

    @Test fun closeFailureAfterNativeReleaseAndRevalidationNeverExposesResult() = fixture { caller ->
        caller.closeFailure = true
        try {
            caller.verify(expected) { _, resources, _, _ ->
                resources.add(Any(), release = { caller.events.add("native-release") }); observed
            }
            fail("Expected close failure")
        } catch (_: IOException) { }
        assertEquals(listOf("initial-hash", "native-release", "final-hash", "revalidate", "close"), caller.events)
        assertNull(caller.completed); assertEquals(1, caller.closes)
        assertTrue(caller.inspection!!.nativeReleaseConfirmed)
        assertTrue(caller.input.fd.valid())
    }

    @Test fun cancellationBeforeNativeBodyAcknowledgesNoResourcesAndNeverClosesBorrowedRead() = fixture { caller ->
        var ran = false
        val native = BorrowedAvcAacInspection(caller.borrowed) { _, _, _, _ -> ran = true; observed }
        val cancel = Cancelled()
        val result = native.inspect(expected) { throw cancel }
        assertSame(cancel, result.failure); assertNull(result.observations)
        assertTrue(native.nativeReleaseConfirmed); assertTrue(native.releaseNative())
        assertFalse(ran); assertEquals(0, caller.closes); assertTrue(caller.input.fd.valid())
    }

    @Test fun runningCancellationReleasesRegisteredResourcesWithoutAnyFinalHash() = fixture { caller ->
        val cancel = Cancelled()
        try {
            caller.verify(expected) { _, resources, _, _ ->
                resources.add(Any(), release = { caller.events.add("native-release") })
                throw cancel
            }
            fail("Expected cancellation")
        } catch (actual: Cancelled) { assertSame(cancel, actual) }
        assertEquals(listOf("initial-hash", "native-release", "close"), caller.events)
        assertTrue(caller.inspection!!.nativeReleaseConfirmed)
        assertNull(caller.completed); assertEquals(1, caller.closes)
    }

    @Test fun cleanupAttemptsEveryNativeResourceInReverseOrderAndNeverRetriesAnUncertainOne() {
        val resources = AvcAacNativeResources()
        val events = arrayListOf<String>()
        val first = Any(); val second = Any(); val third = Any()
        resources.add(first, release = { events.add("first") })
        resources.add(second, stop = { events.add("stop-second") }, release = {
            events.add("second"); throw IOException("test release failure")
        })
        resources.started(second)
        resources.add(third, stop = { fail("An unstarted codec is never stopped") }, release = { events.add("third") })
        assertFalse(resources.releaseAll()); assertFalse(resources.releaseAll()); resources.release(second)
        assertEquals(listOf("third", "stop-second", "second", "first"), events)
        assertTrue(resources.cleanupFinished); assertFalse(resources.nativeReleaseConfirmed)
    }

    @Test fun finallyOnlyStopFailureRejectsNormalBodyResultButKeepsActualReleaseConfirmed() = fixture { caller ->
        val events = arrayListOf<String>()
        val native = BorrowedAvcAacInspection(caller.borrowed) { _, resources, _, _ ->
            val first = Any(); val codec = Any()
            resources.add(first, release = { events.add("first-release") })
            resources.add(codec, stop = { events.add("stop"); throw IOException("test stop failure") },
                release = { events.add("codec-release") })
            resources.started(codec)
            observed // Shared finally, rather than the body, is responsible for these resources.
        }
        val result = native.inspect(expected) {}
        assertTrue(native.nativeReleaseConfirmed); assertTrue(result.nativeReleaseConfirmed)
        failure(AvcAacVerificationError.VERIFICATION_FAILED) { result.observationsOrThrow() }
        assertTrue(native.releaseNative())
        assertEquals(listOf("stop", "codec-release", "first-release"), events)
        assertTrue(caller.input.fd.valid()); assertEquals(0, caller.closes)
    }

    @Test fun earlyNativeReleaseIsStillOneAttemptWhenTheOuterFinallyRuns() {
        val resources = AvcAacNativeResources()
        var releases = 0; val extractor = Any()
        resources.add(extractor, release = { releases++ })
        resources.release(extractor); resources.release(extractor)
        assertFalse(resources.nativeReleaseConfirmed)
        assertTrue(resources.releaseAll()); assertTrue(resources.releaseAll())
        assertEquals(1, releases); assertTrue(resources.nativeReleaseConfirmed)
    }

    @Test fun extentChangeIsDetectedAndPositionalHashDoesNotMoveOriginalRafPosition() = fixture { caller ->
        caller.input.seek(17)
        val hash = caller.borrowed.sha256 {}
        assertEquals(17L, caller.input.filePointer)
        caller.borrowed.requireUnchanged(hash) {}
        assertEquals(17L, caller.input.filePointer)
        caller.input.setLength(127)
        failure(AvcAacVerificationError.VERIFICATION_FAILED) { caller.borrowed.requireUnchanged(hash) {} }
        assertTrue(caller.input.fd.valid()); assertEquals(0, caller.closes)
    }
}
