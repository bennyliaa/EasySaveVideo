package com.easysavevideo.app.library

import com.easysavevideo.app.library.thumbnail.*
import org.junit.Assert.*
import org.junit.Test

class LibraryThumbnailUiPolicyTest {
    @Test fun explicitRequestAndEveryAdmissionGateAreRequired() {
        assertTrue(libraryThumbnailAdmission(true,true,true,false,false,false,false,true))
        assertFalse(libraryThumbnailAdmission(false,true,true,false,false,false,false,true))
        assertFalse(libraryThumbnailAdmission(true,false,true,false,false,false,false,true))
        assertFalse(libraryThumbnailAdmission(true,true,false,false,false,false,false,true))
        assertFalse(libraryThumbnailAdmission(true,true,true,true,false,false,false,true))
        assertFalse(libraryThumbnailAdmission(true,true,true,false,true,false,false,true))
        assertFalse(libraryThumbnailAdmission(true,true,true,false,false,true,false,true))
        assertFalse(libraryThumbnailAdmission(true,true,true,false,false,false,true,true))
        assertFalse(libraryThumbnailAdmission(true,true,true,false,false,false,false,false))
    }
    @Test fun onlyActualMediaViewportKeysCanRequestImages() {
        assertEquals(setOf("one","two"),libraryVisibleThumbnailIds(listOf("header","folder:one","media:one","media:two","media:one",8,"media:")))
        assertTrue(libraryVisibleThumbnailIds(emptyList()).isEmpty())
    }
}
