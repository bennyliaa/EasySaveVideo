package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Ordinary caller aliasing fixtures only; no store, real identity or payment. */
class EntitlementImmutabilityTest {
    private val active = EntitlementGrant(EntitlementId.STREAM_SAVE, GrantState.ACTIVE)
    private val revoked = active.copy(state = GrantState.REVOKED)
    private val free = listOf(Feature.BROWSER, Feature.DIRECT_SAVE, Feature.MEDIA_LIBRARY, Feature.VAULT)
    private fun snapshot(grants: List<EntitlementGrant>, revision: Long = 1) =
        EntitlementSnapshot(revision, grants, 10, 200, EntitlementSourceState.VALIDATED)
    private fun allowed(value: EntitlementSnapshot, feature: Feature = Feature.STREAM_SAVE, now: Long = 100) =
        FeatureGate().evaluate(FeatureRequest(feature), value, PlatformCapabilities(Feature.entries.toSet()),
            BuildFeaturePolicy(), now).allowed
    private fun cannotMutate(action: () -> Unit) {
        try { action(); fail("Public collection accepted mutation") }
        catch (_: UnsupportedOperationException) { }
        catch (_: ClassCastException) { }
    }

    @Test fun constructorCopiesInputBeforeRetainingSnapshot() {
        val input = mutableListOf(revoked)
        val value = snapshot(input)
        input[0] = active
        input.add(active.copy(entitlementId = EntitlementId.RECORD_SAVE))
        assertEquals(listOf(revoked), value.grants)
        assertFalse(allowed(value))
    }
    @Test fun copyDefensivelyCopiesItsNewInput() {
        val input = mutableListOf(revoked)
        val value = snapshot(listOf(active)).copy(grants = input)
        input.clear(); input.add(active)
        assertEquals(listOf(revoked), value.grants)
        assertFalse(allowed(value))
    }
    @Test fun returnedListCannotBeModifiedThroughMutableCastOrIterator() {
        val value = snapshot(listOf(revoked, active.copy(entitlementId = EntitlementId.RECORD_SAVE)))
        cannotMutate { (value.grants as MutableList<EntitlementGrant>)[0] = active }
        cannotMutate { (value.grants.iterator() as MutableIterator<EntitlementGrant>).apply { next(); remove() } }
        assertEquals(2, value.grants.size)
        assertFalse(allowed(value))
    }
    @Test fun destructuredListCannotBeModified() {
        val (_, grants) = snapshot(listOf(revoked))
        cannotMutate { (grants as MutableList<EntitlementGrant>).clear() }
        assertEquals(listOf(revoked), grants)
    }
    @Test fun emptyAndSingleItemOutputsAreAlsoImmutable() {
        for (value in listOf(snapshot(emptyList()), snapshot(listOf(revoked)))) {
            val expected = value.grants.toList()
            cannotMutate { (value.grants as MutableList<EntitlementGrant>).add(active) }
            assertEquals(expected, value.grants)
        }
    }
    @Test fun originalAliasesCannotChangeObservedManagerRevisionOrGate() {
        val input = mutableListOf(revoked)
        val current = snapshot(input)
        val manager = EntitlementManager(object : EntitlementSource { override fun snapshot() = current })
        val observed = manager.snapshot()
        input[0] = active
        assertEquals(1L, manager.snapshot().revision)
        assertEquals(listOf(revoked), observed.grants)
        assertFalse(allowed(manager.snapshot()))
        free.forEach { assertTrue(allowed(manager.snapshot(), it)) }
    }
    @Test fun managerReturnCannotRegrantSameRevisionOrChangeFutureReads() {
        val manager = EntitlementManager(object : EntitlementSource { override fun snapshot() = snapshot(listOf(revoked)) })
        cannotMutate { (manager.snapshot().grants as MutableList<EntitlementGrant>)[0] = active }
        assertFalse(allowed(manager.snapshot()))
    }
    @Test fun sameAndRollbackRevisionDoNotRegrantAfterNewRevocation() {
        var current = snapshot(listOf(active))
        val manager = EntitlementManager(object : EntitlementSource { override fun snapshot() = current })
        assertTrue(allowed(manager.snapshot()))
        current = snapshot(listOf(revoked), 2)
        assertFalse(allowed(manager.snapshot()))
        current = snapshot(listOf(active), 2)
        assertFalse(allowed(manager.snapshot()))
        current = snapshot(listOf(active), 1)
        assertFalse(allowed(manager.snapshot()))
        current = snapshot(listOf(active), 3)
        assertTrue(allowed(manager.snapshot()))
    }
    @Test fun expiryAndRevocationKeepFreeCapabilitiesAvailable() {
        val values = listOf(snapshot(listOf(revoked)), snapshot(listOf(active.copy(expiresAtEpochMillis = 100))),
            snapshot(listOf(active)).copy(validUntilEpochMillis = 100))
        for (value in values) {
            assertFalse(allowed(value))
            free.forEach { assertTrue(allowed(value, it)) }
        }
    }
    @Test fun sourceFailureCannotExposeCachedPaidGrantButPreservesFreeGate() {
        var failSource = false
        val current = snapshot(listOf(active))
        val manager = EntitlementManager(object : EntitlementSource {
            override fun snapshot(): EntitlementSnapshot { check(!failSource); return current }
        })
        assertTrue(allowed(manager.snapshot()))
        failSource = true
        val unavailable = manager.snapshot()
        assertEquals(EntitlementSourceState.UNAVAILABLE, unavailable.sourceState)
        assertTrue(unavailable.grants.isEmpty())
        assertFalse(allowed(unavailable))
        free.forEach { assertTrue(allowed(unavailable, it)) }
        cannotMutate { (unavailable.grants as MutableList<EntitlementGrant>).add(active) }
        assertEquals(listOf(active), current.grants)
    }
    @Test fun valueEqualityHashAndCopyCompatibilityRemainStableAfterInputChanges() {
        val input = mutableListOf(revoked)
        val value = snapshot(input)
        val equal = snapshot(listOf(revoked))
        val hash = value.hashCode()
        input.clear()
        assertEquals(equal, value)
        assertEquals(hash, value.hashCode())
        assertEquals(value, value.copy())
        val (revision, grants, validated, until, state) = value
        assertEquals(1L, revision); assertEquals(listOf(revoked), grants)
        assertEquals(10L, validated); assertEquals(200L, until); assertEquals(EntitlementSourceState.VALIDATED, state)
    }
    @Test fun copiedInputsRetainValidationAndUniquenessRequirements() {
        assertThrows(IllegalArgumentException::class.java) { snapshot(listOf(active, revoked)) }
        assertThrows(IllegalArgumentException::class.java) { snapshot(emptyList()).copy(revision = -1) }
        assertThrows(IllegalArgumentException::class.java) { snapshot(emptyList()).copy(validUntilEpochMillis = 9) }
    }
}
