package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.*
import com.easysavevideo.library.export.ExportRelease
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.*
import java.nio.file.StandardOpenOption

/** Temporary public bytes only. Exercises JVM file ownership, not Android fsync/SQLite durability. */
class VaultMediaFilesTest {
    private val operation = VaultMediaOperation("operation-one", 1, VaultMediaKind.MOVE_IN)
    private val identity = VaultMediaObjectIdentity("object-one", 1)
    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("esv-fixed-media-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }
    @Test fun noDurableReservationCannotCreateAnyFile() = withDirectory { directory ->
        val files = VaultMediaFiles({ directory }, {})
        rejected { files.create(operation, identity) }
        assertTrue(directory.listFiles()!!.isEmpty()); assertEquals(ExportRelease.RELEASED, files.observeMediaDrain())
    }
    @Test fun immutableReadAnchorSurvivesPromotionAndReadOnlyCapCannotAppend() = withDirectory { directory ->
        val files = VaultMediaFiles({ directory }, {})
        files.preflightReservation(operation, identity); files.confirmReservation(operation, identity)
        val owned = files.create(operation, identity)
        owned.append(byteArrayOf(1, 2, 3), 0, 3)
        assertEquals(ExportRelease.UNCONFIRMED, files.observeMediaDrain())
        assertEquals(ExportRelease.RELEASED, owned.forceAndCloseWrite()); files.promote(owned)
        val bytes = ByteArray(3); assertEquals(3, owned.readAt(0, bytes, 0, 3)); assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals(ExportRelease.RELEASED, owned.closeAndObserve())
        val readOnly = files.open(identity); rejected { readOnly.append(byteArrayOf(4), 0, 1) }
        assertEquals(ExportRelease.RELEASED, readOnly.closeAndObserve()); assertEquals(ExportRelease.RELEASED, files.observeMediaDrain())
    }
    @Test fun collisionCannotBeAdoptedOrDeletedByAbandon() = withDirectory { directory ->
        val files = VaultMediaFiles({ directory }, {})
        files.preflightReservation(operation, identity); files.confirmReservation(operation, identity)
        val collision = File(directory, "media_object-one_1.part"); collision.writeBytes(byteArrayOf(42))
        rejected { files.create(operation, identity) }; rejected { files.cleanup(operation, identity) }
        assertArrayEquals(byteArrayOf(42), collision.readBytes())
    }
    @Test fun postOpenDirectorySyncFailureClosesUnreturnedOwnerBeforeReconciliation() = withDirectory { directory ->
        var failSync = false
        val files = VaultMediaFiles({ directory }, { if (failSync) error("fixed sync failure") })
        files.preflightReservation(operation, identity); files.confirmReservation(operation, identity); failSync = true
        rejected { files.create(operation, identity) }
        assertEquals(ExportRelease.RELEASED, files.observeMediaDrain())
        failSync = false; assertTrue(files.cleanup(operation, identity)); assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun unknownOpenWithoutReturnedOwnerStillPinsLifetimeOwner() = withDirectory { directory ->
        var opens = 0
        val files = VaultMediaFiles({ directory }, {}, { _, _ -> opens++; throw IOException("fixed unknown open") })
        files.preflightReservation(operation, identity); files.confirmReservation(operation, identity)
        try { files.create(operation, identity); fail("Expected open failure") } catch (_: IOException) { }
        assertEquals(1, opens); assertEquals(ExportRelease.UNCONFIRMED, files.observeMediaDrain())
        repeat(3) { assertEquals(ExportRelease.UNCONFIRMED, files.closeAll()) }
        assertEquals(1, opens)
    }
    @Test fun firstUnknownCloseDoesNotSkipOtherOwnersOrRetryEitherClose() = withDirectory { directory ->
        val channels = mutableListOf<ClosingChannel>()
        val files = VaultMediaFiles({ directory }, {}, { file, writable ->
            assertTrue(writable)
            ClosingChannel(FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                failClose = true).also(channels::add)
        })
        listOf(identity, identity.copy(objectId = "object-two")).forEach {
            files.preflightReservation(operation, it); files.confirmReservation(operation, it); files.create(operation, it)
        }
        repeat(3) { assertEquals(ExportRelease.UNCONFIRMED, files.closeAll()) }
        assertEquals(listOf(1, 1), channels.map { it.closeCalls })
        assertEquals(ExportRelease.UNCONFIRMED, files.observeMediaDrain())
        assertTrue(channels.none { it.isOpen }) // Facade closed is deliberately not an actual-close ACK.
    }
    @Test fun unknownReadAnchorOpenCannotBeErasedByClosingKnownWriter() = withDirectory { directory ->
        var opens = 0
        var writer: FileChannel? = null
        val files = VaultMediaFiles({ directory }, {}, { file, writable ->
            opens++
            if (!writable) throw IOException("fixed unknown read-anchor open")
            FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).also { writer = it }
        })
        files.preflightReservation(operation, identity); files.confirmReservation(operation, identity)
        val owner = files.create(operation, identity); owner.append(byteArrayOf(1), 0, 1)
        assertEquals(ExportRelease.UNCONFIRMED, owner.forceAndCloseWrite())
        repeat(3) { assertEquals(ExportRelease.UNCONFIRMED, owner.closeAndObserve()) }
        assertFalse(writer!!.isOpen); assertEquals(2, opens)
        assertEquals(ExportRelease.UNCONFIRMED, files.closeAll())
        assertEquals(ExportRelease.UNCONFIRMED, files.observeMediaDrain())
    }
    private class ClosingChannel(private val delegate: FileChannel, private val failClose: Boolean) : FileChannel() {
        var closeCalls = 0
        override fun implCloseChannel() { closeCalls++; delegate.close(); if (failClose) throw IOException("fixed close failure") }
        override fun read(dst: ByteBuffer): Int = delegate.read(dst)
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = delegate.read(dsts, offset, length)
        override fun read(dst: ByteBuffer, position: Long): Int = delegate.read(dst, position)
        override fun write(src: ByteBuffer): Int = delegate.write(src)
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = delegate.write(srcs, offset, length)
        override fun write(src: ByteBuffer, position: Long): Int = delegate.write(src, position)
        override fun position(): Long = delegate.position()
        override fun position(newPosition: Long): FileChannel { delegate.position(newPosition); return this }
        override fun size(): Long = delegate.size()
        override fun truncate(size: Long): FileChannel { delegate.truncate(size); return this }
        override fun force(metaData: Boolean) = delegate.force(metaData)
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = delegate.transferTo(position, count, target)
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = delegate.transferFrom(src, position, count)
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = delegate.map(mode, position, size)
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = delegate.lock(position, size, shared)
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = delegate.tryLock(position, size, shared)
    }

}
