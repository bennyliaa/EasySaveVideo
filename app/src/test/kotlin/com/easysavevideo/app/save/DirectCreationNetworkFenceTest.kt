package com.easysavevideo.app.save

import com.easysavevideo.save.DirectWorkspacePresence
import org.junit.Assert.*
import org.junit.Test

class DirectCreationNetworkFenceTest {
    @Test fun existingTaskNeverConsultsOrStartsNewWorkspace() {
        assertEquals(DirectCreationNetworkDecision.EXISTING, inspectDirectCreationNetwork({ true }, { error("must not inspect") }))
    }
    @Test fun orphanAndUnreadableCannotMintSession() {
        for (presence in listOf(DirectWorkspacePresence.PRESENT, DirectWorkspacePresence.UNREADABLE))
            assertEquals(DirectCreationNetworkDecision.RECOVERY, inspectDirectCreationNetwork({ false }, { presence }))
    }
    @Test fun onlyConfirmedAbsenceAllowsNewSession() {
        val order = mutableListOf<String>()
        assertEquals(DirectCreationNetworkDecision.FRESH, inspectDirectCreationNetwork(
            { order += "save"; false }, { order += "workspace"; DirectWorkspacePresence.ABSENT }))
        assertEquals(listOf("save", "workspace"), order)
    }
    @Test fun failedReadsRemainRecovery() {
        assertEquals(DirectCreationNetworkDecision.RECOVERY, inspectDirectCreationNetwork({ error("read") }, { error("unexpected") }))
        assertEquals(DirectCreationNetworkDecision.RECOVERY, inspectDirectCreationNetwork({ false }, { error("read") }))
    }
}
