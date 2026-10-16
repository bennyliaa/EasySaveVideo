package com.easysavevideo.app.storage

import com.easysavevideo.core.model.VaultAutoLockPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Fixed primitive preference doubles only; the production boundary, no Android or user data. */
class VaultAutoLockPersistenceTest {
    private class Preferences {
        var value = VaultAutoLockPolicy.AFTER_15_MINUTES.key
        var writes = 0
        fun commit(policy: VaultAutoLockPolicy?): Boolean {
            if (policy != null) { value = policy.key; writes++ }
            return true
        }
    }
    @Test fun wrongTypedPrimitiveDefaultsWithoutSwallowingWholeStoreFailure() {
        assertEquals(VaultAutoLockPolicy.IMMEDIATE, readVaultAutoLockPreference { throw ClassCastException() })
        assertEquals(VaultAutoLockPolicy.IMMEDIATE, readVaultAutoLockPreference { null })
        assertEquals(VaultAutoLockPolicy.IMMEDIATE, readVaultAutoLockPreference { "invalid" })
        for (policy in VaultAutoLockPolicy.entries)
            assertEquals(policy, readVaultAutoLockPreference { policy.key })
        assertThrows(IllegalStateException::class.java) { readVaultAutoLockPreference { error("fixed failure") } }
    }
    @Test fun unfinishedAndFailedLoadNeverResolveOrReplaceUnknownKey() {
        val prefs = Preferences()
        repeat(2) { SettingsWriteBoundary.commit(false, { error("unloaded resolver") }, prefs::commit) }
        assertEquals(VaultAutoLockPolicy.AFTER_15_MINUTES.key, prefs.value); assertEquals(0, prefs.writes)
    }
    @Test fun missingUninitializedAndClosedOwnerOmitInsteadOfWritingDefault() {
        val prefs = Preferences()
        repeat(3) { SettingsWriteBoundary.commit(true, { null }, prefs::commit) }
        assertEquals(VaultAutoLockPolicy.AFTER_15_MINUTES.key, prefs.value); assertEquals(0, prefs.writes)
    }
    @Test fun oldAndNewActivityWritersResolveLiveAcceptedValueInBothCommitOrders() {
        for (oldFirst in listOf(true, false)) {
            val prefs = Preferences()
            var live = VaultAutoLockPolicy.AFTER_1_MINUTE
            // Two independently queued writers, with no captured policy argument at the boundary.
            val oldWriter = { SettingsWriteBoundary.commit(true, { live }, prefs::commit) }
            live = VaultAutoLockPolicy.AFTER_5_MINUTES
            val newWriter = { SettingsWriteBoundary.commit(true, { live }, prefs::commit) }
            if (oldFirst) { oldWriter(); newWriter() } else { newWriter(); oldWriter() }
            assertEquals(VaultAutoLockPolicy.AFTER_5_MINUTES.key, prefs.value); assertEquals(2, prefs.writes)
        }
    }
    @Test fun crossInstanceBoundarySerializesResolutionWithCheckedCommit() {
        val prefs = Preferences()
        val live = AtomicReference(VaultAutoLockPolicy.AFTER_1_MINUTE)
        val insideFirst = CountDownLatch(1); val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1); val secondResolved = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>(null)
        val first = Thread {
            try { SettingsWriteBoundary.commit(true, live::get) { policy ->
                insideFirst.countDown(); check(releaseFirst.await(3, TimeUnit.SECONDS)); prefs.commit(policy)
            } } catch (t: Throwable) { errors.set(t) }
        }
        val second = Thread {
            secondStarted.countDown()
            try { SettingsWriteBoundary.commit(true, { secondResolved.countDown(); live.get() }, prefs::commit) }
            catch (t: Throwable) { errors.set(t) }
        }
        first.start()
        try {
            assertTrue(insideFirst.await(3, TimeUnit.SECONDS))
            live.set(VaultAutoLockPolicy.AFTER_5_MINUTES)
            second.start(); assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
            assertFalse(secondResolved.await(50, TimeUnit.MILLISECONDS))
        } finally { releaseFirst.countDown(); first.join(3_000); second.join(3_000) }
        assertFalse(first.isAlive); assertFalse(second.isAlive); errors.get()?.let { throw it }
        assertEquals(0L, secondResolved.count); assertEquals(VaultAutoLockPolicy.AFTER_5_MINUTES.key, prefs.value)
    }
    @Test fun rapidCoalescedChoiceAndLateOldSnapshotCannotRollBack() {
        val prefs = Preferences()
        var live = VaultAutoLockPolicy.IMMEDIATE
        val pending = AtomicReference<(() -> Unit)?>(null)
        for (choice in VaultAutoLockPolicy.entries) {
            live = choice
            pending.set { SettingsWriteBoundary.commit(true, { live }, prefs::commit) }
        }
        pending.getAndSet(null)!!.invoke()
        SettingsWriteBoundary.commit(true, { live }, prefs::commit)
        assertEquals(VaultAutoLockPolicy.AFTER_15_MINUTES.key, prefs.value)
    }
    @Test fun failedCheckedCommitKeepsEffectiveOwnerAndDoesNotClaimSaved() {
        val prefs = Preferences(); val effective = VaultAutoLockPolicy.AFTER_1_MINUTE
        assertThrows(IllegalStateException::class.java) {
            SettingsWriteBoundary.commit(true, { effective }) { false }
        }
        assertEquals(VaultAutoLockPolicy.AFTER_1_MINUTE, effective)
        assertEquals(VaultAutoLockPolicy.AFTER_15_MINUTES.key, prefs.value)
        SettingsWriteBoundary.commit(true, { effective }, prefs::commit)
        assertEquals(effective.key, prefs.value)
    }

    @Test fun loadInvalidatedWhileOldWriterWaitsOmitsKeyAndDoesNotResolveOwner() {
        val prefs = Preferences()
        val holderEntered = CountDownLatch(1); val releaseHolder = CountDownLatch(1)
        val oldStarted = CountDownLatch(1); val loaded = java.util.concurrent.atomic.AtomicBoolean(true)
        val resolverCalls = java.util.concurrent.atomic.AtomicInteger()
        val errors = AtomicReference<Throwable?>(null)
        val holder = Thread {
            try { SettingsWriteBoundary.commit(false, { null }) {
                holderEntered.countDown(); check(releaseHolder.await(3, TimeUnit.SECONDS)); true
            } } catch (t: Throwable) { errors.set(t) }
        }
        val old = Thread {
            oldStarted.countDown()
            try { SettingsWriteBoundary.commit(true, {
                if (loaded.get()) { resolverCalls.incrementAndGet(); VaultAutoLockPolicy.AFTER_1_MINUTE } else null
            }, prefs::commit) } catch (t: Throwable) { errors.set(t) }
        }
        holder.start()
        try {
            assertTrue(holderEntered.await(3, TimeUnit.SECONDS)); old.start(); assertTrue(oldStarted.await(3, TimeUnit.SECONDS))
            loaded.set(false)
        } finally { releaseHolder.countDown(); holder.join(3_000); old.join(3_000) }
        assertFalse(holder.isAlive); assertFalse(old.isAlive); errors.get()?.let { throw it }
        assertEquals(0, resolverCalls.get()); assertEquals(0, prefs.writes)
        assertEquals(VaultAutoLockPolicy.AFTER_15_MINUTES.key, prefs.value)
    }
}
