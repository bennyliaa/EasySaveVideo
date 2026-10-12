package com.easysavevideo.app.editor.storage
import com.easysavevideo.app.diagnostics.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class EditInitializationStagesTest {
    @Test fun occupiedDirectoryDoesNotBecomeReadyOrAlterTheBlockingFile() {
        val root=Files.createTempDirectory("esv-init-owned-").toFile()
        try {
            val blocker=File(root,"edit-staging").apply{writeText("retain")};val p=InitializationProbe()
            try{EditArtifactFiles(root,{}, {_,_->},p);fail()}catch(_:IOException){}
            assertEquals(InitializationStage.EDIT_STAGING_CREATE,p.failure?.stage);assertEquals("retain",blocker.readText())
            assertFalse(File(root,"media").exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun fsyncFailureIsNotSwallowedOrReplacedBySuccess() {
        val root=Files.createTempDirectory("esv-init-owned-").toFile()
        try {
            val original=IOException("private");val p=InitializationProbe()
            try{EditArtifactFiles(root,{throw original},{_,_->},p);fail()}catch(failure:IOException){assertSame(original,failure)}
            assertEquals(InitializationStage.EDIT_ROOT_FSYNC,p.failure?.stage)
        }finally{root.deleteRecursively()}
    }
}
