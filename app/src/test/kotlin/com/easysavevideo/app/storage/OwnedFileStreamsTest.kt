package com.easysavevideo.app.storage

import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Actual JVM file/channel callbacks, not a fake which omits FileChannel's parent close. */
class OwnedFileStreamsTest {
    private enum class OwnerKind { ORDINARY_READ, SAVE_STAGING, EDIT_OUTPUT, RECORDED_SEGMENT }
    private class Unconfirmed : RuntimeException()
    private class Owner {
        val lock = Any()
        var count = 1
        var releases = 0
        var poisoned = false
        fun release() { assertTrue(Thread.holdsLock(lock)); count--; releases++ }
        fun fail(): Nothing { poisoned = true; throw Unconfirmed() }
    }
    private class Opened(val stream: Closeable, val descriptor: FileDescriptor, val channel: () -> FileChannel)

    private fun open(file: File, kind: OwnerKind, owner: Owner): Opened = when (kind) {
        OwnerKind.ORDINARY_READ -> {
            val stream = OwnedFileInputStream(file, owner.lock, owner::release, owner::fail)
            Opened(stream, stream.fd) { stream.channel }
        }
        else -> {
            val stream = OwnedFileOutputStream(file, ownershipLock = owner.lock,
                onReleased = owner::release, onUnconfirmed = owner::fail)
            stream.write(byteArrayOf(1, 2, 3))
            Opened(stream, stream.fd) { stream.channel }
        }
    }
    private fun eachOwner(action: (File, OwnerKind, Owner, Opened) -> Unit) {
        OwnerKind.entries.forEach { kind ->
            val file = Files.createTempFile("esv-owned-stream-", ".bin").toFile()
            file.writeBytes(byteArrayOf(1, 2, 3))
            val owner = Owner(); val opened = open(file, kind, owner)
            try { action(file, kind, owner, opened) }
            finally { opened.stream.close(); Files.deleteIfExists(file.toPath()) }
        }
    }
    private fun assertReleased(owner: Owner, opened: Opened) {
        assertFalse("An ownership callback must not stand in for actual FD closure", opened.descriptor.valid())
        assertEquals(0, owner.count)
        assertEquals(1, owner.releases)
        assertFalse(owner.poisoned)
    }

    @Test fun allFourOwnersReleaseAfterStreamFirstCloseWithAnExistingChannel() = eachOwner { _, _, owner, opened ->
        assertEquals(3L, opened.channel().size())
        assertTrue(opened.descriptor.valid())
        assertEquals(1, owner.count)
        opened.stream.close()
        assertReleased(owner, opened)
        assertFalse(opened.channel().isOpen)
        repeat(3) { opened.stream.close(); opened.channel().close() }
        assertReleased(owner, opened)
    }

    @Test fun allFourOwnersReleaseAfterChannelFirstClose() = eachOwner { _, _, owner, opened ->
        val channel = opened.channel()
        channel.close()
        assertReleased(owner, opened)
        opened.stream.close(); channel.close()
        assertReleased(owner, opened)
    }

    @Test fun allFourOwnersCloseWithoutPriorChannelAccess() = eachOwner { _, _, owner, opened ->
        opened.stream.close()
        assertReleased(owner, opened)
    }

    @Test fun allFourOwnersTolerateConcurrentStreamAndChannelCloseWithoutLockInversion() {
        repeat(12) {
            eachOwner { _, kind, owner, opened ->
                val channel = opened.channel()
                val ready = CountDownLatch(2); val start = CountDownLatch(1)
                val done = CountDownLatch(2); val failure = AtomicReference<Throwable?>()
                val actions = listOf<() -> Unit>({ opened.stream.close() }, { channel.close() })
                actions.forEachIndexed { index, action ->
                    Thread({
                        ready.countDown()
                        try { assertTrue(start.await(3, TimeUnit.SECONDS)); action() }
                        catch (error: Throwable) { failure.compareAndSet(null, error) }
                        finally { done.countDown() }
                    }, "owned-stream-$kind-$index").apply { isDaemon = true; start() }
                }
                assertTrue(ready.await(3, TimeUnit.SECONDS)); start.countDown()
                assertTrue("Concurrent stream/channel close must not deadlock: $kind", done.await(3, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("Concurrent close failed: $kind", it) }
                assertReleased(owner, opened)
            }
        }
    }

    @Test fun allFourOwnersTolerateConcurrentRepeatedStreamClose() = eachOwner { _, _, owner, opened ->
        opened.channel().size()
        val start = CountDownLatch(1); val done = CountDownLatch(6)
        val failure = AtomicReference<Throwable?>()
        repeat(6) {
            Thread {
                try { assertTrue(start.await(3, TimeUnit.SECONDS)); opened.stream.close() }
                catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { done.countDown() }
            }.apply { isDaemon = true; start() }
        }
        start.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("Concurrent repeated close failed", it) }
        assertReleased(owner, opened)
    }

    @Test fun outputAppendSemanticsAndMediaBytesArePreserved() {
        val file = Files.createTempFile("esv-owned-append-", ".bin").toFile()
        try {
            file.writeBytes(byteArrayOf(1, 2))
            var releases = 0
            OwnedFileOutputStream(file, append = true, ownershipLock = Any(), onReleased = { releases++ },
                onUnconfirmed = { throw Unconfirmed() }).use { it.write(3); assertEquals(3L, it.channel.size()) }
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
            assertEquals(1, releases)
        } finally { Files.deleteIfExists(file.toPath()) }
    }

    @Test fun genuineChannelFailureRetainsOwnershipAndOriginalException() {
        val owner = Owner(); val release = OwnedFileStreamRelease(owner.lock, owner::release, owner::fail)
        val original = IOException("synthetic-close-failure"); var streamClosed = false
        try {
            release.close({ throw original }, { streamClosed = true }, { false })
            fail("Expected original close failure")
        } catch (actual: IOException) { assertSame(original, actual) }
        assertFalse(streamClosed); assertEquals(1, owner.count); assertEquals(0, owner.releases)
    }

    @Test fun genuineStreamFailureRetainsOwnershipAndOriginalException() {
        val owner = Owner(); val release = OwnedFileStreamRelease(owner.lock, owner::release, owner::fail)
        val original = IOException("synthetic-close-failure")
        try {
            release.close({}, { throw original }, { false })
            fail("Expected original close failure")
        } catch (actual: IOException) { assertSame(original, actual) }
        assertEquals(1, owner.count); assertEquals(0, owner.releases)
    }

    @Test fun returnedPlatformCloseWithStillValidDescriptorNeverReleasesOwner() {
        val owner = Owner(); val release = OwnedFileStreamRelease(owner.lock, owner::release, owner::fail)
        try { release.close({}, {}, { false }); fail("Expected unconfirmed release") }
        catch (_: Unconfirmed) { /* A returned close alone is not descriptor-close evidence. */ }
        assertEquals(1, owner.count); assertEquals(0, owner.releases); assertTrue(owner.poisoned)
    }

    @Test fun unreadableDescriptorStatusNeverReleasesOwner() {
        val owner = Owner(); val release = OwnedFileStreamRelease(owner.lock, owner::release, owner::fail)
        try { release.close({}, {}, { throw IOException("synthetic-status-failure") }); fail("Expected unconfirmed release") }
        catch (_: Unconfirmed) { }
        assertEquals(1, owner.count); assertEquals(0, owner.releases)
    }

    @Test fun exceptionAfterActualDescriptorCloseReleasesOnceButDoesNotHideTheException() {
        val owner = Owner(); val release = OwnedFileStreamRelease(owner.lock, owner::release, owner::fail)
        val original = IOException("synthetic-post-close-failure")
        try { release.close({}, { throw original }, { true }); fail("Expected original failure") }
        catch (actual: IOException) { assertSame(original, actual) }
        release.close({}, {}, { true })
        assertEquals(0, owner.count); assertEquals(1, owner.releases)
    }

    @Test fun blockedCloseRetainsOwnershipUntilActualCompletion() {
        val count = AtomicInteger(1); val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val done = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
        val release = OwnedFileStreamRelease(Any(), { count.decrementAndGet(); Unit }, { throw Unconfirmed() })
        Thread {
            try { release.close({ entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)) }, {}, { true }) }
            catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        assertTrue(entered.await(3, TimeUnit.SECONDS)); assertEquals(1, count.get())
        finish.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }; assertEquals(0, count.get())
    }
}
