package com.easysavevideo.app.library

import com.easysavevideo.library.*

/** Ephemeral user consent for current-page identities and exact representations, never readers. */
internal class LibraryMultiSelection private constructor(val active: Boolean, refs: Set<MediaReadRef>, val query: LibraryQuery?) {
    val references: Set<MediaReadRef> = refs.toSet()
    val count get() = references.size
    init { require(count <= LibraryLimits.MAX_PAGE_SIZE && references.map { it.itemId }.distinct().size == count) }
    fun contains(item: MediaItem): Boolean = item.lifecycle == MediaLifecycle.READY && item.readRef() in references
    fun reconcile(items: List<MediaItem>): LibraryMultiSelection {
        val current = eligible(items).map { it.readRef() }.toSet()
        val retained = references.intersect(current)
        return if (retained == references) this else LibraryMultiSelection(active, retained, query)
    }
    fun toggle(item: MediaItem, items: List<MediaItem>): LibraryMultiSelection {
        if (!active || eligible(items).none { it == item }) return this
        val current = reconcile(items)
        val ref = item.readRef()
        return if (ref in current.references) LibraryMultiSelection(true, current.references - ref, query)
        else if (current.count < LibraryLimits.MAX_PAGE_SIZE) LibraryMultiSelection(true, current.references + ref, query) else current
    }
    fun selectPage(items: List<MediaItem>): LibraryMultiSelection =
        if (!active) this else LibraryMultiSelection(true, eligible(items).take(LibraryLimits.MAX_PAGE_SIZE).map { it.readRef() }.toSet(), query)
    fun clear(): LibraryMultiSelection = if (count == 0) this else LibraryMultiSelection(active, emptySet(), query)
    companion object {
        fun inactive() = LibraryMultiSelection(false, emptySet(), null)
        fun enter(query: LibraryQuery = LibraryQuery(pageSize = 50)) = LibraryMultiSelection(true, emptySet(), query)
        private fun eligible(items: List<MediaItem>): List<MediaItem> {
            val unique = items.groupingBy { it.itemId }.eachCount()
            return items.filter { it.lifecycle == MediaLifecycle.READY && unique[it.itemId] == 1 }
        }
    }
}

/** Existing single-item ports are admitted only for one exact, current foreground selection. */
internal fun libraryMultiSelectionInteractive(selection: LibraryMultiSelection, state: LibraryViewState): Boolean =
    selection.active && selection.query == state.query && state.foreground && state.ready && !state.busy && !state.loading &&
        state.selected == null && state.editor == null && state.deletion == null && state.move == null

internal fun librarySingleMultiAction(selection: LibraryMultiSelection, state: LibraryViewState): MediaItem? {
    if (!libraryMultiSelectionInteractive(selection, state) || selection.count != 1) return null
    val reference = selection.references.single()
    return state.items.singleOrNull { it.itemId == reference.itemId }?.takeIf {
        it.lifecycle == MediaLifecycle.READY && it.readRef() == reference
    }
}
