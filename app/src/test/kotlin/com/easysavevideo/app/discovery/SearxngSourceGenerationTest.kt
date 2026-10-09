package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class SearxngSourceGenerationTest {
    private val source = object : VideoSearchProvider {
        override val descriptor = ProviderDescriptor("searxng-1", "endpoint-fixed")
        override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage = error("No network")
    }
    @Test fun generationChangesEverySavedProfileCompatibilityButKeepsStableId() {
        val before = SearxngSourceRegistration.bindGeneration(listOf(source), SearxngConfigurationPolicy.empty(0), true).single()
        val after = SearxngSourceRegistration.bindGeneration(listOf(source), SearxngConfigurationPolicy.empty(2), true).single()
        assertEquals(before.descriptor.id, after.descriptor.id); assertNotEquals(before.descriptor.version, after.descriptor.version)
        for (profile in listOf("A", "B")) {
            val progress = ProviderProgress(profile, before.descriptor.id, "query", before.descriptor.version, 1,
                "old-cursor", null, true, 1)
            assertTrue(progress.isCompatible(before.descriptor, 2)); assertFalse(progress.isCompatible(after.descriptor, 2))
        }
    }
    @Test fun unchangedConfigurationKeepsStableCompatibilityWithoutQueries() {
        val config = SearxngConfigurationPolicy.empty(3)
        val first = SearxngSourceRegistration.bindGeneration(listOf(source), config, true).single()
        val second = SearxngSourceRegistration.bindGeneration(listOf(source), config, true).single()
        assertEquals(first.descriptor, second.descriptor)
    }
    @Test fun commonsEnableChangeAlsoRetiresOldProgressAndSourceFreeRegistryStaysEmpty() {
        val config = SearxngConfigurationPolicy.empty()
        val on = SearxngSourceRegistration.bindGeneration(listOf(source), config, true).single()
        val off = SearxngSourceRegistration.bindGeneration(listOf(source), config, false).single()
        assertNotEquals(on.descriptor.version, off.descriptor.version)
        assertTrue(SearxngSourceRegistration.bindGeneration(emptyList(), config, false).isEmpty())
    }
}
