package com.easysavevideo.app.editor
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
class EditorInitializationTest {
    @Test fun storageFailureIsVisibleAndDoesNotConstructEditor() {
        var created=false
        val result=initializeEditor({throw IOException("PRIVATE_DETAIL")}){created=true;Any()}
        assertFalse(created);assertNull(result.value);assertEquals(EditorInitializationStatus.STORAGE_UNAVAILABLE,result.status)
        assertFalse(result.toString().contains("PRIVATE_DETAIL"))
    }
    @Test fun runtimeFailureIsVisibleWithoutRetainingExceptionText() {
        val result=initializeEditor({}){throw IllegalStateException("PRIVATE_DETAIL")}
        assertNull(result.value);assertEquals(EditorInitializationStatus.RUNTIME_UNAVAILABLE,result.status)
        assertFalse(result.toString().contains("PRIVATE_DETAIL"))
    }
    @Test fun successMeansConstructorReadyNotEntitlementOrExecutionSuccess() {
        val value=Any();var submitted=false;val result=initializeEditor({}){value}
        assertSame(value,result.value);assertEquals(EditorInitializationStatus.READY,result.status);assertFalse(submitted)
    }
    @Test fun unavailableOrMissingRuntimeNeverEnablesEntry() {
        EditorInitializationStatus.entries.forEach { status ->
            assertFalse(editorEntryEnabled(false,status))
            assertEquals(status==EditorInitializationStatus.READY,editorEntryEnabled(true,status))
        }
    }
}
