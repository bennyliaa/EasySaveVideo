package com.easysavevideo.app.library

import com.easysavevideo.library.*

class LibraryBatchSelection private constructor(val items:List<MediaItem>,val query:LibraryQuery) {
    val references=items.map { it.readRef() }
    override fun toString()="LibraryBatchSelection(count=${items.size})"
    companion object {
        fun admit(refs:List<MediaReadRef>,state:LibraryViewState):LibraryBatchSelection? {
            if(!state.foreground || !state.ready || state.loading || state.busy || state.selected!=null ||
                state.editor!=null || state.deletion!=null || state.move!=null || refs.size !in 1..LibraryLimits.MAX_PAGE_SIZE ||
                refs.map { it.itemId }.distinct().size!=refs.size)return null
            val items=refs.map { ref -> state.items.singleOrNull { it.itemId==ref.itemId }?.takeIf {
                it.lifecycle==MediaLifecycle.READY && it.readRef()==ref
            } ?: return null }
            return LibraryBatchSelection(items.toList(),state.query)
        }
    }
}
internal fun batchSelectionIsCurrent(selection:LibraryBatchSelection,state:LibraryViewState):Boolean =
    selection.query==state.query && LibraryBatchSelection.admit(selection.references,state)!=null
