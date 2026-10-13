package com.easysavevideo.app.ui.shell

import com.easysavevideo.core.model.*

/** Local auxiliary-page selection only; stable keys, no second navigation or preference owner. */
internal enum class SettingsCategory(val key:String) {
    APPEARANCE("appearance"), LANGUAGE("language"), SOURCES("sources"), SAVE("save"), EDITOR("editor"), DIAGNOSTICS("diagnostics"), ABOUT("about");
    companion object { fun fromKey(key:String?)=entries.firstOrNull{it.key==key} }
}
internal data class SettingsPresentation(val active:SettingsCategory?,val categories:Boolean,val detail:Boolean,val backToCategories:Boolean)
internal fun settingsPresentation(key:String?,split:Boolean):SettingsPresentation {
    val selected=SettingsCategory.fromKey(key)
    return SettingsPresentation(selected ?: if(split)SettingsCategory.APPEARANCE else null,
        split || selected==null,split || selected!=null,!split && selected!=null)
}
internal enum class SettingsBackAction { CLOSE_NOTICES, CATEGORIES, EXIT }
internal fun settingsBackAction(notices:Boolean,presentation:SettingsPresentation)=when {
    notices -> SettingsBackAction.CLOSE_NOTICES
    presentation.backToCategories -> SettingsBackAction.CATEGORIES
    else -> SettingsBackAction.EXIT
}
internal fun settingsWithTheme(settings:AppSettings,mode:ThemeMode)=settings.copy(appearance=settings.appearance.copy(themeMode=mode))
internal fun settingsWithLanguage(settings:AppSettings,language:AppLanguage)=settings.copy(appearance=settings.appearance.copy(language=language))
internal fun settingsWithEditorCompression(settings:AppSettings,mode:EditorCompressionMode)=settings.copy(editor=settings.editor.copy(defaultCompression=mode))

internal fun settingsWithWifiOnly(settings:AppSettings,wifiOnly:Boolean)=settings.copy(save=settings.save.copy(wifiOnly=wifiOnly))

/** Intent values do not carry a stale composable's unrelated preferences. */
sealed interface SettingsEdit {
    fun applyTo(current: AppSettings): AppSettings
    data class Theme(val mode: ThemeMode) : SettingsEdit {
        override fun applyTo(current: AppSettings) = settingsWithTheme(current, mode)
    }
    data class Language(val language: AppLanguage) : SettingsEdit {
        override fun applyTo(current: AppSettings) = settingsWithLanguage(current, language)
    }
    data class EditorCompression(val mode: EditorCompressionMode) : SettingsEdit {
        override fun applyTo(current: AppSettings) = settingsWithEditorCompression(current, mode)
    }
    data class WifiOnly(val enabled: Boolean) : SettingsEdit {
        override fun applyTo(current: AppSettings) = settingsWithWifiOnly(current, enabled)
    }
}
internal fun settingsWithBrowser(current: AppSettings, captured: AppSettings) = current.copy(browser = captured.browser)
