package com.easysavevideo.m10a.review

import com.easysavevideo.app.entitlements.ProductMapping
import com.easysavevideo.core.domain.EntitlementManager
import com.easysavevideo.core.domain.FeatureGate
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Four bounded, ordinary regression checks complementary to the implementation suites. */
class IndependentEntitlementReviewTest {
    private val active = EntitlementGrant(EntitlementId.STREAM_SAVE, GrantState.ACTIVE)
    private val revoked = active.copy(state = GrantState.REVOKED)
    private val other = EntitlementGrant(EntitlementId.RECORD_SAVE, GrantState.ACTIVE)

    private fun value(revision: Long, grants: List<EntitlementGrant>) =
        EntitlementSnapshot(revision, grants, 10, 200, EntitlementSourceState.VALIDATED)

    private fun allowed(snapshot: EntitlementSnapshot, feature: Feature = Feature.STREAM_SAVE, now: Long = 100) =
        FeatureGate().evaluate(FeatureRequest(feature), snapshot, PlatformCapabilities(Feature.entries.toSet()),
            BuildFeaturePolicy(), now).allowed

    private fun mutationRejected(action: () -> Unit) {
        try {
            action()
            fail("Public collection accepted a structural mutation")
        } catch (_: UnsupportedOperationException) {
        } catch (_: ClassCastException) {
        }
    }

    @Test fun snapshotSubviewsAndListIteratorsCannotChangeACopyOrItsSource() {
        val input = mutableListOf(revoked, other)
        val original = value(7, input)
        val copy = original.copy()
        val (_, components) = copy
        val subList = components.subList(0, 1)
        input[0] = active
        mutationRejected { (subList as MutableList<EntitlementGrant>)[0] = active }
        mutationRejected { (subList as MutableList<EntitlementGrant>).clear() }
        mutationRejected {
            (components.listIterator() as MutableListIterator<EntitlementGrant>).apply { next(); set(active) }
        }
        mutationRejected {
            (subList.listIterator() as MutableListIterator<EntitlementGrant>).add(active)
        }
        mutationRejected {
            (subList.listIterator() as MutableListIterator<EntitlementGrant>).apply { next(); remove() }
        }
        assertEquals(listOf(revoked, other), original.grants)
        assertEquals(original, copy)
        assertEquals(listOf(revoked), subList)
        assertFalse(allowed(copy))
    }

    // The baseline fields and order, using Kotlin's generated value methods as an independent oracle.
    private data class BaselineValue(
        val revision: Long = 0,
        val grants: List<EntitlementGrant> = emptyList(),
        val validatedAtEpochMillis: Long? = null,
        val validUntilEpochMillis: Long? = null,
        val sourceState: EntitlementSourceState = EntitlementSourceState.UNKNOWN,
    )

    @Test fun explicitValueApiMatchesBaselineGeneratedValuesAndCopyDefaults() {
        val input = mutableListOf(revoked, other)
        val snapshot = value(0x1_0000_0002L, input)
        val baseline = BaselineValue(snapshot.revision, input.toList(), 10, 200, EntitlementSourceState.VALIDATED)
        val keySet = hashSetOf(snapshot)
        input.clear()
        assertEquals(baseline.hashCode(), snapshot.hashCode())
        assertEquals("EntitlementSnapshot" + baseline.toString().removePrefix("BaselineValue"), snapshot.toString())
        assertEquals(snapshot, snapshot.copy())
        assertTrue(keySet.contains(snapshot.copy()))
        val (revision, grants, validated, until, source) = snapshot
        assertEquals(baseline.revision, revision)
        assertEquals(baseline.grants, grants)
        assertEquals(baseline.validatedAtEpochMillis, validated)
        assertEquals(baseline.validUntilEpochMillis, until)
        assertEquals(baseline.sourceState, source)
        val differences = listOf(snapshot.copy(revision = 5), snapshot.copy(grants = emptyList()),
            snapshot.copy(validatedAtEpochMillis = 11), snapshot.copy(validUntilEpochMillis = 201),
            snapshot.copy(sourceState = EntitlementSourceState.UNKNOWN))
        differences.forEach { assertNotEquals(snapshot, it) }
        assertNotEquals(snapshot, null)
        assertNotEquals(snapshot, baseline)
        assertEquals(BaselineValue().hashCode(), EntitlementSnapshot().hashCode())
        assertEquals(EntitlementSnapshot(snapshot.revision, snapshot.grants),
            snapshot.copy(validatedAtEpochMillis = null, validUntilEpochMillis = null,
                sourceState = EntitlementSourceState.UNKNOWN))
        val replacement = mutableListOf(active)
        val changed = snapshot.copy(grants = replacement)
        replacement.clear()
        assertEquals(listOf(active), changed.grants)
        assertEquals(listOf(revoked, other), snapshot.grants)
    }

    @Test fun productOutputIteratorAndEmptyMappingsCannotAlterFutureLookups() {
        val inputIds = linkedSetOf(EntitlementId.STREAM_SAVE, EntitlementId.RECORD_SAVE)
        val emptyIds = mutableSetOf<EntitlementId>()
        val input = mutableMapOf("review.pair" to inputIds, "review.empty" to emptyIds)
        val mapping = ProductMapping(input)
        val pair = mapping.entitlementsFor("review.pair")
        mutationRejected { (pair.iterator() as MutableIterator<EntitlementId>).apply { next(); remove() } }
        for (id in listOf("review.empty", "review.missing")) {
            mutationRejected { (mapping.entitlementsFor(id) as MutableSet<EntitlementId>).add(EntitlementId.STREAM_SAVE) }
            assertTrue(mapping.entitlementsFor(id).isEmpty())
        }
        inputIds.clear()
        emptyIds.add(EntitlementId.STREAM_SAVE)
        input.clear()
        assertEquals(setOf(EntitlementId.STREAM_SAVE, EntitlementId.RECORD_SAVE), pair)
        assertEquals(pair, mapping.entitlementsFor("review.pair"))
        assertTrue(mapping.entitlementsFor("review.empty").isEmpty())
    }

    @Test fun sourceExceptionPreservesRevocationHighWatermarkAndNewerGrantExpiresExactly() {
        var current = value(40, listOf(active))
        var unavailable = false
        val manager = EntitlementManager(object : EntitlementSource {
            override fun snapshot(): EntitlementSnapshot {
                if (unavailable) throw IllegalStateException("synthetic source failure")
                return current
            }
        })
        assertTrue(allowed(manager.snapshot()))
        current = value(41, listOf(revoked))
        assertFalse(allowed(manager.snapshot()))
        unavailable = true
        val failed = manager.snapshot()
        assertEquals(41L, failed.revision)
        assertEquals(EntitlementSourceState.UNAVAILABLE, failed.sourceState)
        assertTrue(failed.grants.isEmpty())
        assertFalse(allowed(failed))
        unavailable = false
        for (revision in listOf(40L, 41L)) {
            current = value(revision, listOf(active))
            val retained = manager.snapshot()
            assertEquals(41L, retained.revision)
            assertEquals(listOf(revoked), retained.grants)
            assertFalse(allowed(retained))
        }
        current = value(42, listOf(active.copy(expiresAtEpochMillis = 101)))
        val newer = manager.snapshot()
        assertTrue(allowed(newer, now = 100))
        assertFalse(allowed(newer, now = 101))
        for (snapshot in listOf(failed, value(41, listOf(revoked)), newer)) {
            for (feature in listOf(Feature.BROWSER, Feature.DIRECT_SAVE, Feature.MEDIA_LIBRARY, Feature.VAULT)) {
                assertTrue(feature.name, allowed(snapshot, feature, now = 200))
            }
        }
    }
}
