package com.easysavevideo.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticPickerPolicyTest {
    private val valid = DiagnosticDestinationFacts("content","example.documents",false,80,false,true,true,true)
    @Test fun onlyDocumentContentWithReturnedAndActualWriteGrantIsAllowed() {
        assertTrue(diagnosticDestinationAllowed(valid))
        listOf(valid.copy(scheme="file"),valid.copy(scheme="https"),valid.copy(scheme=null),
            valid.copy(authority=""),valid.copy(authority=null),valid.copy(authority=" "),
            valid.copy(hasFragment=true),valid.copy(utf8Bytes=8193),valid.copy(utf8Bytes=0),
            valid.copy(hasClipData=true),valid.copy(isDocument=false),valid.copy(returnedWrite=false),
            valid.copy(actualWrite=false)).forEach { assertFalse(it.toString(),diagnosticDestinationAllowed(it)) }
        assertTrue(diagnosticDestinationAllowed(valid.copy(utf8Bytes=8192)))
    }
    @Test fun firstCurrentResultWaitsForResumeAndIsDeliveredOnlyOnce() {
        val p=DiagnosticPickerDelivery<String>();p.begin("a")
        p.receive("wrong","WRONG");p.receive("a","FIRST");p.receive("a","DUPLICATE")
        assertNull(p.take());p.setResumed(true);assertEquals("a" to "FIRST",p.take());assertNull(p.take())
        p.receive("a","LATE");assertNull(p.take())
    }
    @Test fun dismissCloseAndRecreationDiscardPendingWithoutReplay() {
        val p=DiagnosticPickerDelivery<String>();p.begin("old");p.receive("old","DATA");p.dismiss("old")
        p.setResumed(true);assertNull(p.take());p.begin("new");p.receive("old","STALE");assertNull(p.take())
        p.receive("new","DATA");p.close();assertNull(p.take())
        val recreated=DiagnosticPickerDelivery<String>();recreated.setResumed(true);recreated.receive("new","RESTORED")
        assertNull(recreated.take());recreated.begin("unique");recreated.receive("new","STALE");assertNull(recreated.take())
    }
    @Test fun secondPauseBeforeDeliveryStillDefersThePendingResult() {
        val p=DiagnosticPickerDelivery<String>();p.begin("a");p.setResumed(true);p.setResumed(false)
        p.receive("a","data");assertNull(p.take());p.setResumed(true);assertEquals("a" to "data",p.take())
    }
}
