package com.easysavevideo.app.library.share

import org.junit.Assert.*
import org.junit.Test

class ShareGroupRevocationTest {
    private data class Copy(val id:Int,var allowed:Boolean=true,var activeReader:Boolean=true,var removed:Boolean=false)
    @Test fun allLogicalReadsStopBeforeFirstFrameworkRevocationAndFailuresDoNotStarveOtherCopies() {
        val copies=listOf(Copy(1),Copy(2));val unrelated=Copy(3);val attempts=mutableListOf<Int>()
        revokeShareGroup(copies,deny={it.allowed=false},revoke={
            assertTrue(copies.none{it.allowed});attempts+=it.id
            if(it.id==1)throw IllegalStateException("framework unavailable")
        },cleanup={if(!it.activeReader)it.removed=true})
        assertEquals(listOf(1,2),attempts);assertTrue(unrelated.allowed)
        assertTrue(copies.none{it.removed}) // Authority denial is not actual FD release/cleanup.
    }
    @Test fun cleanupFailureRetainsOwnedResourceAndLaterCopiesStillReceiveCleanupAttempt() {
        val copies=listOf(Copy(1,activeReader=false),Copy(2,activeReader=false));val attempts=mutableListOf<Int>()
        revokeShareGroup(copies,deny={it.allowed=false},revoke={},cleanup={
            attempts+=it.id;if(it.id==1)throw IllegalStateException("cleanup pending") else it.removed=true
        })
        assertEquals(listOf(1,2),attempts);assertFalse(copies[0].removed);assertTrue(copies[1].removed)
        assertTrue(copies.none{it.allowed})
    }
}
