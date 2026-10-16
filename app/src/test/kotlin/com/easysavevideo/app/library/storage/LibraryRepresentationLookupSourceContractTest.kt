package com.easysavevideo.app.library.storage

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

/** Source wiring assertions only: not SQLite execution, migration, device or Android acceptance. */
class LibraryRepresentationLookupSourceContractTest {
    private fun file(path: String) = String(Files.readAllBytes(Paths.get(path)), Charsets.UTF_8)
    private fun source(path: String) = file("src/main/kotlin/com/easysavevideo/app/$path")
    private val repository get() = source("library/storage/AndroidLibraryRepository.kt")
    private val runtime get() = source("save/AndroidSaveRuntime.kt")
    private fun section(text: String, start: String, end: String): String {
        assertTrue("Missing $start", text.contains(start))
        val remaining = text.substringAfter(start)
        assertTrue("Missing $end", remaining.contains(end))
        return remaining.substringBefore(end)
    }
    private fun ordered(text: String, vararg parts: String) {
        var offset = 0
        for (part in parts) {
            val found = text.indexOf(part, offset)
            assertTrue("Missing or out of order: $part", found >= 0)
            offset = found + part.length
        }
    }

    @Test fun exactLookupReturnsNullOnlyWithoutAnItemAndValidatesBeforeDecoding() {
        val lookup = section(repository, "private fun readItem(id: String)", "private fun readItem(c: Cursor)")
        ordered(lookup, "\"\$ITEM_LOOKUP_SELECT WHERE i.item_id=?\"", "arrayOf(id)",
            "if (!c.moveToFirst()) return@use null", "requireLibraryRepresentationBinding(", "readItem(c)")
        for (argument in listOf("itemId = c.getString(0)", "representationId = c.getString(12)",
            "generation = c.getLong(2)", "boundRepresentationId = if (c.isNull(13)) null else c.getString(13)",
            "boundItemId = if (c.isNull(14)) null else c.getString(14)",
            "boundGeneration = if (c.isNull(15)) null else c.getLong(15)")) assertTrue(lookup.contains(argument))
        assertFalse(lookup.contains("catch"))
        assertTrue(repository.contains("override fun item(itemId: String): MediaItem? = guard { LibraryIds.requireValid(itemId); readItem(itemId) }"))
    }

    @Test fun exactLeftJoinRetainsBadBindingsForValidationWithNoLifecycleFilter() {
        val columns = section(repository, "private const val ITEM_COLUMNS =", "private const val ITEM_SELECT =")
        assertTrue(columns.contains("SELECT i.item_id,i.revision,i.generation,i.folder_id,i.display_name,i.created_at,"))
        assertTrue(columns.contains("i.date_kind,r.size_bytes,i.duration_millis,i.width,i.height,i.lifecycle"))
        val sql = section(repository, "private const val ITEM_LOOKUP_SELECT =", "private const val FOLDER_SELECT =")
        assertTrue(sql.contains("\$ITEM_COLUMNS,i.representation_id,r.representation_id,r.item_id,r.generation"))
        assertTrue(sql.contains("FROM library_item i LEFT JOIN media_representation r ON r.representation_id=i.representation_id"))
        assertFalse(sql.contains("WHERE"))
        assertFalse(sql.contains("AND r.item_id"))
        assertFalse(sql.contains("AND r.generation"))
    }

    @Test fun pagedQueryKeepsExistingInnerJoinAndReadyOrdinaryFilters() {
        val sql = section(repository, "private const val ITEM_SELECT =", "private const val ITEM_LOOKUP_SELECT =")
        assertTrue(sql.contains("\$ITEM_COLUMNS FROM library_item i"))
        assertTrue(sql.contains("JOIN media_representation r ON r.representation_id=i.representation_id AND r.item_id=i.item_id AND r.generation=i.generation"))
        assertFalse(sql.contains("LEFT JOIN"))
        val query = section(repository, "override fun query(", "override fun item(")
        assertTrue(query.contains("\$ITEM_SELECT WHERE \$selection AND i.lifecycle='READY' AND r.state='READY'"))
        assertTrue(query.contains("AND i.privacy_class='ORDINARY' AND r.kind='ORDINARY_FILE_V1'"))
        assertFalse(query.contains("ITEM_LOOKUP_SELECT"))
    }

    @Test fun deletedTombstoneNullableMetadataDecodingAndClearingStayIntact() {
        val decode = section(repository, "private fun readItem(c: Cursor)", "private fun readFolder(id: String)")
        assertTrue(decode.contains("if (c.isNull(4)) \"Deleted item\" else c.getString(4)"))
        assertTrue(decode.contains("if (c.isNull(5)) 0 else c.getLong(5)"))
        assertTrue(decode.contains("if (c.isNull(6)) MediaDateKind.IMPORTED"))
        assertTrue(decode.contains("if (c.isNull(7)) 0 else c.getLong(7)"))
        assertTrue(decode.contains("MediaLifecycle.valueOf(c.getString(11))"))
        val deletion = section(repository, "private fun finishDeleteOwned(", "private fun recoverAfterRelease(")
        assertTrue(deletion.contains("put(\"state\", \"DELETED\"); putNull(\"opaque_locator\"); putNull(\"size_bytes\"); putNull(\"sha256\")"))
        assertTrue(deletion.contains("putNull(\"date_kind\"); putNull(\"duration_millis\"); putNull(\"width\"); putNull(\"height\")"))
    }

    @Test fun hardReadsValidateBeforeLeaseAndKeepMissingAndStaleRefErrors() {
        val ready = section(repository, "private fun requireReady(", "private fun readItem(id: String)")
        ordered(ready, "readItem(ref.itemId) ?: throw LibraryFailure(LibraryError.NOT_FOUND)",
            "item.lifecycle != MediaLifecycle.READY", "item.revision != ref.revision || item.representationGeneration != ref.representationGeneration",
            "throw LibraryFailure(LibraryError.STALE_REVISION)")
        assertFalse(ready.contains("catch"))
        val read = section(repository, "fun acquireRead(", "fun resolveReceipt(")
        ordered(read, "= guard {", "requireReady(ref)", "leases.tryAcquireRead(ref.itemId)",
            "val representation = representation(ref.itemId)", "files.readable(", "activeReaders++")
        assertTrue(read.contains("catch (failure: Exception) { lease.close(); throw failure }"))
        assertTrue(repository.contains("private inline fun <T> guard(block: () -> T): T = synchronized(lock)"))
        assertTrue(repository.contains("catch (failure: LibraryFailure) { throw failure }"))
    }

    @Test fun receiptResolutionStillRejectsCorruptionWhilePublishedQueryKeepsNullableContract() {
        val receipt = section(repository, "fun resolveReceipt(", "fun hasActiveReaders()")
        ordered(receipt, "readItem(receipt.mediaId) ?: throw LibraryFailure(LibraryError.NOT_FOUND)",
            "val representation = representation(item.itemId)", "representation.commitId != receipt.commitId",
            "SELECT artifact_id FROM save_media", "item.readRef()")
        assertFalse(receipt.contains("catch"))
        val representation = section(repository, "private fun representation(itemId: String)", "private data class Operation")
        assertTrue(representation.contains("AND r.item_id=i.item_id AND r.generation=i.generation"))
        assertTrue(representation.contains("i.privacy_class='ORDINARY' AND r.kind='ORDINARY_FILE_V1'"))
        val published = section(source("save/storage/AndroidSaveStorage.kt"), "override fun published(commitId: String)", "override fun discard(")
        assertTrue(published.contains("try { libraryStorage.resolveReceipt(receipt) } catch (_: LibraryFailure) { return null }"))
    }

    @Test fun startupAndRefreshBothUseTheDisplayOnlyPerItemReader() {
        val display = section(runtime, "private fun readMediaDispositions(", "private fun canResumeFinalized(")
        assertTrue(display.contains("readSaveMediaDispositions(items.mapNotNull { it.receipt?.mediaId }, storage.library::item)"))
        assertFalse(display.contains("MediaLifecycle.READY"))
        assertFalse(display.contains("MediaLifecycle.DELETED"))
        assertTrue(runtime.contains("val dispositions = readMediaDispositions(initial)"))
        assertTrue(runtime.contains("val dispositions = readMediaDispositions(next)"))
        assertTrue(runtime.contains("mediaDispositions = dispositions"))
    }

    @Test fun unknownDispositionKeepsExistingCopyAndDisablesPreview() {
        val ui = source("save/SaveTaskScreen.kt")
        assertTrue(ui.contains("record.receipt?.let { runtime.mediaDispositions[it.mediaId] }"))
        assertTrue(ui.contains("null -> stringResource(R.string.flow_disposition_unknown)"))
        assertTrue(ui.contains("enabled = disposition == MediaLifecycle.READY && runtime.currentRecordingId == null && !runtime.draining"))
    }

    @Test fun everyDirectSourceReadIsRegisteredAsAGradleTestInput() {
        val inputs = section(file("build.gradle.kts"), "val contractSourceInputs = files(", "tasks.withType<Test>()")
        assertTrue(inputs.contains("\"build.gradle.kts\""))
        for (path in listOf("library/storage/AndroidLibraryRepository.kt", "save/AndroidSaveRuntime.kt",
            "save/SaveTaskScreen.kt", "save/storage/AndroidSaveStorage.kt")) {
            assertTrue("Missing input $path", inputs.contains("\"src/main/kotlin/com/easysavevideo/app/$path\""))
        }
    }
}
