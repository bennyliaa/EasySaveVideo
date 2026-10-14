package com.easysavevideo.app.library.destination

import com.easysavevideo.library.destination.OrdinaryDestinationPreference

internal data class DestinationSettingsAvailability(
    val ready: Boolean, val saving: Boolean, val choosingFolder: Boolean,
    val asking: Boolean, val admitting: Boolean, val rememberedFolder: Boolean,
) {
    val mayEdit: Boolean get() = ready && !saving && !choosingFolder
    val mayChooseFolder: Boolean get() = mayEdit && !asking && !admitting
}

/** Retires only this UI's callbacks. Accepted persistence/picker work stays with the existing owner. */
internal class DestinationSettingsActions(
    private val canAct: () -> Boolean,
    private val current: () -> DestinationSettingsAvailability,
    private val setPreference: (OrdinaryDestinationPreference) -> Unit,
    private val chooseFolder: () -> Unit,
    private val cancelFolder: () -> Unit,
    private val forgetFolder: () -> Unit,
    private val dismissNotice: () -> Unit,
) {
    private var closed = false
    private fun active() = !closed && canAct()
    fun select(value: OrdinaryDestinationPreference) {
        if (!active()) return
        val state = current()
        if (!state.mayEdit) return
        // FOLDER_COPY without a remembered grant launches the existing explicit system picker.
        if (value == OrdinaryDestinationPreference.FOLDER_COPY && !state.rememberedFolder && !state.mayChooseFolder) return
        setPreference(value)
    }
    fun choose() { if (active() && current().mayChooseFolder) chooseFolder() }
    fun cancel() { if (active() && current().choosingFolder) cancelFolder() }
    fun forget() { if (active() && current().let { it.mayEdit && it.rememberedFolder }) forgetFolder() }
    fun dismiss() { if (active()) dismissNotice() }
    fun close() { closed = true }
}
