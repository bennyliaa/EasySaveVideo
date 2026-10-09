package com.easysavevideo.save

import com.easysavevideo.core.model.*

/** Test actors only. These never establish that a real MP4 was downloaded or decoded. */
internal class MemorySaveJournal : SaveJournal {
    val records = linkedMapOf<String, SaveRecord>()
    var rejectSuccess = false
    var writes = 0
    var beforeSuccess: (() -> Unit)? = null
    @Volatile var beforeRead: (() -> Unit)? = null
    @Synchronized override fun findByIdempotencyKey(key: String) = records.values.firstOrNull { it.request.idempotencyKey == key }
    @Synchronized override fun read(taskId: String): SaveRecord? { beforeRead?.invoke(); return records[taskId] }
    @Synchronized override fun insert(record: SaveRecord, maxEntries: Int): SaveRecord {
        findByIdempotencyKey(record.request.idempotencyKey)?.let { return it }
        check(records.size < maxEntries && !records.containsKey(record.task.taskId))
        records[record.task.taskId] = record; return record
    }
    @Synchronized override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean {
        if (rejectSuccess && next.task.state == TaskState.SUCCEEDED) return false
        if (next.task.state == TaskState.SUCCEEDED) beforeSuccess?.invoke()
        val current = records[next.task.taskId] ?: return false
        if (current.task.revision != expectedRevision || next.task.revision != expectedRevision + 1) return false
        records[next.task.taskId] = next; writes++; return true
    }
    @Synchronized override fun listUnfinished(limit: Int) = records.values.filter { !it.task.state.isTerminal }.take(limit)
    @Synchronized override fun listRecent(limit: Int) = records.values.toList().takeLast(limit).reversed()
}
internal class FakeSaveClock(var now: Long = 0) : EpochClock { override fun nowMillis() = now }
internal open class FakeMediaStore : PrivateMediaStore {
    val committed = linkedMapOf<String, MediaCommitReceipt>()
    val cleanup = mutableListOf<Pair<StagingArtifact, Boolean>>()
    var commitCalls = 0
    var inspected = true
    override fun prepare(taskId: String, commitId: String, context: OperationContext) = StagingArtifact(taskId, commitId)
    override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt {
        commitCalls++
        return committed.getOrPut(artifact.artifact.commitId) { MediaCommitReceipt(artifact.artifact.commitId,
            artifact.artifact.artifactId, "media-${artifact.artifact.commitId}", artifact.evidence.sizeBytes, artifact.evidence.sha256) }
    }
    override fun inspectCommitted(commitId: String, context: OperationContext) = committed[commitId]?.let { CommitInspection(it, true, inspected, true) }
    override fun cleanup(artifact: StagingArtifact, committed: Boolean) { cleanup.add(artifact to committed) }
}
internal fun goodEvidence(artifact: StagingArtifact, result: TransferResult) = VerificationEvidence(
    artifact.artifactId, result.bytesOnDisk, "a".repeat(64), true, 1, 0, 1_000, 1920, 1080,
    false, DecodePosition.entries.toSet(), false, result.streamExpectation?.conservation,
    result.streamExpectation?.conservation?.let { StreamTailProof(it.videoTail.precedingSync, it.videoTail.lastSample, it.video.lastSample.presentationTimeUs, true) })
internal class SaveHarness(
    val journal: MemorySaveJournal = MemorySaveJournal(),
    val clock: FakeSaveClock = FakeSaveClock(),
    val store: FakeMediaStore = FakeMediaStore(),
    val limits: SaveLimits = SaveLimits(10_000_000, 30_000_000, waitingUserLifetimeMillis = 100),
    transferBlock: ((SelectedMediaResource, StagingArtifact, DownloadCheckpoint?, OperationContext, TransferProgress) -> TransferResult)? = null,
    verifyBlock: ((StagingArtifact, TransferResult, OperationContext) -> VerificationEvidence)? = null,
    delay: SaveRetryDelay = SaveRetryDelay { _, _ -> },
    releaseUncertain: () -> Boolean = { false },
) {
    var transfers = 0
    var verifications = 0
    val coordinator = SaveCoordinator(journal, object : DirectTransferPort {
        override fun requiresProcessRecovery() = releaseUncertain()
        override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?, context: OperationContext,
                              limits: SaveLimits, progress: TransferProgress): TransferResult {
            transfers++
            if (transferBlock != null) return transferBlock(resource, artifact, checkpoint, context, progress)
            progress.onBytes(1024)
            return TransferResult(artifact.artifactId, 1024, "video/mp4", 1024)
        }
    }, ArtifactVerifier { artifact, result, context ->
        verifications++
        verifyBlock?.invoke(artifact, result, context) ?: goodEvidence(artifact, result)
    }, store, clock, limits, delay)
    fun request(id: String = "task") = SaveRequest(id, "key-$id", SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/video.mp4")))
    fun operation(id: String = "operation", deadline: Long = 10_000, retries: Int = 2, cancellation: CancellationToken = CancellationSource()) =
        OperationContext(id, deadline, cancellation, retries, resourceBudget = ResourceBudget(1, limits.maxTotalTransferBytes))
    fun submit() = coordinator.submit(request(), operation())
    fun run(): SaveRecord { submit(); return coordinator.execute("task")!! }
}

internal fun testStreamConservation(audio: Boolean = false): StreamConservationContract {
    val video = StreamSampleAccumulator(StreamSampleKind.AVC)
    repeat(20) { index -> video.add(index * 100_000L, if (index % 10 == 0) 1 else 0,
        byteArrayOf(0, 0, 1, if (index % 10 == 0) 0x65 else 0x41, (index + 1).toByte())) }
    val sound = if (audio) StreamSampleAccumulator(StreamSampleKind.AAC, 48_000).apply {
        repeat(94) { index -> add(index * 1_024_000_000L / 48_000, 1, byteArrayOf(1, index.toByte(), 3)) }
    }.finish() else null
    return StreamConservationContract(video.finish(), sound, if (audio) 0 else null, video.videoTail(), video.endpoint(), if (audio) 2 else null)
}
