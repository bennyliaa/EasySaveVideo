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
    private fun card(): String = source("ui/DiscoveryScreen.kt")
        .substringAfter("items(state.results, key = { it.resultId }) { result ->")
        .substringBefore("Column(Modifier.fillMaxWidth().heightIn(max = paneLayout.footerMaxHeightDp.dp)")

    @Test fun bothCardOpenAffordancesShareOneCapturedRequestAndExactDispatchLambda() {
        val card = card()
        assertEquals(1, Regex("discoveryCardOpenRequest\\(").findAll(card).count())
        assertTrue(card.contains("val openRequest = discoveryCardOpenRequest(state, result, cardNavigationLifecycle)"))
        assertTrue(card.indexOf("val openRequest") < card.indexOf("val openCard: () -> Unit"))
        assertTrue(card.contains("if (currentQueryClearInteractive() && !controller.filterControlsBusy && openRequest != null)"))
        assertEquals(1, Regex("onOpen\\(openRequest\\)").findAll(card).count())
        assertTrue(card.contains("cardOpenRejected = !onOpen(openRequest)"))
        assertEquals(2, Regex("onClick = openCard").findAll(card).count())
        assertTrue(card.contains("EsvButton(openLabel, onClick = openCard, enabled = openEnabled, modifier = Modifier.fillMaxWidth())"))
    }
    @Test fun primaryRegionAndFooterKeepTheSameEnabledAndCurrentInteractionFences() {
        val card = card()
        val enabled = card.substringAfter("val openEnabled = ").substringBefore("val openCard:")
        for (gate in listOf("active", "openRequest != null", "!controller.filterControlsBusy", "!optionsIsModal",
            "localControlConfirm == null", "safeDisableConfirm == null", "rightsDetails == null")) {
            assertTrue(gate, enabled.contains(gate))
        }
        assertEquals(2, Regex("enabled = openEnabled").findAll(card).count())
        val screen = source("ui/DiscoveryScreen.kt")
        val interaction = screen.substringAfter("val currentQueryClearInteractive by rememberUpdatedState {")
            .substringBefore("val presentationCopy")
        assertTrue(interaction.contains("active && canClearQuery() && !(options && !paneLayout.split) && localControlConfirm == null &&"))
        assertTrue(interaction.contains("safeDisableConfirm == null && rightsDetails == null"))
    }
    @Test fun primaryContentUsesOneLabeledFoundationClickWithButtonSemantics() {
        val screen = source("ui/DiscoveryScreen.kt")
        val card = card()
        assertTrue(screen.contains("import androidx.compose.foundation.clickable"))
        assertTrue(screen.contains("import androidx.compose.ui.semantics.Role"))
        assertEquals(1, Regex("\\.clickable\\(").findAll(card).count())
        assertTrue(card.contains("val openLabel = stringResource(R.string.flow_discoveryscreen_16)"))
        assertTrue(card.contains("Column(Modifier.fillMaxWidth().clickable(enabled = openEnabled, onClickLabel = openLabel,"))
        assertTrue(card.contains("role = Role.Button, onClick = openCard)"))
        // Foundation clickable owns keyboard/focus/action semantics; no custom touch-only handler.
        for (forbidden in listOf("pointerInput", "detectTapGestures", "onKeyEvent", "clearAndSetSemantics", "focusable("))
            assertFalse(forbidden, card.contains(forbidden))
    }
    @Test fun primaryRegionContainsOnlyThumbnailAndMainMetadataWithSecondaryActionsOutside() {
        val card = card()
        val beforePrimary = card.substringBefore("Column(Modifier.fillMaxWidth().clickable")
        assertFalse(beforePrimary.contains(".clickable("))
        val primary = card.substringAfter("role = Role.Button, onClick = openCard), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {")
            .substringBefore("RightsPresentationPolicy.summary")
        for (required in listOf("EsvThemeIcon(", "EsvText(result.title", "EsvText(result.sourceName",
            "result.provenance.any", "EsvText(result.durationMs")) assertTrue(required, primary.contains(required))
        assertFalse(primary.contains("EsvButton("))
        assertFalse(primary.contains("rightsDetails"))
        assertFalse(primary.contains("onOpen("))
        assertTrue(primary.trimEnd().endsWith("}"))
        assertTrue(card.contains("""                            }
                            RightsPresentationPolicy.summary(result.rights, rightsCopy).forEach { EsvText(it, style = v.captionTextStyle) }
                            EsvButton(stringResource(R.string.flow_discoveryscreen_15), onClick = { rightsDetails = result }, modifier = Modifier.fillMaxWidth())
                            EsvButton(openLabel, onClick = openCard, enabled = openEnabled, modifier = Modifier.fillMaxWidth())"""))
    }
    @Test fun cardAffordancesAddNoAlternateNavigationSaveSeenOrRecordingAuthority() {
        val card = card()
        for (forbidden in listOf("canonicalPageUrl", "onBrowser(", "onLink(", "navigate(", "dispatchDiscoveryCardOpen(",
            "recordSeen(", "SeenEvent.", "save(", "startSave", "stopRecording", "discardRecording", "recordingActive"))
            assertFalse(forbidden, card.contains(forbidden))
    }
    @Test fun browserRejectsInvalidPageIdentityBeforeAnyNavigateCall() {
        val host = source("browser/SystemBrowserHost.kt")
        val block = host.substringAfter("fun openDiscoveryResult(").substringBefore("fun captureSaveOrigin")
        assertTrue(block.startsWith("target: DiscoveryControlTarget,"))
        val gate = block.indexOf("discoveryTargetMatchesPage(target, page, result)")
        assertTrue(gate >= 0); assertTrue(gate < block.indexOf("navigate(result.canonicalPageUrl)"))
        assertTrue(block.contains("if (!canAct() || privateMode || !foreground || !browserCommandAllowed()"))
        assertFalse(block.contains("if (target != null)"))
        assertTrue(host.contains("private fun canAct(): Boolean = !closed && !closing"))
        assertTrue(block.contains("state.selectedTab?.incognito == true"))
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
