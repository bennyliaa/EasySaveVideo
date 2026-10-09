package com.easysavevideo.library.thumbnail

import org.junit.Assert.*
import org.junit.Test

class ThumbnailReadResourcesTest {
    @Test fun releaseRunsNativeThenDescriptorThenCatalogFenceExactlyOnce() {
        val calls = mutableListOf<String>(); val resources = resources(calls)
        assertTrue(resources.releaseConfirmed()); assertTrue(resources.releaseConfirmed())
        assertEquals(listOf("native", "input", "fence"), calls)
        assertNull(resources.nativeReader); assertNull(resources.input); assertNull(resources.catalogFence)
    }

    @Test fun failedNativeReleaseRetainsDescriptorAndCatalogFenceWithoutSpeculativeRetry() {
        val calls = mutableListOf<String>(); val resources = resources(calls, "native")
        assertFalse(resources.releaseConfirmed()); assertFalse(resources.releaseConfirmed())
        assertEquals(listOf("native"), calls)
        assertNotNull(resources.nativeReader); assertNotNull(resources.input); assertNotNull(resources.catalogFence)
        assertFalse(resources.released)
    }

    @Test fun failedDescriptorCloseRetainsCatalogFence() {
        val calls = mutableListOf<String>(); val resources = resources(calls, "input")
        assertFalse(resources.releaseConfirmed())
        assertEquals(listOf("native", "input"), calls)
        assertNull(resources.nativeReader); assertNotNull(resources.input); assertNotNull(resources.catalogFence)
    }

    @Test fun failedFenceCloseIsNotReportedAsConfirmed() {
        val calls = mutableListOf<String>(); val resources = resources(calls, "fence")
        assertFalse(resources.releaseConfirmed()); assertFalse(resources.released)
        assertNotNull(resources.catalogFence)
    }

    @Test fun partialConstructionStillReleasesOnlyAcquiredReadAuthorities() {
        val calls = mutableListOf<String>()
        val resources = ThumbnailReadResources().apply { catalogFence = AutoCloseable { calls.add("fence") } }
        assertTrue(resources.releaseConfirmed()); assertEquals(listOf("fence"), calls)
    }

    private fun resources(calls: MutableList<String>, throwsAt: String? = null) = ThumbnailReadResources().apply {
        fun handle(name: String) = AutoCloseable { calls.add(name); if (name == throwsAt) throw IllegalStateException("technical") }
        nativeReader = handle("native"); input = handle("input"); catalogFence = handle("fence")
    }
}
