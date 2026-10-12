package com.easysavevideo.app.library

import com.easysavevideo.library.*
import org.junit.Assert.*
import org.junit.Test

class LibraryBackDispatchTest {
    @Test fun pageAndSystemBackBothExitTwoItemSelectionWithoutBusinessCommand() {
        for (entry in listOf("page", "system")) {
            val items=listOf(media("one"),media("two"))
            var multi=LibraryMultiSelection.enter().selectPage(items)
            val events=mutableListOf<String>()
            val state=LibraryViewState(items=items,foreground=true,ready=true)
            dispatchLibraryBack(state,multi.active,{events+="cancel_delete"},{events+="cancel_editor"},
                {events+="exit_multi";multi=LibraryMultiSelection.inactive()},
                {events+="dismiss_detail"},{events+="cancel_move"},{events+="up"},{events+="leave"})
            assertEquals(entry,listOf("exit_multi"),events)
            assertFalse(multi.active);assertEquals(0,multi.count)
        }
    }
    @Test fun multiSelectionWinsOverFolderAndDetailNavigation() {
        val events=mutableListOf<String>()
        dispatchLibraryBack(LibraryViewState(query=LibraryQuery(folderId="folder"),selected=LibrarySelection.Media(media("one"))),true,
            {events+="delete"},{events+="editor"},{events+="multi"},{events+="detail"},{events+="move"},{events+="up"},{events+="leave"})
        assertEquals(listOf("multi"),events)
    }
    @Test fun consentDismissalKeepsPriorityAndNormalBackRetainsExistingActions() {
        val target=LibrarySelection.Media(media("one"))
        val cases=listOf(LibraryViewState(deletion=LibraryDeleteSelection(target)) to "delete",
            LibraryViewState(editor=LibraryNameEditor(null,null,"")) to "editor",
            LibraryViewState(selected=target) to "detail",
            LibraryViewState(move=LibraryMoveSelection(target,null)) to "move",
            LibraryViewState(query=LibraryQuery(folderId="folder")) to "up",LibraryViewState() to "leave")
        for((state,expected) in cases){
            val events=mutableListOf<String>()
            dispatchLibraryBack(state,false,{events+="delete"},{events+="editor"},{events+="multi"},
                {events+="detail"},{events+="move"},{events+="up"},{events+="leave"})
            assertEquals(listOf(expected),events)
        }
    }
    private fun media(id:String)=MediaItem(id,1,1,null,id,0,MediaDateKind.SAVED,1000,1000,320,240)
}
