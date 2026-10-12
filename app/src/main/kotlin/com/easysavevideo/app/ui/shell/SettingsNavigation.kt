package com.easysavevideo.app.ui.shell

import com.easysavevideo.core.model.*

/** Local auxiliary-page selection only; stable keys, no second navigation or preference owner. */
internal enum class SettingsCategory(val key:String) {
    APPEARANCE("appearance"), LANGUAGE("language"), SOURCES("sources"), ABOUT("about");
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
