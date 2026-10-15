package com.easysavevideo.app.save

import com.easysavevideo.app.browser.privacy.*
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import com.easysavevideo.save.network.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

/** Production registry, lease store and actual admission/retirement; no Android/network acceptance. */
class PrivateNetworkRuntimeSourcesTest {
    private class Fixture(cookie: String? = "partition-only=synthetic") {
        var now = 0L
        val url = SourceUrl("https://private.example.invalid/manifest?secret=synthetic")
        val session = PrivateSessionLease(PrivateBrowsingSession(PrivateProfileName.random()))
        val leases = RequestContextLeaseStore(EpochClock { now })
        val admission = SaveExecutionAdmission(maxArtifactBytes = 4096)
        val claim = admission.tryAcquireDirect()!!
        var stops = 0; var preparationStops = 0; var clears = 0; var drains = 0
        var releaseBlocked = false; var canClear = true; var dormant = false
        var cleared: Any? = null
        lateinit var capture: BrowserSaveRequestContext
        val capability = BrowserSaveResumeAuthority { capture }
        val sources = PrivateNetworkRuntimeSources<Any>(leases, { value ->
            clears++; cleared = value; canClear
        }, { _, _ -> stops++ })
        val retirement = SaveExecutionRetirement(admission::owns, { releaseBlocked }, admission::release) {
            sources.executionRetired(it) { dormant }
        }
        val entry: PrivateNetworkRuntimeSources<Any>.Entry
        init {
            capture = BrowserSaveRequestContext(BrowserSavePartition.Private(session), cookie, "partition-agent", capability)
            entry = sources.register(capture, url, claim) { preparationStops++ }
        }
        fun bind() = entry.bindTask("task", "operation")
        fun resource(ref: RequestContextRef, binding: PrivateNetworkSource = entry.binding ?: entry.seed) =
            SelectedMediaResource("candidate", "live-parser-variant", url, requestContextRef = ref,
                sourceIdentity = binding.sourceIdentity.encoded, privateSource = binding)
        fun issue(claim: Any = this.claim) = entry.issueLease(claim, capture)
        fun exit() = session.revokeAndDrain { drains++ }
    }

    @Test fun cookieFreePreparationStillHasLivePartitionFenceAndWaitsForRealClaimRelease() {
        val f = Fixture(cookie = null)
        val ref = f.issue(); val authority = f.leases.captureAuthority(f.resource(ref))!!
        assertNull(f.leases.headers(ref, URI(f.url.value), URI(f.url.value), true)["Cookie"])
        f.releaseBlocked = true; f.exit(); f.retirement.request(f.claim)
        assertEquals(1, f.preparationStops); assertEquals(0, f.drains)
        assertThrows(SaveFailure::class.java) { authority.check() }
        assertTrue(f.admission.owns(f.claim)); assertSame(f.entry, f.sources.forClaim(f.claim))
        f.releaseBlocked = false; f.retirement.retry()
        assertEquals(1, f.drains); assertNull(f.sources.forClaim(f.claim)); assertNull(f.capability.refreshForExplicitResume())
    }

    @Test fun preparedSeedBindsOnceAndPreservesRandomIdentityWithoutDurableParserIdentity() {
        val f = Fixture(); val prepared = Any(); f.entry.attachPreparation(prepared)
        val ref = f.issue(); val source = f.resource(ref)
        val binding = f.bind()
        assertTrue(binding.isFrom(f.entry.seed)); assertEquals(source.sourceIdentity, binding.sourceIdentity.encoded)
        val request = SaveRequest("task", "task", source.copy(privateSource = binding), TaskMethod.STREAM)
        val durable = request.asDurableReference().input as SaveInput.PrivateNetwork
        assertEquals(binding.sourceIdentity, durable.source.identity)
        assertNotEquals(source.variantId, durable.source.variantId.encoded)
        assertThrows(SaveFailure::class.java) { f.entry.bindTask("other", "other-operation") }
        f.retirement.request(f.claim)
        assertSame(prepared, f.cleared); assertEquals(1, f.clears); assertNull(f.sources.forTask("task"))
    }

    @Test fun exactCoreDetachClearsCredentialsAndPreparedReferencesWithoutCancellingOutputClaim() {
        val f = Fixture(); val binding = f.bind(); val prepared = Any(); f.entry.attachPreparation(prepared)
        val authority = f.leases.captureAuthority(f.resource(f.issue()))!!
        f.sources.sourceDetached("task", "operation", binding)
        assertEquals(0, f.stops); assertTrue(f.admission.owns(f.claim))
        assertSame(prepared, f.cleared); assertNull(f.sources.forTask("task")); assertNull(f.sources.forClaim(f.claim))
        assertNull(f.capability.refreshForExplicitResume()); assertThrows(SaveFailure::class.java) { authority.check() }
        f.exit(); assertEquals(1, f.drains)
        f.retirement.request(f.claim); assertFalse(f.admission.owns(f.claim)); assertEquals(1, f.clears)
    }

    @Test fun wrongBindingOrOperationCannotDetachAndFailedReferenceCleanupNeverAcknowledgesDrain() {
        val f = Fixture(); val binding = f.bind(); f.entry.attachPreparation(Any()); f.issue()
        assertThrows(SaveFailure::class.java) { f.sources.sourceDetached("task", "old", binding) }
        assertThrows(SaveFailure::class.java) { f.sources.sourceDetached("task", "operation", PrivateNetworkSourceSeed.create().bindTask("task")) }
        assertEquals(0, f.clears)
        f.canClear = false
        assertThrows(SaveFailure::class.java) { f.sources.sourceDetached("task", "operation", binding) }
        f.exit(); f.retirement.request(f.claim)
        assertEquals(0, f.drains); assertSame(f.entry, f.sources.forTask("task"))
        assertTrue(f.entry.ownsPreparation(requireNotNull(f.cleared))); assertNull(f.capability.refreshForExplicitResume())
    }

    @Test fun dormantDirectRetainsTaskCapabilityButRevokesOldClaimLeasesAndLateOldCallbackCannotTouchResume() {
        val f = Fixture(); val binding = f.bind()
        val first = f.leases.captureAuthority(f.resource(f.issue()))!!
        f.dormant = true; f.retirement.request(f.claim)
        assertThrows(SaveFailure::class.java) { first.check() }
        assertSame(f.entry, f.sources.forTask("task")); assertSame(f.capture, f.entry.refreshForExplicitResume())
        val fresh = f.admission.tryAcquireDirect()!!
        assertTrue(f.sources.resumeQueued(f.entry, fresh, "resumed"))
        val current = f.leases.captureAuthority(f.resource(f.issue(fresh)))!!
        f.sources.executionRetired(f.claim) { fail("old claim cannot consult current disposition"); false }
        current.check(); assertSame(f.entry, f.sources.forClaim(fresh))
        assertThrows(SaveFailure::class.java) { f.sources.sourceDetached("task", "operation", binding) }
        f.exit(); assertEquals(0, f.drains); assertThrows(SaveFailure::class.java) { current.check() }
        f.retirement.request(fresh); assertEquals(1, f.drains); assertNull(f.sources.forTask("task"))
    }

    @Test fun cancelAndExpiryRetireDormantSourceWithoutReplayingOldClaimRetirement() {
        for (expired in listOf(false, true)) {
            val f = Fixture(); f.bind(); f.issue(); f.dormant = true; f.retirement.request(f.claim)
            if (expired) f.sources.retireUnavailable { task, exact -> task == "task" && exact === f.entry.binding }
            else f.sources.retireTask("task")
            assertEquals(1, f.stops); assertNull(f.sources.forTask("task")); assertNull(f.capability.refreshForExplicitResume())
            f.exit(); assertEquals(1, f.drains)
        }
        val f = Fixture(); f.bind(); f.sources.retireTask("task"); f.exit()
        assertEquals(0, f.drains); assertTrue(f.admission.owns(f.claim))
        f.retirement.request(f.claim); assertEquals(1, f.drains)
    }

    @Test fun refreshedContextCannotSwitchPartitionCapabilityOrCredentialOrigin() {
        val f = Fixture(); f.bind()
        val foreign = PrivateSessionLease(PrivateBrowsingSession(PrivateProfileName.random()))
        assertThrows(SaveFailure::class.java) { f.entry.issueLease(f.claim,
            BrowserSaveRequestContext(BrowserSavePartition.Private(foreign), "foreign", "foreign", f.capability)) }
        assertThrows(SaveFailure::class.java) { f.entry.issueLease(f.claim,
            BrowserSaveRequestContext(BrowserSavePartition.Private(f.session), null, "agent", BrowserSaveResumeAuthority { null })) }
        val ref = f.issue()
        assertEquals("partition-only=synthetic", f.leases.headers(ref, URI(f.url.value), URI(f.url.value), true)["Cookie"])
        assertThrows(SaveFailure::class.java) { f.leases.captureAuthority(f.resource(ref).copy(url = SourceUrl("https://cdn.example.invalid/segment"))) }
    }

    @Test fun ttlExpiryDoesNotDropTaskOwnerOrPretendAnIssuedRequestDrained() {
        val f = Fixture(); f.bind()
        val captured = f.leases.captureAuthority(f.resource(f.issue()))!!
        f.now = 60_001; f.leases.put(f.url); captured.check()
        assertSame(f.entry, f.sources.forTask("task")); assertSame(f.capture, f.capability.refreshForExplicitResume())
        f.exit(); assertEquals(0, f.drains); assertThrows(SaveFailure::class.java) { captured.check() }
        f.retirement.request(f.claim); assertEquals(1, f.drains)
    }

    @Test fun actualCoreWaitingResumeAndPositiveDetachUseRegistryWithoutCancellingOutput() {
        val h = CoreHarness()
        assertEquals(TaskState.WAITING_USER, h.run().task.state)
        assertSame(h.entry, h.sources.forTask("task")); assertTrue(h.binding.isLive())
        val fresh = h.admission.tryAcquireDirect()!!
        assertTrue(h.sources.resumeQueued(h.entry, fresh, "resume"))
        val lease = h.entry.issueLease(fresh, h.entry.refreshForExplicitResume()!!)
        val operation = h.operation("resume")
        assertEquals(TaskState.RUNNING, h.coordinator.resume("task", operation, fresh, lease)!!.task.state)
        h.pause = false; h.exitAtDetach = true
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("task", fresh)!!.task.state)
        assertFalse(operation.cancelToken.isCancelled); assertEquals(1, h.drains)
        assertNull(h.sources.forTask("task")); assertNull(h.coordinator.sourceForTask("task"))
        h.retirement.request(fresh); assertEquals(2, h.transfers)
    }

    private class CoreHarness {
        val session = PrivateSessionLease(PrivateBrowsingSession(PrivateProfileName.random()))
        val leases = RequestContextLeaseStore(EpochClock { 0 })
        val limits = SaveLimits(16384, 4L * 1024 * 1024)
        val admission = SaveExecutionAdmission(maxArtifactBytes = limits.maxArtifactBytes)
        val claim = admission.tryAcquireDirect()!!
        var row: SaveRecord? = null
        var pause = true; var transfers = 0; var drains = 0; var exitAtDetach = false
        lateinit var capture: BrowserSaveRequestContext
        val capability = BrowserSaveResumeAuthority { capture }
        val sources = PrivateNetworkRuntimeSources<Any>(leases, { true }) { task, exact -> coordinator.signalPrivateSourceExit(task, exact); Unit }
        val entry: PrivateNetworkRuntimeSources<Any>.Entry
        val binding: PrivateNetworkSourceBinding
        val journal = object : SaveJournal {
            override fun read(taskId: String) = row?.takeIf { it.task.taskId == taskId }
            override fun findByIdempotencyKey(key: String) = row?.takeIf { it.request.idempotencyKey == key }
            override fun insert(record: SaveRecord, maxEntries: Int): SaveRecord { check(row == null); row = record; return record }
            override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean {
                if (row?.task?.revision != expectedRevision) return false
                row = next; return true
            }
            override fun listUnfinished(limit: Int) = listOfNotNull(row?.takeUnless { it.task.state.isTerminal }).take(limit)
            override fun listRecent(limit: Int) = listOfNotNull(row).take(limit)
        }
        val coordinator: SaveCoordinator = SaveCoordinator(journal, object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                leases.captureAuthority(resource)!!.check(); transfers++
                if (pause) throw SaveFailure(SaveError.WIFI_REQUIRED)
                progress.onBytes(1024); return TransferResult(artifact.artifactId, 1024, "video/mp4", 1024)
            }
        }, ArtifactVerifier { artifact, transfer, context ->
            check(!context.cancelToken.isCancelled)
            VerificationEvidence(artifact.artifactId, transfer.bytesOnDisk, "a".repeat(64), true, 1, 0,
                1000, 16, 16, false, DecodePosition.entries.toSet(), false)
        }, object : PrivateMediaStore {
            private var receipt: MediaCommitReceipt? = null
            override fun prepare(taskId: String, commitId: String, context: OperationContext) = StagingArtifact("artifact", commitId)
            override fun commit(artifact: VerifiedArtifact, context: OperationContext) = MediaCommitReceipt(
                artifact.artifact.commitId, artifact.artifact.artifactId, "media", artifact.evidence.sizeBytes, artifact.evidence.sha256).also { receipt = it }
            override fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection? =
                receipt?.takeIf { it.commitId == commitId }?.let { CommitInspection(it, true, true, true) }
            override fun cleanup(artifact: StagingArtifact, committed: Boolean) = Unit
        }, EpochClock { 0 }, limits, admission = admission,
            privateSourceDetachmentObserver = PrivateSourceDetachmentObserver { task, operation, exact ->
                if (exitAtDetach) session.revokeAndDrain { drains++ }
                sources.sourceDetached(task, operation, exact)
            })
        val retirement = SaveExecutionRetirement(admission::owns, { false }, admission::release) { exact ->
            sources.executionRetired(exact) { entry -> coordinator.snapshot("task")?.state == TaskState.WAITING_USER &&
                coordinator.sourceForTask("task")?.privateSource === entry.binding }
        }
        init {
            capture = BrowserSaveRequestContext(BrowserSavePartition.Private(session), null, "private-agent", capability)
            entry = sources.register(capture, SourceUrl("https://private.invalid/secret"), claim) {}
            binding = entry.bindTask("task", "operation")
        }
        fun operation(id: String) = OperationContext(id, 100000, CancellationSource(), resourceBudget = ResourceBudget(1, limits.maxTotalTransferBytes))
        fun run(): SaveRecord {
            val resource = SelectedMediaResource("candidate", "variant", SourceUrl("https://private.invalid/secret"),
                requestContextRef = entry.issueLease(claim, capture), sourceIdentity = binding.sourceIdentity.encoded, privateSource = binding)
            check(coordinator.submit(SaveRequest("task", "task", resource), operation("operation"), claim) is SaveSubmission.Accepted)
            return try { coordinator.execute("task", claim)!! } finally { retirement.request(claim) }
        }
    }
}
