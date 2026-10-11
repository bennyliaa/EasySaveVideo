package com.easysavevideo.app.library

import com.easysavevideo.library.*
import org.junit.Assert.*
import org.junit.Test

/** Pure snapshots only; no filesystem, reader, decoder or permission. */
class LibraryPanelPolicyTest {
    @Test fun paneBudgetsAccountForWidthHeightAndLargeFonts() {
        assertTrue(libraryPanelLayout(1080f,720f,1f,16f).expanded)
        assertEquals(360f,libraryPanelLayout(1080f,720f,1f,16f).detailWidthDp)
        assertFalse(libraryPanelLayout(1079f,720f,1f,16f).expanded)
        assertFalse(libraryPanelLayout(1400f,359f,1f,16f).expanded)
        assertFalse(libraryPanelLayout(1400f,900f,2f,16f).expanded)
        assertTrue(libraryPanelLayout(2000f,900f,2f,16f).expanded)
        assertEquals(720f,libraryPanelLayout(2000f,900f,2f,16f).detailWidthDp)
    }
    @Test fun invalidMeasurementsFailToCompact() {
        assertFalse(libraryPanelLayout(Float.NaN,720f,1f,16f).expanded)
        assertFalse(libraryPanelLayout(1400f,Float.POSITIVE_INFINITY,1f,16f).expanded)
        assertFalse(libraryPanelLayout(1400f,720f,0f,16f).expanded)
        assertFalse(libraryPanelLayout(1400f,720f,1f,-1f).expanded)
    }
    @Test fun stableMediaIdentityResolvesLatestReferenceAfterReorder() {
        val old=media("one");val updated=old.copy(revision=2,representationGeneration=3,displayName="Updated")
        val result=libraryCurrentDetail(LibrarySelection.Media(old),listOf(media("other"),updated),emptyList()) as LibrarySelection.Media
        assertEquals(updated,result.item)
        assertEquals(MediaReadRef("one",2,3),result.item.readRef())
    }
    @Test fun absentOrNonReadyOrAmbiguousIdentityNeverFallsBackToNeighbor() {
        val old=media("one");val selection=LibrarySelection.Media(old)
        assertNull(libraryCurrentDetail(selection,listOf(media("other")),emptyList()))
        assertNull(libraryCurrentDetail(selection,listOf(old.copy(lifecycle=MediaLifecycle.DELETE_PENDING)),emptyList()))
        assertNull(libraryCurrentDetail(selection,listOf(old,old.copy(revision=2)),emptyList()))
        assertNull(libraryCurrentDetail(null,listOf(old),emptyList()))
    }
    @Test fun folderIdentityResolvesLatestCountsAndCannotAliasMediaId() {
        val old=LibraryFolder("one",1,null,"Folder")
        val updated=old.copy(revision=2,itemCount=3)
        assertEquals(LibrarySelection.Folder(updated),libraryCurrentDetail(LibrarySelection.Folder(old),listOf(media("one")),listOf(updated)))
        assertNull(libraryCurrentDetail(LibrarySelection.Folder(old),listOf(media("one")),emptyList()))
        assertNull(libraryCurrentDetail(LibrarySelection.Media(media("one")),emptyList(),listOf(updated)))
    }
    private fun media(id:String)=MediaItem(id,1,1,null,id,1000,MediaDateKind.SAVED,1024,30000,640,480)
}
