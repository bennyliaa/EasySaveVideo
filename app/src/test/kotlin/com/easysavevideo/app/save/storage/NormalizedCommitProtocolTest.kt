package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Real protocol + descriptor adapter and accepted Coordinator; fake syscalls/journal, not SQLite/device acceptance. */
class NormalizedCommitProtocolTest {
    @Test fun freshCommitInspectAndAtomicPublicationRetainRawUntilExactSuccessCleanup() = accepted { f, execution, permit, context ->
        val before = f.io.calls.count { it.operation == "pread" }
        val handle = f.protocol.begin(f.state.record, f.output, execution, permit, context)
        assertEquals(OwnedState.INDEXED, f.state.owned.state)
        assertFalse(f.state.indexVisible); assertFalse(f.state.libraryLinked)
        assertFalse(f.io.exists(f.primary)); assertTrue(f.io.exists(f.final)); assertTrue(f.io.exists(f.rawFile))
        val afterCommit = f.io.calls.count { it.operation == "pread" }
        assertTrue(afterCommit > before)
        val publication = f.protocol.inspect(handle, f.state.record, f.output, execution, permit, context)
        assertTrue(f.io.calls.count { it.operation == "pread" } > afterCommit)
        val next = f.success(publication.receipt)
        assertTrue(f.protocol.publish(f.state.record.task.revision, next, publication))
        assertEquals(next, f.state.record); assertTrue(f.state.indexVisible); assertTrue(f.state.libraryLinked)
        assertFalse(f.reservations.hasAnyReservations())
        // User deleted/edited Library media after publication: raw cleanup still must finish,
        // without reopening, recreating, deleting, or reindexing that canonical media.
        f.io.remove(f.final); f.state = f.state.copy(indexed = null, indexVisible = false, libraryLinked = false)
        f.protocol.cleanup(f.state.record, permit, context, f.cleanupContext())
        assertEquals(NormalizationLifecycle.CLEANED, f.state.workspace.lifecycle)
        assertFalse(f.io.exists(f.rawFile)); assertFalse(f.io.exists(f.workspace)); assertFalse(f.io.exists(f.final))
        assertEquals(1, f.writes.count { it == "publish" })
    }

    @Test fun currentAudioVerifiedStagingIntentAndIndexedRecoveryUseActualRoleHashAndTypedPublication() {
        for (catalog in listOf(OwnedState.STAGING, OwnedState.INTENT, OwnedState.INDEXED)) accepted(audio = true) { f, execution, permit, context ->
            if (catalog != OwnedState.STAGING) {
                val owned = f.state.owned.copy(state = catalog, evidence = f.evidence)
                f.state = f.state.copy(owned = owned, indexed = if (catalog == OwnedState.INDEXED) owned.receipt() else null)
                f.io.move(f.primary, f.final)
            }
            val handle = f.protocol.begin(f.state.record, f.output, execution, permit, context)
            val publication = f.protocol.inspect(handle, f.state.record, f.output, execution, permit, context)
            assertTrue(f.protocol.publish(f.state.record.task.revision, f.success(publication.receipt), publication))
            assertEquals(2, f.state.record.directNormalization!!.policyRevision)
            assertTrue(f.state.indexVisible); assertTrue(f.state.libraryLinked)
            assertTrue(f.io.calls.any { it.operation == "pread" && it.path == f.final })
            assertEquals(1, f.writes.count { it == "publish" })
        }
    }

    @Test fun directCommitCannotPublishHistoricalAudioDespiteStructurallyMatchingEvidenceAndLedger() {
        for (catalog in listOf(OwnedState.STAGING, OwnedState.INTENT, OwnedState.INDEXED)) accepted(audio = true, policy = 1) { f, execution, permit, context ->
            if (catalog != OwnedState.STAGING) {
                val owned = f.state.owned.copy(state = catalog, evidence = f.evidence)
                f.state = f.state.copy(owned = owned, indexed = if (catalog == OwnedState.INDEXED) owned.receipt() else null)
                f.io.move(f.primary, f.final)
            }
            val before = f.state; val calls = f.io.calls.size
            assertThrows(Exception::class.java) { f.protocol.begin(f.state.record, f.output, execution, permit, context) }
            assertEquals(before, f.state); assertEquals(calls, f.io.calls.size)
            assertTrue(f.writes.isEmpty()); assertEquals(0, f.moves)
            assertFalse(f.state.indexVisible); assertFalse(f.reservations.hasAnyReservations())
        }
    }

    @Test fun currentAudioCannotReuseLedgerDigestFromOldRevisionOrForgedPublicationHandle() = accepted(audio = true) { f, execution, permit, context ->
        val valid = f.state
        f.state = f.state.copy(workspace = f.state.workspace.copy(proofSha256 = f.proof.copy(policyRevision = 1).digest()))
        assertThrows(Exception::class.java) { f.protocol.begin(f.state.record, f.output, execution, permit, context) }
        assertTrue(f.writes.isEmpty()); assertEquals(0, f.moves)
        f.state = valid
        val handle = f.protocol.begin(f.state.record, f.output, execution, permit, context)
        val publication = f.protocol.inspect(handle, f.state.record, f.output, execution, permit, context)
        val forged = object : NormalizedPublicationHandle { override val receipt = publication.receipt }
        assertFalse(f.protocol.publish(f.state.record.task.revision, f.success(forged.receipt), forged))
        assertFalse(f.state.indexVisible)
        handle.close()
    }

    @Test fun historicalSucceededAudioTerminalCleanupPreservesRecordEvidenceAndCanonicalMedia() {
        val f = Fixture(audio = true, policy = 1)
        val owned = f.state.owned.copy(state = OwnedState.INDEXED, evidence = f.evidence)
        val succeeded = f.success(owned.receipt())
        f.state = f.state.copy(record = succeeded, owned = owned, indexed = owned.receipt(), indexVisible = true, libraryLinked = true)
        f.io.move(f.primary, f.final)
        f.cleanup()
        assertEquals(succeeded, f.state.record)
        assertEquals(f.evidence, f.state.owned.evidence)
        assertArrayEquals(f.outputBytes, f.io.bytes(f.final))
        assertEquals(NormalizationLifecycle.CLEANED, f.state.workspace.lifecycle)
        assertEquals(0, f.writes.count { it == "publish" })
    }

    @Test fun eachSqlWriteThenThrowReconcilesOnlyFullExactProposal() {
        for (step in listOf("intent", "index", "publish")) accepted { f, execution, permit, context ->
            f.afterWrite = { if (it == step) throw IOException("WRITE_THEN_THROW") }
            val h = f.protocol.begin(f.state.record, f.output, execution, permit, context)
            val p = f.protocol.inspect(h, f.state.record, f.output, execution, permit, context)
            assertTrue(f.protocol.publish(f.state.record.task.revision, f.success(p.receipt), p))
            assertEquals(TaskState.SUCCEEDED, f.state.record.task.state)
            assertEquals(1, f.writes.count { it == step })
        }
    }

    @Test fun writeThenThrowWithOnlyPartialOrAlteredProposalCannotPublish() = accepted { f, execution, permit, context ->
        f.afterWrite = { if (it == "publish") {
            f.state = f.state.copy(record = f.state.record.copy(bytesTransferred = f.state.record.bytesTransferred + 1))
            throw IOException("ALTERED_PROPOSAL")
        } }
        val h = f.protocol.begin(f.state.record, f.output, execution, permit, context)
        val p = f.protocol.inspect(h, f.state.record, f.output, execution, permit, context)
        assertThrows(Exception::class.java) { f.protocol.publish(f.state.record.task.revision, f.success(p.receipt), p) }
        assertEquals(1, f.writes.count { it == "publish" })
        assertFalse(f.reservations.hasAnyReservations())
        // A contradictory adapter result is recovery, never a second attempt to publish.
        assertFalse(f.protocol.publish(f.state.record.task.revision, f.success(p.receipt), p))
    }

    @Test fun renameThenThrowIsReconciledButDuplicatePrimaryAndFinalNeverAre() {
        accepted { f, execution, permit, context ->
            f.afterMove = { throw IOException("RENAMED_THEN_THROW") }
            f.protocol.begin(f.state.record, f.output, execution, permit, context).close()
            assertEquals(OwnedState.INDEXED, f.state.owned.state)
            assertEquals(1, f.moves)
        }
        accepted { f, execution, permit, context ->
            f.io.file(f.final, f.outputBytes)
            assertThrows(Exception::class.java) { f.protocol.begin(f.state.record, f.output, execution, permit, context) }
            assertTrue(f.writes.isEmpty()); assertEquals(0, f.moves)
            assertTrue(f.io.exists(f.primary)); assertTrue(f.io.exists(f.final))
        }
    }

    @Test fun localExtentLimitDoesNotDoubleChargeAlreadyRecordedNetworkBytes() {
        accepted { f, execution, permit, context ->
            f.state = f.state.copy(record = f.state.record.copy(bytesTransferred = 3_999_999))
            f.protocol.begin(f.state.record, f.output, execution, permit, context).close()
            assertEquals(3_999_999, f.state.record.bytesTransferred)
            assertEquals(f.raw.size, f.state.record.transfer!!.bytesOnDisk)
        }
        accepted(budget = 700) { f, execution, permit, context ->
            val calls = f.io.calls.size
            assertThrows(Exception::class.java) { f.protocol.begin(f.state.record, f.output, execution, permit, context) }
            assertEquals(calls, f.io.calls.size); assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun changedCanonicalAfterNativeEvidenceCannotReachIntent() = accepted { f, execution, permit, context ->
        f.io.mutate(f.primary, ByteArray(f.outputBytes.size) { 7 })
        assertThrows(Exception::class.java) { f.protocol.begin(f.state.record, f.output, execution, permit, context) }
        assertTrue(f.writes.isEmpty()); assertEquals(OwnedState.STAGING, f.state.owned.state)
        assertFalse(f.reservations.hasAnyReservations())
    }

    @Test fun changedFullRecordDuringHashCannotAdvanceEvenWithUnchangedRevision() = accepted { f, execution, permit, context ->
        var changed = false
        f.io.after = { call -> if (!changed && call.operation == "pread") {
            changed = true; f.state = f.state.copy(record = f.state.record.copy(bytesTransferred = f.state.record.bytesTransferred + 1))
        } }
        assertThrows(Exception::class.java) { f.protocol.begin(f.state.record, f.output, execution, permit, context) }
        assertTrue(f.writes.isEmpty())
    }

    @Test fun sameContentNewInodeCannotCrossCommitOrIndependentPublicationHandoff() {
        accepted { f, execution, permit, context ->
            f.afterWrite = { if (it == "intent") f.io.file(f.primary, f.outputBytes) }
            assertThrows(Exception::class.java) { f.protocol.begin(f.state.record, f.output, execution, permit, context) }
            assertEquals(OwnedState.INTENT, f.state.owned.state); assertFalse(f.state.indexVisible); assertEquals(0, f.moves)
        }
        for (replaceRaw in listOf(false, true)) accepted { f, execution, permit, context ->
            val h = f.protocol.begin(f.state.record, f.output, execution, permit, context)
            if (replaceRaw) f.io.file(f.rawFile, f.rawBytes) else f.io.file(f.final, f.outputBytes)
            assertThrows(Exception::class.java) { f.protocol.inspect(h, f.state.record, f.output, execution, permit, context) }
            assertFalse(f.state.indexVisible); assertFalse(f.reservations.hasAnyReservations())
        }
    }

    @Test fun namespaceAndLeafReplacementDuringLastCloseCannotMintPublication() {
        for (corruption in 0..2) accepted { f, execution, permit, context ->
            val h = f.protocol.begin(f.state.record, f.output, execution, permit, context)
            var changed = false
            f.io.after = { call -> if (!changed && call.operation == "close" && call.path == f.final) {
                changed = true
                when (corruption) {
                    0 -> f.io.file(f.final, byteArrayOf(9, 8, 7))
                    1 -> f.io.file(File(f.generation, "unexpected"), byteArrayOf(1))
                    else -> f.io.replaceDirectory(f.workspace)
                }
            } }
            assertThrows(Exception::class.java) { f.protocol.inspect(h, f.state.record, f.output, execution, permit, context) }
            assertTrue(changed); assertFalse(f.state.indexVisible); assertFalse(f.reservations.hasAnyReservations())
        }
    }

    @Test fun foreignAndClosedHandlesCannotInspectOrPublish() = accepted { f, execution, permit, context ->
        val forged = object : NormalizedCommitHandle { override fun close() {} }
        assertThrows(Exception::class.java) { f.protocol.inspect(forged, f.state.record, f.output, execution, permit, context) }
        val h = f.protocol.begin(f.state.record, f.output, execution, permit, context)
        val p = f.protocol.inspect(h, f.state.record, f.output, execution, permit, context)
        val forgedPublication = object : NormalizedPublicationHandle { override val receipt = p.receipt }
        assertFalse(f.protocol.publish(f.state.record.task.revision, f.success(p.receipt), forgedPublication))
        h.close()
        assertFalse(f.protocol.publish(f.state.record.task.revision, f.success(p.receipt), p))
        assertFalse(f.state.indexVisible)
    }

    @Test fun terminalIntentAndIndexDiscardOnlyOneCanonicalRoleAndNeverUnrelatedMedia() {
        for (catalogState in listOf(OwnedState.STAGING, OwnedState.INTENT, OwnedState.INDEXED)) {
            for (moved in listOf(false, true)) {
                if (catalogState == OwnedState.STAGING && moved) continue
                val f = Fixture(); f.terminal(catalogState, moved)
                val unrelated = File(File(f.root, "media"), "99999999-9999-4999-8999-999999999999.mp4")
                f.io.file(unrelated, byteArrayOf(9))
                f.cleanup()
                assertEquals(NormalizationLifecycle.CLEANED, f.state.workspace.lifecycle)
                assertEquals(OwnedState.DISCARDED, f.state.owned.state); assertNull(f.state.indexed)
                assertFalse(f.io.exists(f.primary)); assertFalse(f.io.exists(f.final)); assertFalse(f.io.exists(f.rawFile))
                assertTrue(f.io.exists(unrelated)); assertFalse(f.reservations.hasAnyReservations())
            }
        }
    }

    @Test fun typedCleanupHandlesEveryPreCommitTerminalWorkspaceShape() {
        for (lifecycle in listOf(NormalizationLifecycle.RAW_COMPLETE, NormalizationLifecycle.WRITING,
            NormalizationLifecycle.OUTPUT_READY, NormalizationLifecycle.ADOPT_INTENT, NormalizationLifecycle.ADOPTED)) {
            val f = Fixture(); f.terminal(OwnedState.STAGING, false)
            val early = lifecycle in setOf(NormalizationLifecycle.RAW_COMPLETE, NormalizationLifecycle.WRITING)
            f.state = f.state.copy(record = f.state.record.copy(verification = null,
                directNormalization = if (lifecycle in setOf(NormalizationLifecycle.ADOPT_INTENT, NormalizationLifecycle.ADOPTED)) f.proof else null),
                workspace = f.state.workspace.copy(lifecycle = lifecycle, output = if (early) null else f.canonical,
                    proofSha256 = if (early) null else f.proof.digest()))
            if (lifecycle != NormalizationLifecycle.ADOPTED) {
                f.io.remove(f.rawFile); f.io.file(f.primary, f.rawBytes)
                if (lifecycle != NormalizationLifecycle.RAW_COMPLETE)
                    f.io.file(File(f.generation, "output.mp4"), if (lifecycle == NormalizationLifecycle.WRITING) byteArrayOf(1, 2) else f.outputBytes)
            }
            f.cleanup()
            assertEquals(lifecycle.name, NormalizationLifecycle.CLEANED, f.state.workspace.lifecycle)
            assertFalse(f.io.exists(f.primary)); assertFalse(f.io.exists(f.workspace)); assertFalse(f.io.exists(f.final))
        }
        val moved = Fixture(); moved.terminal(OwnedState.STAGING, false)
        moved.state = moved.state.copy(record = moved.state.record.copy(verification = null),
            workspace = moved.state.workspace.copy(lifecycle = NormalizationLifecycle.ADOPT_INTENT))
        moved.io.remove(moved.primary); moved.io.file(File(moved.generation, "output.mp4"), moved.outputBytes)
        moved.cleanup(); assertEquals(NormalizationLifecycle.CLEANED, moved.state.workspace.lifecycle)
    }

    @Test fun cleanupRejectsDuplicateMissingAliasUnknownAndPublishedLayoutsBeforeWritingPlan() {
        val corruptions: List<(Fixture) -> Unit> = listOf(
            { it.io.file(it.final, it.outputBytes) },
            { it.io.remove(it.primary) },
            { it.io.alias(it.primary, it.final, reportedLinks = 1) },
            { it.io.file(File(it.generation, "unexpected"), byteArrayOf(1)) },
            { it.state = it.state.copy(indexVisible = true) },
            { it.state = it.state.copy(libraryLinked = true) },
        )
        for (corrupt in corruptions) {
            val f = Fixture(); f.terminal(OwnedState.INTENT, false); corrupt(f)
            assertThrows(Exception::class.java) { f.cleanup() }
            assertNull(f.state.workspace.cleanup); assertTrue(f.writes.isEmpty())
            assertEquals(0, f.io.calls.count { it.operation == "unlink" })
        }
    }

    @Test fun cleanupUnlinkThenThrowRetainsImmutablePlanAndReentryDeletesOnlyRemainingRoles() {
        val f = Fixture(); f.terminal(OwnedState.INDEXED, true)
        var thrown = false
        f.io.after = { if (it.operation == "unlink" && !thrown) { thrown = true; throw IOException("UNLINKED_THEN_THROW") } }
        assertThrows(Exception::class.java) { f.cleanup() }
        val plan = f.state.workspace.cleanup!!
        assertEquals(OwnedState.DISCARDED, f.state.owned.state); assertNull(f.state.indexed)
        f.io.after = {}; f.cleanup()
        assertEquals(plan, f.state.workspace.cleanup)
        assertEquals(NormalizationLifecycle.CLEANED, f.state.workspace.lifecycle)
        assertEquals(2, f.io.calls.count { it.operation == "unlink" })
    }

    @Test fun sameHashReplacementInodeCannotUsePersistedCleanupAuthority() {
        val f = Fixture(); f.terminal(OwnedState.INDEXED, true)
        f.afterWrite = { if (it == "plan") throw IOException("PERSISTED") }
        f.io.before = { if (it.operation == "unlink") throw IOException("BEFORE_UNLINK") }
        assertThrows(Exception::class.java) { f.cleanup() }
        val plan = f.state.workspace.cleanup!!
        f.io.before = {}; f.afterWrite = {}; f.io.file(f.final, f.outputBytes)
        assertThrows(Exception::class.java) { f.cleanup() }
        assertEquals(plan, f.state.workspace.cleanup)
        assertTrue(f.io.exists(f.final))
    }

    @Test fun replacementDuringDeleteHashCloseIsPreservedAndCannotReusePlan() {
        val f = Fixture(); f.terminal(OwnedState.INDEXED, true)
        var deletingFinal = false; var replaced = false
        f.beforeDelete = { if (it == NormalizedCommitRole.FINAL) deletingFinal = true }
        f.io.after = { call -> if (deletingFinal && !replaced && call.operation == "close" && call.path == f.final) {
            replaced = true; f.io.file(f.final, byteArrayOf(9, 8, 7))
        } }
        assertThrows(Exception::class.java) { f.cleanup() }
        assertTrue(replaced); assertArrayEquals(byteArrayOf(9, 8, 7), f.io.bytes(f.final))
        assertEquals(0, f.io.calls.count { it.operation == "unlink" && it.path == f.final })
        f.io.after = {}; f.beforeDelete = {}
        assertThrows(Exception::class.java) { f.cleanup() }
        assertTrue(f.io.exists(f.final)); assertEquals(NormalizationLifecycle.CLEANUP_PENDING, f.state.workspace.lifecycle)
    }

    @Test fun closeFailureRetainsExactDescriptorAndNeverRetriesIt() = accepted { f, execution, permit, context ->
        f.io.closeMode = { MemoryNormalizationDescriptorIo.CloseMode.INVALIDATES_THEN_THROWS }
        assertThrows(Exception::class.java) { f.protocol.begin(f.state.record, f.output, execution, permit, context) }
        assertTrue(f.reservations.hasAnyReservations()); assertTrue(f.reservations.resourcesUnreleased())
        assertEquals(1, f.io.calls.count { it.operation == "close" })
        assertTrue(f.writes.isEmpty())
    }

    @Test fun terminalBlockedHashAndUnlinkLeaveMonitorFreeAndLifetimeReserved() {
        for (operation in listOf("pread", "unlink", "fsync", "children", "close")) {
            val f = Fixture(); f.terminal(OwnedState.INTENT, true)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            var blocked = false; var failure: Throwable? = null
            f.io.before = { if (it.operation == operation && !blocked) {
                blocked = true; entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            } }
            val worker = thread { try { f.cleanup() } catch (t: Throwable) { failure = t } }
            assertTrue(operation, entered.await(5, TimeUnit.SECONDS))
            val acquired = CountDownLatch(1)
            val other = thread { synchronized(f.lock) { acquired.countDown() } }
            assertTrue(operation, acquired.await(1, TimeUnit.SECONDS))
            assertTrue(f.reservations.hasAnyReservations())
            f.cancel.cancel(); assertFalse(f.reservations.requiresProcessRecovery())
            release.countDown(); worker.join(5_000); other.join(5_000)
            assertFalse(worker.isAlive)
            // Cleanup uses its independent bounded IO token, so cancelling the original invocation
            // cannot invalidate the exact terminal deletion plan or close a foreign descriptor.
            if (failure != null) throw AssertionError(operation, failure)
            assertEquals(NormalizationLifecycle.CLEANED, f.state.workspace.lifecycle)
            assertFalse(f.reservations.hasAnyReservations())
        }
    }

    @Test fun versionOnePlanDoesNotAcquireNewTypedDeletionPermission() {
        val f = Fixture(); f.terminal(OwnedState.STAGING, false)
        f.state = f.state.copy(owned = f.state.owned.copy(state = OwnedState.DISCARDED), workspace = f.state.workspace.copy(
            revision = 6, lifecycle = NormalizationLifecycle.CLEANUP_PENDING, disposition = NormalizationDisposition.DISCARD_ONLY,
            cleanup = NormalizationCleanupPlan(f.canonical, f.raw, null)))
        assertThrows(Exception::class.java) { f.cleanup() }
        assertTrue(f.io.exists(f.primary)); assertTrue(f.io.exists(f.rawFile))
        assertEquals(0, f.io.calls.count { it.operation == "unlink" })
    }

    @Test fun blockedCommitIoCancellationIsMemoryOnlyAndCannotAdvanceItsLateResult() {
        for (operation in listOf("open", "pread", "fsync", "children", "childrenClose", "close")) accepted { f, execution, permit, context ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            var blocked = false; var helperFailure: Throwable? = null
            f.io.before = { call ->
                check(!Thread.holdsLock(f.lock))
                if (call.operation == operation && !blocked) {
                    blocked = true; entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val helper = thread {
                try {
                    check(entered.await(5, TimeUnit.SECONDS))
                    synchronized(f.lock) { assertTrue(f.reservations.hasAnyReservations()) }
                    f.cancel.cancel()
                    assertTrue(f.reservations.resourcesUnreleased())
                    assertFalse(f.reservations.requiresProcessRecovery())
                } catch (t: Throwable) { helperFailure = t }
                finally { release.countDown() }
            }
            assertThrows(Exception::class.java) { f.protocol.begin(f.state.record, f.output, execution, permit, context) }
            helper.join(5_000); helperFailure?.let { throw it }; assertFalse(helper.isAlive)
            assertFalse(f.reservations.hasAnyReservations())
            assertEquals(OwnedState.STAGING, f.state.owned.state); assertTrue(f.writes.isEmpty())
            assertTrue(f.io.handles.none { it.active })
        }
    }

    @Test fun closeWhileInspectionHashIsBlockedRetiresOnlyAfterActualWorkerRelease() = accepted { f, execution, permit, context ->
        val h = f.protocol.begin(f.state.record, f.output, execution, permit, context)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var blocked = false; var helperFailure: Throwable? = null
        f.io.before = { if (it.operation == "pread" && !blocked) {
            blocked = true; entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        } }
        val helper = thread {
            try {
                check(entered.await(5, TimeUnit.SECONDS)); h.close()
                assertTrue(f.reservations.hasAnyReservations()); assertTrue(f.reservations.resourcesUnreleased())
            } catch (t: Throwable) { helperFailure = t }
            finally { release.countDown() }
        }
        assertThrows(Exception::class.java) { f.protocol.inspect(h, f.state.record, f.output, execution, permit, context) }
        helper.join(5_000); helperFailure?.let { throw it }
        assertFalse(f.reservations.hasAnyReservations()); assertFalse(f.state.indexVisible)
        assertTrue(f.io.handles.none { it.active })
    }

    @Test fun conversionFailedAndCancelledOutputReadyDiscardOnlyExactRawAndOutput() {
        for (terminal in listOf(TaskState.FAILED, TaskState.CANCELLED)) {
            val f = Fixture(); f.conversionTerminal(terminal)
            val record = f.state.record
            val primary = f.io.node(f.primary); val output = f.io.node(f.outputFile)
            assertTrue(sqlPredicate("terminal", f.state)); assertFalse(sqlPredicate("active", f.state))
            f.cleanup()
            val plan = f.state.workspace.cleanup!!
            assertEquals(record, f.state.record)
            assertEquals(f.raw, plan.primary); assertEquals(f.canonical, plan.output)
            assertNull(plan.raw); assertNull(plan.commitOutput)
            assertEquals(NormalizationCleanupIdentity(primary.device, primary.inode), plan.identities!!.primary)
            assertEquals(NormalizationCleanupIdentity(output.device, output.inode), plan.identities.output)
            assertNull(plan.identities.raw); assertNull(plan.identities.commitOutput)
            assertEquals(OwnedState.DISCARDED, f.state.owned.state)
            assertEquals(NormalizationLifecycle.CLEANED, f.state.workspace.lifecycle)
            assertTrue(sqlPredicate("terminal", f.state)); assertFalse(sqlPredicate("active", f.state))
            assertFalse(f.io.exists(f.primary)); assertFalse(f.io.exists(f.outputFile)); assertFalse(f.io.exists(f.workspace))
            assertEquals(listOf("plan", "cleaned"), f.writes)
            assertFalse(f.state.indexVisible); assertFalse(f.state.libraryLinked); assertNull(f.state.indexed)
            assertNull(f.state.owned.evidence); assertEquals(0, f.moves); assertFalse(f.reservations.hasAnyReservations())
            f.cleanup(); assertEquals(listOf("plan", "cleaned"), f.writes)
        }
    }

    @Test fun conversionValidatorsRejectEveryPersistedBindingAndPublicationMismatchBeforeIo() {
        val corruptions: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "owner" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(
                key = f.key.copy(owner = f.owner.copy(taskId = "foreign")))) },
            "normalization" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(
                key = f.key.copy(normalizationId = "44444444-4444-4444-8444-444444444444"))) },
            "catalog owner" to { f -> f.state = f.state.copy(owned = f.state.owned.copy(taskId = "foreign")) },
            "catalog artifact" to { f -> f.state = f.state.copy(owned = f.state.owned.copy(
                artifact = StagingArtifact("44444444-4444-4444-8444-444444444444", "task"))) },
            "source transfer" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(sourceTransferSha256 = "e".repeat(64))) },
            "raw" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(raw = f.raw.copy(sha256 = "e".repeat(64)))) },
            "output" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(output = f.canonical.copy(sha256 = "e".repeat(64)))) },
            "proof digest" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(proofSha256 = "e".repeat(64))) },
            "proof raw" to { f -> f.state = f.state.copy(record = f.state.record.copy(
                directConversion = f.state.record.directConversion!!.copy(rawSha256 = "e".repeat(64)))) },
            "proof output" to { f -> f.state = f.state.copy(record = f.state.record.copy(
                directConversion = f.state.record.directConversion!!.copy(outputSha256 = "e".repeat(64)))) },
            "audio expectation" to { f -> f.state = f.state.copy(record = f.state.record.copy(request =
                f.state.record.request.copy(resource = f.state.record.request.resource.copy(expectedAudio = true)))) },
            "failure" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(failure = NormalizationFailure.CONTENT_MISMATCH)) },
            "disposition" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(disposition = NormalizationDisposition.DISCARD_ONLY)) },
            "raw complete" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(
                lifecycle = NormalizationLifecycle.RAW_COMPLETE, output = null, proofSha256 = null)) },
            "writing" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(
                lifecycle = NormalizationLifecycle.WRITING, output = null, proofSha256 = null)) },
            "recovery required" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(lifecycle = NormalizationLifecycle.RECOVERY_REQUIRED)) },
            "adopt intent" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(lifecycle = NormalizationLifecycle.ADOPT_INTENT)) },
            "adopted" to { f -> f.state = f.state.copy(workspace = f.state.workspace.copy(lifecycle = NormalizationLifecycle.ADOPTED)) },
            "intent" to { f -> f.state = f.state.copy(owned = f.state.owned.copy(state = OwnedState.INTENT)) },
            "indexed" to { f -> f.state = f.state.copy(owned = f.state.owned.copy(state = OwnedState.INDEXED)) },
            "discarded without plan" to { f -> f.state = f.state.copy(owned = f.state.owned.copy(state = OwnedState.DISCARDED)) },
            "catalog evidence" to { f -> f.state = f.state.copy(owned = f.state.owned.copy(evidence = f.conversionEvidence())) },
            "index" to { f -> f.state = f.state.copy(indexed = f.conversionReceipt()) },
            "visible" to { f -> f.state = f.state.copy(indexVisible = true) },
            "library association or tombstone" to { f -> f.state = f.state.copy(libraryLinked = true) },
            "verification" to { f -> f.state = f.state.copy(record = f.state.record.copy(verification = f.conversionEvidence())) },
            "receipt" to { f -> f.state = f.state.copy(record = f.state.record.copy(
                verification = f.conversionEvidence(), receipt = f.conversionReceipt())) },
            "succeeded" to { f -> f.state = f.state.copy(record = f.state.record.copy(
                task = f.state.record.task.copy(state = TaskState.SUCCEEDED),
                verification = f.conversionEvidence(), receipt = f.conversionReceipt())) },
            "running" to { f -> f.state = f.state.copy(record = f.state.record.copy(task = f.state.record.task.copy(state = TaskState.RUNNING))) },
            "interrupted" to { f -> f.state = f.state.copy(record = f.state.record.copy(task = f.state.record.task.copy(state = TaskState.INTERRUPTED))) },
            "verifying" to { f -> f.state = f.state.copy(record = f.state.record.copy(phase = SavePhase.VERIFYING)) },
            "committing" to { f -> f.state = f.state.copy(record = f.state.record.copy(phase = SavePhase.COMMITTING)) },
            "cleanup phase" to { f -> f.state = f.state.copy(record = f.state.record.copy(phase = SavePhase.CLEANUP)) },
        )
        for ((name, corrupt) in corruptions) {
            val f = Fixture(); f.conversionTerminal(); corrupt(f)
            val before = f.state; val calls = f.io.calls.size
            assertFalse(name, sqlPredicate("terminal", f.state)); assertFalse(name, sqlPredicate("active", f.state))
            assertThrows(name, Exception::class.java) { f.cleanup() }
            assertEquals(name, before, f.state); assertEquals(name, calls, f.io.calls.size)
            assertTrue(name, f.writes.isEmpty()); assertFalse(name, f.reservations.hasAnyReservations())
        }
    }

    @Test fun conversionRejectsUnknownMissingAliasRawAndFinalLayoutsWithoutPlanningDeletion() {
        val corruptions: List<(Fixture) -> Unit> = listOf(
            { it.io.file(it.rawFile, it.rawBytes) }, { it.io.file(it.final, it.outputBytes) },
            { it.io.remove(it.primary) }, { it.io.remove(it.outputFile) },
            { it.io.file(File(it.generation, "unexpected"), byteArrayOf(1)) },
            { it.io.file(File(it.workspace, "foreign-generation"), byteArrayOf(1)) },
            { it.io.alias(it.primary, it.outputFile, reportedLinks = 1) },
            { it.io.mutate(it.outputFile, byteArrayOf(1)) }, { it.io.mutate(it.primary, byteArrayOf(1)) },
        )
        for (corrupt in corruptions) {
            val f = Fixture(); f.conversionTerminal(); corrupt(f)
            val before = f.state
            assertThrows(Exception::class.java) { f.cleanup() }
            assertEquals(before, f.state); assertTrue(f.writes.isEmpty())
            assertEquals(0, f.io.calls.count { it.operation == "unlink" || it.operation == "rmdir" })
            assertFalse(f.reservations.hasAnyReservations())
        }
    }

    @Test fun conversionPlanPersistsBeforeDeleteAndEveryPartialRoleSubsetUsesOriginalIdentities() {
        for (remaining in 0..3) {
            val f = Fixture(); f.conversionTerminal(); f.pauseConversionAfterPlan()
            val record = f.state.record; val plan = f.state.workspace.cleanup!!
            assertTrue(sqlPredicate("terminal", f.state))
            if (remaining and 1 == 0) f.io.remove(f.primary)
            if (remaining and 2 == 0) f.io.remove(f.outputFile)
            f.cleanup()
            assertEquals(record, f.state.record); assertEquals(plan, f.state.workspace.cleanup)
            assertEquals(NormalizationLifecycle.CLEANED, f.state.workspace.lifecycle)
            assertEquals(listOf("plan", "cleaned"), f.writes)
            assertFalse(f.reservations.hasAnyReservations())
        }
    }

    @Test fun conversionPlanRejectsSameBytesReplacementAndAdditionalRawOrFinalOnRestart() {
        val corruptions: List<(Fixture) -> Unit> = listOf(
            { it.io.file(it.primary, it.rawBytes) }, { it.io.file(it.outputFile, it.outputBytes) },
            { it.io.file(it.rawFile, it.rawBytes) }, { it.io.file(it.final, it.outputBytes) },
            { it.io.file(File(it.generation, "unexpected"), byteArrayOf(1)) },
        )
        for (corrupt in corruptions) {
            val f = Fixture(); f.conversionTerminal(); f.pauseConversionAfterPlan(); corrupt(f)
            val before = f.state; val deletes = f.io.calls.count { it.operation == "unlink" }
            assertThrows(Exception::class.java) { f.cleanup() }
            assertEquals(before, f.state)
            assertEquals(deletes, f.io.calls.count { it.operation == "unlink" })
            assertEquals(listOf("plan"), f.writes); assertFalse(f.reservations.hasAnyReservations())
        }
    }

    @Test fun conversionNewOwnersReloadExactPlanAfterPartialUnlinkAndRejectReplacementInode() {
        for (replaceSurvivor in listOf(false, true)) {
            val f = Fixture(); f.conversionTerminal()
            var unlinked = false
            f.io.after = { call -> if (!unlinked && call.operation == "unlink" && call.path == f.primary) {
                unlinked = true; throw IOException("PROCESS_STOP_AFTER_PRIMARY_UNLINK")
            } }
            assertThrows(Exception::class.java) { f.cleanup() }
            assertTrue(unlinked); assertFalse(f.io.exists(f.primary)); assertTrue(f.io.exists(f.outputFile))
            assertFalse(f.reservations.hasAnyReservations()); assertTrue(f.io.handles.none { it.active })
            val record = f.state.record; val plan = f.state.workspace.cleanup!!
            f.io.after = {}
            if (replaceSurvivor) f.io.file(f.outputFile, f.outputBytes)
            val unlinks = f.io.calls.count { it.operation == "unlink" }
            if (replaceSurvivor) {
                assertThrows(Exception::class.java) { f.cleanupWithFreshOwners() }
                assertEquals(NormalizationLifecycle.CLEANUP_PENDING, f.state.workspace.lifecycle)
                assertTrue(f.io.exists(f.outputFile)); assertArrayEquals(f.outputBytes, f.io.bytes(f.outputFile))
                assertEquals(unlinks, f.io.calls.count { it.operation == "unlink" })
            } else {
                assertEquals(listOf("cleaned"), f.cleanupWithFreshOwners())
                assertEquals(NormalizationLifecycle.CLEANED, f.state.workspace.lifecycle)
                assertFalse(f.io.exists(f.outputFile)); assertFalse(f.io.exists(f.workspace))
                assertEquals(unlinks + 1, f.io.calls.count { it.operation == "unlink" })
            }
            assertEquals(record, f.state.record); assertEquals(plan, f.state.workspace.cleanup)
            assertEquals(listOf("plan"), f.writes) // No original journal or reservation is reused.
        }
    }

    @Test fun conversionBothValidatorsRejectLegacyWrongRoleAndAliasedInodePlans() {
        val corruptions: List<(NormalizationCleanupPlan) -> NormalizationCleanupPlan> = listOf(
            { it.copy(identities = null) },
            { NormalizationCleanupPlan(null, fRaw(), it.output,
                identities = NormalizationCleanupIdentities(null, it.identities!!.primary, it.identities.output, null)) },
            { it.copy(output = null, identities = it.identities!!.copy(output = null)) },
            { it.copy(identities = it.identities!!.copy(output = it.identities.primary)) },
            { it.copy(identities = it.identities!!.copy(output = it.identities.output!!.copy(device = 2))) },
            { NormalizationCleanupPlan(null, fRaw(), null,
                NormalizationCommitOutput("33333333-3333-4333-8333-333333333333", it.output!!),
                NormalizationCleanupIdentities(null, it.identities!!.primary, null, it.identities.output)) },
        )
        for (lifecycle in listOf(NormalizationLifecycle.CLEANUP_PENDING, NormalizationLifecycle.CLEANED)) {
            for (corrupt in corruptions) {
                val f = Fixture(); f.conversionTerminal(); f.pauseConversionAfterPlan()
                f.state = f.state.copy(workspace = f.state.workspace.copy(lifecycle = lifecycle, cleanup = corrupt(f.state.workspace.cleanup!!)))
                val before = f.state; val calls = f.io.calls.size
                assertFalse(sqlPredicate("terminal", f.state))
                assertThrows(Exception::class.java) { f.cleanup() }
                assertEquals(before, f.state); assertEquals(calls, f.io.calls.size)
            }
        }
    }

    @Test fun conversionEveryUnlinkSyncAndDirectoryRemovalBeforeAndAfterFaultResumesExactPlan() {
        val baseline = Fixture(); baseline.conversionTerminal(); baseline.cleanup()
        val mutations = setOf("unlink", "fsync", "rmdir")
        val count = baseline.io.calls.count { it.operation in mutations }
        assertTrue(count >= 8)
        for (after in listOf(false, true)) for (boundary in 0 until count) {
            val f = Fixture(); f.conversionTerminal()
            var seen = 0; var fired = false
            val fault: (MemoryNormalizationDescriptorIo.Call) -> Unit = { call ->
                check(!Thread.holdsLock(f.lock))
                if (call.operation in mutations && seen++ == boundary) { fired = true; throw IOException("CLEANUP_BOUNDARY") }
            }
            if (after) f.io.after = fault else f.io.before = fault
            assertThrows(Exception::class.java) { f.cleanup() }
            assertTrue(fired); val plan = f.state.workspace.cleanup!!; val record = f.state.record
            f.io.before = { check(!Thread.holdsLock(f.lock)) }; f.io.after = {}
            f.cleanup()
            assertEquals(record, f.state.record); assertEquals(plan, f.state.workspace.cleanup)
            assertEquals(NormalizationLifecycle.CLEANED, f.state.workspace.lifecycle)
            assertEquals(listOf("plan", "cleaned"), f.writes)
            assertTrue(f.io.handles.none { it.active }); assertFalse(f.reservations.hasAnyReservations())
        }
    }

    @Test fun conversionPlanAndCleanedCasBeforeFailureOrWriteThenThrowResolveWholeStateOnly() {
        for (step in listOf("plan", "cleaned")) for (after in listOf(false, true)) {
            val f = Fixture(); f.conversionTerminal(); val record = f.state.record
            val fault: (String) -> Unit = { if (it == step) throw IOException("CLEANUP_CAS_BOUNDARY") }
            if (after) f.afterWrite = fault else f.beforeWrite = fault
            if (after) f.cleanup() else assertThrows(Exception::class.java) { f.cleanup() }
            if (!after && step == "plan") {
                assertNull(f.state.workspace.cleanup)
                assertTrue(f.io.exists(f.primary)); assertTrue(f.io.exists(f.outputFile))
            }
            f.beforeWrite = {}; f.afterWrite = {}; f.cleanup()
            assertEquals(record, f.state.record); assertEquals(listOf("plan", "cleaned"), f.writes)
            assertEquals(NormalizationLifecycle.CLEANED, f.state.workspace.lifecycle)
        }
        val altered = Fixture(); altered.conversionTerminal()
        altered.afterWrite = { if (it == "plan") {
            altered.state = altered.state.copy(record = altered.state.record.copy(bytesTransferred = altered.state.record.bytesTransferred + 1))
            throw IOException("PARTIAL_PROPOSAL")
        } }
        assertThrows(Exception::class.java) { altered.cleanup() }
        assertEquals(listOf("plan"), altered.writes)
        assertTrue(altered.io.exists(altered.primary)); assertTrue(altered.io.exists(altered.outputFile))
        assertEquals(0, altered.io.calls.count { it.operation == "unlink" })
    }

    @Test fun conversionCleanedAcknowledgementRequiresAlreadyEmptyLayoutAndExactDiscardedCatalog() {
        for (extraFinal in listOf(false, true)) {
            val f = Fixture(); f.conversionTerminal(); f.cleanup()
            if (extraFinal) f.io.file(f.final, f.outputBytes) else f.io.file(f.primary, f.rawBytes)
            val before = f.state
            assertThrows(Exception::class.java) { f.cleanup() }
            assertEquals(before, f.state); assertEquals(listOf("plan", "cleaned"), f.writes)
            assertTrue(f.io.exists(if (extraFinal) f.final else f.primary))
        }
        val f = Fixture(); f.conversionTerminal(); f.pauseConversionAfterPlan()
        for (catalog in listOf(OwnedState.STAGING, OwnedState.INTENT, OwnedState.INDEXED)) {
            val before = f.state
            f.state = before.copy(owned = before.owned.copy(state = catalog))
            val calls = f.io.calls.size
            assertFalse(sqlPredicate("terminal", f.state))
            assertThrows(Exception::class.java) { f.cleanup() }
            assertEquals(calls, f.io.calls.size); f.state = before
        }
        f.state = f.state.copy(workspace = f.state.workspace.copy(disposition = NormalizationDisposition.CONTINUE))
        assertFalse(sqlPredicate("terminal", f.state))
        assertThrows(Exception::class.java) { f.cleanup() }
    }

    @Test fun conversionActualSqlValidatorRequiresExactV2PlanAndKeepsEveryActiveMutationClosed() {
        val f = Fixture(); f.conversionTerminal(); val initial = f.state
        f.pauseConversionAfterPlan(); val pending = f.state.workspace
        assertTrue(sqlPredicate("binding", initial)); assertTrue(sqlPredicate("terminal", initial))
        assertTrue(sqlCleanupProposal(initial, pending)); assertFalse(sqlCleanupProposal(initial, pending, discard = false))
        assertFalse(sqlCleanupProposal(initial, pending.copy(revision = pending.revision + 1)))
        assertFalse(sqlCleanupProposal(initial, pending.copy(cleanup = pending.cleanup!!.copy(identities = null))))
        assertFalse(sqlCleanupProposal(initial.copy(record = initial.record.copy(phase = SavePhase.COMMITTING)), pending))
        val sql = validatorJournal()
        assertFalse(sql.writeIntent(initial, initial.owned.copy(state = OwnedState.INTENT)))
        assertFalse(sql.writeIndex(initial))
        val verified = f.conversionEvidence()
        val succeeded = initial.record.copy(task = initial.record.task.copy(state = TaskState.SUCCEEDED),
            verification = verified, receipt = f.conversionReceipt())
        assertFalse(sql.publish(initial, succeeded))
        assertFalse(sql.planCleanup(initial, pending, discard = false))
        assertFalse(sql.finishCleanup(initial, pending.copy(lifecycle = NormalizationLifecycle.CLEANED)))
        // Valid transaction execution is deliberately not simulated here: these are actual adapter predicates only.
    }

    @Test fun conversionCannotConstructCheckpointOrMixedProofRecordAndCannotBeginMovCommit() {
        val f = Fixture(); f.conversionTerminal(); val record = f.state.record
        assertThrows(IllegalArgumentException::class.java) { record.copy(checkpoint = DownloadCheckpoint(
            f.artifact.artifactId, record.directConversion!!.sourceIdentity, f.raw.size, "\"fixture\"", f.raw.size)) }
        assertThrows(IllegalArgumentException::class.java) { record.copy(directNormalization = f.proof) }
        assertThrows(IllegalArgumentException::class.java) { record.directConversion!!.copy(policyRevision = 2) }
        accepted { fixture, execution, permit, context ->
            fixture.conversionTerminal()
            fixture.state = fixture.state.copy(record = fixture.state.record.copy(
                task = fixture.state.record.task.copy(state = TaskState.RUNNING), phase = SavePhase.COMMITTING))
            val before = fixture.state; val calls = fixture.io.calls.size
            assertTrue(sqlPredicate("binding", fixture.state)); assertFalse(sqlPredicate("active", fixture.state))
            assertThrows(Exception::class.java) {
                fixture.protocol.begin(fixture.state.record, fixture.output, execution, permit, context)
            }
            assertEquals(before, fixture.state); assertEquals(calls, fixture.io.calls.size)
            assertTrue(fixture.writes.isEmpty()); assertFalse(fixture.reservations.hasAnyReservations())
        }
    }

    @Test fun convertedFreshOwnersRecoverVerifiedStagingAndInvisibleCommitWithoutRecreatingEvidence() {
        for (catalog in listOf(OwnedState.STAGING, OwnedState.INTENT, OwnedState.INDEXED)) {
            for (moved in listOf(false, true)) {
                if ((catalog == OwnedState.STAGING && moved) || (catalog == OwnedState.INDEXED && !moved)) continue
                val previous = Fixture(); previous.conversionActive(catalog, moved)
                val original = previous.state
                val rawInode = previous.io.node(previous.rawFile).inode
                val outputInode = previous.io.node(if (moved) previous.final else previous.primary).inode
                val restarted = previous.fresh()
                assertNotSame(previous.protocol, restarted.protocol); assertNotSame(previous.reservations, restarted.reservations)
                assertNotSame(original.record, restarted.state.record); assertEquals(original, restarted.state)
                if (catalog == OwnedState.STAGING) { assertNotNull(restarted.state.record.verification); assertNull(restarted.state.owned.evidence) }
                assertTrue(sqlPredicate("active", restarted.state))
                acceptedFixture(restarted) { f, execution, permit, context ->
                    val output = f.state.record.selectedOutputDescriptor() as ArtifactOutputDescriptor.ConvertedDirect
                    val h = f.protocol.begin(f.state.record, output, execution, permit, context)
                    val afterBegin = f.io.calls.count { it.operation == "pread" }
                    val p = f.protocol.inspect(h, f.state.record, output, execution, permit, context)
                    assertTrue(f.io.calls.count { it.operation == "pread" } > afterBegin)
                    assertTrue(f.protocol.publish(f.state.record.task.revision, f.success(p.receipt), p))
                    assertEquals(original.record.directConversion, f.state.record.directConversion)
                    assertEquals(original.record.verification, f.state.record.verification)
                    assertEquals(original.workspace, f.state.workspace)
                    assertEquals(rawInode, f.io.node(f.rawFile).inode); assertEquals(outputInode, f.io.node(f.final).inode)
                    assertEquals(1, f.writes.count { it == "publish" }); assertTrue(f.state.indexVisible)
                }
                assertTrue(previous.writes.isEmpty())
            }
        }
    }

    @Test fun convertedVerifiedStagingRestartRehashesOriginalRawAndCanonicalBeforeFirstIntent() {
        for (mutateRaw in listOf(false, true)) {
            val previous = Fixture(); previous.conversionActive()
            previous.io.mutate(if (mutateRaw) previous.rawFile else previous.primary, byteArrayOf(9))
            val restarted = previous.fresh()
            acceptedFixture(restarted) { f, execution, permit, context ->
                val before = f.state
                assertThrows(Exception::class.java) { f.protocol.begin(f.state.record,
                    f.state.record.selectedOutputDescriptor() as ArtifactOutputDescriptor.ConvertedDirect, execution, permit, context) }
                assertEquals(before, f.state); assertTrue(f.writes.isEmpty()); assertEquals(0, f.moves)
            }
        }
    }

    @Test fun convertedFreshOwnerCancellationCleansEveryAdoptionAndInvisibleCommitLayout() {
        for ((audio, policy) in listOf(false to 1, true to 1, true to 2)) for (phase in listOf(SavePhase.NORMALIZING, SavePhase.ADOPTING, SavePhase.VERIFYING, SavePhase.COMMITTING)) {
            val layouts = when (phase) {
                SavePhase.NORMALIZING -> listOf("ready")
                SavePhase.ADOPTING -> listOf("ready", "intent-ready", "raw-moved", "adopted-unrecorded", "adopted")
                SavePhase.VERIFYING -> listOf("adopted")
                else -> listOf("staging", "intent-primary", "intent-final", "indexed")
            }
            for (layout in layouts) {
                val previous = Fixture()
                if (phase == SavePhase.COMMITTING) previous.conversionActive(when (layout) {
                    "staging" -> OwnedState.STAGING; "indexed" -> OwnedState.INDEXED; else -> OwnedState.INTENT
                }, layout in setOf("intent-final", "indexed"), audio = audio, policy = policy)
                else {
                    previous.conversionTerminal(audio = audio, policy = policy)
                    val lifecycle = when (layout) {
                        "ready" -> NormalizationLifecycle.OUTPUT_READY
                        "adopted" -> NormalizationLifecycle.ADOPTED
                        else -> NormalizationLifecycle.ADOPT_INTENT
                    }
                    previous.state = previous.state.copy(record = previous.state.record.copy(phase = phase),
                        workspace = previous.state.workspace.copy(lifecycle = lifecycle))
                    if (layout in setOf("raw-moved", "adopted-unrecorded", "adopted")) previous.io.move(previous.primary, previous.rawFile)
                    if (layout in setOf("adopted-unrecorded", "adopted")) previous.io.move(previous.outputFile, previous.primary)
                }
                previous.cancelConverted(); val record = previous.state.record
                val restarted = previous.fresh()
                assertTrue("audio=$audio policy=$policy $phase $layout", sqlPredicate("terminal", restarted.state))
                restarted.cleanup()
                assertEquals(record, restarted.state.record); assertTrue(previous.writes.isEmpty())
                assertEquals(NormalizationLifecycle.CLEANED, restarted.state.workspace.lifecycle)
                assertEquals(OwnedState.DISCARDED, restarted.state.owned.state); assertNull(restarted.state.indexed)
                assertFalse(restarted.io.exists(restarted.primary)); assertFalse(restarted.io.exists(restarted.rawFile))
                assertFalse(restarted.io.exists(restarted.outputFile)); assertFalse(restarted.io.exists(restarted.final))
            }
        }
    }

    @Test fun convertedCancellationAtIntentPromotionIndexAndPublicationLeavesFreshOwnerExactCleanup() {
        for (boundary in listOf("intent", "promotion", "index", "publication")) {
            var previous: Fixture? = null
            accepted { f, execution, permit, context ->
                previous = f; f.conversionActive()
                if (boundary == "promotion") f.afterMove = { f.cancelConverted() }
                else f.afterWrite = { if (it == boundary) f.cancelConverted() }
                val output = f.state.record.selectedOutputDescriptor() as ArtifactOutputDescriptor.ConvertedDirect
                if (boundary == "publication") {
                    val h = f.protocol.begin(f.state.record, output, execution, permit, context)
                    val p = f.protocol.inspect(h, f.state.record, output, execution, permit, context)
                    f.cancelConverted()
                    assertThrows(Exception::class.java) { f.protocol.publish(f.state.record.task.revision, f.success(p.receipt), p) }
                    h.close()
                } else assertThrows(Exception::class.java) { f.protocol.begin(f.state.record, output, execution, permit, context) }
                assertFalse(f.state.indexVisible); assertFalse(f.state.libraryLinked)
            }
            val restarted = previous!!.fresh(); restarted.cleanup()
            assertEquals(NormalizationLifecycle.CLEANED, restarted.state.workspace.lifecycle)
            assertNull(restarted.state.indexed); assertEquals(0, restarted.writes.count { it == "publish" })
        }
    }

    @Test fun convertedFinalRehashAndDescriptorIdentityCannotBeBypassed() = accepted { f, execution, permit, context ->
        f.conversionActive()
        val output = f.state.record.selectedOutputDescriptor() as ArtifactOutputDescriptor.ConvertedDirect
        val h = f.protocol.begin(f.state.record, output, execution, permit, context)
        assertThrows(Exception::class.java) { f.protocol.inspect(h, f.state.record, output.copy(), execution, permit, context) }
        f.io.file(f.final, f.outputBytes)
        assertThrows(Exception::class.java) { f.protocol.inspect(h, f.state.record, output, execution, permit, context) }
        assertFalse(f.state.indexVisible); assertEquals(0, f.writes.count { it == "publish" })
    }

    @Test fun convertedSuccessfulRawCleanupAfterLaterLibraryDeletionNeverInspectsOrRecreatesFinal() {
        val previous = Fixture(); previous.conversionActive(OwnedState.INDEXED, true)
        previous.state = previous.state.copy(record = previous.success(previous.state.owned.receipt()), indexVisible = true, libraryLinked = true)
        previous.io.remove(previous.final)
        previous.state = previous.state.copy(indexed = null, indexVisible = false, libraryLinked = false)
        val restarted = previous.fresh(); val start = restarted.io.calls.size
        restarted.cleanup()
        assertEquals(NormalizationLifecycle.CLEANED, restarted.state.workspace.lifecycle)
        assertFalse(restarted.io.exists(restarted.final)); assertFalse(restarted.io.exists(restarted.rawFile))
        assertTrue(restarted.io.calls.drop(start).none { it.path == restarted.final })
        assertEquals(0, restarted.writes.count { it == "publish" })
    }

    @Test fun convertedRawPlusOutputBudgetAndProducerOnlyRecordsCannotCreateCommitIntent() {
        accepted(budget = 700) { f, execution, permit, context ->
            f.conversionActive(); val calls = f.io.calls.size
            assertThrows(Exception::class.java) { f.protocol.begin(f.state.record,
                f.state.record.selectedOutputDescriptor() as ArtifactOutputDescriptor.ConvertedDirect, execution, permit, context) }
            assertEquals(calls, f.io.calls.size); assertTrue(f.writes.isEmpty()); assertEquals(0, f.moves)
        }
        accepted { f, execution, permit, context ->
            f.conversionActive(); f.state = f.state.copy(record = f.state.record.copy(verification = null))
            val calls = f.io.calls.size
            assertThrows(Exception::class.java) { f.protocol.begin(f.state.record,
                f.state.record.selectedOutputDescriptor() as ArtifactOutputDescriptor.ConvertedDirect, execution, permit, context) }
            assertEquals(calls, f.io.calls.size); assertTrue(f.writes.isEmpty()); assertFalse(f.state.indexVisible)
        }
    }

    @Test fun convertedSqlActiveRequiresIndependentEvidenceAndExactStagingCatalogThenIntentAndIndex() {
        val f = Fixture(); f.conversionActive(); val valid = f.state
        assertTrue(sqlPredicate("binding", valid)); assertTrue(sqlPredicate("active", valid))
        val invalid = listOf(valid.copy(record = valid.record.copy(verification = null)),
            valid.copy(owned = valid.owned.copy(evidence = valid.record.verification)),
            valid.copy(workspace = valid.workspace.copy(proofSha256 = "e".repeat(64))),
            valid.copy(indexVisible = true), valid.copy(libraryLinked = true),
            valid.copy(record = valid.record.copy(phase = SavePhase.VERIFYING)))
        for (state in invalid) assertFalse(sqlPredicate("active", state))
        for (catalog in listOf(OwnedState.INTENT, OwnedState.INDEXED)) {
            val owned = valid.owned.copy(state = catalog, evidence = valid.record.verification)
            val state = valid.copy(owned = owned, indexed = if (catalog == OwnedState.INDEXED) owned.receipt() else null)
            assertTrue(sqlPredicate("active", state)); assertFalse(sqlPredicate("active", state.copy(owned = owned.copy(evidence = null))))
        }
        // Only actual SQL adapter predicates run in this host test. SQLite atomicity/device execution remain separate.
    }

    @Test fun convertedCurrentAudioFreshOwnerCommitsWithIndependentEvidenceEvenWhenNotExpected() {
        for (expectedAudio in listOf(false, true)) for (catalog in listOf(OwnedState.STAGING, OwnedState.INTENT, OwnedState.INDEXED)) {
            val previous = Fixture(); previous.conversionActive(catalog, catalog != OwnedState.STAGING, audio = true, expectedAudio = expectedAudio)
            val original = previous.state; val restarted = previous.fresh()
            assertTrue(sqlPredicate("active", restarted.state))
            acceptedFixture(restarted) { f, execution, permit, context ->
                val output = f.state.record.selectedOutputDescriptor() as ArtifactOutputDescriptor.ConvertedDirect
                val h = f.protocol.begin(f.state.record, output, execution, permit, context)
                val publication = f.protocol.inspect(h, f.state.record, output, execution, permit, context)
                assertTrue(f.protocol.publish(f.state.record.task.revision, f.success(publication.receipt), publication))
                assertEquals(original.record.directConversion, f.state.record.directConversion)
                assertEquals(original.record.verification, f.state.record.verification)
                assertEquals(2, f.state.record.directConversion!!.policyRevision)
                assertEquals(3_600L, f.state.record.directConversion!!.expectation.audio!!.sourcePcmFrameCount)
                assertEquals(6_000L, f.state.record.directConversion!!.expectation.audio!!.encoderPcmFrameCount)
                assertTrue(f.io.calls.any { it.operation == "pread" && it.path == f.final })
                assertEquals(1, f.writes.count { it == "publish" }); assertTrue(f.state.libraryLinked)
            }
        }
    }
    @Test fun convertedHistoricalAudioFreshOwnerCannotCommitOrPublishButCanCancel() {
        for (catalog in listOf(OwnedState.STAGING, OwnedState.INTENT, OwnedState.INDEXED)) {
            val previous = Fixture(); previous.conversionActive(catalog, catalog != OwnedState.STAGING, audio = true, policy = 1)
            val restarted = previous.fresh()
            acceptedFixture(restarted) { f, execution, permit, context ->
                val before = f.state; val calls = f.io.calls.size
                assertFalse(sqlPredicate("active", before)); assertFalse(sqlPredicate("terminal", before))
                assertThrows(Exception::class.java) { f.protocol.begin(before.record,
                    before.record.selectedOutputDescriptor() as ArtifactOutputDescriptor.ConvertedDirect, execution, permit, context) }
                assertEquals(before, f.state); assertEquals(calls, f.io.calls.size)
                assertTrue(f.writes.isEmpty()); assertEquals(0, f.moves)
            }
            restarted.cancelConverted(); val cancelled = restarted.state.record; val cleanupOwner = restarted.fresh()
            assertTrue(sqlPredicate("terminal", cleanupOwner.state)); assertFalse(sqlPredicate("active", cleanupOwner.state))
            cleanupOwner.cleanup()
            assertEquals(cancelled, cleanupOwner.state.record); assertEquals(NormalizationLifecycle.CLEANED, cleanupOwner.state.workspace.lifecycle)
            assertEquals(OwnedState.DISCARDED, cleanupOwner.state.owned.state); assertNull(cleanupOwner.state.indexed)
            assertEquals(0, cleanupOwner.writes.count { it == "publish" })
        }
    }
    @Test fun convertedHistoricalAudioFreshOwnerTerminalCleanupKeepsExactBindingAndInodes() {
        for (terminal in listOf(TaskState.FAILED, TaskState.CANCELLED)) {
            val previous = Fixture(); previous.conversionTerminal(terminal, audio = true, policy = 1)
            val record = previous.state.record
            var stopped = false
            previous.io.after = { call -> if (!stopped && call.operation == "unlink" && call.path == previous.primary) {
                stopped = true; throw IOException("STOP_AFTER_HISTORICAL_PRIMARY_UNLINK")
            } }
            assertThrows(Exception::class.java) { previous.cleanup() }
            assertTrue(stopped); val plan = previous.state.workspace.cleanup!!; previous.io.after = {}
            val restarted = previous.fresh(); assertTrue(sqlPredicate("terminal", restarted.state))
            restarted.cleanup()
            assertEquals(record, restarted.state.record); assertEquals(plan, restarted.state.workspace.cleanup)
            assertEquals(NormalizationLifecycle.CLEANED, restarted.state.workspace.lifecycle)
            assertFalse(restarted.state.record.directConversion!!.isCurrentPolicy())
            assertEquals(listOf("cleaned"), restarted.writes)
        }
        val previous = Fixture(); previous.conversionActive(OwnedState.INDEXED, true, audio = true, policy = 1)
        previous.state = previous.state.copy(record = previous.success(previous.state.owned.receipt()), indexVisible = true, libraryLinked = true)
        val succeeded = previous.state.record; val restarted = previous.fresh(); val start = restarted.io.calls.size
        assertTrue(sqlPredicate("terminal", restarted.state)); assertFalse(sqlPredicate("active", restarted.state))
        restarted.cleanup()
        assertEquals(succeeded, restarted.state.record); assertEquals(succeeded.verification, restarted.state.owned.evidence)
        assertArrayEquals(restarted.outputBytes, restarted.io.bytes(restarted.final))
        assertTrue(restarted.io.calls.drop(start).none { it.path == restarted.final })
        assertFalse(restarted.state.record.directConversion!!.isCurrentPolicy()); assertEquals(0, restarted.writes.count { it == "publish" })
        val wrong = Fixture(); wrong.conversionTerminal(audio = true, policy = 1)
        wrong.state = wrong.state.copy(workspace = wrong.state.workspace.copy(proofSha256 = "e".repeat(64)))
        assertFalse(sqlPredicate("terminal", wrong.state)); val calls = wrong.io.calls.size
        assertThrows(Exception::class.java) { wrong.cleanup() }; assertEquals(calls, wrong.io.calls.size)
    }

    /** Executes the actual SQL adapter's memory predicates; no SQLite runtime or device claim. */
    private fun validatorJournal() = AndroidNormalizedCommitJournal(
        { error("DATABASE_NOT_PART_OF_VALIDATOR_TEST") }, Any(), { false }, { _, _ -> error("PUBLICATION_FORBIDDEN") })
    private fun sqlPredicate(name: String, state: NormalizedCommitState): Boolean =
        AndroidNormalizedCommitJournal::class.java.getDeclaredMethod(name, NormalizedCommitState::class.java)
            .apply { isAccessible = true }.invoke(validatorJournal(), state) as Boolean
    private fun sqlCleanupProposal(state: NormalizedCommitState, pending: NormalizationWorkspace, discard: Boolean = true): Boolean =
        AndroidNormalizedCommitJournal::class.java.getDeclaredMethod("cleanupProposal", NormalizedCommitState::class.java,
            NormalizationWorkspace::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }
            .invoke(validatorJournal(), state, pending, discard) as Boolean
    private fun fRaw() = normalizationTestFingerprint(ByteArray(512) { it.toByte() })

    private class Fixture(budget: Long = 4_000_000, audio: Boolean = false, policy: Int = if (audio) 2 else 1,
        sharedIo: MemoryNormalizationDescriptorIo? = null, restored: NormalizedCommitState? = null) {
        val root = File("/owned-normalized-commit")
        val lock = Any(); val reservations = NormalizationIoReservations(lock)
        val io = sharedIo ?: MemoryNormalizationDescriptorIo()
        val owner = NormalizationOwner("task", "11111111-1111-4111-8111-111111111111", "task")
        val key = NormalizationKey(owner, "22222222-2222-4222-8222-222222222222")
        val artifact = StagingArtifact(owner.artifactId, owner.commitId)
        val mediaId = "33333333-3333-4333-8333-333333333333"
        val primary = File(File(root, "staging"), owner.artifactId + ".part")
        val workspace = File(File(root, "staging"), owner.artifactId + ".normalization")
        val generation = File(workspace, key.normalizationId)
        val rawFile = File(generation, "raw.bin")
        val outputFile = File(generation, "output.mp4")
        val final = File(File(root, "media"), "$mediaId.mp4")
        val rawBytes = ByteArray(512) { it.toByte() }; val outputBytes = ByteArray(256) { (it + 13).toByte() }
        val raw = normalizationTestFingerprint(rawBytes); val canonical = normalizationTestFingerprint(outputBytes)
        val source = SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/fixture.mov"), expectedAudio = audio)
        val transfer = TransferResult(artifact.artifactId, raw.size, "video/quicktime", raw.size)
        val proof = DirectNormalizationProof(key.normalizationId, "task", source.sourceIdentity, artifact.artifactId,
            artifact.commitId, raw.size, raw.sha256, sourceTransferFingerprint(transfer), canonical.size, canonical.sha256, conservation(audio), policyRevision = policy)
        val output = ArtifactOutputDescriptor.NormalizedDirect(transfer, proof)
        val evidence = VerificationEvidence(artifact.artifactId, canonical.size, canonical.sha256, true, 1, if (audio) 1 else 0,
            proof.conservation.endpoint.minimumUs / 1_000, proof.conservation.width, proof.conservation.height, false,
            DecodePosition.entries.toSet(), audio, directNormalizationProof = DirectNormalizationVerificationProof(
                key.normalizationId, "task", source.sourceIdentity, artifact.artifactId, artifact.commitId, proof.digest(), proof.conservation,
                DirectVideoTailProof(proof.conservation.precedingVideoSync, proof.conservation.video.lastSample,
                    proof.conservation.video.lastSample.presentationTimeUs, true)))
        val cancel = CancellationSource()
        val context = OperationContext("operation", 100_000, cancel, resourceBudget = ResourceBudget(1, budget))
        val gate = DirectLocalProcessingGate(EpochClock { 0 }, reservations::resourcesUnreleased)
        val admission = SaveExecutionAdmission(maxArtifactBytes = 1_000_000)
        var state = restored ?: NormalizedCommitState(SaveRecord(SaveRequest("task", "key", source.asDurableReference()),
            TaskSnapshot("task", 12, TaskState.RUNNING, TaskMethod.DIRECT), "task", "operation", 100_000, 0,
            phase = SavePhase.COMMITTING, artifact = artifact, transfer = transfer, verification = evidence,
            bytesTransferred = raw.size, directNormalization = proof),
            NormalizationWorkspace(key, 5, NormalizationLifecycle.ADOPTED, NormalizationDisposition.CONTINUE,
                raw, proof.sourceTransferSha256, canonical, proof.digest()), OwnedMedia("task", artifact, mediaId, OwnedState.STAGING))
        val writes = mutableListOf<String>(); var beforeWrite: (String) -> Unit = {}; var afterWrite: (String) -> Unit = {}; var afterMove: () -> Unit = {}; var moves = 0
        val journal = object : NormalizedCommitJournal {
            override fun read(owner: NormalizationOwner) = state.takeIf { it.workspace.key.owner == owner }
            fun write(expected: NormalizedCommitState, next: NormalizedCommitState, step: String): Boolean {
                check(Thread.holdsLock(lock)); if (state != expected) return false
                beforeWrite(step)
                state = next; writes += step; afterWrite(step); return true
            }
            override fun writeIntent(expected: NormalizedCommitState, intent: OwnedMedia) = write(expected, expected.copy(owned = intent), "intent")
            override fun writeIndex(expected: NormalizedCommitState) = write(expected,
                expected.copy(owned = expected.owned.copy(state = OwnedState.INDEXED), indexed = expected.owned.receipt()), "index")
            override fun publish(expected: NormalizedCommitState, succeeded: SaveRecord) = write(expected,
                expected.copy(record = succeeded, indexVisible = true, libraryLinked = true), "publish")
            override fun planCleanup(expected: NormalizedCommitState, pending: NormalizationWorkspace, discard: Boolean) = write(expected,
                expected.copy(workspace = pending, owned = if (discard) expected.owned.copy(state = OwnedState.DISCARDED) else expected.owned,
                    indexed = if (discard) null else expected.indexed), "plan")
            override fun finishCleanup(expected: NormalizedCommitState, cleaned: NormalizationWorkspace) = write(expected, expected.copy(workspace = cleaned), "cleaned")
        }
        var beforeDelete: (NormalizedCommitRole) -> Unit = {}
        val actualFiles = AndroidNormalizedCommitFiles(root, reservations, object : DurableFileOps {
            override fun syncDirectory(directory: File) = error("DESCRIPTOR_SYNC_REQUIRED")
            override fun promoteNoReplace(source: File, destination: File) { check(!Thread.holdsLock(lock)); moves++; io.move(source, destination); afterMove() }
            override fun replaceOwnedStaging(source: File, destination: File) = error("NO_REPLACEMENT")
        }, io)
        val files = object : NormalizedCommitFiles by actualFiles {
            override fun delete(key: NormalizationKey, mediaId: String, role: NormalizedCommitRole,
                expected: NormalizedFileObservation, token: NormalizationIoReservations.Token) {
                beforeDelete(role); actualFiles.delete(key, mediaId, role, expected, token)
            }
        }
        val protocol = NormalizedCommitProtocol(journal, files, reservations, lock, gate, { false }, {}, ::checkOperation,
            { execution, record, _, op -> execution.assertAdmission(admission); execution.requireLive(op); check(execution.taskId == record.task.taskId) })
        init {
            if (restored == null) { io.directory(File(root, "media")); io.file(primary, outputBytes); io.file(rawFile, rawBytes) }
            io.before = { check(!Thread.holdsLock(lock)) { "IO_UNDER_STORAGE_LOCK" } }
        }
        private fun conservation(audio: Boolean): DirectRemuxConservation {
            val c = normalizationTestConservation()
            if (!audio) return c
            val clock = DirectMovClock.audio(48_000, 3)
            val hash = "b".repeat(64)
            val track = DirectTrackConservation(DirectSampleKind.AAC_LC, hash, 3, 24, hash, hash, hash, 0,
                DirectSampleIdentity(2, clock.presentationTimeUs(2), 8, hash, 1), clock.endpoint)
            return c.copy(audio = track, audioSampleRate = 48_000, audioChannelCount = 2)
        }
        fun checkOperation(op: OperationContext) { if (op.cancelToken.isCancelled) throw SaveFailure(SaveError.CANCELLED) }
        fun cleanupContext() = OperationContext("cleanup", 100_000, CancellationSource(), resourceBudget = ResourceBudget(1, 4_000_000))
        fun success(receipt: MediaCommitReceipt) = state.record.copy(task = state.record.task.copy(
            state = TaskState.SUCCEEDED, revision = state.record.task.revision + 1, progressPercent = 100), phase = SavePhase.CLEANUP, receipt = receipt)
        fun terminal(owned: OwnedState, moved: Boolean) {
            val row = state.owned.copy(state = owned, evidence = if (owned == OwnedState.STAGING) null else evidence)
            state = state.copy(record = state.record.copy(task = state.record.task.copy(state = TaskState.CANCELLED)), owned = row,
                indexed = if (owned == OwnedState.INDEXED) row.receipt() else null)
            if (moved) io.move(primary, final)
        }
        /** Durable consumer fixture only. It is not an actual successful conversion producer/native run. */
        fun conversionTerminal(terminal: TaskState = TaskState.CANCELLED, audio: Boolean = false,
            policy: Int = if (audio) 2 else 1, expectedAudio: Boolean = audio) {
            val source = SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/fixture.webm"), expectedAudio = expectedAudio)
            val transfer = TransferResult(artifact.artifactId, raw.size, "video/webm", raw.size)
            val sound = if (audio) DirectConversionAudioExpectation(0, if (policy == 2) 75_000 else 125_000,
                if (policy == 2) 3_600 else 6_000, 6_000, 2, 0, 0, 0) else null
            val expectation = DirectConversionExpectation(DirectConversionVideoExpectation(16, 16, 24, 125_000, 3, 0, 83_333, 0), sound)
            val fact = if (audio) true else null
            val proof = DirectConversionProof(key.normalizationId, owner.taskId, source.sourceIdentity, artifact.artifactId,
                artifact.commitId, raw.size, raw.sha256, sourceTransferFingerprint(transfer), canonical.size, canonical.sha256,
                expectation, DirectConversionEncodingSettings.forExpectation(expectation),
                DirectConversionCompletionFacts(true, true, true, true, true, true, fact, fact, fact, fact, fact, fact), policyRevision = policy)
            state = state.copy(record = state.record.copy(request = SaveRequest("task", "key", source.asDurableReference()),
                task = state.record.task.copy(state = terminal), phase = SavePhase.NORMALIZING, transfer = transfer,
                directNormalization = null, directConversion = proof, verification = null, receipt = null, checkpoint = null),
                workspace = state.workspace.copy(lifecycle = NormalizationLifecycle.OUTPUT_READY,
                    sourceTransferSha256 = proof.sourceTransferSha256, proofSha256 = proof.digest()),
                owned = state.owned.copy(state = OwnedState.STAGING, evidence = null), indexed = null, indexVisible = false, libraryLinked = false)
            io.remove(rawFile); io.file(primary, rawBytes); io.file(outputFile, outputBytes)
        }
        fun conversionActive(catalog: OwnedState = OwnedState.STAGING, moved: Boolean = catalog == OwnedState.INDEXED,
            audio: Boolean = false, policy: Int = if (audio) 2 else 1, expectedAudio: Boolean = audio) {
            conversionTerminal(TaskState.RUNNING, audio, policy, expectedAudio)
            val evidence = conversionEvidence()
            val owned = state.owned.copy(state = catalog, evidence = if (catalog == OwnedState.STAGING) null else evidence)
            state = state.copy(record = state.record.copy(phase = SavePhase.COMMITTING, verification = evidence),
                workspace = state.workspace.copy(lifecycle = NormalizationLifecycle.ADOPTED), owned = owned,
                indexed = if (catalog == OwnedState.INDEXED) owned.receipt() else null)
            io.move(primary, rawFile); io.move(outputFile, if (moved) final else primary)
        }
        fun fresh(): Fixture {
            check(!reservations.hasAnyReservations() && io.handles.none { it.active })
            val durable = state.copy(record = SaveRecordCodec.decode(SaveRecordCodec.encode(state.record)),
                workspace = NormalizationWorkspaceCodec.decode(NormalizationWorkspaceCodec.encode(state.workspace)),
                owned = state.owned.copy(artifact = state.owned.artifact.copy(), evidence = state.owned.evidence?.let {
                    SaveRecordCodec.evidence(SaveRecordCodec.evidenceBytes(it)) }), indexed = state.indexed?.copy())
            return Fixture(sharedIo = io, restored = durable)
        }
        fun cancelConverted() { state = state.copy(record = state.record.copy(task = state.record.task.copy(state = TaskState.CANCELLED))) }
        fun conversionEvidence(): VerificationEvidence {
            val p = state.record.directConversion!!; val v = p.expectation.video
            val checked = DirectConversionVerificationProof(p.normalizationId, p.taskId, p.sourceIdentity, p.artifactId,
                p.commitId, p.digest(), p.outputSizeBytes, p.outputSha256, v.displayedFrameCount, v.firstDisplayedTimeUs,
                v.lastDisplayedTimeUs, v.sourceDurationUs, v.maximumFrameDeviationUs,
                p.expectation.audio?.let { DirectConversionAudioVerification(0, it.encoderDurationUs, it.encoderPcmFrameCount,
                    it.sampleRate, it.channelCount, true, true) }, true, true, true, policyRevision = p.policyRevision)
            return VerificationEvidence(artifact.artifactId, canonical.size, canonical.sha256, true, 1,
                if (p.expectation.audio == null) 0 else 1, 125, 16, 16,
                false, DecodePosition.entries.toSet(), p.expectation.audio != null, directConversionProof = checked)
        }
        fun conversionReceipt() = MediaCommitReceipt(artifact.commitId, artifact.artifactId, mediaId, canonical.size, canonical.sha256)
        fun pauseConversionAfterPlan() {
            beforeDelete = { throw IOException("AFTER_PLAN_BEFORE_UNLINK") }
            assertThrows(Exception::class.java) { cleanup() }
            beforeDelete = {}
            assertEquals(NormalizationLifecycle.CLEANUP_PENDING, state.workspace.lifecycle)
            assertEquals(OwnedState.DISCARDED, state.owned.state); assertEquals(listOf("plan"), writes)
        }
        fun cleanupWithFreshOwners(): List<String> {
            // Model a new storage owner: share only durable values and filesystem contents.
            // Round-trip both durable blobs so no previous workspace/record object identity survives.
            state = state.copy(record = SaveRecordCodec.decode(SaveRecordCodec.encode(state.record)),
                workspace = NormalizationWorkspaceCodec.decode(NormalizationWorkspaceCodec.encode(state.workspace)),
                owned = state.owned.copy(artifact = state.owned.artifact.copy()), indexed = state.indexed?.copy())
            val newLock = Any(); val newReservations = NormalizationIoReservations(newLock)
            val newGate = DirectLocalProcessingGate(EpochClock { 0 }, newReservations::resourcesUnreleased)
            val newWrites = mutableListOf<String>()
            val newJournal = object : NormalizedCommitJournal {
                override fun read(owner: NormalizationOwner) = state.takeIf { it.workspace.key.owner == owner }
                override fun writeIntent(expected: NormalizedCommitState, intent: OwnedMedia): Boolean = error("NO_RESTART_PUBLICATION")
                override fun writeIndex(expected: NormalizedCommitState): Boolean = error("NO_RESTART_PUBLICATION")
                override fun publish(expected: NormalizedCommitState, succeeded: SaveRecord): Boolean = error("NO_RESTART_PUBLICATION")
                override fun planCleanup(expected: NormalizedCommitState, pending: NormalizationWorkspace, discard: Boolean): Boolean =
                    error("PERSISTED_PLAN_MUST_NOT_BE_REBUILT")
                override fun finishCleanup(expected: NormalizedCommitState, cleaned: NormalizationWorkspace): Boolean {
                    check(Thread.holdsLock(newLock)); if (state != expected) return false
                    state = expected.copy(workspace = cleaned); newWrites += "cleaned"; return true
                }
            }
            val newFiles = AndroidNormalizedCommitFiles(root, newReservations, object : DurableFileOps {
                override fun syncDirectory(directory: File) = error("DESCRIPTOR_SYNC_REQUIRED")
                override fun promoteNoReplace(source: File, destination: File) = error("NO_RESTART_PROMOTION")
                override fun replaceOwnedStaging(source: File, destination: File) = error("NO_RESTART_REPLACEMENT")
            }, io)
            val newProtocol = NormalizedCommitProtocol(newJournal, newFiles, newReservations, newLock, newGate,
                { false }, {}, { op -> if (op.cancelToken.isCancelled) throw SaveFailure(SaveError.CANCELLED) },
                { _, _, _, _ -> error("NO_RESTART_ACTIVE_COMMIT") })
            val invocation = OperationContext("restart-invocation", 100_000, CancellationSource(), resourceBudget = ResourceBudget(1, 4_000_000))
            val cleanup = OperationContext("restart-cleanup", 100_000, CancellationSource(), resourceBudget = ResourceBudget(1, 4_000_000))
            io.before = { check(!Thread.holdsLock(newLock)) { "IO_UNDER_RESTART_LOCK" } }
            try {
                newGate.withBoundPermit(Any(), invocation) { newProtocol.cleanup(state.record, it, invocation, cleanup) }
                return newWrites
            } finally {
                assertFalse(newReservations.hasAnyReservations()); assertTrue(io.handles.none { it.active })
            }
        }
        fun cleanup() = gate.withBoundPermit(this, context) { protocol.cleanup(state.record, it, context, cleanupContext()) }
    }

    /** Obtain authority only inside the production Coordinator's real entered execution. */
    private fun accepted(budget: Long = 4_000_000, audio: Boolean = false, policy: Int = if (audio) 2 else 1,
        block: (Fixture, AcceptedDirectAttempt, DirectLocalProcessingGate.LocalPermit, OperationContext) -> Unit) {
        acceptedFixture(Fixture(budget, audio, policy), block)
    }
    private fun acceptedFixture(f: Fixture,
        block: (Fixture, AcceptedDirectAttempt, DirectLocalProcessingGate.LocalPermit, OperationContext) -> Unit) {
        var captured = false; var failure: Throwable? = null
        var coreRecord: SaveRecord? = null
        val journal = object : ControlledDirectJournal {
            override fun findByIdempotencyKey(key: String) = coreRecord?.takeIf { it.request.idempotencyKey == key }
            override fun read(taskId: String) = coreRecord?.takeIf { it.task.taskId == taskId }
            override fun insert(record: SaveRecord, maxEntries: Int) = record.also { coreRecord = it }
            override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean {
                if (coreRecord?.task?.revision != expectedRevision) return false; coreRecord = next; return true
            }
            override fun listUnfinished(limit: Int) = listOfNotNull(coreRecord).filter { !it.task.state.isTerminal }
            override fun listRecent(limit: Int) = listOfNotNull(coreRecord)
        }
        val controlled = object : ControlledDirectStorage {
            override fun inspectBeforeNetwork(taskId: String, artifact: StagingArtifact?, commitId: String) = DirectWorkspacePresence.ABSENT
            override fun requiresProcessRecovery() = false
            override fun onTransferReleased(execution: AcceptedDirectAttempt) {}
            override fun selectRaw(record: SaveRecord, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): DirectRawSelection {
                captured = true
                try { block(f, execution, permit, context) } catch (t: Throwable) { failure = t }
                throw SaveFailure(SaveError.CANCELLED)
            }
            override fun normalize(selection: DirectRawSelection, record: SaveRecord, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, limits: SaveLimits): DirectProofCompletion = error("UNUSED")
            override fun inspectRecovery(record: SaveRecord, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): DirectRecoveryAction = error("UNUSED")
            override fun adopt(record: SaveRecord, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext) = error("UNUSED")
            override fun verifyTransferred(record: SaveRecord, output: ArtifactOutputDescriptor.Transferred, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, verifier: ArtifactVerifier): VerificationEvidence = error("UNUSED")
            override fun verifyNormalized(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, verifier: ArtifactVerifier): DirectVerificationCompletion = error("UNUSED")
            override fun beginNormalizedCommit(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedCommitHandle = error("UNUSED")
            override fun inspectNormalizedCommit(handle: NormalizedCommitHandle, record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedPublicationHandle = error("UNUSED")
            override fun cleanupTerminal(record: SaveRecord, permit: DirectLocalProcessingGate.LocalPermit, invocationContext: OperationContext, ioContext: OperationContext) {}
        }
        val transfer = object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?, context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                progress.onBytes(f.raw.size); return f.transfer
            }
        }
        val store = object : PrivateMediaStore {
            override fun prepare(taskId: String, commitId: String, context: OperationContext) = f.artifact
            override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt = error("UNUSED")
            override fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection? = error("UNUSED")
            override fun cleanup(artifact: StagingArtifact, committed: Boolean) {}
        }
        val coordinator = SaveCoordinator(journal, transfer, ArtifactVerifier { _, _, _ -> error("UNUSED") }, store,
            EpochClock { 0 }, SaveLimits(1_000_000, 4_000_000), admission = f.admission,
            controlledDirect = ControlledDirectPipeline(f.gate, controlled, true))
        val claim = f.admission.tryAcquireDirect()!!
        assertTrue(coordinator.submit(SaveRequest("task", "key", f.source), f.context, claim) is SaveSubmission.Accepted)
        coordinator.execute("task", claim)
        failure?.let { throw it }
        assertTrue("Real accepted execution reached the controlled selector", captured)
    }
}
