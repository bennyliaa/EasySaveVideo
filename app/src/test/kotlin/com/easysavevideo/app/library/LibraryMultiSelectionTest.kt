package com.easysavevideo.app.library

import com.easysavevideo.library.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LibraryMultiSelectionTest {
    private val one=media("one")
    private val two=media("two")
    private fun state(items:List<MediaItem> = listOf(one,two))=LibraryViewState(items=items,ready=true,foreground=true)
    @Test fun inactiveNeverAdmitsToggleOrCommands() {
        val s=LibraryMultiSelection.inactive().toggle(one,listOf(one))
        assertFalse(s.active);assertEquals(0,s.count);assertNull(librarySingleMultiAction(s,state()))
    }
    @Test fun currentPageSelectionRetainsStableReferencesAcrossReordering() {
        val s=LibraryMultiSelection.enter().selectPage(listOf(one,two))
        assertEquals(2,s.count);assertTrue(s.reconcile(listOf(two,one)).contains(one));assertTrue(s.contains(two))
    }
    @Test fun staleRevisionOrRepresentationIsClearedWithoutRebasingConsent() {
        val s=LibraryMultiSelection.enter().selectPage(listOf(one))
        assertEquals(0,s.reconcile(listOf(one.copy(revision=2))).count)
        assertEquals(0,s.reconcile(listOf(one.copy(representationGeneration=2))).count)
        assertNull(librarySingleMultiAction(s,state(listOf(one.copy(revision=2)))))
    }
    @Test fun missingDeletedAndAmbiguousIdsNeverSelectAdjacentItems() {
        val s=LibraryMultiSelection.enter().selectPage(listOf(one))
        assertEquals(0,s.reconcile(listOf(two)).count)
        assertEquals(0,s.reconcile(listOf(one.copy(lifecycle=MediaLifecycle.DELETED))).count)
        assertEquals(0,s.reconcile(listOf(one,one.copy(revision=2))).count)
        assertFalse(s.reconcile(listOf(two)).contains(two))
    }
    @Test fun togglingAnObsoleteSnapshotCannotSelectReplacement() {
        val current=one.copy(revision=2)
        val s=LibraryMultiSelection.enter().toggle(one,listOf(current))
        assertEquals(0,s.count);assertEquals(1,s.toggle(current,listOf(current)).count)
    }
    @Test fun pageSelectionIsBoundedAndExcludesNonReadyOrDuplicateIds() {
        val items=(0..150).map{media("owned-$it")}
        assertEquals(LibraryLimits.MAX_PAGE_SIZE,LibraryMultiSelection.enter().selectPage(items).count)
        val s=LibraryMultiSelection.enter().selectPage(listOf(one,one,two.copy(lifecycle=MediaLifecycle.DELETE_PENDING)))
        assertEquals(0,s.count)
    }
    @Test fun deselectClearAndExitRetainNoCommandIntent() {
        val s=LibraryMultiSelection.enter().toggle(one,listOf(one))
        assertEquals(0,s.toggle(one,listOf(one)).count)
        assertTrue(s.clear().active);assertEquals(0,s.clear().count)
        assertFalse(LibraryMultiSelection.inactive().active)
    }
    @Test fun onlyOneExactCurrentItemCanUseExistingSinglePorts() {
        val s=LibraryMultiSelection.enter().selectPage(listOf(one))
        assertEquals(one,librarySingleMultiAction(s,state()))
        assertNull(librarySingleMultiAction(s,state(listOf(two))))
        assertNull(librarySingleMultiAction(s,state(listOf(one,one))))
        assertNull(librarySingleMultiAction(LibraryMultiSelection.enter().selectPage(listOf(one,two)),state()))
    }
    @Test fun foregroundReadyBusyLoadingAndModalGatesAreRequired() {
        val s=LibraryMultiSelection.enter().selectPage(listOf(one));val st=state()
        listOf(st.copy(foreground=false),st.copy(ready=false),st.copy(busy=true),st.copy(loading=true),
            st.copy(selected=LibrarySelection.Media(one)),st.copy(editor=LibraryNameEditor(null,null,"")),
            st.copy(deletion=LibraryDeleteSelection(LibrarySelection.Media(one))),
            st.copy(move=LibraryMoveSelection(LibrarySelection.Media(one),null))).forEach {assertNull(librarySingleMultiAction(s,it))}
    }
    @Test fun metadataResolutionDoesNotRequireAnyReaderOrNativeFrame() {
        val s=LibraryMultiSelection.enter().selectPage(listOf(one));val latestMetadata=one.copy(durationMillis=40000)
        assertEquals(latestMetadata,librarySingleMultiAction(s,state(listOf(latestMetadata))))
        assertEquals(one.readRef(),s.references.single())
    }
    @Test fun changedQueryFolderSortOrPageRevokesActionAdmission() {
        val s=LibraryMultiSelection.enter().selectPage(listOf(one));val st=state()
        listOf(st.query.copy(text="owned query"),st.query.copy(folderId="owned-folder"),
            st.query.copy(sort=LibrarySort.SIZE),st.query.copy(direction=if(st.query.direction==SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING),
            st.query.copy(cursor=LibraryCursor(2,st.query.queryKey(),50))).forEach {
            assertFalse(libraryMultiSelectionInteractive(s,st.copy(query=it)))
            assertNull(librarySingleMultiAction(s,st.copy(query=it)))
        }
    }
    @Test fun toolbarResourcesHaveMatchingKeysAndFormatParameters() {
        val root=listOf(File("src/main/res"),File("app/src/main/res")).first{it.isDirectory}
        fun read(folder:String):Map<String,String>{
            val f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            val nodes=f.newDocumentBuilder().parse(File(root,"$folder/library_multi.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate{nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent}
        }
        val en=read("values");val zh=read("values-zh-rCN");assertEquals(21,en.size);assertEquals(en.keys,zh.keys)
        val format=Regex("%[0-9]+\\$[ds]")
        en.keys.forEach {assertEquals(format.findAll(en.getValue(it)).map{m->m.value}.toList(),format.findAll(zh.getValue(it)).map{m->m.value}.toList())}
    }
    private fun media(id:String)=MediaItem(id,1,1,null,id,1000,MediaDateKind.SAVED,1024,30000,640,480)
}
