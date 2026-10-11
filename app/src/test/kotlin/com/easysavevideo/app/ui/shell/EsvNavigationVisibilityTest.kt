package com.easysavevideo.app.ui.shell

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

/** Actual production dispatch + discovery coordinator; owned in-memory provider,
 * no Android UI, website, save, recording or network side effect. */
class EsvNavigationVisibilityTest {
    private class Runtime : ProviderRuntime {
        val queries = mutableListOf<String>()
        var cancellations = 0
        override fun execute(provider: VideoSearchProvider, request: SearchRequest,
            operation: DiscoveryOperation, callback: (ProviderOutcome) -> Unit): CancellationHandle {
            queries += request.profile.query
            return CancellationHandle { cancellations++; operation.cancellation.cancel() }
        }
    }
    private fun coordinator(runtime: Runtime, providers: Boolean = true): VideoDiscoveryCoordinator {
        val source = object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor("owned-ui-fixture", "1")
            override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage = error("No network")
        }
        return VideoDiscoveryCoordinator(ProviderRegistry(if (providers) listOf(source) else emptyList()),
            runtime, InMemoryDiscoveryStore(), object : DiscoveryTimer {
                override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle {}
            })
    }
    @Test fun repeatedDiscoveryAndAppNameClicksDoNotSuspendAndNextQueryExecutes() {
        val runtime = Runtime()
        coordinator(runtime).use { discovery ->
            discovery.setVisibility(true, true)
            discovery.start("owned-ui-session", SearchProfile("owned-ui-profile", "first"))
            var navigationCalls = 0
            // Both bottom-bar Discover and top-bar app name call the same dispatcher.
            repeat(2) {
                assertFalse(dispatchEsvNavigation("discover", EsvDestination.DISCOVER,
                    onLeaving = { discovery.setVisibility(true, false) }, onNavigate = { navigationCalls++ }))
            }
            assertEquals(0, runtime.cancellations)
            assertEquals(0, navigationCalls)
            assertNotEquals(DiscoveryState.SUSPENDED, discovery.status().state)
            discovery.start("owned-ui-session", SearchProfile("owned-ui-profile", "after repeat"))
            assertEquals(listOf("first", "after repeat"), runtime.queries)
            assertNotEquals(DiscoveryState.SUSPENDED, discovery.status().state)
        }
    }
    @Test fun leavingForBrowserStillCancelsDiscoveryBeforeNavigation() {
        val runtime = Runtime()
        coordinator(runtime).use { discovery ->
            discovery.setVisibility(true, true)
            discovery.start("owned-ui-session", SearchProfile("owned-ui-profile", "first"))
            val order = mutableListOf<String>()
            assertTrue(dispatchEsvNavigation("discover", EsvDestination.BROWSER,
                onLeaving = { discovery.setVisibility(true, false); order += "leave" },
                onNavigate = { order += "navigate" }))
            assertEquals(listOf("leave", "navigate"), order)
            assertEquals(1, runtime.cancellations)
            assertEquals(DiscoveryState.SUSPENDED, discovery.status().state)
        }
    }
    @Test fun repeatedZeroProviderDiscoveryPreservesExplicitNoSources() {
        val runtime = Runtime()
        coordinator(runtime, false).use { discovery ->
            discovery.setVisibility(true, true)
            discovery.start("owned-ui-session", SearchProfile("owned-ui-profile", "first"))
            dispatchEsvNavigation("discover", EsvDestination.DISCOVER,
                onLeaving = { discovery.setVisibility(true, false) }, onNavigate = { fail("same route") })
            discovery.start("owned-ui-session", SearchProfile("owned-ui-profile", "after repeat"))
            assertEquals(DiscoveryState.NO_SOURCES, discovery.status().state)
            assertTrue(runtime.queries.isEmpty())
        }
    }
}
