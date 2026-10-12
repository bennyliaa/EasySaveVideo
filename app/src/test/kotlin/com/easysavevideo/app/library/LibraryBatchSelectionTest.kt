package com.easysavevideo.app.library

import com.easysavevideo.library.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LibraryBatchSelectionTest {
    @Test fun capturesExactNamesAndRefSnapshotWithoutRebasingConsent() {
        val a=media("a");val b=media("b");val state=LibraryViewState(items=listOf(a,b),ready=true,foreground=true)
        val selected=LibraryBatchSelection.admit(listOf(a.readRef(),b.readRef()),state)!!
        assertTrue(batchSelectionIsCurrent(selected,state))
        assertFalse(batchSelectionIsCurrent(selected,state.copy(items=listOf(a.copy(revision=2),b))))
        assertEquals(listOf(a,b),selected.items)
        assertFalse(batchSelectionIsCurrent(selected,state.copy(query=state.query.copy(sort=LibrarySort.SIZE))))
        assertFalse(selected.toString().contains(a.displayName))
    }
    @Test fun backgroundBusyLoadingModalAndAmbiguousSourcesCannotAdmitBatch() {
        val a=media("a");val state=LibraryViewState(items=listOf(a),ready=true,foreground=true);val refs=listOf(a.readRef())
        for(s in listOf(state.copy(foreground=false),state.copy(ready=false),state.copy(busy=true),state.copy(loading=true),
            state.copy(selected=LibrarySelection.Media(a)),state.copy(items=listOf(a,a)),state.copy(items=emptyList()),
            state.copy(items=listOf(a.copy(lifecycle=MediaLifecycle.DELETE_PENDING)))))assertNull(LibraryBatchSelection.admit(refs,s))
        assertNull(LibraryBatchSelection.admit(listOf(a.readRef(),a.readRef()),state))
    }
    @Test fun newShareCopyLabelsRemainPairedAndDoNotPromiseDelivery() {
        val root=listOf(File("src/main/res"),File("app/src/main/res")).first{it.isDirectory}
        fun read(folder:String):Map<String,String> {
            val f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            val nodes=f.newDocumentBuilder().parse(File(root,"$folder/library_batch_export.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate{nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent}
        }
        val en=read("values");val zh=read("values-zh-rCN");assertEquals(23,en.size);assertEquals(en.keys,zh.keys)
        assertTrue(en.getValue("library_transfer_chooser").contains("unconfirmed"))
        assertTrue(en.getValue("library_batch_share_warning").contains("does not mean delivery"))
    }
    private fun media(id:String)=MediaItem(id,1,1,null,"name-$id",1,MediaDateKind.SAVED,10,null,null,null)
}
