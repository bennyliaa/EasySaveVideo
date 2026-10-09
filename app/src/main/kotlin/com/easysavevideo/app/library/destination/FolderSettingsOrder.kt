package com.easysavevideo.app.library.destination

import com.easysavevideo.library.destination.OrdinaryDestinationSelectionSnapshot

/** UI-only snapshot ordering. Database mutations, including grant invalidation, own the revision. */
internal class FolderSettingsOrder {
    private var applied: OrdinaryDestinationSelectionSnapshot? = null
    fun accept(snapshot: OrdinaryDestinationSelectionSnapshot): Boolean {
        val before = applied
        if (before != null && (snapshot.revision < before.revision ||
                (snapshot.revision == before.revision && snapshot != before))) return false
        applied = snapshot
        return true
    }
}
