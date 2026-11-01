package com.easysavevideo.app.storage

import com.easysavevideo.app.discovery.SearxngConfigurationEdit
import com.easysavevideo.app.discovery.SearxngConfigurationPolicy
import com.easysavevideo.searxng.SearxngResultFormat
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearxngConfigurationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun config() = (SearxngConfigurationPolicy.add(SearxngConfigurationPolicy.empty(),
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
    @Test fun legacyReadPreservesFileAndOnlyAnExplicitCommittedEditWritesVersionThree() {
        val file = temporary.newFile("legacy.bin")
        val originalBytes = legacySearxngConfiguration(); file.writeBytes(originalBytes)
        val store = FileSearxngConfigurationStore(file)
        val old = (store.load() as SearxngConfigurationRead.Present).config
        assertArrayEquals(originalBytes, file.readBytes())
        val changed = (SearxngConfigurationPolicy.replace(old, "searxng-1", old.slots.first().searchUrl!!,
            true, true, SearxngResultFormat.HTML_WEB) as SearxngConfigurationEdit.Accepted).config
        assertTrue(store.replace(old.generation, changed) is SearxngConfigurationWrite.Committed)
        assertEquals(3, java.nio.ByteBuffer.wrap(file.readBytes()).getInt(4))
        assertEquals(SearxngConfigurationRead.Present(changed), store.load())
    }
    @Test fun duplicateLegacyOriginPreservesBytesAndCannotBeOverwritten() {
        val file = temporary.newFile("legacy-duplicate.bin")
        val originalBytes = legacySearxngConfiguration(duplicateOrigin = true); file.writeBytes(originalBytes)
        val store = FileSearxngConfigurationStore(file)
        assertEquals(SearxngConfigurationRead.Malformed, store.load())
        assertTrue(store.replace(SearxngConfigurationPolicy.empty().generation, config()) is SearxngConfigurationWrite.Conflict)
        assertArrayEquals(originalBytes, file.readBytes())
    }
    @Test fun versionTwoAndEmptyLegacyReadsPreserveExactBytesUntilExplicitMutation() {
        for (empty in listOf(false, true)) {
            val file = temporary.newFile("v2-$empty.bin")
            val original = legacySearxngConfiguration(version = 2, empty = empty); file.writeBytes(original)
            val store = FileSearxngConfigurationStore(file)
            repeat(2) {
                val loaded = (store.load() as SearxngConfigurationRead.Present).config
                assertEquals(if (empty) 0 else 2, loaded.slots.size)
                assertEquals(4L, loaded.nextInstanceOrdinal)
                assertArrayEquals(original, file.readBytes())
            }
        }
    }
    @Test fun deleteAndResetPreserveAllocatorAcrossDiskReloadAndReadd() {
        val file = File(temporary.root, "persisted.bin")
        val store = FileSearxngConfigurationStore(file)
        val first = config()
        assertTrue(store.replace(SearxngConfigurationPolicy.empty().generation, first) is SearxngConfigurationWrite.Committed)
        val removed = (SearxngConfigurationPolicy.remove(first, "searxng-1") as SearxngConfigurationEdit.Accepted).config
        assertTrue(store.replace(first.generation, removed) is SearxngConfigurationWrite.Committed)
        val reloaded = (FileSearxngConfigurationStore(file).load() as SearxngConfigurationRead.Present).config
        assertTrue(reloaded.slots.isEmpty()); assertEquals(2L, reloaded.nextInstanceOrdinal)
        val reset = (SearxngConfigurationPolicy.reset(reloaded) as SearxngConfigurationEdit.Accepted).config
        assertTrue(store.replace(reloaded.generation, reset) is SearxngConfigurationWrite.Committed)
        val afterReset = (FileSearxngConfigurationStore(file).load() as SearxngConfigurationRead.Present).config
        val added = (SearxngConfigurationPolicy.add(afterReset, "https://search.example.org/search", acknowledged = true)
            as SearxngConfigurationEdit.Accepted).config
        assertEquals("searxng-2", added.slots.single().id)
        assertTrue(store.replace(afterReset.generation, added) is SearxngConfigurationWrite.Committed)
        assertEquals(added, (store.load() as SearxngConfigurationRead.Present).config)
    }
    @Test fun callerCannotRollBackAllocatorOrResurrectDeletedIdentityEvenWithCurrentGeneration() {
        val file = File(temporary.root, "allocator.bin")
        val store = FileSearxngConfigurationStore(file)
        val first = config()
        assertTrue(store.replace(SearxngConfigurationPolicy.empty().generation, first) is SearxngConfigurationWrite.Committed)
        val removed = (SearxngConfigurationPolicy.remove(first, "searxng-1") as SearxngConfigurationEdit.Accepted).config
        assertTrue(store.replace(first.generation, removed) is SearxngConfigurationWrite.Committed)
        val bytes = file.readBytes()
        val rollback = com.easysavevideo.app.discovery.SearxngSourceConfiguration(removed.revision + 1, emptyList(), 1)
        val resurrection = com.easysavevideo.app.discovery.SearxngSourceConfiguration(removed.revision + 1, first.slots, 2)
        for (bad in listOf(rollback, resurrection)) {
            assertTrue(store.replace(removed.generation, bad) is SearxngConfigurationWrite.Conflict)
            assertArrayEquals(bytes, file.readBytes())
        }
    }
    @Test fun malformedVersionThreeAllocatorIsPreservedAndCannotBeReset() {
        val file = temporary.newFile("malformed-v3.bin")
        val bytes = SearxngConfigurationCodec.encode(config()).also { java.nio.ByteBuffer.wrap(it).putLong(16, 1L) }
        file.writeBytes(bytes); val store = FileSearxngConfigurationStore(file)
        assertEquals(SearxngConfigurationRead.Malformed, store.load())
        assertTrue(store.replace(SearxngConfigurationPolicy.empty().generation, config()) is SearxngConfigurationWrite.Conflict)
        assertArrayEquals(bytes, file.readBytes())
    }

}
