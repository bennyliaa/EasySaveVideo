package com.easysavevideo.app.save

import com.easysavevideo.core.model.BrowserContextRef

/** Ephemeral page identity only. Never persisted or used to reopen the failed task source. */
internal class SaveInspectionPage(
    val context: BrowserContextRef, val epoch: Long, val ready: Boolean,
    val pageUrl: String?, val viewUrl: String?, val viewIdentity: Any?, val revision: Long, val documentRevision: Long = 0,
) {
    fun isUsable() = ready && viewIdentity != null && pageUrl != null && pageUrl == viewUrl
    fun matches(current: SaveInspectionPage?, foreground: Boolean): Boolean = foreground && isUsable() &&
        current != null && current.isUsable() && current.context == context && current.epoch == epoch &&
        current.pageUrl == pageUrl && current.viewIdentity === viewIdentity && current.revision == revision &&
        current.documentRevision == documentRevision
    override fun toString() = "SaveInspectionPage([redacted])"
}
