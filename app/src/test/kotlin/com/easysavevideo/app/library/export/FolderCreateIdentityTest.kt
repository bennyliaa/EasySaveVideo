package com.easysavevideo.app.library.export

import org.junit.Assert.*
import org.junit.Test

class FolderCreateIdentityTest {
    @Test fun knownExistingDocumentIdCannotBeAcceptedAsNewOutput() {
        val before=mutableSetOf("existing-user-doc");val guard=FolderCreateIdentity(before);before.clear()
        try{guard.requireFresh("existing-user-doc");fail("Overwrite") }catch(_:IllegalArgumentException){}
        guard.requireFresh("new-operation-doc")
        assertFalse(guard.toString().contains("existing-user-doc"))
    }
    @Test fun preflightIsBoundedBeforeAnyCreation() {
        try{FolderCreateIdentity((0..1000).map{"id$it"}.toSet());fail("Unbounded directory")}catch(_:IllegalArgumentException){}
        try{FolderCreateIdentity(setOf(""));fail("Missing identity")}catch(_:IllegalArgumentException){}
    }
    @Test fun returnedExistingIdCannotReachBindingWritingOrCleanupOwnership() {
        var bound=false
        try{createFreshFolderChild(setOf("existing"),{"existing"},{},{it},{bound=true});fail("Existing child adopted")}catch(_:IllegalArgumentException){}
        assertFalse(bound)
    }
    @Test fun uncertainCreateOrBindingIsNeverRetriedOrReplacedByName() {
        var calls=0;var bound=false
        try{createFreshFolderChild(emptySet(),{calls++;throw IllegalStateException("create uncertain")},{},{it:String->it},{bound=true});fail("Uncertain create")}catch(_:IllegalStateException){}
        assertEquals(1,calls);assertFalse(bound)
        calls=0
        try{createFreshFolderChild(emptySet(),{calls++;"new"},{},{it},{throw IllegalStateException("bind uncertain")});fail("Uncertain bind")}catch(_:IllegalStateException){}
        assertEquals(1,calls)
    }
}
