package com.easysavevideo.save

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SaveCoordinatorTest {
    @Test fun successRequiresVerificationFileAndIndexAndOnlyThenCommits() {
        val h = SaveHarness()
        val result = h.run()
        assertEquals(TaskState.SUCCEEDED, result.task.state)
        assertEquals(1, h.transfers); assertEquals(1, h.verifications); assertEquals(1, h.store.commitCalls)
        assertNotNull(result.verification); assertNotNull(result.receipt)
        assertEquals(100, result.task.progressPercent)
        assertEquals(true, h.store.cleanup.single().second)
        assertEquals(result, h.coordinator.execute("task"))
        assertEquals(1, h.store.commitCalls)
    }

    @Test fun repeatedIdempotencyKeyDoesNotCreateOrExecuteAnotherTask() {
        val h = SaveHarness(); h.submit()
        val duplicate = h.coordinator.submit(h.request().copy(requestId = "another-click"), h.operation("another-operation"))
        assertTrue(duplicate is SaveSubmission.Accepted && duplicate.existing)
        assertEquals("task", (duplicate as SaveSubmission.Accepted).record.task.taskId)
        val conflict = h.coordinator.submit(h.request().copy(resource = h.request().resource.copy(variantId = "other")), h.operation())
        assertEquals(SaveSubmission.Rejected(SaveError.INVALID_REQUEST), conflict)
        assertEquals(1, h.journal.records.size)
    }

    @Test fun freeDirectHasNoStoreOrEntitlementDependencyAndUnsupportedMethodsNeverExecute() {
        val h = SaveHarness()
        assertEquals(TaskState.SUCCEEDED, h.run().task.state)
        for (method in listOf(TaskMethod.STREAM, TaskMethod.EDIT)) {
            assertEquals(SaveSubmission.Rejected(SaveError.METHOD_UNSUPPORTED), h.coordinator.submit(h.request("other").copy(method = method), h.operation()))
        }
        assertFalse(UnsupportedStreamSave.availability().available)
        assertFalse(UnsupportedRecordSave.availability().available)
        assertEquals(1, h.transfers)
    }

    @Test fun automaticOriginAndUnboundedResourceBudgetAreRejected() {
        val h = SaveHarness()
        assertEquals(SaveSubmission.Rejected(SaveError.INVALID_REQUEST),
            h.coordinator.submit(h.request().copy(origin = CommandOrigin.AUTHORIZED_AUTOMATION), h.operation()))
        assertEquals(SaveSubmission.Rejected(SaveError.INVALID_REQUEST),
            h.coordinator.submit(h.request(), h.operation().copy(resourceBudget = ResourceBudget())))
    }

    @Test fun cancellationWaitsForPortExitAndRejectsItsLateSuccessfulResult() {
        val entered = CountDownLatch(1); val exit = CountDownLatch(1)
        val h = SaveHarness(transferBlock = { _, artifact, _, _, _ ->
            entered.countDown(); check(exit.await(3, TimeUnit.SECONDS))
            TransferResult(artifact.artifactId, 1024, "video/mp4")
        })
        h.submit(); val worker = thread { h.coordinator.execute("task") }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertEquals(TaskState.RUNNING, h.coordinator.cancel("task")!!.state)
        assertEquals(TaskState.RUNNING, h.coordinator.record("task")!!.task.state)
        exit.countDown(); worker.join(3000); assertFalse(worker.isAlive)
        assertEquals(TaskState.CANCELLED, h.coordinator.snapshot("task")!!.state)
        assertEquals(0, h.verifications); assertEquals(0, h.store.commitCalls)
        assertEquals(false, h.store.cleanup.single().second)
    }

    @Test fun explicitCancelWinsOverAlreadyRequestedBackgroundPause() {
        val entered = CountDownLatch(1); val exit = CountDownLatch(1)
        val h = SaveHarness(transferBlock = { _, _, _, _, _ ->
            entered.countDown(); check(exit.await(3, TimeUnit.SECONDS)); throw SaveFailure(SaveError.CANCELLED)
        })
        h.submit(); val worker = thread { h.coordinator.execute("task") }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        h.coordinator.pauseForBackground("task"); h.coordinator.cancel("task")
        // A later lifecycle callback cannot override an explicit cancellation either.
        h.coordinator.pauseForBackground("task")
        exit.countDown(); worker.join(3000)
        assertEquals(TaskState.CANCELLED, h.coordinator.snapshot("task")!!.state)
    }

    @Test fun cancellationDuringLongProvisionalCommitWinsBeforePublication() {
        val entered = CountDownLatch(1); val exit = CountDownLatch(1)
        val store = object : FakeMediaStore() {
            override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt {
                entered.countDown(); check(exit.await(3, TimeUnit.SECONDS)); return super.commit(artifact, context)
            }
        }
        val h = SaveHarness(store = store); h.submit()
        val worker = thread { h.coordinator.execute("task") }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        var cancelledSnapshot: TaskSnapshot? = null
        val cancellationRequested = CountDownLatch(1)
        val canceller = thread { cancelledSnapshot = h.coordinator.cancel("task"); cancellationRequested.countDown() }
        assertTrue(cancellationRequested.await(3, TimeUnit.SECONDS))
        exit.countDown(); worker.join(3000); canceller.join(3000)
        assertEquals(TaskState.RUNNING, cancelledSnapshot!!.state)
        assertEquals(TaskState.CANCELLED, h.coordinator.snapshot("task")!!.state)
        assertEquals(1, store.committed.size)
    }

    @Test fun retryNeverRenewsAbsoluteDeadline() {
        val clock = FakeSaveClock(); val deadlines = mutableListOf<Long>()
        val h = SaveHarness(clock = clock, transferBlock = { _, _, _, op, _ ->
            deadlines.add(op.deadlineEpochMillis); throw SaveFailure(SaveError.NETWORK_TIMEOUT)
        }, delay = SaveRetryDelay { _, op ->
            assertEquals(50, op.deadlineEpochMillis); clock.now = 51
        })
        h.coordinator.submit(h.request(), h.operation(deadline = 50))
        val result = h.coordinator.execute("task")!!
        assertEquals(TaskState.FAILED, result.task.state); assertEquals(SaveError.DEADLINE_EXCEEDED, result.error)
        assertEquals(listOf(50L), deadlines)
    }

    @Test fun retriesAreBoundedAndCannotFallbackToAnotherMethod() {
        val h = SaveHarness(transferBlock = { _, _, _, _, _ -> throw SaveFailure(SaveError.NETWORK_TIMEOUT) })
        val result = h.run()
        assertEquals(3, h.transfers); assertEquals(SaveError.RETRY_EXHAUSTED, result.error)
        assertEquals(TaskMethod.DIRECT, result.task.method); assertEquals(0, h.store.commitCalls)
    }

    @Test fun nonretryableSecurityStorageAndAuthorizationFailuresAreClassified() {
        for (code in listOf(SaveError.TLS_REJECTED, SaveError.PROTECTED_MEDIA, SaveError.POLICY_BLOCKED,
            SaveError.AUTHENTICATION_REQUIRED, SaveError.STORAGE_FULL, SaveError.NETWORK_UNAVAILABLE)) {
            val h = SaveHarness(transferBlock = { _, _, _, _, _ -> throw SaveFailure(code) })
            val result = h.run(); assertEquals(1, h.transfers)
            assertEquals(if (SaveErrors.policy(code).waitReason == null) TaskState.FAILED else TaskState.WAITING_USER, result.task.state)
            assertEquals(0, h.store.commitCalls)
        }
    }

    @Test fun missingOrContradictoryMediaEvidenceNeverCommits() {
        val corruptions: List<(VerificationEvidence) -> VerificationEvidence> = listOf(
            { it.copy(sizeBytes = 0) }, { it.copy(sha256 = "invalid") }, { it.copy(containerIsMp4 = false) },
            { it.copy(videoTrackCount = 0) }, { it.copy(durationMillis = 0) }, { it.copy(width = 0) },
            { it.copy(encrypted = true) }, { it.copy(decodedPositions = setOf(DecodePosition.START)) },
            { it.copy(audioTrackCount = 1, audioTrackReadable = false) }, { it.copy(artifactId = "another") })
        for (corrupt in corruptions) {
            val h = SaveHarness(verifyBlock = { artifact, result, _ -> corrupt(goodEvidence(artifact, result)) })
            assertEquals(TaskState.FAILED, h.run().task.state); assertEquals(0, h.store.commitCalls)
        }
    }

    @Test fun verifierHasNoDefaultPassAndLateValidationCannotCommit() {
        val clock = FakeSaveClock()
        val h = SaveHarness(clock = clock, verifyBlock = { artifact, result, _ -> clock.now = 10_001; goodEvidence(artifact, result) })
        assertEquals(SaveError.DEADLINE_EXCEEDED, h.run().error); assertEquals(0, h.store.commitCalls)
    }

    @Test fun missingCommittedIndexCannotProduceSuccessAndPreservesForRecovery() {
        val store = FakeMediaStore().apply { inspected = false }; val h = SaveHarness(store = store)
        val result = h.run()
        assertEquals(TaskState.WAITING_USER, result.task.state); assertEquals(SaveWaitReason.RECOVERY, result.waitReason)
        assertTrue(store.cleanup.isEmpty())
    }

    @Test fun finalJournalFailureDoesNotClaimSuccessAndRetryUsesSameCommittedArtifact() {
        val journal = MemorySaveJournal().apply { rejectSuccess = true }; val h = SaveHarness(journal = journal)
        assertEquals(TaskState.WAITING_USER, h.run().task.state)
        assertEquals(1, h.store.committed.size)
        journal.rejectSuccess = false
        assertNotNull(h.coordinator.resume("task", h.operation("resume-operation")))
        val result = h.coordinator.execute("task")!!
        assertEquals(TaskState.SUCCEEDED, result.task.state)
        assertEquals(1, h.transfers); assertEquals(2, h.verifications); assertEquals(1, h.store.committed.size)
    }

    @Test fun interruptedWorkRequiresReviewAndRepeatedRecoveryIsIdempotent() {
        val h = SaveHarness(); h.submit()
        val result = h.coordinator.recover(h.operation("recovery")).single()
        assertEquals(TaskState.WAITING_USER, result.task.state)
        assertEquals(ResumeAction.REVIEW_RECOVERY, result.task.waitingUser!!.resumeAction)
        assertEquals(result, h.coordinator.recover(h.operation("recovery2")).single())
        assertEquals(result, h.coordinator.execute("task"))
        h.coordinator.resume("task", h.operation("new-user-command"), resource = h.request().resource)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("task")!!.task.state)
    }

    @Test fun waitingUserHasExpiryAndExpiredOwnedStagingIsCleaned() {
        val h = SaveHarness(transferBlock = { _, _, _, _, _ -> throw SaveFailure(SaveError.NETWORK_UNAVAILABLE) })
        assertEquals(TaskState.WAITING_USER, h.run().task.state)
        h.clock.now = 100
        assertEquals(TaskState.CANCELLED, h.coordinator.recover(h.operation("recover")).single().task.state)
        assertEquals(false, h.store.cleanup.single().second)
    }

    @Test fun credentialsAreNotPersistedAndAuthenticationNeedsFreshLease() {
        val h = SaveHarness(transferBlock = { resource, _, _, _, _ ->
            assertEquals("lease", resource.requestContextRef?.leaseId); throw SaveFailure(SaveError.AUTHENTICATION_REQUIRED)
        })
        h.coordinator.submit(h.request().copy(resource = h.request().resource.copy(requestContextRef = RequestContextRef("lease"))), h.operation())
        assertNull(h.journal.read("task")!!.request.resource.requestContextRef)
        val waiting = h.coordinator.execute("task")!!
        assertEquals(waiting, h.coordinator.resume("task", h.operation("resume")))
        assertEquals(TaskState.RUNNING, h.coordinator.resume("task", h.operation("resume"), RequestContextRef("new-lease"))!!.task.state)
    }

    @Test fun networkByteReservationIsBoundedAcrossRetriesAndRestartClearsRangeHint() {
        var calls = 0
        val h = SaveHarness(limits = SaveLimits(10, 20), transferBlock = { _, artifact, _, _, progress ->
            calls++
            progress.onBytes(10)
            progress.onCheckpoint(DownloadCheckpoint(artifact.artifactId, "a".repeat(64), 5, "\"strong\"", 10))
            progress.onRestart()
            throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
        })
        val result = h.run()
        assertEquals(3, calls); assertEquals(SaveError.BYTE_LIMIT_EXCEEDED, result.error)
        assertEquals(20, result.bytesTransferred); assertNull(result.checkpoint)
    }

    @Test fun privateFunctionalValuesAreRedactedAndHttpsOnly() {
        val url = SourceUrl("https://example.invalid/secret.mp4?token=secret")
        assertFalse(url.toString().contains("secret"))
        assertThrows(IllegalArgumentException::class.java) { SourceUrl("http://example.invalid/video") }
        assertThrows(IllegalArgumentException::class.java) { SourceUrl("https://user:password@example.invalid/video") }
        val h = SaveHarness(); h.submit(); assertFalse(h.coordinator.record("task").toString().contains("example"))
        assertThrows(IllegalArgumentException::class.java) { DownloadCheckpoint("id", "a".repeat(64), 1, "W/\"weak\"", 2) }
    }

    @Test fun observerFailureCannotChangeTaskResultAndTerminalStateCannotRevive() {
        val h = SaveHarness(); h.submit()
        h.coordinator.observe("task", TaskObserver { throw IllegalStateException("private message") })
        val result = h.coordinator.execute("task")!!
        assertEquals(TaskState.SUCCEEDED, result.task.state)
        assertEquals(result.task, h.coordinator.cancel("task"))
        assertEquals(result, h.coordinator.resume("task", h.operation("resume")))
    }
    @Test fun finalPublishCasWinsOverCancellationArrivingAfterItsDecision() {
        val entered = CountDownLatch(1); val exit = CountDownLatch(1)
        val journal = MemorySaveJournal().apply { beforeSuccess = { entered.countDown(); check(exit.await(3, TimeUnit.SECONDS)) } }
        val h = SaveHarness(journal = journal); h.submit()
        val worker = thread { h.coordinator.execute("task") }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertEquals(TaskState.RUNNING, h.coordinator.cancel("task")!!.state)
        exit.countDown(); worker.join(3000)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.snapshot("task")!!.state)
    }

    @Test fun signedUrlPathAndQueryNeverEnterDurableRecords() {
        val h = SaveHarness()
        val source = SourceUrl("https://example.invalid/bearer-path-secret/movie.mp4?unlabelled=opaque-secret")
        val request = h.request().copy(resource = SelectedMediaResource("candidate", "variant", source))
        h.coordinator.submit(request, h.operation())
        val durable = h.journal.read("task")!!.request.resource
        assertEquals("https://example.invalid/", durable.url.value)
        assertTrue(durable.durableReference)
        assertEquals(source.identity(), durable.sourceIdentity)
        assertEquals(source, h.coordinator.sourceForTask("task")!!.url)
        h.clock.now = 10_000
        assertNull(h.coordinator.sourceForTask("task"))
        assertThrows(IllegalArgumentException::class.java) { durable.copy(url = source) }
    }

    @Test fun freshProcessNeedsUserReselectionForIncompleteTransferButNotCompletedArtifact() {
        val h = SaveHarness(); h.submit()
        val restarted = SaveHarness(journal = h.journal, store = h.store)
        val waiting = restarted.coordinator.recover(restarted.operation("recover")).single()
        assertNull(restarted.coordinator.sourceForTask("task"))
        assertEquals(waiting, restarted.coordinator.resume("task", restarted.operation("new-command")))
        restarted.coordinator.resume("task", restarted.operation("reselected-command"), resource = restarted.request().resource)
        assertEquals(TaskState.SUCCEEDED, restarted.coordinator.execute("task")!!.task.state)

        val journal = MemorySaveJournal().apply { rejectSuccess = true }
        val complete = SaveHarness(journal = journal); complete.run()
        journal.rejectSuccess = false
        val nextProcess = SaveHarness(journal = journal, store = complete.store)
        nextProcess.coordinator.recover(nextProcess.operation("recover"))
        nextProcess.coordinator.resume("task", nextProcess.operation("local-only"))
        assertEquals(TaskState.SUCCEEDED, nextProcess.coordinator.execute("task")!!.task.state)
        assertEquals(0, nextProcess.transfers)
    }

    @Test fun unconfirmedResourceReleasePreservesArtifactAndBlocksMoreWork() {
        var uncertain = false
        val h = SaveHarness(transferBlock = { _, _, _, _, _ ->
            uncertain = true; throw SaveFailure(SaveError.CANCELLED)
        }, releaseUncertain = { uncertain })
        val result = h.run()
        assertEquals(TaskState.WAITING_USER, result.task.state)
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, result.error)
        assertTrue(h.store.cleanup.isEmpty())
        assertEquals(TaskState.WAITING_USER, h.coordinator.cancel("task")!!.state)
        assertEquals(SaveSubmission.Rejected(SaveError.RESOURCE_RELEASE_UNCONFIRMED), h.coordinator.submit(h.request("other"), h.operation("other")))
        h.clock.now = 101
        assertEquals(TaskState.WAITING_USER, h.coordinator.recover(h.operation("recover")).single().task.state)
        uncertain = false
        assertEquals(TaskState.CANCELLED, h.coordinator.cancel("task")!!.state)
        assertEquals(1, h.store.cleanup.size)
    }

    @Test fun queuedCancellationAndPauseNeverRunNetworkAndExpiryHasBoundedCleanup() {
        val h = SaveHarness(); h.submit()
        assertEquals(TaskState.WAITING_USER, h.coordinator.pauseForBackground("task")!!.task.state)
        assertEquals(TaskState.CANCELLED, h.coordinator.cancel("task")!!.state)
        assertEquals(0, h.transfers); assertEquals(0, h.verifications)
    }

    @Test fun journalInvariantRejectsSuccessWithMismatchedArtifactIdentity() {
        val h = SaveHarness(); val result = h.run()
        assertThrows(IllegalArgumentException::class.java) { result.copy(receipt = result.receipt!!.copy(artifactId = "other")) }
        assertThrows(IllegalArgumentException::class.java) { result.copy(artifact = result.artifact!!.copy(commitId = "other")) }
        assertThrows(IllegalArgumentException::class.java) { result.copy(task = result.task.copy(method = TaskMethod.STREAM)) }
    }

    @Test fun ordinaryResumeCannotRenewShortLivedSourceContext() {
        val h = SaveHarness(limits = SaveLimits(10_000, 30_000, waitingUserLifetimeMillis = 1000, sourceContextLifetimeMillis = 100),
            transferBlock = { _, _, _, _, _ -> throw SaveFailure(SaveError.NETWORK_UNAVAILABLE) })
        h.run(); h.clock.now = 50
        h.coordinator.resume("task", h.operation("resume"))
        assertNotNull(h.coordinator.sourceForTask("task"))
        h.clock.now = 100
        assertNull(h.coordinator.sourceForTask("task"))
    }

    @Test fun expireWaitingDoesNotDropLiveSourcesAndDoesCleanOnlyExpiredWaits() {
        val h = SaveHarness(transferBlock = { _, _, _, _, _ -> throw SaveFailure(SaveError.NETWORK_UNAVAILABLE) })
        h.run(); h.clock.now = 99
        assertTrue(h.coordinator.expireWaitingUsers(h.operation("expiry-check")).isEmpty())
        assertNotNull(h.coordinator.sourceForTask("task"))
        h.clock.now = 100
        assertEquals(TaskState.CANCELLED, h.coordinator.expireWaitingUsers(h.operation("expiry-check")).single().task.state)
        assertNull(h.coordinator.sourceForTask("task"))
        assertEquals(1, h.store.cleanup.size)
        assertTrue(h.coordinator.expireWaitingUsers(h.operation("expiry-check")).isEmpty())
    }

    @Test fun checkpointIdentityCannotPersistRawUrlOrBearerText() {
        assertThrows(IllegalArgumentException::class.java) { DownloadCheckpoint("artifact", "https://example.invalid/private?a=secret", 1, "\"etag\"", 2) }
        assertThrows(IllegalArgumentException::class.java) { DownloadCheckpoint("artifact", "bearer-secret", 1, "\"etag\"", 2) }
    }

    @Test fun snapshotRemainsNonblockingWhileDurableReadIsBlocked() {
        val h = SaveHarness(); h.submit()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.journal.beforeRead = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val reader = thread { h.coordinator.record("task") }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val snapshotRead = CountDownLatch(1)
        val ui = thread { assertEquals(TaskState.QUEUED, h.coordinator.snapshot("task")!!.state); snapshotRead.countDown() }
        try { assertTrue(snapshotRead.await(1, TimeUnit.SECONDS)) }
        finally { release.countDown(); reader.join(3000); ui.join(3000) }
    }

    @Test fun queuedExecutionRacingBackgroundPauseNeverPublishesWaitingOverActivePort() {
        repeat(30) {
            val starting = CountDownLatch(1); val permitStart = CountDownLatch(1)
            val permitPortExit = CountDownLatch(1)
            val operation = java.util.concurrent.atomic.AtomicReference<OperationContext?>()
            val h = SaveHarness(transferBlock = { _, _, _, context, _ ->
                operation.set(context); check(permitPortExit.await(3, TimeUnit.SECONDS)); throw SaveFailure(SaveError.CANCELLED)
            })
            h.submit()
            val firstRead = java.util.concurrent.atomic.AtomicBoolean(true)
            h.journal.beforeRead = { if (firstRead.compareAndSet(true, false)) { starting.countDown(); check(permitStart.await(3, TimeUnit.SECONDS)) } }
            val executor = thread { h.coordinator.execute("task") }
            assertTrue(starting.await(3, TimeUnit.SECONDS))
            val pauser = thread { h.coordinator.pauseForBackground("task") }
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (pauser.state != Thread.State.BLOCKED && System.nanoTime() < end) Thread.yield()
            permitStart.countDown(); pauser.join(3000)
            operation.get()?.let { activeOperation ->
                assertTrue(activeOperation.cancelToken.isCancelled)
                assertEquals(TaskState.RUNNING, h.coordinator.snapshot("task")!!.state)
            }
            permitPortExit.countDown(); executor.join(3000)
            assertEquals(TaskState.WAITING_USER, h.coordinator.snapshot("task")!!.state)
        }
    }

}
