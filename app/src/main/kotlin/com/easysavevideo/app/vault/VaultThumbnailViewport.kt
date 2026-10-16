package com.easysavevideo.app.vault

/** Process-local layout marker, never a lazy/saveable key or an authentication capability. */
internal class VaultThumbnailViewportRow(val ref: VaultMediaReadRef) {
    override fun toString() = "VaultThumbnailViewportRow(REDACTED)"
}

/**
 * Read only actual measured viewport content types. Identity intersection rejects old layout
 * information after reveal/catalog replacement, even when row positions happen to match.
 * Stable viewport order determines the bounded first sixteen; composition is not visibility.
 */
internal fun vaultVisibleThumbnailRefs(catalog: List<VaultThumbnailViewportRow>, visible: List<Any?>): List<VaultMediaReadRef> {
    if (catalog.size > VaultThumbnailLimits.MAX_IDENTITIES) return emptyList()
    val admitted = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<VaultThumbnailViewportRow, Boolean>())
    admitted.addAll(catalog)
    val result = LinkedHashSet<VaultMediaReadRef>()
    for (value in visible) {
        val row = value as? VaultThumbnailViewportRow ?: continue
        if (row !in admitted) continue
        result += row.ref
        if (result.size == VaultThumbnailLimits.MAX_FRAMES) break
    }
    return result.toList()
}
