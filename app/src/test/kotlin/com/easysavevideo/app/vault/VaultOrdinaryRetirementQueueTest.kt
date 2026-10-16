package com.easysavevideo.app.vault

import com.easysavevideo.library.MediaReadRef
import org.junit.Assert.*
import org.junit.Test

class VaultOrdinaryRetirementQueueTest {
    @Test fun routeLossBeforeRealCacheReleasePreservesAckUntilPresentationIsAbsentAgain() {
        var absent = true; var callback: (() -> Unit)? = null; var ack = 0; var purges = 0
        val queue = VaultOrdinaryRetirementQueue({ absent }, { _, done -> purges++; callback = done })
        queue.request(MediaReadRef("fixture", 1, 1)) { ack++ }
        absent = false; callback!!.invoke(); assertEquals(0, ack)
        queue.progress(); assertEquals(0, ack)
        absent = true; queue.progress(); assertEquals(1, ack); assertEquals(1, purges)
        queue.progress(); assertEquals(1, ack)
    }
    @Test fun requestingWhileOrdinaryContentExistsCannotStartPurgeOrAcknowledge() {
        var absent = false; var ack = 0; var purges = 0
        val queue = VaultOrdinaryRetirementQueue({ absent }, { _, done -> purges++; done() })
        queue.request(MediaReadRef("fixture", 1, 1)) { ack++ }
        assertEquals(0, purges); assertEquals(0, ack)
        absent = true; queue.progress(); assertEquals(1, purges); assertEquals(1, ack)
    }
}
