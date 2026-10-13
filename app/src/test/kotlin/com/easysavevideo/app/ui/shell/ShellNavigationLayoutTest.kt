package com.easysavevideo.app.ui.shell

import androidx.compose.foundation.checkScrollableContainerConstraints
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.Constraints
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

/** Source/geometry contracts only; not a claim of Android render or TalkBack acceptance. */
class ShellNavigationLayoutTest {
    private fun layout(width: Float = 360f, height: Float = 800f, scale: Float = 1f,
        wide: Boolean = false, low: Boolean = false) = shellNavigationLayout(width, height, scale, wide, low)

    @Test fun ordinaryCompactRetainsFourItemBottomBarAtEverySupportedTextScale() {
        for (scale in listOf(.85f, 1f, 1.5f, 2f, 3f)) {
            assertFalse(layout(scale = scale).rail)
            assertTrue(360f >= 4 * 48f + 3 * 8f)
        }
    }
    @Test fun officialMediumExpandedAndLowHeightInputsSelectRail() {
        assertTrue(layout(width = 700f, wide = true).rail)
        assertTrue(layout(width = 1000f, wide = true).rail)
        assertTrue(layout(width = 500f, height = 240f, low = true).rail)
    }
    @Test fun subminimumFourTargetWidthUsesRailWithoutChangingDestinations() {
        assertTrue(layout(width = 215f).rail)
        assertFalse(layout(width = 216f).rail)
        assertEquals(listOf("discover", "tasks", "record", "library"), EsvDestination.roots.map { it.route })
    }
    @Test fun actualNavigationHorizontalInsetsCountBeforeChoosingBottomBar() {
        assertTrue(shellNavigationLayout(240f, 800f, 2f, false, false, 25f).rail)
        assertFalse(shellNavigationLayout(240f, 800f, 2f, false, false, 24f).rail)
        assertEquals(120f, shellNavigationLayout(360f, 240f, 2f, false, true, 60f).railWidthDp, .001f)
    }
    @Test fun railGrowsAtLargeTextButLeavesAContentBudget() {
        assertEquals(80f, layout(width = 800f, wide = true).railWidthDp, 0f)
        assertEquals(160f, layout(width = 800f, scale = 2f, wide = true).railWidthDp, 0f)
        assertEquals(144f, layout(scale = 2f, low = true).railWidthDp, .001f)
    }
    @Test fun narrowRailBoundsDoNotUseAnInvertedClamp() {
        assertEquals(80f, layout(width = 180f, scale = 2f).railWidthDp, 0f)
        assertEquals(60f, layout(width = 60f, scale = 2f).railWidthDp, 0f)
    }
    @Test fun boundedChromeLeavesContentAtSmallButUsableHeights() {
        for (height in listOf(160f, 240f, 320f, 480f, 800f)) {
            val result = layout(height = height, scale = 2f)
            assertTrue(result.headerMaxHeightDp >= 48f)
            assertTrue(result.bottomMaxHeightDp >= 48f)
            assertTrue(result.headerMaxHeightDp + result.bottomMaxHeightDp < height)
        }
    }
    @Test fun invalidGeometryHasFinitePositiveFallbacks() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            val result = layout(width = value, height = value, scale = value)
            assertTrue(result.railWidthDp.isFinite() && result.railWidthDp > 0f)
            assertTrue(result.headerMaxHeightDp.isFinite() && result.headerMaxHeightDp > 0f)
            assertTrue(result.bottomMaxHeightDp.isFinite() && result.bottomMaxHeightDp > 0f)
        }
    }
    @Test fun actualFoundationAcceptsFiniteViewportConstraints() {
        for (height in listOf(48, 80, 160, 240, 480)) {
            checkScrollableContainerConstraints(Constraints(maxWidth = 160, maxHeight = height), Orientation.Vertical)
        }
    }
    private fun source(): String {
        val relative = "src/main/kotlin/com/easysavevideo/app/ui/shell/EsvAppShell.kt"
        val path = Path.of(relative).takeIf { Files.exists(it) } ?: Path.of("app", relative)
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }
    @Test fun bothStandardContainersAlwaysExposeWrappingVisibleText() {
        val text = source()
        assertEquals(2, Regex("""alwaysShowLabel = true""").findAll(text).count())
        assertEquals(2, Regex("""label = \{ Text\(label, textAlign = TextAlign.Center, softWrap = true\) \}""").findAll(text).count())
        assertFalse(text.contains("showNavigationLabels"))
        assertFalse(text.contains("maxLines ="))
        assertFalse(text.contains("TextOverflow.Ellipsis"))
        assertFalse(text.contains("fontSize ="))
    }
    @Test fun standardSelectionDisabledAndClickSemanticsRemainWithDecorativeIcons() {
        val text = source()
        assertEquals(2, Regex("""selected = destination !in listOf""").findAll(text).count())
        assertEquals(2, Regex("""onClick = \{ onNavigate\(root\) \}, enabled = navigationEnabled""").findAll(text).count())
        assertEquals(2, Regex("""contentDescription = null""").findAll(text).count())
        assertEquals(2, Regex("""sizeIn\(minWidth = 48.dp, minHeight = 48.dp\)""").findAll(text).count())
        assertFalse(text.contains("clearAndSetSemantics"))
    }
    @Test fun eachScrollableViewportIsBoundedBeforeItsScrollModifier() {
        val text = source()
        assertTrue(text.contains("heightIn(max = layout.headerMaxHeightDp.dp).verticalScroll(headerScroll)"))
        assertTrue(text.contains("heightIn(max = layout.bottomMaxHeightDp.dp).verticalScroll(bottomScroll)"))
        assertTrue(text.contains("fillMaxHeight().verticalScroll(railScroll)"))
        assertTrue(text.contains("padding(padding).consumeWindowInsets(padding)"))
    }
    @Test fun navigationOwnerAndAuxiliarySettingsEntryAreRetained() {
        val text = source()
        assertEquals(1, Regex("""NavHost\(""").findAll(text).count())
        assertTrue(text.contains("EsvDestination.entries.forEach"))
        assertTrue(text.contains("onNavigate(EsvDestination.SETTINGS)"))
        assertFalse(text.contains("LaunchedEffect"))
        assertFalse(text.contains("navigate("))
    }
}
