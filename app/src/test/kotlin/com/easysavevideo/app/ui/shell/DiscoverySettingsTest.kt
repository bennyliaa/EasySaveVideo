package com.easysavevideo.app.ui.shell

import com.easysavevideo.app.R
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class DiscoverySettingsTest {
    private val original = AppSettings(browser = BrowserSettings(false, 4, false, true),
        discovery = DiscoverySettings(4, 37, RepeatPolicy.HIDE_WATCHED, DiscoveryMode.AGGRESSIVE),
        appearance = AppearanceSettings(ThemeMode.DARK, AppLanguage.ENGLISH),
        editor = EditorSettings(EditorCompressionMode.SAVE_SPACE), save = SaveSettings(true))
    private fun untouched(next: AppSettings) {
        assertSame(original.browser, next.browser); assertSame(original.appearance, next.appearance)
        assertSame(original.editor, next.editor); assertSame(original.save, next.save)
        assertEquals(original.schemaVersion, next.schemaVersion)
    }
    @Test fun columnsCoverEveryLegalValueAndClampAtBothBoundaries() {
        for (value in 1..6) for (delta in listOf(-1, 1)) {
            val before = original.copy(discovery = original.discovery.copy(columns = value))
            val after = SettingsEdit.DiscoveryColumns(delta).applyTo(before)
            assertEquals((value + delta).coerceIn(1, 6), after.discovery.columns)
            assertEquals(before.discovery.copy(columns = after.discovery.columns), after.discovery); untouched(after)
        }
    }
    @Test fun pageSizeCoversEveryLegalValueIncludingLegacyNonPresetValues() {
        for (value in 1..100) for (delta in listOf(-1, 1)) {
            val before = original.copy(discovery = original.discovery.copy(pageSize = value))
            val after = SettingsEdit.DiscoveryPageSize(delta).applyTo(before)
            assertEquals((value + delta).coerceIn(1, 100), after.discovery.pageSize)
            assertEquals(before.discovery.copy(pageSize = after.discovery.pageSize), after.discovery); untouched(after)
        }
    }
    @Test fun malformedDeltasCannotOverflowOrChangeMultipleSteps() {
        for (bad in listOf(Int.MIN_VALUE, -2, 0, 2, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { SettingsEdit.DiscoveryColumns(bad) }
            assertThrows(IllegalArgumentException::class.java) { SettingsEdit.DiscoveryPageSize(bad) }
        }
    }
    @Test fun policyAndIntensityPreserveTheOtherThreePreferences() {
        for (policy in RepeatPolicy.entries) {
            val next = SettingsEdit.DiscoveryRepeat(policy).applyTo(original)
            assertEquals(original.discovery.copy(repeatPolicy = policy), next.discovery); untouched(next)
        }
        for (mode in DiscoveryMode.entries) {
            val next = SettingsEdit.DiscoveryIntensity(mode).applyTo(original)
            assertEquals(original.discovery.copy(mode = mode), next.discovery); untouched(next)
        }
    }
    @Test fun rapidRelativeActionsUseLatestOwnerRatherThanRenderedValue() {
        val action = SettingsEdit.DiscoveryColumns(1)
        val once = action.applyTo(original); val twice = action.applyTo(once)
        assertEquals(5, once.discovery.columns); assertEquals(6, twice.discovery.columns)
        val changedInFilter = twice.copy(discovery = twice.discovery.copy(pageSize = 99, mode = DiscoveryMode.POWER_SAVING))
        val next = SettingsEdit.DiscoveryPageSize(1).applyTo(changedInFilter)
        assertEquals(DiscoverySettings(6, 100, RepeatPolicy.HIDE_WATCHED, DiscoveryMode.POWER_SAVING), next.discovery)
        assertEquals(next, SettingsEdit.DiscoveryPageSize(1).applyTo(next))
    }
    @Test fun defaultsResetOnlyDiscoveryAndAreIdempotent() {
        val next = SettingsEdit.DiscoveryDefaults.applyTo(original)
        assertEquals(DiscoverySettings(), next.discovery); untouched(next)
        assertEquals(next, SettingsEdit.DiscoveryDefaults.applyTo(next))
    }
    @Test fun interleavedOtherEditsSurviveOldDiscoveryIntent() {
        val intent = SettingsEdit.DiscoveryRepeat(RepeatPolicy.SHOW_ALL)
        val changed = SettingsEdit.Language(AppLanguage.CHINESE).applyTo(
            SettingsEdit.WifiOnly(false).applyTo(original))
        val next = intent.applyTo(changed)
        assertEquals(AppLanguage.CHINESE, next.appearance.language); assertFalse(next.save.wifiOnly)
        assertEquals(RepeatPolicy.SHOW_ALL, next.discovery.repeatPolicy)
    }
    @Test fun discoveryCategoryRestoresInBothLayoutsWithoutNewRoute() {
        for (split in listOf(false, true)) {
            val presentation = settingsPresentation("discovery", split)
            assertEquals(SettingsCategory.DISCOVERY, presentation.active); assertTrue(presentation.detail)
            assertEquals(if (split) SettingsBackAction.EXIT else SettingsBackAction.CATEGORIES, settingsBackAction(false, presentation))
        }
    }
    @Test fun labelsReuseExactExistingPolicyAndModeResources() {
        assertEquals(listOf(R.string.discovery_copy_74, R.string.discovery_copy_75, R.string.discovery_copy_76, R.string.discovery_copy_77),
            RepeatPolicy.entries.map(::discoveryRepeatLabel))
        assertEquals(listOf(R.string.discovery_copy_19, R.string.discovery_copy_20, R.string.discovery_copy_21),
            DiscoveryMode.entries.map(::discoveryIntensityLabel))
    }
    @Test fun sharedGuardRejectsOldRoutesBackgroundDestroyedAndIncompleteLoads() {
        for (route in EsvDestination.entries) for (expected in EsvDestination.entries)
            for (ready in listOf(false,true)) for (foreground in listOf(false,true)) for (alive in listOf(false,true)) {
                val accepted = ready && foreground && alive && route == expected &&
                    expected in listOf(EsvDestination.DISCOVER,EsvDestination.SETTINGS)
                val result = applyDiscoverySettingsEdit(original,SettingsEdit.DiscoveryColumns(1),ready,foreground,alive,route,expected)
                if (accepted) { assertNotNull(result); assertEquals(5,result!!.discovery.columns) } else assertNull(result)
            }
    }
    @Test fun filterAndSettingsEditsComposeAgainstOneOwner() {
        var owner = original
        owner = applyDiscoverySettingsEdit(owner,SettingsEdit.DiscoveryIntensity(DiscoveryMode.POWER_SAVING),true,true,true,EsvDestination.SETTINGS,EsvDestination.SETTINGS)!!
        val rejectedOldFilter = applyDiscoverySettingsEdit(owner,SettingsEdit.DiscoveryColumns(-1),true,true,true,EsvDestination.SETTINGS,EsvDestination.DISCOVER)
        assertNull(rejectedOldFilter)
        owner = applyDiscoverySettingsEdit(owner,SettingsEdit.DiscoveryPagePresetStep(10),true,true,true,EsvDestination.DISCOVER,EsvDestination.DISCOVER)!!
        assertEquals(47,owner.discovery.pageSize);assertEquals(DiscoveryMode.POWER_SAVING,owner.discovery.mode)
        assertEquals(4,owner.discovery.columns);untouched(owner)
        assertEquals(10,SettingsEdit.DiscoveryPagePresetStep(-10).applyTo(owner.copy(discovery=DiscoverySettings(pageSize=11))).discovery.pageSize)
        assertEquals(100,SettingsEdit.DiscoveryPagePresetStep(10).applyTo(owner.copy(discovery=DiscoverySettings(pageSize=99))).discovery.pageSize)
        for (size in 1..10) {
            val small = owner.copy(discovery=DiscoverySettings(pageSize=size))
            assertSame(small,SettingsEdit.DiscoveryPagePresetStep(-10).applyTo(small))
        }
    }
    @Test fun delayedLoadMergesOnlyDiscoveryPreservingEarlyUnrelatedEdits() {
        val loaded = original
        val early = AppSettings(appearance=AppearanceSettings(ThemeMode.LIGHT,AppLanguage.CHINESE), save=SaveSettings(false))
        val merged = mergeLoadedDiscoverySettings(early,loaded,discoveryEdited=false,loadSucceeded=true)
        assertEquals(loaded.discovery,merged.discovery)
        assertSame(early.appearance,merged.appearance);assertSame(early.browser,merged.browser)
        assertSame(early.editor,merged.editor);assertSame(early.save,merged.save)
    }
    @Test fun failedLoadOrExplicitDiscoveryEditDoesNotOverwriteOwner() {
        for (edited in listOf(false,true)) for (loaded in listOf(false,true)) {
            val next = mergeLoadedDiscoverySettings(original,AppSettings(),edited,loaded)
            if (edited || !loaded) assertSame(original,next) else assertEquals(DiscoverySettings(),next.discovery)
        }
    }
    private fun path(relative: String): Path = Path.of(relative).takeIf(Files::exists) ?: Path.of("app", relative)
    private fun read(relative: String) = String(Files.readAllBytes(path(relative)), Charsets.UTF_8)
    @Test fun translationsHaveEqualKeysAndFormatArguments() {
        fun values(locale: String): Map<String, String> {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path("src/main/res/$locale/settings_discovery.xml").toFile())
            val strings = document.getElementsByTagName("string")
            return (0 until strings.length).associate { val node = strings.item(it); node.attributes.getNamedItem("name").nodeValue to node.textContent }
        }
        val en = values("values"); val zh = values("values-zh-rCN")
        assertEquals(16, en.size); assertEquals(en.keys, zh.keys)
        for (key in en.keys) {
            assertTrue(en.getValue(key).isNotBlank()); assertTrue(zh.getValue(key).isNotBlank())
            val format = Regex("%[0-9]+\\$[ds]")
            assertEquals(format.findAll(en.getValue(key)).map { it.value }.toList(), format.findAll(zh.getValue(key)).map { it.value }.toList())
        }
    }
    @Test fun wiringUsesExistingPersistenceControllerAndPreloadForegroundGuard() {
        val main = read("src/main/kotlin/com/easysavevideo/app/MainActivity.kt")
            .substringAfter("else if (route == EsvDestination.SETTINGS)").substringBefore("else if (route == EsvDestination.EDITOR)")
        assertTrue(main.contains("settingsReady && persistence.settingsLoaded && isForeground && activeDestination == EsvDestination.SETTINGS"))
        assertTrue(main.contains("applyDiscoverySettingsEdit(settings, edit, settingsReady"))
        assertTrue(main.contains("persistence.saveSettings(value, confirmNetworkPreference = changedNetwork)"))
        assertTrue(main.contains("if (edit is SettingsEdit.DiscoveryEdit) {")); assertTrue(main.contains("discovery.updateSettings(value.discovery)"))
        for (forbidden in listOf("discovery.search(", ".clearHistory(", ".resetProgress(", "enableCommons(", "setVisibility(")) assertFalse(main.contains(forbidden))
        val store = read("src/main/kotlin/com/easysavevideo/app/storage/AndroidStores.kt")
        for (key in listOf("discovery_columns", "discovery_page_size", "discovery_repeat", "discovery_mode"))
            assertTrue("Existing store reads and writes $key", Regex("\"$key\"").findAll(store).count() >= 2)
    }
    @Test fun pageHasRadioSemanticsBoundedControlsAndNoIndependentStateOrScroll() {
        val source = read("src/main/kotlin/com/easysavevideo/app/ui/shell/DiscoverySettingsDetail.kt")
        assertTrue(source.contains("role = Role.RadioButton")); assertTrue(source.contains("Modifier.selectableGroup()"))
        assertTrue(source.contains("heightIn(min = 48.dp)")); assertTrue(source.contains("FlowRow("))
        for (forbidden in listOf("remember", "verticalScroll(", "SharedPreferences", "ViewModel", "clearHistory", "requestMore")) assertFalse(source.contains(forbidden))
        for (bound in listOf("settings.columns > 1", "settings.columns < 6", "settings.pageSize > 1", "settings.pageSize < 100")) assertTrue(source.contains(bound))
    }
}
