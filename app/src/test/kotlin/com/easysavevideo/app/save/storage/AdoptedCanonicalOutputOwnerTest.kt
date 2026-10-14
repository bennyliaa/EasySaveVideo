package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Host-only real RAF checks with deliberately synthetic metadata and execution/batch grants. */
class AdoptedCanonicalOutputOwnerTest {
    @Test fun exactAdoptedSnapshotReturnsOriginalDescriptorAndOneGenuineReadOnlyRaf() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            val lease = f.owner.acquire(f.artifact, f.output, f.context)
            assertSame(f.artifact, lease.artifact)
            assertSame(f.output, lease.output)
            assertSame(f.io.handles.single(), lease.readOnlyInput)
            assertTrue(f.owner.hasReservation(f.artifact.artifactId))
            assertTrue(f.owner.hasAnyLeases())
            assertFalse(f.owner.requiresProcessRecovery())
            val input = lease.readOnlyInput
            val descriptor = input.fd
            val bytes = ByteArray(f.outputBytes.size)
            input.readFully(bytes)
            assertArrayEquals(f.outputBytes, bytes)
            assertThrows(IOException::class.java) { input.write(1) }
            assertThrows(IOException::class.java) { input.setLength(0) }
            input.seek(0)
            lease.revalidate(); lease.revalidate()
            assertSame(input, lease.readOnlyInput)
            assertSame(descriptor, lease.readOnlyInput.fd)
            assertEquals(1, f.io.count("open"))
            assertEquals(f.primary, f.io.calls.single { it.operation == "open" }.path)
            lease.close(); lease.close()
            assertEquals(1, f.io.count("close"))
            assertTrue(lease.releaseConfirmed)
            assertFalse(descriptor.valid())
            assertFalse(f.owner.hasAnyLeases())
            assertFalse(f.owner.requiresProcessRecovery())
            assertThrows(SaveFailure::class.java) { lease.readOnlyInput }
            assertThrows(SaveFailure::class.java) { lease.revalidate() }
            assertEquals(1, f.io.count("open"))
        } }
    }

    @Test fun localPermitWithoutCompleteBindingOpensNothing() {
        AdoptedCanonicalTestFixture().use { f ->
            f.gate.withBoundPermit(Any(), f.context) {
                assertThrows(SaveFailure::class.java) { f.owner.acquire(f.artifact, f.output, f.context) }
            }
            assertTrue(f.io.calls.isEmpty())
            assertFalse(f.owner.hasAnyLeases())
            assertFalse(f.owner.requiresProcessRecovery())
        }
    }

    @Test fun revokedBindingAndClosedStorageRejectBeforeAnyFilesystemWork() {
        for (revoke in listOf(true, false)) AdoptedCanonicalTestFixture().use { f -> f.bound { binding ->
            if (revoke) binding.revoke() else f.storageClosed = true
            assertThrows(SaveFailure::class.java) { f.owner.acquire(f.artifact, f.output, f.context) }
            assertTrue(f.io.calls.isEmpty())
            assertFalse(f.owner.hasAnyLeases())
            assertFalse(f.owner.requiresProcessRecovery())
        } }
    }

    @Test fun descriptorAndContextCopiesDoNotConsumeExactBinding() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            assertThrows(SaveFailure::class.java) { f.owner.acquire(f.artifact.copy(), f.output, f.context) }
            assertThrows(SaveFailure::class.java) { f.owner.acquire(f.artifact, f.output.copy(), f.context) }
            assertThrows(SaveFailure::class.java) { f.owner.acquire(f.artifact, f.output, f.context.copy()) }
            assertTrue(f.io.calls.isEmpty())
            val lease = f.owner.acquire(f.artifact, f.output, f.context)
            assertThrows(SaveFailure::class.java) { f.owner.acquire(f.artifact, f.output, f.context) }
            assertEquals(1, f.io.count("open"))
            lease.close()
        } }
    }

    @Test fun everyNonVerifyingPhaseRejectsBeforeOpening() {
        for (phase in SavePhase.entries.filter { it != SavePhase.VERIFYING } + listOf(null)) {
            AdoptedCanonicalTestFixture().use { f ->
                f.record = f.record!!.copy(phase = phase)
                assertPreIoRejection(f)
            }
        }
    }

    @Test fun everyNonRunningTaskIncludingSucceededRejectsWithoutRevival() {
        for (state in TaskState.entries.filter { it != TaskState.RUNNING }) {
            AdoptedCanonicalTestFixture().use { f ->
                val before = f.record!!
                f.record = when (state) {
                    TaskState.WAITING_USER -> before.copy(task = before.task.copy(state = state,
                        waitingUser = UserWait(100_000, ResumeAction.REVIEW_RECOVERY)), waitReason = SaveWaitReason.RECOVERY)
                    TaskState.SUCCEEDED -> succeeded(f)
                    else -> before.copy(task = before.task.copy(state = state))
                }
                assertPreIoRejection(f)
            }
        }
    }

    @Test fun exactRecordTransferProofAndCallerContractAreRequired() {
        val changes: List<(AdoptedCanonicalTestFixture) -> Unit> = listOf(
            { it.record = null },
            { it.record = it.record!!.let { r -> r.copy(task = r.task.copy(revision = r.task.revision + 1)) } },
            { it.record = it.record!!.copy(operationId = "replacement") },
            { it.record = it.record!!.copy(retryBudget = 1, attempt = 1) },
            { it.record = it.record!!.copy(retryBudget = 1) },
            { it.record = it.record!!.copy(deadlineEpochMillis = it.context.deadlineEpochMillis - 1) },
            { it.record = it.record!!.copy(directNormalization = null) },
            { it.record = it.record!!.copy(transfer = null, directNormalization = null) },
            { it.record = it.record!!.copy(transfer = it.transfer.copy(responseMimeType = "video/mp4"), directNormalization = null) },
            { it.record = it.record!!.copy(transfer = it.transfer.copy(expectedTotalBytes = null), directNormalization = null) },
            { it.record = it.record!!.copy(transfer = it.transfer.copy(bytesOnDisk = it.raw.size - 1, expectedTotalBytes = it.raw.size - 1), directNormalization = null) },
            { it.record = it.record!!.copy(directNormalization = it.proof.copy(rawSha256 = "b".repeat(64))) },
            { it.record = it.record!!.copy(directNormalization = it.proof.copy(outputSha256 = "b".repeat(64))) },
            { it.record = it.record!!.copy(directNormalization = it.proof.copy(conservation = it.proof.conservation.copy(width = 32))) },
            { it.record = it.record!!.let { r -> r.copy(request = r.request.copy(resource = r.request.resource.copy(expectedAudio = true))) } },
            { it.recordFailure = true }, { it.priorHandles = true },
        )
        changes.forEachIndexed { index, change -> AdoptedCanonicalTestFixture().use { f ->
            change(f)
            assertPreIoRejection(f, "record case $index")
        } }
    }

    @Test fun catalogMustBeExactStagingTaskArtifactCommitWithNoPublishedEvidence() {
        val changes: List<(AdoptedCanonicalTestFixture) -> Unit> = listOf(
            { it.catalog = null }, { it.catalogFailure = true },
            { it.catalog = it.catalog!!.copy(taskId = "other") },
            { it.catalog = it.catalog!!.copy(artifact = it.artifact.copy(commitId = "other")) },
            { it.catalog = it.catalog!!.copy(artifact = it.artifact.copy(artifactId = "33333333-3333-4333-8333-333333333333")) },
            { it.catalog = it.catalog!!.copy(state = OwnedState.INTENT) },
            { it.catalog = it.catalog!!.copy(state = OwnedState.INDEXED) },
            { it.catalog = it.catalog!!.copy(state = OwnedState.DISCARDED) },
            { it.catalog = it.catalog!!.copy(evidence = succeeded(it).verification) },
        )
        changes.forEachIndexed { index, change -> AdoptedCanonicalTestFixture().use { f ->
            change(f); assertPreIoRejection(f, "catalog case $index")
        } }
    }

    @Test fun everyNonAdoptedLedgerLifecycleAndAbsentUnreadableRowsReject() {
        for (state in NormalizationLifecycle.entries.filter { it != NormalizationLifecycle.ADOPTED }) {
            AdoptedCanonicalTestFixture().use { f ->
                f.ledger.row = f.ledger.row!!.copy(lifecycle = state,
                    output = if (state in setOf(NormalizationLifecycle.RAW_COMPLETE, NormalizationLifecycle.WRITING)) null else f.canonical,
                    proofSha256 = if (state in setOf(NormalizationLifecycle.RAW_COMPLETE, NormalizationLifecycle.WRITING)) null else f.proof.digest(),
                    cleanup = if (state in setOf(NormalizationLifecycle.CLEANUP_PENDING, NormalizationLifecycle.CLEANED))
                        NormalizationCleanupPlan(f.canonical, f.raw, null) else null)
                assertPreIoRejection(f, state.name)
            }
        }
        AdoptedCanonicalTestFixture().use { f -> f.ledger.row = null; assertPreIoRejection(f) }
        AdoptedCanonicalTestFixture().use { f -> f.ledger.unreadable = true; assertPreIoRejection(f) }
    }

    @Test fun adoptedLedgerMustMatchCompleteGenerationAndProof() {
        val changes: List<(AdoptedCanonicalTestFixture) -> Unit> = listOf(
            { it.ledger.row = it.ledger.row!!.copy(disposition = NormalizationDisposition.DISCARD_ONLY) },
            { it.ledger.row = it.ledger.row!!.copy(failure = NormalizationFailure.IO_UNCERTAIN) },
            { it.ledger.row = it.ledger.row!!.copy(key = it.key.copy(normalizationId = "33333333-3333-4333-8333-333333333333")) },
            { it.ledger.row = it.ledger.row!!.copy(key = it.key.copy(owner = it.ownerKey.copy(taskId = "other"))) },
            { it.ledger.row = it.ledger.row!!.copy(raw = it.raw.copy(sha256 = "b".repeat(64))) },
            { it.ledger.row = it.ledger.row!!.copy(raw = it.raw.copy(size = it.raw.size - 1)) },
            { it.ledger.row = it.ledger.row!!.copy(output = it.canonical.copy(sha256 = "b".repeat(64))) },
            { it.ledger.row = it.ledger.row!!.copy(output = it.canonical.copy(size = it.canonical.size - 1)) },
            { it.ledger.row = it.ledger.row!!.copy(sourceTransferSha256 = "b".repeat(64)) },
            { it.ledger.row = it.ledger.row!!.copy(proofSha256 = "b".repeat(64)) },
        )
        changes.forEachIndexed { index, change -> AdoptedCanonicalTestFixture().use { f ->
            change(f); assertPreIoRejection(f, "ledger case $index")
        } }
    }

    @Test fun malformedOrAliasedLeafAndParentMetadataNeverOpensCanonicalRaf() {
        val changes: List<(AdoptedCanonicalTestFixture) -> Unit> = listOf(
            { it.io.removeStat(it.primary) }, { it.io.removeStat(it.rawFile) }, { it.io.removeStat(it.generation) },
            { it.io.replaceStat(it.primary, it.io.stat(it.primary).copy(kind = NormalizationDescriptorIo.Kind.OTHER)) },
            { it.io.replaceStat(it.rawFile, it.io.stat(it.rawFile).copy(kind = NormalizationDescriptorIo.Kind.OTHER)) },
            { it.io.replaceStat(it.primary, it.io.stat(it.primary).copy(links = 2)) },
            { it.io.replaceStat(it.rawFile, it.io.stat(it.rawFile).copy(links = 2)) },
            { it.io.replaceStat(it.primary, it.io.stat(it.primary).copy(size = it.canonical.size + 1)) },
            { it.io.replaceStat(it.rawFile, it.io.stat(it.rawFile).copy(size = it.raw.size - 1)) },
            { it.io.replaceStat(it.primary, it.io.stat(it.primary).copy(device = 2)) },
            { it.io.replaceStat(it.rawFile, it.io.stat(it.rawFile).copy(inode = it.io.stat(it.primary).inode)) },
            { it.io.replaceStat(it.workspace, it.io.stat(it.workspace).copy(kind = NormalizationDescriptorIo.Kind.OTHER)) },
            { it.io.replaceStat(it.generation, it.io.stat(it.generation).copy(device = 2)) },
            { it.io.replaceStat(it.generation, it.io.stat(it.workspace)) },
            { it.outputFile.writeBytes(byteArrayOf(1)); it.io.file(it.outputFile, 1) },
            { java.io.File(it.workspace, "foreign-generation").mkdir() },
            { java.io.File(it.generation, "unexpected").writeBytes(byteArrayOf(1)) },
        )
        changes.forEachIndexed { index, change -> AdoptedCanonicalTestFixture().use { f ->
            change(f)
            f.bound { assertThrows("layout case $index", SaveFailure::class.java) { f.owner.acquire(f.artifact, f.output, f.context) } }
            assertEquals("layout case $index", 0, f.io.count("open"))
            assertFalse("layout case $index", f.owner.hasAnyLeases())
            assertFalse("layout case $index", f.owner.requiresProcessRecovery())
            assertEquals(f.io.count("openDirectory"), f.io.count("closeDirectory"))
        } }
    }

    @Test fun revalidationRejectsStrictRecordRevisionEvenWhenValuesOtherwiseUnchanged() {
        assertInvalidates { f -> f.record = f.record!!.let { it.copy(task = it.task.copy(revision = it.task.revision + 1)) } }
    }

    @Test fun revalidationRejectsLedgerRevisionCatalogAndFullRecordChanges() {
        val changes: List<(AdoptedCanonicalTestFixture) -> Unit> = listOf(
            { it.ledger.row = it.ledger.row!!.copy(revision = it.ledger.row!!.revision + 1) },
            { it.catalog = it.catalog!!.copy(mediaId = "replacement") },
            { it.record = it.record!!.copy(bytesTransferred = it.raw.size + 1) },
            { it.record = it.record!!.let { r -> r.copy(request = r.request.copy(idempotencyKey = "new-key")) } },
            { it.record = it.record!!.let { r -> r.copy(task = r.task.copy(state = TaskState.CANCELLED)) } },
            { it.record = it.record!!.copy(operationId = "replacement") },
        )
        changes.forEach(::assertInvalidates)
    }

    @Test fun revalidationRejectsEveryCapturedParentAndLeafIdentityChangeWithoutReopen() {
        // root/staging are shared: unrelated entries may change their size/links/timestamps.
        // Owned workspace/generation and both leaves retain the strict full-metadata snapshot.
        for (role in 0..5) for (field in 0..(if (role < 2) 1 else 8)) assertInvalidates { f ->
            val file = listOf(f.root, f.staging, f.workspace, f.generation, f.primary, f.rawFile)[role]
            val before = f.io.stat(file)
            val changed = when (field) {
                0 -> before.copy(device = before.device + 1)
                1 -> before.copy(inode = before.inode + 100)
                2 -> before.copy(size = before.size + 1)
                3 -> before.copy(modifiedSeconds = before.modifiedSeconds + 1)
                4 -> before.copy(modifiedNanos = before.modifiedNanos + 1)
                5 -> before.copy(changedSeconds = before.changedSeconds + 1)
                6 -> before.copy(changedNanos = before.changedNanos + 1)
                7 -> before.copy(links = before.links + 1)
                else -> before.copy(kind = NormalizationDescriptorIo.Kind.OTHER)
            }
            f.io.replaceStat(file, changed)
        }
    }

    @Test fun sharedParentNonDirectoryReplacementStillInvalidatesWithoutReopen() {
        for (role in 0..1) assertInvalidates { f ->
            val path = if (role == 0) f.root else f.staging
            f.io.replaceStat(path, f.io.stat(path).copy(kind = NormalizationDescriptorIo.Kind.OTHER))
        }
    }

    @Test fun unrelatedRealRootJournalAndStagingSiblingChurnKeepsSameReadOnlyLease() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            val lease = f.owner.acquire(f.artifact, f.output, f.context)
            val input = lease.readOnlyInput
            val descriptor = input.fd
            val siblings = listOf(java.io.File(f.root, "owned-host-test.db-journal"),
                java.io.File(f.staging, "33333333-3333-4333-8333-333333333333.part"))
            siblings.forEach { it.writeBytes(byteArrayOf(1, 2, 3)) }
            // Actual create/delete above/below uses host temp files. These metadata changes are
            // injected boundary facts, not evidence of Android directory-stat behavior.
            fun churnSharedMetadata() {
                for (path in listOf(f.root, f.staging)) f.io.replaceStat(path, f.io.stat(path).let {
                    it.copy(links = it.links + 1, size = it.size + 128,
                        modifiedSeconds = it.modifiedSeconds + 1, modifiedNanos = it.modifiedNanos + 1,
                        changedSeconds = it.changedSeconds + 1, changedNanos = it.changedNanos + 1)
                })
            }
            churnSharedMetadata()
            lease.revalidate()
            siblings.forEach { assertTrue(it.delete()) }
            churnSharedMetadata()
            lease.revalidate()
            assertSame(input, lease.readOnlyInput); assertSame(descriptor, lease.readOnlyInput.fd)
            assertEquals(1, f.io.count("open")); assertFalse(f.owner.requiresProcessRecovery())
            lease.close()
            assertTrue(lease.releaseConfirmed); assertFalse(f.owner.hasAnyLeases())
        } }
    }

    @Test fun finalAcquisitionAuthorityCheckRejectsRevisionChangeAfterRafOpenAndClosesOnce() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            f.io.after = { call -> if (call.operation == "open") f.record = f.record!!.let { r -> r.copy(task = r.task.copy(revision = r.task.revision + 1)) } }
            assertThrows(SaveFailure::class.java) { f.owner.acquire(f.artifact, f.output, f.context) }
            assertEquals(1, f.io.count("open")); assertEquals(1, f.io.count("close"))
            assertFalse(f.io.handles.single().fd.valid())
            assertFalse(f.owner.hasAnyLeases()); assertFalse(f.owner.requiresProcessRecovery())
        } }
    }

    @Test fun metadataFailureAfterReturnedRafClosesOnceAndDrainsKnownResources() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            f.io.before = { if (it.operation == "fstat") throw IOException("HOST_FSTAT") }
            assertThrows(Exception::class.java) { f.owner.acquire(f.artifact, f.output, f.context) }
            assertEquals(1, f.io.count("open")); assertEquals(1, f.io.count("close"))
            assertEquals(f.io.count("openDirectory"), f.io.count("closeDirectory"))
            assertFalse(f.owner.hasAnyLeases()); assertFalse(f.owner.requiresProcessRecovery())
        } }
    }

    @Test fun descriptorAccessFailureStillClosesReturnedRafButRetainsUnconfirmedIdentity() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            f.io.before = { if (it.operation == "descriptor") throw IOException("HOST_FD") }
            assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED,
                assertThrows(SaveFailure::class.java) { f.owner.acquire(f.artifact, f.output, f.context) }.code)
            assertEquals(1, f.io.count("close"))
            assertTrue(f.owner.hasAnyLeases()); assertTrue(f.owner.requiresProcessRecovery())
        } }
    }

    @Test fun thrownRafConstructorOutcomeRemainsStickyEvenWithoutReturnedHandle() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            f.io.before = { if (it.operation == "open") throw IOException("HOST_OPEN_OUTCOME_UNKNOWN") }
            assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED,
                assertThrows(SaveFailure::class.java) { f.owner.acquire(f.artifact, f.output, f.context) }.code)
            assertEquals(1, f.io.count("open")); assertEquals(0, f.io.count("close"))
            assertTrue(f.owner.hasAnyLeases()); assertTrue(f.owner.requiresProcessRecovery())
        } }
    }

    @Test fun lostRafConstructorReturnRetainsRealOpenHandleAndRecoveryRoot() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            f.io.after = { if (it.operation == "open") throw IOException("HOST_LOST_OPEN_RETURN") }
            assertThrows(SaveFailure::class.java) { f.owner.acquire(f.artifact, f.output, f.context) }
            assertEquals(1, f.io.handles.size); assertTrue(f.io.handles.single().fd.valid())
            assertEquals(0, f.io.count("close"))
            assertTrue(f.owner.hasAnyLeases()); assertTrue(f.owner.requiresProcessRecovery())
        } }
    }

    @Test fun directoryConstructorAndCloseUncertaintyRetainReservationBeforeRafOpen() {
        for (constructor in listOf(true, false)) AdoptedCanonicalTestFixture().use { f -> f.bound {
            if (constructor) f.io.before = { if (it.operation == "openDirectory") throw IOException("HOST_DIRECTORY_OPEN") }
            else f.io.directoryCloseFailure = true
            assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED,
                assertThrows(SaveFailure::class.java) { f.owner.acquire(f.artifact, f.output, f.context) }.code)
            assertEquals(0, f.io.count("open"))
            assertTrue(f.owner.hasAnyLeases()); assertTrue(f.owner.requiresProcessRecovery())
            assertEquals(if (constructor) 0 else 1, f.io.count("closeDirectory"))
        } }
    }

    @Test fun enumerationFailureClosesReturnedDirectoryWithoutPermanentPoison() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            f.io.before = { if (it.operation == "children") throw IOException("HOST_ENUMERATION") }
            assertThrows(Exception::class.java) { f.owner.acquire(f.artifact, f.output, f.context) }
            assertEquals(1, f.io.count("openDirectory")); assertEquals(1, f.io.count("closeDirectory"))
            assertEquals(0, f.io.count("open"))
            assertFalse(f.owner.hasAnyLeases()); assertFalse(f.owner.requiresProcessRecovery())
        } }
    }

    @Test fun unsuccessfulCloseAndReturnedValidDescriptorAreNeverRetriedOrRetired() {
        for (mode in AdoptedCanonicalTestIo.CloseMode.entries.filter { it != AdoptedCanonicalTestIo.CloseMode.NORMAL }) {
            AdoptedCanonicalTestFixture().use { f -> f.bound {
                val lease = f.owner.acquire(f.artifact, f.output, f.context)
                f.io.closeMode = mode
                repeat(2) {
                    assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, assertThrows(SaveFailure::class.java) { lease.close() }.code)
                }
                assertEquals(1, f.io.count("close")); assertFalse(lease.releaseConfirmed)
                assertTrue(f.owner.hasReservation(f.artifact.artifactId)); assertTrue(f.owner.requiresProcessRecovery())
            } }
        }
    }

    @Test fun nativeUncertaintyRemainsStickyDespiteSuccessfulRafClose() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            val lease = f.owner.acquire(f.artifact, f.output, f.context)
            val descriptor = lease.readOnlyInput.fd
            lease.retainOnNativeReleaseUncertainty(); lease.retainOnNativeReleaseUncertainty()
            assertTrue(f.owner.requiresProcessRecovery())
            assertThrows(SaveFailure::class.java) { lease.revalidate() }
            assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, assertThrows(SaveFailure::class.java) { lease.close() }.code)
            assertFalse(descriptor.valid()); assertEquals(1, f.io.count("close"))
            assertFalse(lease.releaseConfirmed); assertTrue(f.owner.hasAnyLeases())
            assertTrue(f.owner.requiresProcessRecovery())
        } }
    }

    private fun assertPreIoRejection(f: AdoptedCanonicalTestFixture, message: String = "") {
        f.bound { assertThrows(message, Exception::class.java) { f.owner.acquire(f.artifact, f.output, f.context) } }
        assertTrue(message, f.io.calls.isEmpty())
        assertFalse(message, f.owner.hasAnyLeases()); assertFalse(message, f.owner.requiresProcessRecovery())
    }

    private fun assertInvalidates(change: (AdoptedCanonicalTestFixture) -> Unit) {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            val lease = f.owner.acquire(f.artifact, f.output, f.context)
            change(f)
            assertThrows(Exception::class.java) { lease.revalidate() }
            assertThrows(SaveFailure::class.java) { lease.readOnlyInput }
            assertTrue(f.owner.hasAnyLeases()); assertTrue(f.owner.requiresProcessRecovery())
            lease.close()
            assertTrue(lease.releaseConfirmed)
            assertFalse(f.owner.hasAnyLeases()); assertFalse(f.owner.requiresProcessRecovery())
            assertEquals(1, f.io.count("open")); assertEquals(1, f.io.count("close"))
        } }
    }

    private fun succeeded(f: AdoptedCanonicalTestFixture): SaveRecord {
        val c = f.proof.conservation
        val tail = DirectVideoTailProof(c.precedingVideoSync, c.video.lastSample,
            c.video.lastSample.presentationTimeUs, true)
        val checked = DirectNormalizationVerificationProof(f.key.normalizationId, f.ownerKey.taskId,
            f.resource.sourceIdentity, f.artifact.artifactId, f.artifact.commitId, f.proof.digest(), c, tail)
        val evidence = VerificationEvidence(f.artifact.artifactId, f.canonical.size, f.canonical.sha256,
            true, 1, 0, c.endpoint.minimumUs / 1000, c.width, c.height, false,
            DecodePosition.entries.toSet(), false, directNormalizationProof = checked)
        return f.record!!.copy(task = f.record!!.task.copy(state = TaskState.SUCCEEDED), verification = evidence,
            receipt = MediaCommitReceipt(f.artifact.commitId, f.artifact.artifactId, "media", f.canonical.size, f.canonical.sha256))
    }
}
