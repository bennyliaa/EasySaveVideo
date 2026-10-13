package com.easysavevideo.app.theme

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring contracts supplement mapper tests; they do not claim pixel or device acceptance. */
class EsvSemanticConsumerTest {
    private val root = listOf(File("src/main/kotlin/com/easysavevideo/app"), File("app/src/main/kotlin/com/easysavevideo/app")).first { it.isDirectory }
    private fun source(path: String) = File(root, path).readText()
    @Test fun realSharedCardBodiesReadCardSemantics() {
        for (path in listOf("ui/DiscoveryScreen.kt", "ui/PendingCandidateCards.kt", "library/LibraryScreen.kt",
            "save/SaveTaskScreen.kt", "editor/ui/EditorScreen.kt", "recording/ui/RecordingSegmentsCard.kt", "ui/ProviderManagementScreen.kt")) {
            assertTrue(path, source(path).contains("RoundedCornerShape(v.cardCorner)"))
        }
    }
    @Test fun realDialogShellsReadElevatedAndSheetSemantics() {
        for (path in listOf("ui/DiscoveryScreen.kt", "library/LibraryScreen.kt", "save/SaveTaskScreen.kt",
            "ui/BrowserScreen.kt", "editor/ui/EditorScreen.kt", "ui/ProviderManagementScreen.kt")) {
            assertTrue(path, source(path).contains("v.colors.elevated, RoundedCornerShape(v.sheetCorner)"))
        }
    }
    @Test fun textEntryControlsRetainTheControlCorner() {
        val libraryField = source("library/LibraryScreen.kt").substringAfter("/** Foundation field")
        assertTrue(libraryField.contains("RoundedCornerShape(v.corner)"))
        val editorField = source("editor/ui/EditorScreen.kt").substringAfter("private fun EditorTimeField").substringBefore("private fun EditorCropDialog")
        assertTrue(editorField.contains("RoundedCornerShape(v.corner)"))
        assertFalse(editorField.contains("RoundedCornerShape(v.cardCorner)"))
        assertFalse(editorField.contains("RoundedCornerShape(v.sheetCorner)"))
    }
    @Test fun selectedSectionHeadersUseSectionTypeWhilePageTitlesRemainTitles() {
        assertTrue(source("ui/shell/EsvSettingsScreen.kt").contains("EsvText(categoryLabel(category),style=v.sectionTextStyle)"))
        assertTrue(source("ui/shell/EsvSettingsScreen.kt").contains("EsvText(stringResource(R.string.ui_settings),style=v.titleTextStyle)"))
        assertTrue(source("save/SaveTaskScreen.kt").contains("EsvText(stringResource(R.string.task_detail_heading), style = v.sectionTextStyle)"))
    }
    @Test fun selectorPresentationComesFromActualHostAndKeepsOneMovableContent() {
        val browser = source("ui/BrowserScreen.kt")
        assertEquals(1, Regex("movableContentOf<Modifier, SaveSelectionPresentation>").findAll(browser).count())
        assertTrue(browser.contains("inspectorPanel(Modifier.width(pane.sideWidthDp.dp).fillMaxHeight(), SaveSelectionPresentation.INLINE)"))
        assertTrue(browser.contains("inspectorPanel(Modifier.heightIn(max = maxHeight.coerceAtMost(680.dp)), SaveSelectionPresentation.FLOATING)"))
        val save = source("save/SaveTaskScreen.kt")
        assertTrue(save.contains("SaveSelectionPanel(runtime, browser, Modifier.heightIn(max = 680.dp), SaveSelectionPresentation.FLOATING)"))
        assertTrue(save.contains(".background(panel.background, RoundedCornerShape(panel.corner))"))
        assertTrue(save.contains(".padding(panel.padding)"))
        assertTrue(save.contains("Arrangement.spacedBy(v.spacingCompact)"))
        assertTrue(save.contains("Arrangement.spacedBy(v.spacingExtraSmall)"))
    }
    @Test fun themeHostKeepsTheExistingGenerationSubscriptionAndSafetyMinimum() {
        val theme = source("theme/EsvComposeTheme.kt")
        assertTrue(theme.contains("remember(currentTheme, generation) { currentTheme.readSnapshot().toComposeVisuals() }"))
        assertTrue(theme.contains("onDispose { subscription.close() }"))
        assertTrue(theme.contains(".sizeIn(minWidth = 48.dp, minHeight = 48.dp)"))
        assertFalse(theme.contains("dynamicLightColorScheme"))
        assertFalse(theme.contains("dynamicDarkColorScheme"))
    }
}
