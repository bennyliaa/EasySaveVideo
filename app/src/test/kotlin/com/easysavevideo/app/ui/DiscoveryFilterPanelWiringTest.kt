package com.easysavevideo.app.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Exact source wiring guards complement value tests; actual transition behavior needs device QA. */
class DiscoveryFilterPanelWiringTest {
    private val root = listOf(File("src/main/kotlin/com/easysavevideo/app/ui"),
        File("app/src/main/kotlin/com/easysavevideo/app/ui")).first { it.isDirectory }
    private val screen get() = File(root, "DiscoveryScreen.kt").readText()

    @Test fun realSidebarAndDialogHostsPassTheirOwnExplicitRole() {
        val source = screen
        assertTrue(source.contains("if (options && paneLayout.split) optionsPanel(Modifier.width(paneLayout.sideWidthDp.dp).fillMaxHeight(), DiscoveryFilterPanelPresentation.INLINE)"))
        val modal = source.substringAfter("if (options && !paneLayout.split) Dialog(onDismissRequest = { options = false }) {")
            .substringBefore("safeDisableConfirm?.let")
        assertTrue(modal.contains("optionsPanel(Modifier.heightIn(max = maxHeight.coerceAtMost(680.dp)), DiscoveryFilterPanelPresentation.FLOATING)"))
        assertEquals(2, Regex("optionsPanel\\(Modifier").findAll(source).count())
    }

    @Test fun oneMovableCompositionReceivesLatestContentAndPresentationWithoutAStateKey() {
        val source = screen
        assertEquals(1, Regex("movableContentOf<").findAll(source).count())
        assertTrue(source.contains("val optionsContentState = rememberUpdatedState(optionsContent)"))
        val holder = source.substringAfter("val optionsPanel = remember {").substringBefore("EsvBackdrop(")
        assertTrue(holder.contains("movableContentOf<Modifier, DiscoveryFilterPanelPresentation> { modifier, presentation ->"))
        assertTrue(holder.contains("optionsContentState.value(modifier, presentation)"))
        assertFalse(holder.contains("remember("))
    }

    @Test fun actualOuterContainerUsesMappedRoleWhilePaddingScrollAndControlsStayUnchanged() {
        val source = screen
        val content = source.substringAfter("val optionsContent:").substringBefore("val optionsContentState")
        assertTrue(content.contains("@Composable (Modifier, DiscoveryFilterPanelPresentation) -> Unit = { panelModifier, presentation ->"))
        assertTrue(content.contains("val panel = discoveryFilterPanelVisuals(v, presentation)"))
        assertTrue(content.contains("Column(panelModifier.fillMaxWidth().background(panel.background, RoundedCornerShape(panel.corner))"))
        assertTrue(content.contains(".padding(v.spacingLarge).verticalScroll(optionsScroll)"))
        assertTrue(source.contains("val optionsScroll = rememberScrollState()"))
        assertEquals(1, Regex("verticalScroll\\(optionsScroll\\)").findAll(source).count())
        assertTrue(content.contains("SearxngSourceSettings(controller.sourceSettings)"))
        assertTrue(content.contains("VerificationServiceSettings(controller.verificationSettings)"))
        val field = source.substringAfter("private fun DurationFilterField(").substringBefore("private fun policyText(")
        assertTrue(field.contains("RoundedCornerShape(v.corner)"))
        assertFalse(field.contains("v.cardCorner"))
        assertFalse(field.contains("v.sheetCorner"))
    }

    @Test fun draftDismissalClearAndShownGatesRetainExistingOwnership() {
        val source = screen
        for (required in listOf(
            "var options by rememberSaveable { mutableStateOf(false) }",
            "val optionsIsModal = options && !paneLayout.split",
            "BackHandler(enabled = options) { options = false }",
            "val storedDraft = controller.filterDraft",
            "DiscoveryFilterPresentationPolicy.reconcile(storedDraft, state.profile, state.demo, appliedReadable)",
            "EsvButton(stringResource(R.string.flow_discoveryscreen_22), onClick = { options = false })",
            "val currentQueryClearInteractive by rememberUpdatedState",
            "active && canClearQuery() && !(options && !paneLayout.split) && localControlConfirm == null",
            "safeDisableConfirm == null && rightsDetails == null",
            "onClear = { if (currentQueryClearInteractive())",
            "controller.clearFilterQuery(it, queryClearLifecycle)",
            "if (page == null || !active || showPending || optionsIsModal || localControlConfirm != null || safeDisableConfirm != null || rightsDetails != null) return@LaunchedEffect",
            "active, !optionsIsModal && localControlConfirm == null && safeDisableConfirm == null && rightsDetails == null)"
        )) assertTrue(required, source.contains(required))
    }
}
