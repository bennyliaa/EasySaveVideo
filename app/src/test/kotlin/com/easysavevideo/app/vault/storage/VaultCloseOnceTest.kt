package com.easysavevideo.app.vault.storage

import com.easysavevideo.library.export.ExportRelease
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fake resource release only; never equates a timeout or thrown close to release. */
class VaultCloseOnceTest {
    @Test fun throwingCloseIsNotRetriedOrAcknowledged() {
        var attempts = 0
        val close = VaultCloseOnce({ attempts++; error("fixed failure") }, { false })
        repeat(3) { assertEquals(ExportRelease.UNCONFIRMED, close.closeAndObserve()) }
        assertEquals(1, attempts)
    }
    @Test fun closedChannelFacadeCannotClearThrownPhysicalClose() {
        var facadeOpen = true; var attempts = 0
        val close = VaultCloseOnce({ attempts++; facadeOpen = false; error("native close failed") }, { !facadeOpen })
        repeat(3) { assertEquals(ExportRelease.UNCONFIRMED, close.closeAndObserve()) }
        assertEquals(1, attempts)
    }
    @Test fun laterActualObservationCanAcknowledgeWithoutAnotherClose() {
        var attempts = 0; var released = false
        val close = VaultCloseOnce({ attempts++ }, { released })
        assertEquals(ExportRelease.UNCONFIRMED, close.closeAndObserve())
        released = true
        assertEquals(ExportRelease.RELEASED, close.closeAndObserve())
        assertEquals(1, attempts)
    }
    @Test fun blockedCloseDoesNotLetAnotherObserverReturnEarly() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val returned = CountDownLatch(2)
        var attempts = 0; var released = false
        val close = VaultCloseOnce({ attempts++; entered.countDown(); finish.await(); released = true }, { released })
        val first = Thread { close.closeAndObserve(); returned.countDown() }
        val second = Thread { close.closeAndObserve(); returned.countDown() }
        first.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); second.start()
        try { assertFalse(returned.await(50, TimeUnit.MILLISECONDS)) } finally { finish.countDown() }
        assertTrue(returned.await(2, TimeUnit.SECONDS)); first.join(); second.join()
        assertEquals(1, attempts)
    }
}
