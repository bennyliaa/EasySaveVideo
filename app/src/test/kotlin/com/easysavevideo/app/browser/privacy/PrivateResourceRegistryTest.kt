package com.easysavevideo.app.browser.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivateResourceRegistryTest {
    @Test fun reservationCountsBeforeNativeAllocation() {
        val registry = PrivateResourceRegistry<Any>(1)
        val token = registry.reserve()!!; assertNull(registry.reserve())
        registry.cancel(token); assertNotNull(registry.reserve())
    }
    @Test fun successfulReleaseFreesCapacity() {
        val registry = PrivateResourceRegistry<Any>(1); val resource = Any()
        registry.publish(registry.reserve()!!, resource); assertFalse(registry.hasCapacity())
        registry.released(resource); assertTrue(registry.hasCapacity())
    }
    @Test fun quarantineSurvivesLostHostReferenceAndCannotBePretendedReleased() {
        val registry = PrivateResourceRegistry<Any>(1); val resource = Any()
        registry.publish(registry.reserve()!!, resource); registry.quarantine(resource)
        // Host disposal or a late duplicate acknowledgement cannot retire uncertain process ownership.
        registry.released(resource); registry.released(resource)
        assertTrue(registry.isQuarantined(resource)); assertFalse(registry.hasCapacity()); assertNull(registry.reserve())
    }
    @Test fun noCapacityLimitCanEvictAnOlderQuarantinedResource() {
        val registry = PrivateResourceRegistry<Any>(2); val a = Any(); val b = Any()
        registry.publish(registry.reserve()!!, a); registry.quarantine(a)
        registry.publish(registry.reserve()!!, b); registry.quarantine(b)
        assertNull(registry.reserve()); assertTrue(registry.isQuarantined(a)); assertTrue(registry.isQuarantined(b))
    }
}
