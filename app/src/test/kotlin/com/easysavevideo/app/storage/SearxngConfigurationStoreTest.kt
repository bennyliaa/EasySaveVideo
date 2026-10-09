package com.easysavevideo.app.storage

import com.easysavevideo.app.discovery.SearxngConfigurationEdit
import com.easysavevideo.app.discovery.SearxngConfigurationPolicy
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearxngConfigurationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun config() = (SearxngConfigurationPolicy.replace(SearxngConfigurationPolicy.empty(), "searxng-1",
        "https://search.example.org/search", true, true) as SearxngConfigurationEdit.Accepted).config
    @Test fun missingReadDoesNotCreateAnyFileAndSuccessfulWriteRestores() {
        val file = File(temporary.root, "new/config.bin"); val store = FileSearxngConfigurationStore(file)
        assertEquals(SearxngConfigurationRead.Missing, store.load()); assertFalse(file.parentFile.exists())
        assertTrue(store.replace(SearxngConfigurationPolicy.empty().generation, config()) is SearxngConfigurationWrite.Committed)
        assertEquals(SearxngConfigurationRead.Present(config()), FileSearxngConfigurationStore(file).load())
        assertEquals(listOf("config.bin"), file.parentFile.list()!!.toList())
    }
    @Test fun malformedAndOversizedDataArePreservedAndCannotBeOverwritten() {
        val file = temporary.newFile("config.bin"); val store = FileSearxngConfigurationStore(file)
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), ByteArray(SearxngConfigurationCodec.MAX_BYTES + 1))) {
            file.writeBytes(bytes)
            assertEquals(SearxngConfigurationRead.Malformed, store.load())
            assertTrue(store.replace(SearxngConfigurationPolicy.empty().generation, config()) is SearxngConfigurationWrite.Conflict)
            assertArrayEquals(bytes, file.readBytes())
        }
    }
    @Test fun staleGenerationAndInvalidRevisionCannotOverwriteNewConfig() {
        val file = temporary.newFile("config.bin"); file.delete()
        val store = FileSearxngConfigurationStore(file); val other = FileSearxngConfigurationStore(file)
        val empty = SearxngConfigurationPolicy.empty()
        assertTrue(store.replace(empty.generation, config()) is SearxngConfigurationWrite.Committed)
        val bytes = file.readBytes()
        assertTrue(other.replace(empty.generation, config()) is SearxngConfigurationWrite.Conflict)
        assertTrue(other.replace(config().generation, config()) is SearxngConfigurationWrite.Conflict)
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun directoryOrSymlinkTargetIsNeverReadAsConfigurationOrReplaced() {
        val directory = temporary.newFolder("directory")
        assertEquals(SearxngConfigurationRead.Malformed, FileSearxngConfigurationStore(directory).load())
        val target = temporary.newFile("target"); target.writeBytes(SearxngConfigurationCodec.encode(config()))
        val link = File(temporary.root, "link"); Files.createSymbolicLink(link.toPath(), target.toPath())
        val store = FileSearxngConfigurationStore(link)
        assertEquals(SearxngConfigurationRead.Malformed, store.load())
        assertTrue(store.replace(SearxngConfigurationPolicy.empty().generation, config()) is SearxngConfigurationWrite.Conflict)
        assertEquals(SearxngConfigurationRead.Present(config()), FileSearxngConfigurationStore(target).load())
    }
    @Test fun readFailureIsDistinctFromMissingAndNoFileIsErased() {
        val blocker = temporary.newFile("not-a-directory"); blocker.writeText("preserve")
        val store = FileSearxngConfigurationStore(File(blocker, "config.bin"))
        assertEquals(SearxngConfigurationRead.Unavailable, store.load())
        assertTrue(store.replace(SearxngConfigurationPolicy.empty().generation, config()) is SearxngConfigurationWrite.Conflict)
        assertEquals("preserve", blocker.readText())
    }
}
