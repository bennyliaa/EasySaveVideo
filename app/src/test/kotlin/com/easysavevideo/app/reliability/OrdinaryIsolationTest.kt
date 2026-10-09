package com.easysavevideo.app.reliability

import com.easysavevideo.app.save.MediaTaskActivitySignals
import com.easysavevideo.app.save.MediaTaskActivityTracker
import com.easysavevideo.app.save.storage.SaveRecordCodec
import com.easysavevideo.app.storage.BrowserSessionCodec
import com.easysavevideo.commons.CommonsIdentity
import com.easysavevideo.core.domain.BrowserSessionController
import com.easysavevideo.core.model.*
import com.easysavevideo.discovery.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Host component tests. Transfer, verification and media publication ports below are test actors,
 * not evidence of a downloaded/decoded file, WebView execution or Android storage acceptance. */
class OrdinaryIsolationTest {
    private class SourceRuntime : ProviderRuntime {
        data class Call(val operation: DiscoveryOperation, val done: (ProviderOutcome) -> Unit, var cancelled: Boolean = false)
        val calls = mutableListOf<Call>()
        override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
                             callback: (ProviderOutcome) -> Unit): CancellationHandle {
            val call = Call(operation, callback); calls += call
            return CancellationHandle { call.cancelled = true; operation.cancellation.cancel() }
        }
    }
    private class DiscoveryFixture(enabled: Boolean, store: DiscoveryStore = InMemoryDiscoveryStore(), startImmediately: Boolean = true) : AutoCloseable {
        val runtime = SourceRuntime()
        // Only a test actor bearing the admitted fixed Commons identity/schema. No dynamic source.
        private val source = object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor(CommonsIdentity.PROVIDER_ID, CommonsIdentity.VERSION, 1)
            override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage =
                throw AssertionError("UNEXPECTED_PROVIDER_EXECUTION")
        }
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(if (enabled) listOf(source) else emptyList()),
            runtime, store, object : DiscoveryTimer {
                override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle { }
            }, EpochClock { 1_000 })
        init { if (startImmediately) start() }
        fun start() {
            coordinator.setVisibility(true, true)
            coordinator.start("discovery-session", SearchProfile("profile", "fixture", filters = SearchFilters(safeSearch = false)))
        }
        override fun close() = coordinator.close()
    }
    private class Journal : SaveJournal {
        val records = linkedMapOf<String, SaveRecord>()
        override fun findByIdempotencyKey(key: String) = records.values.firstOrNull { it.request.idempotencyKey == key }
        override fun read(taskId: String) = records[taskId]
        override fun insert(record: SaveRecord, maxEntries: Int): SaveRecord {
            findByIdempotencyKey(record.request.idempotencyKey)?.let { return it }
            check(records.size < maxEntries && record.task.taskId !in records)
            records[record.task.taskId] = record; return record
        }
        override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean {
            if (records[next.task.taskId]?.task?.revision != expectedRevision || next.task.revision != expectedRevision + 1) return false
            records[next.task.taskId] = next; return true
        }
        override fun listUnfinished(limit: Int) = records.values.filterNot { it.task.state.isTerminal }.take(limit)
        override fun listRecent(limit: Int) = records.values.toList().asReversed().take(limit)
    }
    private class MediaFixture {
        val browser = BrowserSessionController()
        val journal = Journal()
        var transfers = 0
        var verifications = 0
        var commits = 0
        var onTransfer: () -> Unit = { }
        private val receipts = mutableMapOf<String, MediaCommitReceipt>()
        private val store = object : PrivateMediaStore {
            override fun prepare(taskId: String, commitId: String, context: OperationContext) = StagingArtifact(taskId, commitId)
            override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt {
                commits++
                return MediaCommitReceipt(artifact.artifact.commitId, artifact.artifact.artifactId, "private-media",
                    artifact.evidence.sizeBytes, artifact.evidence.sha256).also { receipts[it.commitId] = it }
            }
            override fun inspectCommitted(commitId: String, context: OperationContext) =
                receipts[commitId]?.let { CommitInspection(it, true, true, true) }
            override fun cleanup(artifact: StagingArtifact, committed: Boolean) { }
        }
        val save = SaveCoordinator(journal, object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                                  context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                transfers++; onTransfer(); progress.onBytes(1_024)
                return TransferResult(artifact.artifactId, 1_024, "video/mp4", 1_024)
            }
        }, ArtifactVerifier { artifact, transfer, _ ->
            verifications++
            VerificationEvidence(artifact.artifactId, transfer.bytesOnDisk, "a".repeat(64), true, 1, 0,
                1_000, 640, 480, false, DecodePosition.entries.toSet(), false)
        }, store, EpochClock { 1_000 }, SaveLimits(1_000_000, 2_000_000), SaveRetryDelay { _, _ -> })
        init { browser.openTab("normal-tab", "browser-session") }
        fun operation(id: String) = OperationContext(id, 20_000, CancellationSource(), resourceBudget = ResourceBudget(1, 2_000_000))
        fun navigate(id: String = "browser-operation"): BrowserRequest {
            val address = PageAddress("https://example.invalid/watch")
            val request = browser.beginNavigation("normal-tab", address, operation(id))
            assertTrue(browser.acceptPageStarted(request, address, 1_000))
            assertTrue(browser.acceptPageFinished(request, address, true, false, 1_000))
            assertEquals(BrowserLoadState.READY, browser.state.selectedTab!!.loadState)
            return request
        }
        fun save(id: String = "save-task"): SaveRecord {
            val selected = SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/video.mp4"))
            val request = SaveRequest(id, "key-$id", selected)
            assertTrue(save.submit(request, operation("operation-$id")) is SaveSubmission.Accepted)
            return requireNotNull(save.execute(id)).also {
                assertEquals(TaskState.SUCCEEDED, it.task.state)
                assertNotNull(it.receipt)
            }
        }
    }

    @Test fun zeroProvidersLeaveBrowserNavigationAndExplicitSaveAvailable() {
        DiscoveryFixture(false).use { discovery ->
            assertEquals(DiscoveryState.NO_SOURCES, discovery.coordinator.status().state)
            assertTrue(discovery.runtime.calls.isEmpty())
            val media = MediaFixture()
            media.navigate(); media.save()
            assertEquals(1, media.transfers); assertEquals(1, media.verifications); assertEquals(1, media.commits)
            assertEquals(DiscoveryState.NO_SOURCES, discovery.coordinator.status().state)
            assertTrue(discovery.runtime.calls.isEmpty())
        }
    }

    @Test fun everyFixedSourceFailureLeavesBrowserAndSaveIndependent() {
        val failures = listOf(DiscoveryError.PROVIDER_FAILURE, DiscoveryError.DEADLINE_EXCEEDED,
            DiscoveryError.SOURCE_CONFIGURATION, DiscoveryError.RATE_LIMITED, DiscoveryError.RESPONSE_TOO_LARGE)
        failures.forEach { failure ->
            DiscoveryFixture(true).use { discovery ->
                assertEquals(1, discovery.runtime.calls.size)
                val media = MediaFixture(); val request = media.navigate()
                discovery.runtime.calls.single().done(ProviderOutcome.Failure(failure, false))
                assertEquals(failure, discovery.coordinator.status().health[CommonsIdentity.PROVIDER_ID]!!.lastError)
                assertTrue(media.browser.isCurrent(request))
                media.save(); media.navigate("next-navigation")
                assertEquals(1, media.transfers); assertEquals(1, media.commits)
                assertEquals(0, discovery.coordinator.status().availableCount)
            }
        }
    }

    @Test fun failedDiscoveryPersistenceDoesNotGateIndependentBrowserOrSave() {
        val failedStore = object : DiscoveryStore {
            override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = throw IllegalStateException("INJECTED_STORAGE_FAILURE")
        }
        DiscoveryFixture(true, failedStore, false).use { discovery ->
            try { discovery.start(); fail("EXPECTED_STORAGE_FAILURE") }
            catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.STORAGE_UNAVAILABLE, failure.code) }
            assertEquals(DiscoveryState.FAILED, discovery.coordinator.status().state)
            assertTrue(discovery.runtime.calls.isEmpty())
            val media = MediaFixture(); media.navigate(); media.save()
            assertEquals(1, media.commits)
        }
    }

    @Test fun mediaOwnerPriorityCancelsDiscoveryAndLateFailureCannotFailSave() {
        DiscoveryFixture(true).use { discovery ->
            val activity = MediaTaskActivityTracker()
            val subscription = activity.addListener { discovery.coordinator.setPriority(it.saveActive, it.recordingActive) }
            val failingObserver = activity.addListener { throw IllegalStateException("INJECTED_OBSERVER_FAILURE") }
            val media = MediaFixture()
            media.onTransfer = {
                activity.update(MediaTaskActivitySignals(executing = true))
                assertTrue(discovery.runtime.calls.single().cancelled)
                assertTrue(discovery.runtime.calls.single().operation.cancellation.isCancelled)
                assertEquals(DiscoveryState.SUSPENDED, discovery.coordinator.status().state)
                discovery.runtime.calls.single().done(ProviderOutcome.Failure(DiscoveryError.PROVIDER_FAILURE))
                discovery.coordinator.requestMore()
                assertEquals(1, discovery.runtime.calls.size)
            }
            val record = media.save()
            assertEquals(TaskState.SUCCEEDED, record.task.state)
            activity.update(MediaTaskActivitySignals(pendingRecording = true))
            assertTrue(activity.snapshot.recordingActive)
            assertEquals(1, discovery.runtime.calls.size)
            activity.update(MediaTaskActivitySignals())
            assertEquals(2, discovery.runtime.calls.size)
            assertEquals(TaskState.SUCCEEDED, media.journal.read("save-task")!!.task.state)
            subscription.close(); failingObserver.close()
        }
    }

    @Test fun browserDiskCloseReopenUsesFreshIdentityAndNeverRestoresIncognitoOrOldCallbacks() {
        val media = MediaFixture(); val prior = media.navigate()
        media.browser.openTab("private-tab", "private-session", true)
        media.browser.beginNavigation("private-tab", PageAddress("https://example.invalid/private"), media.operation("private-operation"))
        val checkpoint = media.browser.persistableSession()
        val directory = Files.createTempDirectory("esv-m9a-browser-")
        val file = directory.resolve("checkpoint.bin")
        try {
            Files.newOutputStream(file).use { it.write(BrowserSessionCodec.encode(checkpoint)) }
            val restored = BrowserSessionController()
            val decoded = Files.newInputStream(file).use { BrowserSessionCodec.decode(it.readBytes()) }
            restored.restore(decoded) { "fresh-$it" }
            assertTrue(restored.persistableSession() == checkpoint)
            assertEquals(1, restored.state.tabs.size)
            assertEquals(BrowserLoadState.IDLE, restored.state.selectedTab!!.loadState)
            assertFalse(restored.state.selectedTab!!.incognito)
            assertFalse(restored.isCurrent(prior))
            assertFalse(restored.acceptPageFinished(prior, requireNotNull(prior.address), false, false, 1_000))
            assertTrue(restored.state.selectedTab!!.context != prior.context)
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory) }
    }

    @Test fun succeededSaveRecordSurvivesCodecDiskReopenAndDiscoveryFailureWithoutReexecution() {
        val media = MediaFixture(); val original = media.save()
        val directory = Files.createTempDirectory("esv-m9a-save-")
        val file = directory.resolve("record.bin")
        try {
            Files.newOutputStream(file).use { it.write(SaveRecordCodec.encode(original)) }
            DiscoveryFixture(true).use { discovery ->
                discovery.runtime.calls.single().done(ProviderOutcome.Failure(DiscoveryError.PROVIDER_FAILURE))
                val restored = Files.newInputStream(file).use { SaveRecordCodec.decode(it.readBytes()) }
                assertTrue(restored == original)
                assertEquals(TaskState.SUCCEEDED, restored.task.state)
                assertTrue(restored.request.networkResource!!.durableReference)
                assertNull(restored.request.networkResource!!.requestContextRef)
                assertEquals(1, media.transfers); assertEquals(1, media.commits)
            }
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory) }
    }
}
