package com.easysavevideo.app.discovery

import org.junit.Assert.*
import org.junit.Test

class VerificationConfigurationTest {
    private val endpoint = "https://verify.publicdomain.org/v1/verify"
    @Test fun defaultHasNoServiceOrNetworkSideEffects() {
        var calls = 0
        val controller = VerificationConfigurationController({ calls++ }, { calls++ })
        assertNull(controller.state.endpoint); assertFalse(controller.state.dynamicAllowed); assertEquals(0, calls)
    }
    @Test fun explicitAcknowledgementRequired() {
        val c = VerificationConfigurationController({ fail() }, { fail() })
        assertFalse(c.enable(c.claim(), endpoint, false, false)); assertNull(c.state.endpoint)
    }
    @Test fun invalidEndpointCannotFenceOrBecomeConfigured() {
        val c = VerificationConfigurationController({ fail() }, { fail() })
        listOf("http://verify.publicdomain.org/v1/verify", "https://127.0.0.1/v1/verify", "https://user:pass@verify.publicdomain.org/v1/verify",
            "https://verify.publicdomain.org/search", "https://verify.publicdomain.org/v1/verify?key=secret").forEach {
            assertFalse(c.enable(c.claim(), it, false, true))
        }
    }
    @Test fun fenceOccursBeforeNewConfigurationPublished() {
        lateinit var c: VerificationConfigurationController
        var calls = 0
        c = VerificationConfigurationController({ assertNull(c.state.endpoint); calls++ }, { assertNotNull(c.state.endpoint); calls++ })
        assertTrue(c.enable(c.claim(), endpoint, true, true)); assertEquals(2, calls)
    }
    @Test fun staleDialogCannotEnableOrDisableNewerConfiguration() {
        val c = VerificationConfigurationController({}, {})
        val old = c.claim(); assertTrue(c.enable(old, endpoint, false, true))
        assertFalse(c.enable(old, endpoint, true, true)); assertFalse(c.disable(old)); assertFalse(c.state.dynamicAllowed)
    }
    @Test fun editAwayAndBackHasDistinctGeneration() {
        val c = VerificationConfigurationController({}, {})
        c.enable(c.claim(), endpoint, false, true); val first = c.state.generation
        c.disable(c.claim()); c.enable(c.claim(), endpoint, false, true)
        assertNotEquals(first, c.state.generation); assertEquals(3, c.state.revision)
    }
    @Test fun separateProcessDefaultsDoNotRestoreConsent() {
        val first = VerificationConfigurationController({}, {}); first.enable(first.claim(), endpoint, true, true)
        val second = VerificationConfigurationController({}, {})
        assertNull(second.state.endpoint); assertNotEquals(first.state.generation, second.state.generation)
    }
    @Test fun sessionGenerationChangesCheckpointCompatibility() {
        val c = VerificationConfigurationController({}, {}); val before = VerificationConfigurationPolicy.configurationKey("source", c.state)
        c.enable(c.claim(), endpoint, false, true)
        assertNotEquals(before, VerificationConfigurationPolicy.configurationKey("source", c.state))
    }
    @Test fun reentrantCallbackCannotOverwriteNewerConsent() {
        lateinit var c: VerificationConfigurationController
        c = VerificationConfigurationController({ assertFalse(c.enable(c.claim(), endpoint, true, true)) }, {})
        assertTrue(c.enable(c.claim(), endpoint, false, true)); assertFalse(c.state.dynamicAllowed)
    }
    @Test fun busyFenceCoversOldAndNewSnapshotPublication() {
        lateinit var c: VerificationConfigurationController
        c = VerificationConfigurationController({ assertTrue(c.busy); assertNull(c.current.endpoint) }, {
            assertFalse(c.busy); assertSame(it, c.current)
        })
        assertTrue(c.enable(c.claim(), endpoint, false, true)); assertFalse(c.busy)
    }
    @Test fun subscriberAttachedDuringFenceCannotRunOldConfiguration() {
        val h = VerificationConfigurationHub(); val events = mutableListOf<String>()
        h.subscribe({ h.subscribe({ events += "new-fence" }, { events += if (h.controller.busy) "old-busy" else "new-current" }) }, {})
        h.controller.enable(h.controller.claim(), endpoint, false, true)
        assertEquals(listOf("new-fence", "old-busy", "new-current"), events)
    }
    @Test fun redactedDiagnosticStrings() {
        val c = VerificationConfigurationController({}, {}); c.enable(c.claim(), endpoint, false, true)
        assertFalse(c.state.toString().contains(endpoint)); assertFalse(c.claim().toString().contains(c.state.generation))
    }
    @Test fun sharedHubFencesAllOwnersBeforePublishing() {
        val h = VerificationConfigurationHub(); val events = mutableListOf<String>()
        h.subscribe({ events += "f1" }, { events += "s1" }); h.subscribe({ events += "f2" }, { events += "s2" }); events.clear()
        h.controller.enable(h.controller.claim(), endpoint, false, true)
        assertEquals(listOf("f1", "f2", "s1", "s2"), events)
    }
    @Test fun detachedOwnerIsNeverReopenedByChangedService() {
        val h = VerificationConfigurationHub(); var calls = 0
        val handle = h.subscribe({ calls++ }, { calls++ }); handle.close(); val initial = calls
        h.controller.enable(h.controller.claim(), endpoint, false, true); assertEquals(initial, calls)
    }
    @Test fun recreatedOwnerReceivesExactSessionConsent() {
        val h = VerificationConfigurationHub(); h.controller.enable(h.controller.claim(), endpoint, true, true)
        var received: VerificationConfiguration? = null
        h.subscribe({}, { received = it }); assertSame(h.controller.state, received)
    }
    @Test fun brokenOwnerDoesNotPreventOtherOwnersBeingFenced() {
        val h = VerificationConfigurationHub(); var safeFences = 0
        h.subscribe({ throw IllegalStateException() }, {}); h.subscribe({ safeFences++ }, {})
        assertFalse(h.controller.enable(h.controller.claim(), endpoint, false, true))
        assertEquals(1, safeFences); assertNull(h.controller.state.endpoint); assertFalse(h.controller.busy)
    }
}
