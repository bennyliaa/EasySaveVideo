package com.easysavevideo.save

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SaveWifiCoordinatorTest {
    @Test fun gateWaitsWithoutTransferAndOnlyExplicitResumeCanContinue() {
        var allowed = false
        val h = SaveHarness(networkGate = SaveNetworkGate { if (!allowed) throw SaveFailure(SaveError.WIFI_REQUIRED) })
        val waiting = h.run()
        assertEquals(TaskState.WAITING_USER, waiting.task.state)
        assertEquals(SaveWaitReason.NETWORK, waiting.waitReason)
        assertEquals(SaveError.WIFI_REQUIRED, waiting.error)
        assertNull(waiting.artifact); assertEquals(0, h.transfers)
        allowed = true
        assertEquals(waiting, h.coordinator.execute("task"))
        h.coordinator.resume("task", h.operation("new-command"))
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("task")!!.task.state)
        assertEquals(1, h.transfers)
    }
    @Test fun resumeRechecksInsteadOfTreatingButtonAsPermission() {
        val h = SaveHarness(networkGate = SaveNetworkGate { throw SaveFailure(SaveError.WIFI_REQUIRED) })
        h.run(); h.coordinator.resume("task", h.operation("next"))
        assertEquals(SaveWaitReason.NETWORK, h.coordinator.execute("task")!!.waitReason)
        assertEquals(0, h.transfers)
    }
    @Test fun explicitCancellationBeatsNetworkWait() {
        lateinit var h: SaveHarness
        h = SaveHarness(networkGate = SaveNetworkGate {
            h.coordinator.signalUserCancel("task"); throw SaveFailure(SaveError.WIFI_REQUIRED)
        })
        assertEquals(TaskState.CANCELLED, h.run().task.state)
        assertEquals(0, h.transfers)
    }
    @Test fun schedulingDeferralUsesNetworkReasonAndCancellationWins() {
        val h = SaveHarness(); h.submit()
        assertEquals(SaveWaitReason.NETWORK, h.coordinator.deferForNetwork("task")!!.waitReason)
        assertEquals(0, h.transfers)
        h.coordinator.cancel("task")
        val terminal = h.coordinator.record("task")!!
        assertEquals(TaskState.CANCELLED, terminal.task.state)
        assertEquals(terminal, h.coordinator.deferForNetwork("task"))
        val other = SaveHarness(); other.submit(); other.coordinator.signalUserCancel("task")
        assertEquals(TaskState.CANCELLED, other.coordinator.deferForNetwork("task")!!.task.state)
    }
    @Test fun completedTransferLocalWorkAndPublishedSuccessIgnoreLaterNetworkChanges() {
        var allowed = true
        val h = SaveHarness(networkGate = SaveNetworkGate { if (!allowed) throw SaveFailure(SaveError.WIFI_REQUIRED) },
            verifyBlock = { artifact, result, _ -> allowed = false; goodEvidence(artifact, result) })
        val success = h.run()
        assertEquals(TaskState.SUCCEEDED, success.task.state)
        assertEquals(success, h.coordinator.deferForNetwork("task"))
        assertEquals(success, h.coordinator.execute("task"))
        assertEquals(1, h.store.commitCalls)
    }
    @Test fun policyFailurePreservesExistingDirectCheckpoint() {
        var attempt = 0
        val h = SaveHarness(transferBlock = { _, artifact, checkpoint, _, progress ->
            if (attempt++ == 0) {
                progress.onBytes(512)
                progress.onCheckpoint(DownloadCheckpoint(artifact.artifactId, "a".repeat(64), 512, "\"etag\"", 1024))
                throw SaveFailure(SaveError.WIFI_REQUIRED)
            }
            assertEquals(512L, checkpoint!!.offset)
            progress.onBytes(512)
            TransferResult(artifact.artifactId, 1024, "video/mp4", 1024)
        })
        val waiting = h.run()
        assertEquals(SaveWaitReason.NETWORK, waiting.waitReason)
        assertNotNull(waiting.checkpoint); assertTrue(h.store.cleanup.isEmpty())
        h.coordinator.resume("task", h.operation("retry"))
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("task")!!.task.state)
    }
    @Test fun unreleasedResourceCannotMasqueradeAsRecoverableWifiWait() {
        var unreleased = false
        val h = SaveHarness(releaseUncertain = { unreleased }, transferBlock = { _, _, _, _, _ ->
            unreleased = true; throw SaveFailure(SaveError.WIFI_REQUIRED)
        })
        val result = h.run()
        assertEquals(SaveWaitReason.RECOVERY, result.waitReason)
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, result.error)
        assertTrue(h.store.cleanup.isEmpty())
    }
}
