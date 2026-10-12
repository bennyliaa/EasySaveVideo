package com.easysavevideo.app.library

/** Shared by the visible Back control and system Back; exactly one existing action. */
internal fun dispatchLibraryBack(state: LibraryViewState, multiActive: Boolean,
    cancelDelete: () -> Unit, cancelEditor: () -> Unit, exitMulti: () -> Unit,
    dismissSelection: () -> Unit, cancelMove: () -> Unit, up: () -> Unit, leave: () -> Unit) {
    when {
        state.deletion != null -> cancelDelete()
        state.editor != null -> cancelEditor()
        multiActive -> exitMulti()
        state.selected != null -> dismissSelection()
        state.move != null -> cancelMove()
        state.query.folderId != null -> up()
        else -> leave()
    }
}
