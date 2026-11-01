package com.easysavevideo.app.ui.shell

import com.easysavevideo.core.model.AppSettings

/** A retired screen cannot edit preferences after returning to the same route. */
internal class SaveMethodSettingsActions(private val canAct: () -> Boolean,
    private val dispatch: (SettingsEdit.SaveMethodEnabled) -> Unit) {
    private var closed = false
    fun edit(edit: SettingsEdit.SaveMethodEnabled) { if (!closed && canAct()) dispatch(edit) }
    fun close() { closed = true }
}

/** Loaded local preferences and live settings-route authority are both required. */
internal fun applySaveMethodSettingsEdit(current: AppSettings, edit: SettingsEdit.SaveMethodEnabled,
    ready: Boolean, foreground: Boolean, alive: Boolean, route: EsvDestination): AppSettings? {
    if (!ready || !foreground || !alive || route != EsvDestination.SETTINGS) return null
    return edit.applyTo(current)
}
