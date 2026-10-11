package com.easysavevideo.app.ui.shell

import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.easysavevideo.core.model.AppLanguage

/** API33+ uses platform per-app locales; API31/32 use the existing settings owner. */
@Composable
fun EsvLocaleHost(language: AppLanguage, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val configuration = LocalConfiguration.current
    if (Build.VERSION.SDK_INT >= 33 || language == AppLanguage.SYSTEM) {
        content()
    } else {
        val localized = remember(base, configuration, language) {
            base.createConfigurationContext(Configuration(configuration).apply {
                setLocales(LocaleList.forLanguageTags(language.tag))
            })
        }
        CompositionLocalProvider(LocalContext provides localized,
            LocalConfiguration provides localized.resources.configuration, content = content)
    }
}
