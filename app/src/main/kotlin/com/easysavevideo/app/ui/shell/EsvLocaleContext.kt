package com.easysavevideo.app.ui.shell

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.easysavevideo.core.model.AppLanguage

/** A stateless resource view; the existing settings/platform owners remain the only locale writers. */
internal fun esvLocaleOverrideTag(apiLevel: Int, language: AppLanguage): String? =
    if (apiLevel >= 33 || language == AppLanguage.SYSTEM) null else language.tag

internal fun esvLocalizedContext(base: Context, configuration: Configuration, language: AppLanguage,
    apiLevel: Int = Build.VERSION.SDK_INT): Context {
    val tag = esvLocaleOverrideTag(apiLevel, language) ?: return base
    return base.createConfigurationContext(Configuration(configuration).apply {
        setLocales(LocaleList.forLanguageTags(tag))
    })
}
