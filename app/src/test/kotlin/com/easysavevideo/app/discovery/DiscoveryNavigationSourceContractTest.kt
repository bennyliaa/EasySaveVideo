package com.easysavevideo.app.discovery

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring checks complement executable dispatch tests; they do not instantiate WebView or Compose. */
class DiscoveryNavigationSourceContractTest {
    private fun source(path: String) = File("src/main/kotlin/com/easysavevideo/app/$path").readText()
    @Test fun activityRunsTheVerifiedDispatcherBeforeRoutingOrNativeNavigation() {
        val activity = source("MainActivity.kt")
        val block = activity.substringAfter("onOpen = { request ->").substringBefore("}, onLibrary =")
        assertTrue(block.contains("dispatchDiscoveryCardOpen(request, { discovery.state },"))
        assertTrue(block.contains("discovery.cardNavigationLifecycle"))
        assertTrue(block.contains("activeDestination == EsvDestination.DISCOVER"))
        assertTrue(block.contains("isForeground")); assertTrue(block.contains("!isDestroyed && !isFinishing"))
        assertTrue(block.contains("controlsBusy = { discovery.filterControlsBusy }"))
        val dispatcher = block.indexOf("dispatchDiscoveryCardOpen")
        assertTrue(block.indexOf("navigate(EsvDestination.BROWSER)") > dispatcher)
        assertTrue(block.contains("open = browser::openDiscoveryResult"))
        assertTrue(block.contains("showBrowser = { navigate(EsvDestination.BROWSER) }"))
        assertTrue(block.contains("recordOpened = { page, result -> discovery.recordSeen(page, result, SeenEvent.OPENED) }"))
        assertFalse(block.contains("SeenEvent.SAVED")); assertFalse(block.contains("SeenEvent.PLAYED"))
    }
    @Test fun renderedCardCapturesOwnerBeforeClickAndBusyControlsDoNotOpenOldAppliedQuery() {
        val screen = source("ui/DiscoveryScreen.kt")
        val card = screen.substringAfter("val openRequest = discoveryCardOpenRequest").substringBefore("modifier = Modifier.fillMaxWidth())")
        assertTrue(card.contains("state, result, cardNavigationLifecycle"))
        assertTrue(card.contains("currentQueryClearInteractive() && !controller.filterControlsBusy"))
        assertTrue(card.contains("enabled = active && openRequest != null && !controller.filterControlsBusy"))
        assertTrue(card.contains("cardOpenRejected = !onOpen(openRequest)"))
    }
    @Test fun browserRejectsInvalidPageIdentityBeforeAnyNavigateCall() {
        val host = source("browser/SystemBrowserHost.kt")
        val block = host.substringAfter("fun openDiscoveryResult(").substringBefore("fun captureSaveOrigin")
        assertTrue(block.startsWith("target: DiscoveryControlTarget,"))
        val gate = block.indexOf("discoveryTargetMatchesPage(target, page, result)")
        assertTrue(gate >= 0); assertTrue(gate < block.indexOf("navigate(result.canonicalPageUrl)"))
        assertTrue(block.contains("if (closed || !foreground || !browserCommandAllowed()"))
        assertFalse(block.contains("if (target != null)"))
    }
    @Test fun recoveryButtonsAreWiredOnlyToExistingUiDestinations() {
        val screen = source("ui/DiscoveryScreen.kt")
        val block = screen.substringAfter("discoveryBodyRecovery(bodyState)?.let").substringBefore("EsvButton(stringResource(R.string.flow_discoveryscreen_14)")
        assertTrue(block.contains("dispatchDiscoveryBodyRecovery(recovery, state, controller.state"))
        assertTrue(block.contains("cardNavigationLifecycle, controller.cardNavigationLifecycle"))
        assertTrue(block.contains("openSources = { options = false; onManageProviders() }"))
        assertTrue(block.contains("openFilters = { options = true }"))
        assertFalse(block.contains("controller.retry")); assertFalse(block.contains("submitFilterDraft"))
        assertFalse(block.contains("enableCommons")); assertFalse(block.contains("onSettings"))
    }
    @Test fun navigationUsesExistingLifecycleInvalidatedBeforeQueuedRefresh() {
        val controller = source("discovery/AndroidDiscoveryController.kt")
        assertTrue(controller.contains("internal val cardNavigationLifecycle: Long get() = pageLifecycle.get()"))
        val visibility = controller.substringAfter("fun setVisibility(").substringBefore("osSignals.setActive")
        assertTrue(visibility.indexOf("pageLifecycle.incrementAndGet()") >= 0)
        assertTrue(visibility.contains("if (!isForeground || !discoveryVisible)"))
    }
}
