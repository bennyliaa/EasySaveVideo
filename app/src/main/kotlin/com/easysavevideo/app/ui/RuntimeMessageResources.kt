package com.easysavevideo.app.ui

import android.content.Context
import com.easysavevideo.app.ui.shell.esvLocalizedContext
import com.easysavevideo.core.model.AppLanguage

/** Resolve each new notice against current resources; never retain an Activity or localized context.
 * The language supplier belongs to the existing settings mirror, not a new preference owner.
 * Notices already stored in view state remain generation-time strings.
 */
internal fun androidRuntimeMessageText(context: Context, language: () -> AppLanguage): RuntimeMessageText {
    val application = context.applicationContext
    return RuntimeMessageText { key, arguments ->
        esvLocalizedContext(application, application.resources.configuration, language())
            .getString(key.resource, *arguments)
    }
}
