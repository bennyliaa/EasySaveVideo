package com.easysavevideo.app.ui.shell

import com.easysavevideo.core.model.AppSettings

/** One composition's authority only; no settings snapshot or second persistence owner. */
internal class BrowserSettingsActions(private val canAct: () -> Boolean,
    private val dispatch: (SettingsEdit.BrowserEdit) -> Unit) {
    private var closed = false
    fun edit(edit: SettingsEdit.BrowserEdit) { if (!closed && canAct()) dispatch(edit) }
    fun close() { closed = true }
}

internal fun applyBrowserSettingsEdit(current: AppSettings, edit: SettingsEdit.BrowserEdit,
    ready: Boolean, foreground: Boolean, alive: Boolean, route: EsvDestination,
    expectedRoute: EsvDestination): AppSettings? {
    if (!ready || !foreground || !alive || route != expectedRoute ||
        expectedRoute !in setOf(EsvDestination.BROWSER, EsvDestination.SETTINGS)) return null
    return edit.applyTo(current)
}

/** Browser edits are admitted only after successful loading; preserve loaded preferences after early appearance edits. */
internal fun mergeLoadedBrowserSettings(current: AppSettings, loaded: AppSettings,
    loadSucceeded: Boolean): AppSettings = if (loadSucceeded) current.copy(browser = loaded.browser) else current
