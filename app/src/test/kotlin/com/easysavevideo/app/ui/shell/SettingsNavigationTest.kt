package com.easysavevideo.app.ui.shell

import com.easysavevideo.app.ui.responsivePaneLayout
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun categoryKeysAreUniqueStableAndInvalidRestoresHaveControlledFallback() {
        assertEquals(listOf("appearance","language","sources","editor","diagnostics","about"),SettingsCategory.entries.map{it.key})
        assertEquals(6,SettingsCategory.entries.map{it.key}.toSet().size)
        for(key in listOf(null,"", "1","unknown")) {
            assertNull(SettingsCategory.fromKey(key));assertTrue(settingsPresentation(key,false).categories)
            assertFalse(settingsPresentation(key,false).detail);assertEquals(SettingsCategory.APPEARANCE,settingsPresentation(key,true).active)
        }
    }
    @Test fun compactListThenDetailThenBackIsOneAuxiliaryRoute() {
        val list=settingsPresentation(null,false);assertTrue(list.categories);assertFalse(list.detail)
        assertEquals(SettingsBackAction.EXIT,settingsBackAction(false,list))
        for(c in SettingsCategory.entries) {
            val detail=settingsPresentation(c.key,false);assertFalse(detail.categories);assertTrue(detail.detail)
            assertEquals(c,detail.active);assertEquals(SettingsBackAction.CATEGORIES,settingsBackAction(false,detail))
        }
    }
    @Test fun wideHasBothPanelsAndBackExitsRatherThanInventingAnotherRoot() {
        for(key in listOf(null)+SettingsCategory.entries.map{it.key}) {
            val wide=settingsPresentation(key,true);assertTrue(wide.categories);assertTrue(wide.detail)
            assertFalse(wide.backToCategories);assertEquals(SettingsBackAction.EXIT,settingsBackAction(false,wide))
        }
    }
    @Test fun noticesBackHasPriorityAndDoesNotEraseCategory() {
        for(split in listOf(false,true))for(c in SettingsCategory.entries) {
            val p=settingsPresentation(c.key,split);assertEquals(SettingsBackAction.CLOSE_NOTICES,settingsBackAction(true,p))
            assertEquals(c,settingsPresentation(c.key,split).active)
        }
    }
    @Test fun selectionSurvivesWindowAndFontFallbackWithoutMutatingPreferenceValues() {
        val key=SettingsCategory.LANGUAGE.key
        val inputs=listOf(Triple(400f,800f,1f),Triple(1200f,800f,1f),Triple(1200f,300f,1f),Triple(1200f,800f,2f),Triple(1600f,1100f,2f))
        inputs.forEach{(w,h,f)->val split=responsivePaneLayout(w,h,f,16f).split;assertEquals(SettingsCategory.LANGUAGE,settingsPresentation(key,split).active)}
        assertFalse(responsivePaneLayout(1200f,300f,1f,16f).split);assertFalse(responsivePaneLayout(1200f,800f,2f,16f).split)
        assertTrue(responsivePaneLayout(1200f,800f,1f,16f).split)
    }
    @Test fun themeOnlyChangesThemeAndPreservesBrowserDiscoveryLanguageAndSchema() {
        val old=AppSettings(browser=BrowserSettings(false,4,false,true),discovery=DiscoverySettings(6,100,RepeatPolicy.SHOW_ALL,DiscoveryMode.AGGRESSIVE),appearance=AppearanceSettings(ThemeMode.DARK,AppLanguage.ENGLISH))
        for(mode in ThemeMode.entries) {
            val next=settingsWithTheme(old,mode);assertEquals(mode,next.appearance.themeMode)
            assertEquals(old.appearance.language,next.appearance.language);assertSame(old.browser,next.browser);assertSame(old.discovery,next.discovery);assertEquals(old.schemaVersion,next.schemaVersion)
        }
    }
    @Test fun languageOnlyChangesLanguageAndPreservesOtherOwnedSettings() {
        val old=AppSettings(appearance=AppearanceSettings(ThemeMode.DARK,AppLanguage.CHINESE))
        for(language in AppLanguage.entries) {
            val next=settingsWithLanguage(old,language);assertEquals(language,next.appearance.language);assertEquals(ThemeMode.DARK,next.appearance.themeMode)
            assertSame(old.browser,next.browser);assertSame(old.discovery,next.discovery);assertEquals(old.schemaVersion,next.schemaVersion)
        }
    }
    @Test fun editorPreferenceOnlyChangesEditorAndSurvivesOtherSettingsChanges() {
        val old=AppSettings(browser=BrowserSettings(false,4,false,true),discovery=DiscoverySettings(6,100),appearance=AppearanceSettings(ThemeMode.DARK,AppLanguage.CHINESE))
        val next=settingsWithEditorCompression(old,EditorCompressionMode.SAVE_SPACE)
        assertSame(old.browser,next.browser);assertSame(old.discovery,next.discovery);assertSame(old.appearance,next.appearance)
        assertEquals(old.schemaVersion,next.schemaVersion)
        assertEquals(EditorCompressionMode.SAVE_SPACE,next.editor.defaultCompression)
        assertSame(next.editor,settingsWithTheme(next,ThemeMode.LIGHT).editor)
        assertSame(next.editor,settingsWithLanguage(next,AppLanguage.ENGLISH).editor)
    }
}
