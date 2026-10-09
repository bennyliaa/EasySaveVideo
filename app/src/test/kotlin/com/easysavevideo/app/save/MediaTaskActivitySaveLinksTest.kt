package com.easysavevideo.app.save

import com.easysavevideo.app.discovery.DiscoveryOriginHistoryOutcome
import com.easysavevideo.app.discovery.DiscoveryOriginMediaOutcome
import com.easysavevideo.app.discovery.DiscoveryOriginSaveOutcome
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test

class MediaTaskActivitySaveLinksTest {
    private fun record(state: TaskState = TaskState.SUCCEEDED, receipt: Boolean = true): SaveRecord {
        val verification = VerificationEvidence("artifact", 100, "a".repeat(64), true, 1, 0, 5_000,
            1280, 720, false, DecodePosition.entries.toSet(), true)
        return SaveRecord(
            SaveRequest("task", "key", SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/media"))
                .asDurableReference()),
            TaskSnapshot("task", 2, state, TaskMethod.DIRECT),
            "commit", "operation", 10_000, 0, phase = if (state == TaskState.SUCCEEDED) SavePhase.CLEANUP else SavePhase.COMMITTING,
            artifact = StagingArtifact("artifact", "commit"),
            transfer = TransferResult("artifact", 100, "video/mp4", 100), verification = verification,
            receipt = if (receipt) MediaCommitReceipt("commit", "artifact", "media", 100, "a".repeat(64)) else null,
        )
    }
    private class Origin(val name: String = "origin") {
        var bound: String? = null
        var completions = mutableListOf<Pair<String, Boolean>>()
        var abandoned = 0
    }
    private fun links(complete: (Origin, String, Boolean) -> Unit = { o, id, committed -> o.completions += id to committed }) =
        MediaTaskActivitySaveLinks<Origin>(
            bind = { o, id -> o.bound = id; true }, complete = complete, abandon = { it.abandoned++ },
        )

    @Test fun acceptedOrCommittingWithProvisionalReceiptIsNotSuccess() {
        assertFalse(mediaTaskHasCommittedSave("task", record(TaskState.QUEUED, false)))
        assertFalse(mediaTaskHasCommittedSave("task", record(TaskState.RUNNING, false)))
        assertFalse(mediaTaskHasCommittedSave("task", record(TaskState.RUNNING)))
        assertFalse(mediaTaskHasCommittedSave("task", null))
        assertTrue(mediaTaskHasCommittedSave("task", record()))
        assertFalse(mediaTaskHasCommittedSave("unrelated", record()))
    }

    @Test fun cancelledAndFailedRecordsNeverEmitSaved() {
        val links = links()
        val cancelled = Origin()
        links.accepted("task", cancelled)
        links.afterExecution("task") { record(TaskState.CANCELLED) }
        assertEquals(listOf("task" to false), cancelled.completions)
        val failed = Origin()
        links.accepted("task", failed)
        links.afterExecution("task") { record(TaskState.FAILED) }
        assertEquals(listOf("task" to false), failed.completions)
    }

    @Test fun committedCompletionConsumesExactlyOnce() {
        val links = links()
        val origin = Origin()
        links.accepted("task", origin)
        var reads = 0
        repeat(3) { links.afterExecution("task") { reads++; record() } }
        assertEquals(1, reads)
        assertEquals(listOf("task" to true), origin.completions)
    }

    @Test fun cancelClearsAssociationAndLateSuccessCannotReadOrRecord() {
        val links = links()
        val origin = Origin()
        links.accepted("task", origin)
        links.cancel("task"); links.cancel("task")
        links.afterExecution("task") { error("Cancelled link must not inspect a late outcome") }
        assertEquals(1, origin.abandoned)
        assertTrue(origin.completions.isEmpty())
    }

    @Test fun taskIdentityIsBoundAndOtherTasksDoNotConsumeTheLink() {
        val links = links()
        val first = Origin("first")
        val second = Origin("second")
        assertTrue(links.accepted("task", first))
        assertTrue(links.accepted("other", second))
        links.afterExecution("unrelated") { error("Unknown task") }
        links.cancel("other")
        links.afterExecution("task") { record() }
        assertEquals("task", first.bound)
        assertEquals(listOf("task" to true), first.completions)
        assertTrue(second.completions.isEmpty())
        assertEquals(1, second.abandoned)
    }

    @Test fun noOriginResumeAndRestartNeverInferAnAssociationFromStoredSuccess() {
        val old = links()
        old.accepted("task", Origin())
        val restarted = links()
        assertFalse(restarted.accepted("task", null))
        restarted.afterExecution("task") { error("Recovery must not recreate a link") }
        val stale = Origin()
        assertFalse(restarted.accepted("task", stale, existing = true))
        restarted.afterExecution("task") { error("Existing acceptance must not recreate a link") }
        assertEquals(1, stale.abandoned)
    }

    @Test fun finalRecordReadFailureIsSeparateFromMediaExecution() {
        val origin = Origin()
        val links = links()
        links.accepted("task", origin)
        links.afterExecution("task") { throw IllegalStateException() }
        assertEquals(listOf("task" to false), origin.completions)
        links.afterExecution("task") { error("No repeat after failure") }
    }

    @Test fun historyFailureCannotThrowIntoSaveOrCauseASecondCompletion() {
        var historyWrites = 0
        val origin = Origin()
        val links = links { _, _, committed -> assertTrue(committed); historyWrites++; throw IllegalStateException() }
        links.accepted("task", origin)
        links.afterExecution("task") { record() }
        links.afterExecution("task") { error("No retry of a media outcome") }
        assertEquals(1, historyWrites)
        assertEquals(1, origin.abandoned)
        assertTrue(mediaTaskHasCommittedSave("task", record()))
    }

    @Test fun bindingFailureAndCapacityRejectionAbandonWithoutOwningTask() {
        val origin = Origin()
        val failed = MediaTaskActivitySaveLinks<Origin>(bind = { _, _ -> throw IllegalStateException() },
            complete = { _, _, _ -> error("Unbound") }, abandon = { it.abandoned++ })
        assertFalse(failed.accepted("task", origin))
        assertEquals(1, origin.abandoned)
        val bounded = MediaTaskActivitySaveLinks<Origin>(bind = { _, _ -> true },
            complete = { _, _, _ -> }, abandon = { it.abandoned++ }, capacity = 1)
        assertTrue(bounded.accepted("task", Origin()))
        val rejected = Origin()
        assertFalse(bounded.accepted("other", rejected))
        assertEquals(1, rejected.abandoned)
    }

    @Test fun unknownVideoIdentityDoesNotClaimDiscoveryVideoWasSaved() {
        val outcome = DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.COMMITTED, DiscoveryOriginHistoryOutcome.IDENTITY_UNVERIFIED)
        assertEquals("媒体保存已完成；仅能确认来自此页面，发现视频的保存状态仍未知。", mediaTaskDiscoveryOutcomeLabel(outcome))
        assertEquals(DiscoveryOriginMediaOutcome.COMMITTED, outcome.media)
    }

    @Test fun historyFailureLabelKeepsMediaSuccessExplicit() {
        val outcome = DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.COMMITTED, DiscoveryOriginHistoryOutcome.HISTORY_FAILED)
        assertEquals("媒体保存已完成；发现历史未记录。", mediaTaskDiscoveryOutcomeLabel(outcome))
        assertEquals(DiscoveryOriginMediaOutcome.COMMITTED, outcome.media)
    }
}
