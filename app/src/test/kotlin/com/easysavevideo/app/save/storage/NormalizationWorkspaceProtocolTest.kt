package com.easysavevideo.app.save.storage

import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Owned temporary fixtures only. No Android SQLite/device/muxer or media-decode acceptance. */
class NormalizationWorkspaceProtocolTest {
    private val rawBytes = ByteArray(8192) { (it % 251).toByte() }
    private val outputBytes = ByteArray(4096) { ((it + 11) % 251).toByte() }
    private fun fingerprint(bytes: ByteArray) = NormalizationFingerprint(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private val raw get() = fingerprint(rawBytes)
    private val output get() = fingerprint(outputBytes)
    private val owner = NormalizationOwner("task", "11111111-1111-4111-8111-111111111111", "commit")
    private val key = NormalizationKey(owner, "22222222-2222-4222-8222-222222222222")
    private val sourceDigest = sourceTransferFingerprint(TransferResult(owner.artifactId, rawBytes.size.toLong(), "video/quicktime", rawBytes.size.toLong()))
    private fun proof(): DirectNormalizationProof {
        val hash = "a".repeat(64)
        val first = DirectSampleIdentity(0, 0, 8, hash, 1)
        val last = DirectSampleIdentity(1, 33_333, 8, hash, 0)
        val video = DirectTrackConservation(DirectSampleKind.AVC_BASELINE, hash, 2, 16, hash, hash, hash, 0, last, DirectTrackEndpoint(66_666, 66_667))
        return DirectNormalizationProof(key.normalizationId, owner.taskId, hash, owner.artifactId, owner.commitId,
            raw.size, raw.sha256, sourceDigest, output.size, output.sha256, DirectRemuxConservation(16, 16, 30, video, null, null, null, first))
    }
    private class Crash : IOException("TEST_CRASH")
    private class Faults {
        var failAt: String? = null; val events = mutableListOf<String>()
        fun point(event: String) {
            val qualified = "$event#${events.count { it.substringBeforeLast('#') == event } + 1}"
            events += qualified
            if (failAt == event || failAt == qualified) { failAt = null; throw Crash() }
        }
        fun <T> around(event: String, body: () -> T): T { point("before:$event"); val result = body(); point("after:$event"); return result }
    }
    private class Ledger(private val fault: Faults) : NormalizationLedger {
        var row: NormalizationWorkspace? = null; var unreadable = false
        override fun lookup(owner: NormalizationOwner): NormalizationLookup = if (unreadable) NormalizationLookup.Unreadable else row?.let { if (it.key.owner == owner) NormalizationLookup.Present(it) else NormalizationLookup.Unreadable } ?: NormalizationLookup.Absent
        override fun insert(row: NormalizationWorkspace): Boolean = fault.around("insert") { if (this.row != null) false else { this.row = row; true } }
        override fun compareAndSet(expected: NormalizationWorkspace, next: NormalizationWorkspace): Boolean = fault.around("write:${next.lifecycle}:${next.disposition}") {
            expected.requireSuccessor(next); if (row != expected) false else { row = next; true }
        }
        override fun pending(after: Long, limit: Int): NormalizationPendingPage {
            require(after >= 0 && limit in 1..100)
            return if (unreadable) NormalizationPendingPage.Unreadable else NormalizationPendingPage.Readable(
                row?.takeIf { it.lifecycle != NormalizationLifecycle.CLEANED && after == 0L }?.let { listOf(NormalizationPendingEntry(1, it)) } ?: emptyList(), null)
        }
    }
    private inner class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("esv-normalization-").toFile()
        val staging = File(root, "staging").apply { mkdir() }
        val primary = File(staging, owner.artifactId + ".part").apply { writeBytes(rawBytes) }
        val workspace = File(staging, owner.artifactId + ".normalization")
        val generation = File(workspace, key.normalizationId)
        val rawFile = File(generation, "raw.bin")
        val outputFile = File(generation, "output.mp4")
        val faults = Faults(); val ledger = Ledger(faults)
        var auth = NormalizationAuthority(owner, NormalizationTaskState.ACTIVE, NormalizationParentState.STAGING, sourceDigest, null)
        var available = NORMALIZATION_MAX_BYTES; var handlesReleased = true; var authorityUnreadable = false
        val durable = object : DurableFileOps {
            override fun syncDirectory(directory: File) { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
            override fun promoteNoReplace(source: File, destination: File) {
                if (Files.exists(destination.toPath(), NOFOLLOW_LINKS)) throw NormalizationRecovery()
                if (Files.getFileStore(source.toPath()) != Files.getFileStore(destination.parentFile!!.toPath())) throw NormalizationRecovery()
                Files.move(source.toPath(), destination.toPath())
            }
            override fun replaceOwnedStaging(source: File, destination: File) { throw AssertionError("Replacement forbidden") }
        }
        val actual = OwnedNormalizationFiles(root, durable, { if (!handlesReleased) throw NormalizationRecovery() }, { available })
        val files = object : NormalizationFileOps by actual {
            override fun createDirectory(key: NormalizationKey, directory: NormalizationDirectory) = faults.around("mkdir:$directory") { actual.createDirectory(key, directory) }
            override fun createEmptyOutput(key: NormalizationKey) = faults.around("create:OUTPUT") { actual.createEmptyOutput(key) }
            override fun syncOutput(key: NormalizationKey) = faults.around("fsync:OUTPUT") { actual.syncOutput(key) }
            override fun syncDirectory(key: NormalizationKey, directory: NormalizationDirectory) = faults.around("fsync:$directory") { actual.syncDirectory(key, directory) }
            override fun moveNoReplace(key: NormalizationKey, source: NormalizationRole, destination: NormalizationRole, expected: NormalizationFingerprint) = faults.around("rename:$source:$destination") { actual.moveNoReplace(key, source, destination, expected) }
            override fun deleteExact(key: NormalizationKey, role: NormalizationRole, expected: NormalizationFingerprint) = faults.around("unlink:$role") { actual.deleteExact(key, role, expected) }
            override fun removeEmptyDirectory(key: NormalizationKey, directory: NormalizationDirectory) = faults.around("rmdir:$directory") { actual.removeEmptyDirectory(key, directory) }
        }
        fun protocol() = NormalizationWorkspaceProtocol(ledger, files, { if (authorityUnreadable) throw IOException("TEST"); auth }, this)
        fun begin() = protocol().begin(key, raw, sourceDigest)
        fun ready() { begin(); outputFile.writeBytes(outputBytes); protocol().finishOutput(key, proof()); auth = auth.copy(proofSha256 = proof().digest()) }
        fun intent() { ready(); protocol().recordAdoptionIntent(key, proof()) }
        fun adopted() { intent(); protocol().adopt(key) }
        fun success() {
            adopted(); val media = File(root, "media").apply { mkdir() }; Files.move(primary.toPath(), File(media, "published.mp4").toPath())
            auth = auth.copy(taskState = NormalizationTaskState.SUCCEEDED, parentState = NormalizationParentState.PUBLISHED, publishedOutput = output)
        }
        override fun close() { root.deleteRecursively() }
    }
    /** Consumer DTO fixtures only; no actual conversion producer or native codec claim. */
    private fun conversionProof(audio: Boolean = false, policy: Int = if (audio) 2 else 1): DirectConversionProof {
        val sound = if (audio) DirectConversionAudioExpectation(0, if (policy == 2) 60_000 else 100_000,
            if (policy == 2) 2_880 else 4_800, 4_800, 2, 0, 0, 0) else null
        val expectation = DirectConversionExpectation(DirectConversionVideoExpectation(16, 16, 30, 100_000, 3, 0, 66_666, 0), sound)
        val fact = if (audio) true else null
        return DirectConversionProof(key.normalizationId, owner.taskId, "a".repeat(64), owner.artifactId, owner.commitId,
            raw.size, raw.sha256, sourceDigest, output.size, output.sha256, expectation,
            DirectConversionEncodingSettings.forExpectation(expectation),
            DirectConversionCompletionFacts(true, true, true, true, true, true, fact, fact, fact, fact, fact, fact),
            policyRevision = policy)
    }
    @Test fun convertedAudioTwoAdoptsThroughTheSameActualFileProtocol() = Fixture().use { f ->
        f.begin(); f.outputFile.writeBytes(outputBytes); val proof = conversionProof(audio = true)
        val ready = f.protocol().finishOutput(key, proof)
        f.auth = f.auth.copy(proofSha256 = proof.digest())
        val intent = f.protocol().recordAdoptionIntent(key, proof)
        val adopted = f.protocol().adopt(key)
        assertEquals(ready.copy(revision = ready.revision + 1, lifecycle = NormalizationLifecycle.ADOPT_INTENT), intent)
        assertEquals(intent.copy(revision = intent.revision + 1, lifecycle = NormalizationLifecycle.ADOPTED), adopted)
        assertEquals(2, proof.policyRevision); assertEquals(2_880L, proof.expectation.audio!!.sourcePcmFrameCount)
        assertEquals(4_800L, proof.expectation.audio!!.encoderPcmFrameCount)
        assertArrayEquals(rawBytes, f.rawFile.readBytes()); assertArrayEquals(outputBytes, f.primary.readBytes())
    }
    @Test fun convertedHistoricalAudioCannotFinishOrAdoptDespiteExactDurableBinding() {
        Fixture().use { f ->
            f.begin(); f.outputFile.writeBytes(outputBytes); val proof = conversionProof(audio = true, policy = 1)
            val before = f.ledger.row; f.faults.events.clear()
            assertThrows(NormalizationRecovery::class.java) { f.protocol().finishOutput(key, proof) }
            assertEquals(before, f.ledger.row); assertTrue(f.faults.events.none { it.contains("write:") || it.contains("rename:") })
        }
        Fixture().use { f ->
            f.begin(); f.outputFile.writeBytes(outputBytes)
            val current = conversionProof(audio = true); f.protocol().finishOutput(key, current)
            val historical = conversionProof(audio = true, policy = 1)
            f.ledger.row = f.ledger.row!!.copy(proofSha256 = historical.digest())
            f.auth = f.auth.copy(proofSha256 = historical.digest()); val before = f.ledger.row; f.faults.events.clear()
            assertThrows(NormalizationRecovery::class.java) { f.protocol().recordAdoptionIntent(key, historical) }
            assertEquals(before, f.ledger.row); assertTrue(f.faults.events.none { it.contains("write:") || it.contains("rename:") })
        }
    }
    @Test fun convertedAdoptionRequiresAcknowledgedProofAndPreservesExactGeneration() = Fixture().use { f ->
        f.begin(); f.outputFile.writeBytes(outputBytes); val proof = conversionProof()
        val ready = f.protocol().finishOutput(key, proof)
        assertThrows(NormalizationRecovery::class.java) { f.protocol().recordAdoptionIntent(key, proof) }
        assertEquals(ready, f.ledger.row)
        f.auth = f.auth.copy(proofSha256 = proof.digest())
        val intent = f.protocol().recordAdoptionIntent(key, proof)
        assertEquals(ready.copy(revision = ready.revision + 1, lifecycle = NormalizationLifecycle.ADOPT_INTENT), intent)
        val adopted = f.protocol().adopt(key)
        assertEquals(intent.copy(revision = intent.revision + 1, lifecycle = NormalizationLifecycle.ADOPTED), adopted)
        assertArrayEquals(rawBytes, f.rawFile.readBytes()); assertArrayEquals(outputBytes, f.primary.readBytes())
        assertFalse(f.outputFile.exists()); assertEquals(key, adopted.key)
    }
    @Test fun convertedAllIntentRenameAndDurabilityCrashWindowsUseOnlyThreeExistingLayouts() {
        fun ready(f: Fixture) {
            f.begin(); f.outputFile.writeBytes(outputBytes); f.protocol().finishOutput(key, conversionProof())
            f.auth = f.auth.copy(proofSha256 = conversionProof().digest())
        }
        val windows = Fixture().use { f -> ready(f); f.faults.events.clear()
            f.protocol().recordAdoptionIntent(key, conversionProof()); f.protocol().adopt(key); f.faults.events.distinct() }
        for (window in windows) Fixture().use { f ->
            ready(f); f.faults.events.clear(); f.faults.failAt = window
            try { f.protocol().recordAdoptionIntent(key, conversionProof()); f.protocol().adopt(key) } catch (_: Exception) { }
            f.faults.failAt = null
            val freshLedger = Ledger(Faults()).apply {
                row = NormalizationWorkspaceCodec.decode(NormalizationWorkspaceCodec.encode(f.ledger.row!!))
            }
            val fresh = NormalizationWorkspaceProtocol(freshLedger, f.files, { f.auth }, Any())
            val inspected = fresh.inspect(owner) as NormalizationInspection.Present
            assertTrue(inspected.layout in setOf(NormalizationLayout.READY, NormalizationLayout.RAW_MOVED, NormalizationLayout.ADOPTED))
            if (freshLedger.row!!.lifecycle == NormalizationLifecycle.OUTPUT_READY) fresh.recordAdoptionIntent(key, conversionProof())
            fresh.adopt(key)
            assertEquals(NormalizationLifecycle.ADOPTED, freshLedger.row!!.lifecycle)
            assertArrayEquals(rawBytes, f.rawFile.readBytes()); assertArrayEquals(outputBytes, f.primary.readBytes())
            assertFalse(f.outputFile.exists())
        }
    }
    @Test fun convertedAdoptionRejectsWrongDigestGenerationRawAndOverflowWithoutRenames() {
        for (case in 0..3) Fixture().use { f ->
            f.begin(); f.outputFile.writeBytes(outputBytes); val proof = conversionProof()
            f.protocol().finishOutput(key, proof); f.auth = f.auth.copy(proofSha256 = proof.digest())
            when (case) {
                0 -> f.auth = f.auth.copy(proofSha256 = "f".repeat(64))
                1 -> f.ledger.row = f.ledger.row!!.copy(proofSha256 = "e".repeat(64))
                2 -> f.primary.writeBytes(byteArrayOf(1))
                3 -> f.ledger.row = f.ledger.row!!.copy(revision = Long.MAX_VALUE)
            }
            f.faults.events.clear()
            assertThrows(NormalizationRecovery::class.java) { f.protocol().recordAdoptionIntent(key, proof) }
            assertTrue(f.faults.events.none { it.contains("rename:") || it.contains("write:") })
            assertTrue(f.outputFile.exists()); assertFalse(f.rawFile.exists())
        }
    }
    @Test fun conversionFinishUsesActualFixtureSyncHashAndOneExactOutputReadySuccessor() = Fixture().use { f ->
        val writing = f.begin(); f.outputFile.writeBytes(outputBytes); f.faults.events.clear()
        val proof = conversionProof()
        val ready = f.protocol().finishOutput(key, proof)
        assertEquals(writing.copy(revision = writing.revision + 1, lifecycle = NormalizationLifecycle.OUTPUT_READY,
            output = output, proofSha256 = proof.digest()), ready)
        assertTrue(f.faults.events.any { it.startsWith("after:fsync:OUTPUT") })
        assertTrue(f.faults.events.any { it.startsWith("after:fsync:GENERATION") })
        f.auth = f.auth.copy(proofSha256 = proof.digest())
        val inspected = f.protocol().inspect(owner) as NormalizationInspection.Present
        assertEquals(ready, inspected.row); assertEquals(NormalizationLayout.READY, inspected.layout)
        assertFalse(inspected.discardOnly)
        assertThrows(NormalizationRecovery::class.java) { f.protocol().finishOutput(key, proof) }
        assertArrayEquals(rawBytes, f.primary.readBytes()); assertArrayEquals(outputBytes, f.outputFile.readBytes())
    }
    @Test fun conversionFinishWriteThenThrowAcknowledgesOnlyWholeSuccessor() {
        for (after in listOf(false, true)) Fixture().use { f ->
            val writing = f.begin(); f.outputFile.writeBytes(outputBytes)
            f.faults.failAt = (if (after) "after:" else "before:") + "write:OUTPUT_READY:CONTINUE"
            if (after) {
                val ready = f.protocol().finishOutput(key, conversionProof())
                assertEquals(writing.revision + 1, ready.revision); assertEquals(ready, f.ledger.row)
            } else {
                assertThrows(Exception::class.java) { f.protocol().finishOutput(key, conversionProof()) }
                assertEquals(writing, f.ledger.row)
            }
        }
    }
    @Test fun conversionFinishRejectsWrongRawTransferOwnerAndOutput() {
        val proof = conversionProof()
        for (bad in listOf(proof.copy(rawSha256 = "c".repeat(64)), proof.copy(sourceTransferSha256 = "d".repeat(64)),
            proof.copy(taskId = "other"), proof.copy(outputSha256 = "e".repeat(64)))) Fixture().use { f ->
            val writing = f.begin(); f.outputFile.writeBytes(outputBytes)
            assertThrows(NormalizationRecovery::class.java) { f.protocol().finishOutput(key, bad) }
            assertEquals(writing, f.ledger.row)
        }
    }
    @Test fun conversionFinishRejectsOverflowBeforeFilesystemOrSuccessorConstruction() = Fixture().use { f ->
        f.begin(); f.outputFile.writeBytes(outputBytes)
        f.ledger.row = f.ledger.row!!.copy(revision = Long.MAX_VALUE); f.faults.events.clear()
        assertThrows(NormalizationRecovery::class.java) { f.protocol().finishOutput(key, conversionProof()) }
        assertEquals(Long.MAX_VALUE, f.ledger.row!!.revision); assertTrue(f.faults.events.isEmpty())
    }
    @Test fun conversionFinishRejectsUnreleasedStopAndUnexpectedRawRole() {
        for (case in 0..2) Fixture().use { f ->
            val writing = f.begin(); f.outputFile.writeBytes(outputBytes)
            when (case) {
                0 -> f.handlesReleased = false
                1 -> f.auth = f.auth.copy(taskState = NormalizationTaskState.CANCELLED)
                2 -> f.rawFile.writeBytes(rawBytes)
            }
            assertThrows(NormalizationRecovery::class.java) { f.protocol().finishOutput(key, conversionProof()) }
            assertEquals(writing, f.ledger.row)
        }
    }
    @Test fun conversionFinalCasRereadsTerminalAuthority() = Fixture().use { f ->
        val writing = f.begin(); f.outputFile.writeBytes(outputBytes); var checks = 0
        val protocol = NormalizationWorkspaceProtocol(f.ledger, f.files, { f.auth }, f) {
            assertTrue(Thread.holdsLock(f))
            if (++checks == 3) f.auth = f.auth.copy(taskState = NormalizationTaskState.CANCELLED)
        }
        assertThrows(NormalizationRecovery::class.java) { protocol.finishOutput(key, conversionProof()) }
        assertEquals(writing, f.ledger.row)
    }

    @Test fun cancellationAtTheFinalCasCheckCannotAdvanceAnOrdinaryLifecycle(): Unit = Fixture().use { f ->
        f.begin(); f.outputFile.writeBytes(outputBytes); f.faults.events.clear()
        var checks = 0
        val protocol = NormalizationWorkspaceProtocol(f.ledger, f.files, { f.auth }, f) {
            assertTrue("Authority checks must remain in a short owner-lock section", Thread.holdsLock(f))
            // Initial continuing, post-IO continuing, then the persist section's own check.
            if (++checks == 3) f.auth = f.auth.copy(taskState = NormalizationTaskState.CANCELLED)
        }
        assertThrows(NormalizationRecovery::class.java) { protocol.finishOutput(key, proof()) }
        assertTrue(checks >= 3)
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
        assertFalse(f.faults.events.any { it.contains("write:OUTPUT_READY") })
        assertArrayEquals(rawBytes, f.primary.readBytes()); assertArrayEquals(outputBytes, f.outputFile.readBytes())
    }

    @Test fun cleanupCasRequiresCurrentTerminalAuthorityForBothPlanAndCompletion() {
        for (finishing in listOf(false, true)) Fixture().use { f ->
            f.begin()
            f.auth = f.auth.copy(taskState = NormalizationTaskState.CANCELLED)
            if (finishing) {
                val current = f.ledger.row!!
                f.ledger.row = current.copy(revision = current.revision + 1,
                    lifecycle = NormalizationLifecycle.CLEANUP_PENDING,
                    disposition = NormalizationDisposition.DISCARD_ONLY,
                    cleanup = NormalizationCleanupPlan(raw, null, fingerprint(byteArrayOf())))
                assertTrue(f.outputFile.delete()); assertTrue(f.generation.delete()); assertTrue(f.workspace.delete()); assertTrue(f.primary.delete())
            }
            val before = f.ledger.row!!; f.faults.events.clear(); var checks = 0
            val protocol = NormalizationWorkspaceProtocol(f.ledger, f.files, { f.auth }, f) {
                assertTrue(Thread.holdsLock(f))
                // Pretend terminal authority changed just before the final mutation check.
                if (++checks == 1) f.auth = f.auth.copy(taskState = NormalizationTaskState.ACTIVE)
            }
            assertThrows(NormalizationRecovery::class.java) { protocol.cleanup(key, before.revision) }
            assertTrue(checks >= 1); assertEquals(before, f.ledger.row)
            assertFalse(f.faults.events.any { it.contains("write:") || it.contains("unlink:") })
            if (!finishing) { assertArrayEquals(rawBytes, f.primary.readBytes()); assertTrue(f.outputFile.exists()) }
        }
    }

    @Test fun beginBeforeAfterEveryJournalCreateAndSyncFailureIsRecognizableWithoutRawCopy() {
        val windows = Fixture().use { f -> f.begin(); f.faults.events.distinct() }
        assertTrue(windows.any { it.startsWith("after:insert#") }); assertTrue(windows.any { it.startsWith("after:create:OUTPUT#") })
        for (window in windows) Fixture().use { f ->
            f.faults.failAt = window
            try { f.begin() } catch (_: Exception) { }
            f.faults.failAt = null
            val row = f.begin()
            assertEquals("$window", NormalizationLifecycle.WRITING, row.lifecycle)
            assertArrayEquals(rawBytes, f.primary.readBytes()); assertFalse(f.rawFile.exists()); assertEquals(0, f.outputFile.length())
            assertEquals(listOf("output.mp4"), f.generation.list()!!.toList())
        }
    }
    @Test fun finishOutputBeforeAfterEveryFsyncAndWriteFailureNeverInventsReadyOutput() {
        val windows = Fixture().use { f -> f.begin(); f.outputFile.writeBytes(outputBytes); f.faults.events.clear(); f.protocol().finishOutput(key, proof()); f.faults.events.distinct() }
        for (window in windows) Fixture().use { f ->
            f.begin(); f.outputFile.writeBytes(outputBytes); f.faults.events.clear(); f.faults.failAt = window
            try { f.protocol().finishOutput(key, proof()) } catch (_: Exception) { }
            f.faults.failAt = null
            if (f.ledger.row!!.lifecycle == NormalizationLifecycle.WRITING) f.protocol().finishOutput(key, proof())
            assertEquals(NormalizationLifecycle.OUTPUT_READY, f.ledger.row!!.lifecycle)
            assertArrayEquals(rawBytes, f.primary.readBytes()); assertArrayEquals(outputBytes, f.outputFile.readBytes()); assertFalse(f.rawFile.exists())
        }
    }
    @Test fun everyAdoptionBeforeAfterRenameFsyncAndJournalFailureResumesOnlyLegalNoReplaceMove() {
        val windows = Fixture().use { f -> f.ready(); f.faults.events.clear(); f.protocol().recordAdoptionIntent(key, proof()); f.protocol().adopt(key); f.faults.events.distinct() }
        assertTrue(windows.any { it.startsWith("after:rename:PRIMARY:RAW#") }); assertTrue(windows.any { it.startsWith("after:rename:OUTPUT:PRIMARY#") })
        for (window in windows) Fixture().use { f ->
            f.ready(); f.faults.events.clear(); f.faults.failAt = window
            try { f.protocol().recordAdoptionIntent(key, proof()); f.protocol().adopt(key) } catch (_: Exception) { }
            f.faults.failAt = null
            assertTrue("$window", f.protocol().inspect(owner) is NormalizationInspection.Present)
            if (f.ledger.row!!.lifecycle == NormalizationLifecycle.OUTPUT_READY) f.protocol().recordAdoptionIntent(key, proof())
            val row = f.protocol().adopt(key)
            assertEquals(NormalizationLifecycle.ADOPTED, row.lifecycle)
            assertArrayEquals(outputBytes, f.primary.readBytes()); assertArrayEquals(rawBytes, f.rawFile.readBytes()); assertFalse(f.outputFile.exists())
        }
    }
    @Test fun saveProofMustBeAcknowledgedBeforeIntentAndNoPassiveInspectionMovesAnything(): Unit = Fixture().use { f ->
        f.begin(); f.outputFile.writeBytes(outputBytes); f.protocol().finishOutput(key, proof())
        assertThrows(NormalizationRecovery::class.java) { f.protocol().recordAdoptionIntent(key, proof()) }
        f.faults.events.clear(); assertTrue(f.protocol().inspect(owner) is NormalizationInspection.Present)
        assertTrue(f.faults.events.isEmpty()); assertArrayEquals(rawBytes, f.primary.readBytes())
        f.auth = f.auth.copy(proofSha256 = proof().digest()); f.protocol().recordAdoptionIntent(key, proof())
    }
    @Test fun uncertainOrMissingAuthorityAndWrongGenerationAreNeverAbsent(): Unit = Fixture().use { f ->
        assertEquals(NormalizationInspection.Absent, f.protocol().inspect(owner)); f.begin()
        f.ledger.unreadable = true; assertEquals(NormalizationInspection.Unreadable, f.protocol().inspect(owner)); assertThrows(NormalizationRecovery::class.java) { f.begin() }
        f.ledger.unreadable = false; f.authorityUnreadable = true; assertEquals(NormalizationInspection.Unreadable, f.protocol().inspect(owner))
        f.authorityUnreadable = false; f.auth = f.auth.copy(sourceTransferSha256 = null)
        assertEquals(NormalizationInspection.Unreadable, f.protocol().inspect(owner))
        f.auth = f.auth.copy(sourceTransferSha256 = sourceDigest)
        assertThrows(NormalizationRecovery::class.java) { f.protocol().begin(key.copy(normalizationId = "33333333-3333-4333-8333-333333333333"), raw, sourceDigest) }
    }
    @Test fun cancellationBetweenRenamesForbidsContinuationEvenWhenDispositionWriteNeverHappened(): Unit = Fixture().use { f ->
        f.intent(); f.faults.failAt = "after:rename:PRIMARY:RAW"
        assertThrows(Crash::class.java) { f.protocol().adopt(key) }
        f.auth = f.auth.copy(taskState = NormalizationTaskState.CANCELLED)
        assertEquals(NormalizationDisposition.CONTINUE, f.ledger.row!!.disposition)
        assertTrue((f.protocol().inspect(owner) as NormalizationInspection.Present).discardOnly)
        assertThrows(NormalizationRecovery::class.java) { f.protocol().adopt(key) }
        assertFalse(f.primary.exists()); assertTrue(f.rawFile.exists()); assertTrue(f.outputFile.exists())
        f.protocol().cleanup(key, f.ledger.row!!.revision)
        assertEquals(NormalizationDisposition.DISCARD_ONLY, f.ledger.row!!.disposition); assertEquals(NormalizationLifecycle.CLEANED, f.ledger.row!!.lifecycle)
    }
    @Test fun terminalCleanupBeforeAfterEveryWriteUnlinkAndFsyncStaysVisibleUntilCleaned() {
        val windows = Fixture().use { f -> f.success(); f.faults.events.clear(); f.protocol().cleanup(key, f.ledger.row!!.revision); f.faults.events.distinct() }
        for (window in windows) Fixture().use { f ->
            f.success(); f.faults.events.clear(); f.faults.failAt = window
            try { f.protocol().cleanup(key, f.ledger.row!!.revision) } catch (_: Exception) { }
            f.faults.failAt = null
            if (f.ledger.row!!.lifecycle != NormalizationLifecycle.CLEANED) {
                assertEquals(1, (f.ledger.pending() as NormalizationPendingPage.Readable).entries.size)
                f.protocol().cleanup(key, f.ledger.row!!.revision)
            }
            assertEquals(NormalizationLifecycle.CLEANED, f.ledger.row!!.lifecycle)
            assertTrue((f.ledger.pending() as NormalizationPendingPage.Readable).entries.isEmpty())
            assertArrayEquals(outputBytes, File(f.root, "media/published.mp4").readBytes()); assertFalse(f.workspace.exists())
            assertEquals(NormalizationDisposition.CONTINUE, f.ledger.row!!.disposition)
        }
    }
    @Test fun failedDiscardedParentAndIncompleteOutputCanBeCleanedWithExactDurablePlan(): Unit = Fixture().use { f ->
        f.begin(); val partial = byteArrayOf(1, 2, 3); f.outputFile.writeBytes(partial)
        f.begin(); assertArrayEquals(partial, f.outputFile.readBytes()) // Status reuse is not permission to truncate.
        assertThrows(NormalizationRecovery::class.java) { f.protocol().finishOutput(key, proof()) }
        f.primary.delete(); f.auth = f.auth.copy(taskState = NormalizationTaskState.FAILED, parentState = NormalizationParentState.DISCARDED)
        f.faults.failAt = "before:unlink:OUTPUT"
        assertThrows(Crash::class.java) { f.protocol().cleanup(key, f.ledger.row!!.revision) }
        assertEquals(fingerprint(partial), f.ledger.row!!.cleanup!!.output)
        assertEquals(1, (f.ledger.pending() as NormalizationPendingPage.Readable).entries.size)
        f.protocol().cleanup(key, f.ledger.row!!.revision); assertFalse(f.workspace.exists())
    }
    @Test fun cancellationDispositionBeforeAfterWriteUncertaintyAndStaleCleanupRevisionFailClosed() {
        for (side in listOf("before", "after")) Fixture().use { f ->
            f.begin(); f.auth = f.auth.copy(taskState = NormalizationTaskState.CANCELLED)
            val old = f.ledger.row!!.revision
            f.faults.failAt = "$side:write:CLEANUP_PENDING:DISCARD_ONLY"
            try { f.protocol().cleanup(key, old) } catch (_: Exception) { }
            if (f.ledger.row!!.revision != old) assertThrows(NormalizationRecovery::class.java) { f.protocol().cleanup(key, old) }
            if (f.ledger.row!!.lifecycle != NormalizationLifecycle.CLEANED) f.protocol().cleanup(key, f.ledger.row!!.revision)
            assertEquals(NormalizationLifecycle.CLEANED, f.ledger.row!!.lifecycle)
        }
    }
    @Test fun successWinnerCannotDiscardCanonicalOrAcceptForgedPublication(): Unit = Fixture().use { f ->
        f.success(); val current = f.ledger.row!!
        f.auth = f.auth.copy(publishedOutput = raw)
        assertThrows(NormalizationRecovery::class.java) { f.protocol().cleanup(key, current.revision) }
        f.auth = f.auth.copy(publishedOutput = output)
        f.ledger.row = current.copy(disposition = NormalizationDisposition.DISCARD_ONLY)
        assertThrows(NormalizationRecovery::class.java) { f.protocol().cleanup(key, current.revision) }
        assertArrayEquals(outputBytes, File(f.root, "media/published.mp4").readBytes())
    }
    @Test fun unknownChildrenSymlinksHardlinksCollisionsAndWrongContentsAreNeverDeleted() {
        Fixture().use { f -> f.workspace.mkdir(); assertThrows(NormalizationRecovery::class.java) { f.begin() }; assertNull(f.ledger.row) }
        Fixture().use { f -> f.begin(); val unknown = File(f.generation, "unknown").apply { writeText("keep") }; assertEquals(NormalizationInspection.Unreadable, f.protocol().inspect(owner)); f.auth = f.auth.copy(taskState = NormalizationTaskState.FAILED); assertThrows(NormalizationRecovery::class.java) { f.protocol().cleanup(key, f.ledger.row!!.revision) }; assertTrue(unknown.exists()) }
        Fixture().use { f -> val target = File(f.root, "outside").apply { writeBytes(rawBytes) }; f.primary.delete(); Files.createSymbolicLink(f.primary.toPath(), target.toPath()); assertThrows(NormalizationRecovery::class.java) { f.begin() }; assertArrayEquals(rawBytes, target.readBytes()) }
        Fixture().use { f -> val alias = File(f.root, "alias"); Files.createLink(alias.toPath(), f.primary.toPath()); assertThrows(NormalizationRecovery::class.java) { f.begin() }; assertTrue(alias.exists()) }
        Fixture().use { f -> f.intent(); f.rawFile.writeBytes(rawBytes); assertThrows(NormalizationRecovery::class.java) { f.protocol().adopt(key) }; assertArrayEquals(rawBytes, f.primary.readBytes()) }
        Fixture().use { f -> f.intent(); f.outputFile.writeBytes(outputBytes.reversedArray()); assertThrows(NormalizationRecovery::class.java) { f.protocol().adopt(key) }; assertArrayEquals(rawBytes, f.primary.readBytes()) }
        Fixture().use { f -> f.begin(); Files.createDirectory(File(f.generation, "raw.bin").toPath()); assertEquals(NormalizationInspection.Unreadable, f.protocol().inspect(owner)) }
    }
    @Test fun fullAllowanceRecheckedAndUnreleasedHandlesBlockAllMutation() {
        for (bytes in listOf(-1L, 0L, NORMALIZATION_MAX_BYTES - 1)) Fixture().use { f -> f.available = bytes; assertThrows(NormalizationRecovery::class.java) { f.begin() }; assertFalse(f.workspace.exists()); assertNull(f.ledger.row) }
        Fixture().use { f -> f.begin(); f.outputFile.writeBytes(outputBytes); f.handlesReleased = false; assertThrows(NormalizationRecovery::class.java) { f.protocol().finishOutput(key, proof()) }; assertEquals(NormalizationInspection.Unreadable, f.protocol().inspect(owner)) }
        Fixture().use { f -> f.faults.failAt = "after:create:OUTPUT"; assertThrows(Crash::class.java) { f.begin() }; f.available = 0; assertThrows(NormalizationRecovery::class.java) { f.begin() }; assertArrayEquals(rawBytes, f.primary.readBytes()); assertEquals(0, f.outputFile.length()) }
    }
    @Test fun adoptedCommitOwnedCannotBeRepromotedOrFailedCleanedWithoutPublicationAuthority(): Unit = Fixture().use { f ->
        f.adopted(); f.primary.delete(); f.auth = f.auth.copy(parentState = NormalizationParentState.COMMIT_PENDING)
        assertEquals(NormalizationInspection.Unreadable, f.protocol().inspect(owner))
        assertThrows(NormalizationRecovery::class.java) { f.protocol().adopt(key) }
        f.auth = f.auth.copy(taskState = NormalizationTaskState.FAILED)
        assertThrows(NormalizationRecovery::class.java) { f.protocol().cleanup(key, f.ledger.row!!.revision) }
        assertArrayEquals(rawBytes, f.rawFile.readBytes())
    }
    @Test fun creationRecoveryRepeatsEveryParentDirectoryBarrierBeforeWritingAcknowledgement() {
        for (window in listOf("after:mkdir:WORKSPACE", "before:fsync:STAGING", "after:mkdir:GENERATION", "before:fsync:WORKSPACE")) Fixture().use { f ->
            f.faults.failAt = window
            assertThrows(Crash::class.java) { f.begin() }
            f.faults.events.clear(); f.begin()
            val events = f.faults.events
            val ack = events.indexOfFirst { it.startsWith("before:write:WRITING:") }
            assertTrue(ack >= 0)
            for (dir in NormalizationDirectory.entries) {
                val barrier = events.indexOfLast { it.startsWith("after:fsync:$dir#") }
                assertTrue("$window must resync $dir before ACK", barrier in 0 until ack)
            }
        }
    }
    @Test fun primaryOnlyCleanupCannotAcknowledgeAfterUncertainUnlinkUntilStagingSyncSucceeds() = Fixture().use { f ->
        f.faults.failAt = "before:mkdir:WORKSPACE"
        assertThrows(Crash::class.java) { f.begin() }
        assertEquals(NormalizationLifecycle.RAW_COMPLETE, f.ledger.row!!.lifecycle); assertFalse(f.workspace.exists())
        f.auth = f.auth.copy(taskState = NormalizationTaskState.CANCELLED)
        f.faults.failAt = "after:unlink:PRIMARY"
        assertThrows(Crash::class.java) { f.protocol().cleanup(key, f.ledger.row!!.revision) }
        assertFalse(f.primary.exists()); assertEquals(NormalizationLifecycle.CLEANUP_PENDING, f.ledger.row!!.lifecycle)
        f.faults.events.clear(); f.faults.failAt = "before:fsync:STAGING"
        assertThrows(Crash::class.java) { f.protocol().cleanup(key, f.ledger.row!!.revision) }
        assertEquals(NormalizationLifecycle.CLEANUP_PENDING, f.ledger.row!!.lifecycle)
        f.faults.events.clear(); f.protocol().cleanup(key, f.ledger.row!!.revision)
        val sync = f.faults.events.indexOfFirst { it.startsWith("after:fsync:STAGING#") }
        val ack = f.faults.events.indexOfFirst { it.startsWith("before:write:CLEANED:") }
        assertTrue(sync in 0 until ack)
        f.primary.writeBytes(rawBytes)
        assertEquals(NormalizationInspection.Unreadable, f.protocol().inspect(owner))
        assertThrows(NormalizationRecovery::class.java) { f.protocol().cleanup(key, f.ledger.row!!.revision) }
        assertArrayEquals(rawBytes, f.primary.readBytes())
        Unit
    }
    @Test fun codecRejectsTrailingOversizedBadIdsAndImmutableSourceOrProofChanges(): Unit = Fixture().use { f ->
        f.ready(); val row = f.ledger.row!!; val bytes = NormalizationWorkspaceCodec.encode(row)
        assertEquals(row, NormalizationWorkspaceCodec.decode(bytes))
        assertThrows(IllegalArgumentException::class.java) { NormalizationWorkspaceCodec.decode(bytes + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { NormalizationWorkspaceCodec.decode(ByteArray(2049)) }
        assertThrows(IllegalArgumentException::class.java) { NormalizationKey(owner, "../escape") }
        assertThrows(IllegalArgumentException::class.java) { row.requireSuccessor(row.copy(revision = row.revision + 1, sourceTransferSha256 = "b".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { row.requireSuccessor(row.copy(revision = row.revision + 1, proofSha256 = "b".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { row.requireSuccessor(row.copy(revision = row.revision + 1, raw = output)) }
        assertThrows(IllegalArgumentException::class.java) { row.requireSuccessor(row.copy(revision = row.revision + 1, lifecycle = NormalizationLifecycle.WRITING, output = null, proofSha256 = null)) }
    }
}
