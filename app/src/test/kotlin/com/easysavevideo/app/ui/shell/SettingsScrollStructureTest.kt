package com.easysavevideo.app.ui.shell
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import androidx.compose.foundation.checkScrollableContainerConstraints
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.Constraints

/** Constraints/source integration checks, not Android rendering or accessibility acceptance. */
class SettingsScrollStructureTest {
    private fun source():String {
        val relative="src/main/kotlin/com/easysavevideo/app/ui/shell/EsvSettingsScreen.kt"
        val path=Path.of(relative).takeIf{Files.exists(it)} ?: Path.of("app",relative)
        return String(Files.readAllBytes(path),Charsets.UTF_8)
    }
    @Test fun leafCategoryAndDetailContentDoNotNestScrollInsideEitherViewport() {
        val s=source();val leaves=s.substringAfter("@Composable private fun SettingsCategories")
        assertFalse(leaves.contains("verticalScroll("));assertFalse(leaves.contains("LazyColumn("))
        assertTrue(s.contains("Modifier.weight(1f).fillMaxWidth()"));assertTrue(s.contains("fillMaxHeight().verticalScroll"))
        assertTrue(s.contains("Modifier.fillMaxSize().verticalScroll"))
    }
    @Test fun finiteSmallAndWidePanelHeightSatisfyTheRealFoundationContract() {
        for(height in listOf(80,160,360,720))checkScrollableContainerConstraints(Constraints(maxHeight=height),Orientation.Vertical)
    }
    @Test fun pageReusesGeometryPolicyAndNoPreferenceOrRouteOwnerIsAdded() {
        val s=source();assertTrue(s.contains("responsivePaneLayout("));assertFalse(s.contains("NavHost("))
        assertFalse(s.contains("getSharedPreferences("));assertFalse(s.contains(".commit("));assertFalse(s.contains("fontScale.coerce"))
        assertTrue(s.contains("onSources"));assertTrue(s.contains("ThirdPartyNotices"))
    }
}
