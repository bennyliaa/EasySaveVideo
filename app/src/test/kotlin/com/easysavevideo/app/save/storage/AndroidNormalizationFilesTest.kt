package com.easysavevideo.app.save.storage

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Host fake syscalls exercise production file ownership. Actual Android Os behavior is not tested. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class AndroidNormalizationFilesTest {
    @Test fun syntheticErrnoClassificationMapsSpaceOnlyAndLeavesInterruptedIoBounded() {
        // Deliberately injected sentinel codes; this is not verification of Android errno constants.
        for (errno in listOf(701, 702)) {
            val failure = normalizationErrnoFailure(errno, noSpace = 701, quota = 702)
            assertTrue(failure is SaveFailure); assertEquals(SaveError.STORAGE_FULL, (failure as SaveFailure).code)
        }
        for (other in listOf(703, 704, -1)) {
            assertTrue(normalizationErrnoFailure(other, noSpace = 701, quota = 702) is IOException)
        }
    }

    @Test fun hashingIsBoundedAndEveryTemporaryDescriptorHasPositiveRelease() {
        val f = NormalizationDescriptorFixture(150_123); f.io.maxRead = 7001
        val observed = f.files.fingerprint(f.knownKey, NormalizationRole.PRIMARY, sync = true)
        assertEquals(f.raw, observed.fingerprint)
        assertTrue(f.io.calls.filter { it.operation == "pread" }.all { it.count in 1..65536 })
        assertTrue(f.io.calls.any { it.operation == "fsync" }); assertFalse(f.files.hasAnyLeases())
        assertTrue(f.io.handles.all { !it.active })
        assertTrue(f.io.handles.all { fd -> f.io.calls.count { it.operation == "close" && it.descriptor === fd } == 1 })
    }

    @Test fun preparationRequiresTheFullReserveAdjustedTwoGiBOutputAllowance() {
        val f = NormalizationDescriptorFixture(); f.available = NORMALIZATION_MAX_BYTES - 1
        assertThrows(Exception::class.java) { f.prepare() }
        assertNull(f.ledger.row); assertFalse(f.io.exists(f.workspace)); assertFalse(f.attempts.hasAnyLeases())
        assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE })
    }

    @Test fun outputIsCreatedOnlyExclusivelyAndEveryReopenUsesExistingFileModes() {
        val f = NormalizationDescriptorFixture(); val attempt = f.prepare(); val output = f.outputFile(attempt.key)
        val opens = f.io.calls.filter { it.operation == "open" && it.path == output }
        assertEquals(1, opens.count { it.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE })
        assertTrue(opens.all { it.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE || it.mode == NormalizationDescriptorIo.Open.READ })
        attempt.openSource(); val sink = attempt.openEmptyOutput()
        assertEquals(NormalizationDescriptorIo.Open.WRITE_EXISTING, f.io.calls.last { it.operation == "open" && it.path == output }.mode)
        sink.write(ByteBuffer.wrap(f.outputBytes)); attempt.close()
        assertFalse(f.io.calls.any { it.operation == "truncate" })
    }

    @Test fun missingOutputSyncAndSinkOpenNeverCreateIt() {
        NormalizationDescriptorFixture().let { f ->
            val a = f.prepare(); a.close(); f.io.remove(f.outputFile(a.key)); f.io.calls.clear()
            assertThrows(Exception::class.java) { f.files.syncOutput(a.key) }
            assertFalse(f.io.exists(f.outputFile(a.key))); assertFalse(f.io.calls.any { it.operation == "open" })
        }
        NormalizationDescriptorFixture().let { f ->
            val a = f.prepare(); a.openSource(); f.io.remove(f.outputFile(a.key)); f.io.calls.clear()
            assertThrows(Exception::class.java) { a.openEmptyOutput() }
            assertFalse(f.io.exists(f.outputFile(a.key))); assertFalse(f.io.calls.any { it.operation == "open" }); a.close()
        }
    }

    @Test fun existingOutputCollisionIsPreservedRatherThanTruncated() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.close()
        f.io.mutate(f.outputFile(a.key), f.outputBytes); f.io.calls.clear()
        assertThrows(Exception::class.java) { f.files.createEmptyOutput(a.key) }
        assertArrayEquals(f.outputBytes, f.io.bytes(f.outputFile(a.key)))
        assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE || it.operation == "truncate" })
    }

    @Test fun sourceRejectsNonregularHardlinkedAndCrossDeviceLeavesBeforeOpen() {
        val changes: List<(NormalizationDescriptorFixture) -> Unit> = listOf(
            { it.io.other(it.primary) },
            { it.io.remove(it.primary); it.io.directory(it.primary) },
            { it.io.node(it.primary).links = 2 },
            { it.io.node(it.primary).device = 2 },
        )
        for (change in changes) {
            val f = NormalizationDescriptorFixture(); change(f)
            assertThrows(Exception::class.java) { f.prepare() }
            assertFalse(f.io.calls.any { it.operation == "open" }); assertFalse(f.attempts.hasAnyLeases())
        }
    }

    @Test fun sourceSameSizeInodeReplacementIsRejectedEvenWithIdenticalBytes() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); f.io.file(f.primary, f.rawBytes); f.io.calls.clear()
        assertThrows(Exception::class.java) { a.openSource() }
        assertFalse(f.io.calls.any { it.operation == "open" }); a.close(); assertFalse(f.attempts.hasAnyLeases())
    }

    @Test fun capturedRootStagingAndGenerationReplacementOrSymlinkKindsAreRejected() {
        for (which in 0..3) {
            val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.openSource()
            when (which) {
                0 -> f.io.replaceDirectory(f.root)
                1 -> f.io.replaceDirectory(f.staging)
                2 -> f.io.replaceDirectory(f.generation(a.key))
                else -> f.io.other(f.workspace)
            }
            f.io.calls.clear(); assertThrows(Exception::class.java) { a.openEmptyOutput() }
            assertFalse(f.io.calls.any { it.operation == "open" }); a.close(); assertFalse(f.attempts.hasAnyLeases())
        }
    }

    @Test fun sameInodeRoleAliasIsRejectedEvenWhenFixtureReportsSingleLink() {
        val f = NormalizationDescriptorFixture(); val key = f.seedWriting()
        f.io.alias(f.primary, f.outputFile(key), reportedLinks = 1)
        assertThrows(Exception::class.java) { f.files.snapshot(key) }
        assertFalse(f.io.calls.any { it.operation == "pread" }); assertFalse(f.files.hasAnyLeases())
    }

    @Test fun unexpectedWorkspaceGenerationOrLeafIsNeverEnumeratedIntoOwnership() {
        for (extraGeneration in listOf(true, false)) {
            val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.close()
            if (extraGeneration) f.io.directory(File(f.workspace, "33333333-3333-4333-8333-333333333333"))
            else f.io.file(File(f.generation(a.key), "unexpected.bin"), byteArrayOf(1))
            f.io.calls.clear(); assertThrows(Exception::class.java) { f.files.snapshot(a.key) }
            assertFalse(f.io.calls.any { it.operation == "unlink" || it.operation == "rmdir" || it.operation == "open" })
        }
    }

    @Test fun pathToDescriptorRaceClosesTheUnreturnedDescriptor() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare()
        f.io.after = { if (it.operation == "open" && it.path == f.primary && it.mode == NormalizationDescriptorIo.Open.READ) f.io.file(f.primary, f.rawBytes) }
        assertThrows(Exception::class.java) { a.openSource() }
        val fd = f.io.handles.last()
        assertFalse(fd.active); assertEquals(1, f.io.calls.count { it.operation == "close" && it.descriptor === fd })
        a.close(); assertFalse(f.attempts.hasAnyLeases()); assertFalse(f.attempts.releaseUnconfirmed())
    }

    @Test fun failedOpenAndUnconfirmedCloseRetainTheUnreturnedDescriptorAndSession() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare()
        f.io.after = { if (it.operation == "open" && it.path == f.primary && it.mode == NormalizationDescriptorIo.Open.READ) f.io.file(f.primary, f.rawBytes) }
        f.io.closeMode = { MemoryNormalizationDescriptorIo.CloseMode.INVALIDATES_THEN_THROWS }
        val failure = assertThrows(SaveFailure::class.java) { a.openSource() }
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, failure.code)
        val fd = f.io.handles.last(); try { a.close() } catch (_: Exception) { }
        assertFalse(fd.active); assertTrue(f.attempts.hasAnyLeases()); assertTrue(f.attempts.hasActiveSession(f.owner.artifactId))
        assertTrue(f.attempts.releaseUnconfirmed()); assertEquals(1, f.io.calls.count { it.operation == "close" && it.descriptor === fd })
    }

    @Test fun sourceMutationAfterOpenInvalidatesFurtherReadsBeforeReturningStaleBytes() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); val source = a.openSource()
        f.io.mutate(f.primary, f.rawBytes.copyOf().also { it[0] = 99 }); f.io.calls.clear()
        assertThrows(Exception::class.java) { source.readAt(0, ByteArray(1), 0, 1) }
        assertFalse(f.io.calls.any { it.operation == "pread" }); a.close(); assertTrue(source.releaseConfirmed)
    }

    @Test fun outputReplacementAndSameInodeMutationAfterSinkCloseCannotBecomeFinalizedReader() {
        for (replace in listOf(true, false)) {
            val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.openSource(); val sink = a.openEmptyOutput()
            sink.write(ByteBuffer.wrap(f.outputBytes)); sink.close()
            if (replace) f.io.file(f.outputFile(a.key), f.outputBytes) else f.io.mutate(f.outputFile(a.key), f.outputBytes)
            f.io.calls.clear(); assertThrows(Exception::class.java) { a.openFinalizedOutput() }
            assertFalse(f.io.calls.any { it.operation == "open" }); a.close(); assertFalse(f.attempts.hasAnyLeases())
        }
    }

    @Test fun temporaryDescriptorCloseExceptionIsUnconfirmedEvenAfterInvalidation() {
        val f = NormalizationDescriptorFixture(); f.io.closeMode = { MemoryNormalizationDescriptorIo.CloseMode.INVALIDATES_THEN_THROWS }
        val failure = assertThrows(SaveFailure::class.java) { f.files.fingerprint(f.knownKey, NormalizationRole.PRIMARY) }
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, failure.code)
        assertTrue(f.files.hasAnyLeases()); assertTrue(f.files.releaseUnconfirmed()); assertFalse(f.io.handles.single().active)
        assertThrows(Exception::class.java) { f.files.fingerprint(f.knownKey, NormalizationRole.PRIMARY) }
        assertEquals(1, f.io.calls.count { it.operation == "close" })
    }

    @Test fun temporaryHashReadAndFsyncFailuresWithSuccessfulCloseDoNotPoisonRelease() {
        for (operation in listOf("pread", "fsync")) {
            val f = NormalizationDescriptorFixture()
            f.io.before = { if (it.operation == operation) throw IOException("HOST_IO_FAILURE") }
            assertThrows(Exception::class.java) { f.files.fingerprint(f.knownKey, NormalizationRole.PRIMARY, sync = true) }
            assertFalse(f.files.hasAnyLeases()); assertFalse(f.files.releaseUnconfirmed()); assertTrue(f.io.handles.all { !it.active })
        }
    }

    @Test fun directoryListingReadFailureWithPositiveCloseIsOrdinaryIoFailure() {
        val f = NormalizationDescriptorFixture(); val key = f.seedWriting(); f.io.listingReadFailure = true
        assertThrows(Exception::class.java) { f.files.snapshot(key) }
        assertEquals(1, f.io.calls.count { it.operation == "childrenClose" })
        assertFalse(f.files.hasAnyLeases()); assertFalse(f.files.releaseUnconfirmed())
    }

    @Test fun directoryListingCloseExceptionRetainsLeaseAndNeverRetriesItsHandle() {
        for (readAlsoFails in listOf(false, true)) {
            val f = NormalizationDescriptorFixture(); val key = f.seedWriting()
            f.io.listingReadFailure = readAlsoFails; f.io.listingCloseFailure = true
            assertThrows(Exception::class.java) { f.files.snapshot(key) }
            assertTrue(f.files.hasAnyLeases()); assertTrue(f.files.releaseUnconfirmed())
            assertEquals(1, f.io.calls.count { it.operation == "childrenClose" })
            f.io.listingCloseFailure = false; f.io.listingReadFailure = false
            assertThrows(Exception::class.java) { f.files.snapshot(key) }
            assertEquals(1, f.io.calls.count { it.operation == "childrenClose" })
        }
    }

    @Test fun exactDeleteRehashesAndNeverDeletesChangedContentOrUnknownFiles() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.close()
        val output = f.outputFile(a.key); f.io.mutate(output, f.outputBytes)
        assertThrows(Exception::class.java) { f.files.deleteExact(a.key, NormalizationRole.OUTPUT, normalizationTestFingerprint(byteArrayOf())) }
        assertArrayEquals(f.outputBytes, f.io.bytes(output))
        f.files.deleteExact(a.key, NormalizationRole.OUTPUT, f.output)
        assertFalse(f.io.exists(output)); assertArrayEquals(f.rawBytes, f.io.bytes(f.primary)); assertFalse(f.files.releaseUnconfirmed())
    }

    @Test fun noReplaceMovePreservesSourceWhenDestinationExists() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.close()
        f.io.file(f.rawFile(a.key), byteArrayOf(42))
        assertThrows(Exception::class.java) { f.files.moveNoReplace(a.key, NormalizationRole.PRIMARY, NormalizationRole.RAW, f.raw) }
        assertArrayEquals(f.rawBytes, f.io.bytes(f.primary)); assertArrayEquals(byteArrayOf(42), f.io.bytes(f.rawFile(a.key)))
    }
}
