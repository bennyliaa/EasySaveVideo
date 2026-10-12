package com.easysavevideo.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.channels.OverlappingFileLockException
import com.easysavevideo.app.editor.*
import com.easysavevideo.app.library.share.*

class InitializationProbeTest {
    @Test fun everyExactStageRecordsAndRethrowsTheOriginalExceptionWithoutText() {
        for(stage in InitializationStage.entries) {
            val p=InitializationProbe();val original=IOException("PRIVATE_PATH_CONTENT_CREDENTIAL")
            try{p.at(stage){throw original};fail()}catch(failure:Exception){assertSame(original,failure)}
            assertEquals(stage,p.failure?.stage);assertEquals(InitializationErrorKind.IO,p.failure?.kind)
            assertFalse(p.failure.toString().contains("PRIVATE"));assertNull(p.failure?.errno)
        }
    }
    @Test fun firstFailureIsNotOverwrittenByCleanupOrOuterConstructor() {
        val p=InitializationProbe();val original=SecurityException("private")
        try{p.at(InitializationStage.EDIT_ROOT_FSYNC){throw original}}catch(_:Exception){}
        p.record(InitializationStage.EDIT_STORAGE_CONSTRUCT,IllegalStateException())
        assertEquals(InitializationStage.EDIT_ROOT_FSYNC,p.failure?.stage);assertEquals(InitializationErrorKind.SECURITY,p.failure?.kind)
    }
    @Test fun successfulStageAddsNoFailureAndPreservesValue() {
        val p=InitializationProbe();val value=Any();assertSame(value,p.at(InitializationStage.SHARE_SERVICE){value});assertNull(p.failure)
    }
    @Test fun categoriesAreClosedAndDoNotDependOnExceptionMessages() {
        val cases=listOf(IOException() to InitializationErrorKind.IO,OverlappingFileLockException() to InitializationErrorKind.LOCK,
            SecurityException() to InitializationErrorKind.SECURITY,IllegalArgumentException() to InitializationErrorKind.ARGUMENT,
            IllegalStateException() to InitializationErrorKind.STATE,Exception() to InitializationErrorKind.OTHER)
        for((failure,kind) in cases)assertEquals(kind,initializationErrorKind(failure))
    }
    @Test fun errnoOnlyRetainsValidNumericErrnoAndNeverPersistsThrowable() {
        for(number in listOf(-1,0,1,13,4095,4096)) {
            val p=InitializationProbe({InitializationErrorKind.ERRNO},{number})
            p.record(InitializationStage.EDIT_ROOT_FSYNC,IOException("private"))
            assertEquals(number.takeIf{it in 1..4095},p.failure?.errno)
        }
        val p=InitializationProbe({InitializationErrorKind.IO},{13});p.record(InitializationStage.SHARE_OWNER_OPEN,IOException());assertNull(p.failure?.errno)
    }
    @Test fun editorDirectoryFailureRemainsUnavailableAndNeverConstructsRuntime() {
        val p=InitializationProbe();var runtime=false
        val result=initializeEditor(p,{p.at(InitializationStage.EDIT_MEDIA_CANONICAL){throw IOException("private")}}){runtime=true;Any()}
        assertFalse(runtime);assertNull(result.value);assertEquals(EditorInitializationStatus.STORAGE_UNAVAILABLE,result.status)
        assertEquals(InitializationStage.EDIT_MEDIA_CANONICAL,result.diagnostic?.stage)
    }
    @Test fun shareLockFailureKeepsFirstExactStageThroughFailClosedWrapper() {
        val p=InitializationProbe()
        try{initializeShareOwner<Any>(p){p.at(InitializationStage.SHARE_OWNER_LOCK){throw OverlappingFileLockException()}};fail()}
        catch(failure:ShareFailure){assertEquals(ShareError.INITIALIZATION_FAILED,failure.code);assertEquals(InitializationStage.SHARE_OWNER_LOCK,failure.diagnostic?.stage);assertEquals(InitializationErrorKind.LOCK,failure.diagnostic?.kind)}
    }
    @Test fun knownShareResourceFenceIsNotReplacedOrCleared() {
        val p=InitializationProbe();val original=ShareFailure(ShareError.RELEASE_UNCONFIRMED)
        try{initializeShareOwner<Any>(p){throw original};fail()}catch(failure:ShareFailure){assertSame(original,failure)}
    }
}
