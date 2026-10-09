package com.easysavevideo.app.browser.bookmarks

import android.content.Context
import android.util.AtomicFile
import com.easysavevideo.core.model.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Independently authored vectors. Android storage tests use explicit limited host doubles. */
class IndependentBookmarkTest {
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private fun item(n: Int = 1, address: String = "https://example.test/$n", title: String = "Title $n") = BrowserBookmark(id(n), address, title)
    private fun token(address: String = "https://example.test/1", title: String = "Title 1") = BookmarkPageToken(BrowserContextRef("t", "s"), 1, 2, 3, address, title)
    private fun rejected(action: () -> Any?) { try { action(); fail("Expected rejection") } catch (expected: Exception) { } }
    private fun signed(payload: ByteArray) = payload + MessageDigest.getInstance("SHA-256").digest(payload)
    private fun modifiedHeader(bytes: ByteArray, offset: Int, value: Int): ByteArray {
        val body = bytes.copyOf(bytes.size - 32); ByteBuffer.wrap(body).putInt(offset, value); return signed(body)
    }
    private class Queue { val pending = ArrayDeque<() -> Unit>(); fun put(work: () -> Unit) { pending.addLast(work) }; fun run() { pending.removeFirst()() } }
    private class Store : BookmarkStorage {
        var disk: List<BrowserBookmark> = emptyList(); var saves = 0; var reads = 0
        var failRead = false; var failWrite = false; var commitThenFail = false
        override fun load(): List<BrowserBookmark> { reads++; if (failRead) throw IOException("private details"); return disk }
        override fun save(items: List<BrowserBookmark>) { saves++; if (failWrite) throw IOException("private details"); disk=items.toList(); if(commitThenFail) throw IOException("uncertain") }
    }
    private class Harness(val store: Store = Store()) {
        val work = Queue(); val main = Queue(); val states = mutableListOf<BookmarkState>(); var seq = 10
        val controller = BookmarkController(store, work::put, main::put,
            { "00000000-0000-0000-0000-" + (++seq).toString().padStart(12,'0') }, states::add)
        fun finish() { work.run(); main.run() }
        fun ready() { controller.load(); finish() }
    }

    @Test fun canonicalEquivalentAuthoritiesAndEmptyPaths() {
        assertEquals("https://example.test/", BookmarkPolicy.canonicalAddress("HTTPS://EXAMPLE.test:443"))
        assertEquals("http://example.test/", BookmarkPolicy.canonicalAddress("HTTP://EXAMPLE.test:80"))
        assertEquals("https://example.test:8443/", BookmarkPolicy.canonicalAddress("https://EXAMPLE.test:8443"))
        assertEquals("https://[2001:db8::a]/", BookmarkPolicy.canonicalAddress("https://[2001:DB8::A]:443"))
    }
    @Test fun canonicalDoesNotAlterSignedOrMeaningfulComponents() {
        val values = listOf("https://example.test/A/%2f/../b?b=2&a=1&a=3#one", "https://example.test/?", "https://example.test/#", "https://example.test/?q=%2F%2f")
        values.forEach { assertEquals(it, BookmarkPolicy.canonicalAddress(it)) }
        assertNotEquals(BookmarkPolicy.canonicalAddress("https://example.test/a#one"), BookmarkPolicy.canonicalAddress("https://example.test/a#two"))
    }
    @Test fun canonicalUsesRootLocale() {
        val old = Locale.getDefault(); try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals("https://i.test/", BookmarkPolicy.canonicalAddress("HTTPS://I.TEST")) } finally { Locale.setDefault(old) }
    }
    @Test fun canonicalRejectsUnsafeSchemesAuthorityAndHiddenCharacters() {
        listOf("javascript:alert(1)","file:///x", "content://x", "intent://x", "data:text/plain,a", "https://user:pass@example.test/", "https://user%40example.test/", "https://example.test\\@bad.test/", "https://example.test:0/", "https://example.test:65536/", "https://example.test/%zz", "https://example.test/a\n", " https://example.test/", "https://example.test/\u202Etxt", "https://example.test/\u200D", "https://example.test/\uD800", "https://[fe80::1%25eth0]/").forEach { assertNull(it, BookmarkPolicy.canonicalAddress(it)) }
    }
    @Test fun canonicalBoundsEncodedBytesAndNotOnlyCharacters() {
        val prefix = "https://x.test/"; val max = prefix + "a".repeat(BookmarkPolicy.MAX_ADDRESS_BYTES-prefix.length)
        assertEquals(max, BookmarkPolicy.canonicalAddress(max)); assertNull(BookmarkPolicy.canonicalAddress(max + "a"))
        assertNull(BookmarkPolicy.canonicalAddress(prefix + "界".repeat(3000)))
    }
    @Test fun titlesArePlainBoundedCodepointsAndFixedPoints() {
        val title = BookmarkPolicy.safeTitle("  Hello\n\t\u202EWorld\u0000\u200B  😀  ", "https://example.test/")
        assertEquals("Hello World 😀", title)
        val many = BookmarkPolicy.safeTitle("😀".repeat(200), "https://example.test/")
        assertEquals(160, many.codePointCount(0,many.length)); assertFalse(many.endsWith("\uD83D"))
        assertEquals(many, BookmarkPolicy.safeTitle(many,"https://example.test/"))
        assertEquals("example.test", BookmarkPolicy.safeTitle("\u0000\u202E", "https://example.test/"))
        assertFalse(BookmarkPolicy.safeTitle("a\uD800b", "https://example.test/").contains('\uD800'))
    }
    @Test fun longHostFallbackIsBoundedAndAcceptsOwnCapture() {
        val host = listOf("a".repeat(50),"b".repeat(50),"c".repeat(50),"d".repeat(50),"test").joinToString(".")
        val address = "https://$host/"; assertNotNull(BookmarkPolicy.canonicalAddress(address))
        val safe = BookmarkPolicy.safeTitle(null,address)
        assertTrue(safe.codePointCount(0,safe.length) <= BookmarkPolicy.MAX_TITLE_CODEPOINTS)
        assertEquals(safe,BookmarkPolicy.safeTitle(safe,address)); assertTrue(BookmarkPolicy.valid(item(address=address,title=safe)))
    }
    @Test fun captureRequiresLiveOrdinaryReadyGeneration() {
        val ready = BrowserTab(BrowserContextRef("t","s"),epoch=1,address=PageAddress("https://example.test/1"),loadState=BrowserLoadState.READY)
        fun capture(tab: BrowserTab?=ready, actual: String?="https://example.test/1", admitted:String?=actual, active:Boolean=true) = BookmarkPolicy.capture(tab,actual,admitted,"Title",2,3,active)
        assertNotNull(capture()); assertNull(capture(null)); assertNull(capture(ready.copy(incognito=true))); assertNull(capture(ready.copy(epoch=0))); assertNull(capture(active=false)); assertNull(capture(actual=null)); assertNull(capture(admitted="https://other.test/"))
        BrowserLoadState.entries.filter { it != BrowserLoadState.READY }.forEach { assertNull(capture(ready.copy(loadState=it))) }
        val first=capture()!!; assertNotEquals(first, first.copy(epoch=2)); assertNotEquals(first,first.copy(selectionRevision=3)); assertNotEquals(first,first.copy(navigationRevision=4)); assertNotEquals(first,first.copy(context=BrowserContextRef("t","new-session")))
    }
    @Test fun acceptingFreshLiveTitlePreservesEveryIdentityFence() {
        val old=token(); val current=old.copy(title="Changed title")
        assertSame(current,BookmarkPolicy.accept(old,current)); assertNull(BookmarkPolicy.accept(null,current)); assertNull(BookmarkPolicy.accept(old,null))
        val mutations=listOf(current.copy(context=BrowserContextRef("t2","s")),current.copy(context=BrowserContextRef("t","s2")),current.copy(epoch=2),current.copy(selectionRevision=3),current.copy(navigationRevision=4),current.copy(address="https://example.test/other"))
        mutations.forEach { assertNull(BookmarkPolicy.accept(old,it)) }
    }
    @Test fun modelStringFormsNeverExposeFunctionalFields() {
        val address="https://private.test/path?secret=value"; val title="Private title"
        listOf(item(address=address,title=title).toString(),token(address,title).toString(),BookmarkState(listOf(item(address=address,title=title))).toString()).forEach { value ->
            assertFalse(value.contains("private.test")); assertFalse(value.contains("secret")); assertFalse(value.contains(title))
        }
    }
    @Test fun unsafeOrNoncanonicalAddTokenCannotScheduleWrite() {
        val h=Harness(); h.ready()
        listOf(token("https://EXAMPLE.test/1"),token("javascript:bad"),token(title="bad\n title")).forEach { value ->
            h.controller.add(value); assertEquals(BookmarkNotice.STALE_PAGE,h.controller.state.notice); assertTrue(h.controller.state.ready)
        }
        assertTrue(h.work.pending.isEmpty()); assertEquals(0,h.store.saves)
    }
    @Test fun codecRoundTripMaximumCountWithFourByteTitles() {
        val list=(1..200).map { item(it,title="😀".repeat(160)) }; val encoded=BookmarkCodec.encode(list)
        assertTrue(encoded.size<=BookmarkCodec.MAX_BYTES); assertEquals(list,BookmarkCodec.decode(encoded)); assertEquals(emptyList<BrowserBookmark>(),BookmarkCodec.decode(BookmarkCodec.encode(emptyList())))
    }
    @Test fun codecRejectsEveryTruncationAndChecksumCorruption() {
        val encoded=BookmarkCodec.encode(listOf(item()))
        for (n in 0 until encoded.size) rejected { BookmarkCodec.decode(encoded.copyOf(n)) }
        encoded.indices.forEach { index -> val broken=encoded.copyOf(); broken[index]=(broken[index].toInt() xor 1).toByte(); rejected { BookmarkCodec.decode(broken) } }
    }
    @Test fun codecRejectsValidChecksumUnknownFormatAndNegativeCounts() {
        val bytes=BookmarkCodec.encode(emptyList())
        listOf(0 to 0,4 to 0,4 to 2,8 to -1,8 to 201).forEach { (offset,value) -> rejected { BookmarkCodec.decode(modifiedHeader(bytes,offset,value)) } }
    }
    @Test fun codecRejectsTrailingPayloadMalformedUtf8AndHugeLength() {
        val bytes=BookmarkCodec.encode(listOf(item())); val payload=bytes.copyOf(bytes.size-32)
        rejected { BookmarkCodec.decode(signed(payload + byteArrayOf(0))) }
        rejected { BookmarkCodec.decode(modifiedHeader(bytes,12,Int.MAX_VALUE)) }
        val invalid=payload.copyOf(); invalid[16]=0xFF.toByte(); rejected { BookmarkCodec.decode(signed(invalid)) }
        rejected { BookmarkCodec.decode(ByteArray(BookmarkCodec.MAX_BYTES+1)) }
    }
    @Test fun codecRejectsDuplicatesUnsafeDataAndCountOverflow() {
        rejected { BookmarkCodec.encode(listOf(item(),item())) }
        rejected { BookmarkCodec.encode(listOf(item(),item(2,address=item().address))) }
        rejected { BookmarkCodec.encode((1..201).map { item(it) }) }
        rejected { BookmarkCodec.encode(listOf(item(address="https://EXAMPLE.test/"))) }
        rejected { BookmarkCodec.encode(listOf(item(title="a\n b"))) }
        rejected { BookmarkCodec.encode(listOf(item().copy(id="../unsafe"))) }
    }
    @Test fun initialReadAndAddPublishOnlyAfterWorkerThenDelivery() {
        val h=Harness(); h.controller.load(); assertEquals(0,h.store.reads); assertFalse(h.controller.state.ready); assertTrue(h.controller.state.busy)
        h.work.run(); assertEquals(1,h.store.reads); assertFalse(h.controller.state.ready); h.main.run(); assertTrue(h.controller.state.ready)
        h.controller.add(token()); assertEquals(0,h.store.saves); assertTrue(h.controller.state.items.isEmpty())
        h.work.run(); assertEquals(1,h.store.saves); assertTrue(h.controller.state.items.isEmpty()); assertNull(h.controller.state.notice)
        h.main.run(); assertEquals(1,h.controller.state.items.size); assertEquals(BookmarkNotice.ADDED,h.controller.state.notice)
    }
    @Test fun duplicateAndRapidClicksDoNotCreateExtraWork() {
        val h=Harness(); h.ready(); h.controller.add(token()); repeat(30) { h.controller.add(token()); h.controller.load() }; assertEquals(1,h.work.pending.size); h.finish()
        h.controller.add(token()); assertEquals(BookmarkNotice.DUPLICATE,h.controller.state.notice); assertEquals(1,h.store.saves); assertTrue(h.work.pending.isEmpty())
    }
    @Test fun fullStoreNeverEvictsButDuplicateStillReportsDuplicate() {
        val h=Harness(); h.store.disk=(1..200).map { item(it) }; h.ready(); h.controller.add(token()); assertEquals(BookmarkNotice.DUPLICATE,h.controller.state.notice)
        h.controller.add(token("https://new.test/","new")); assertEquals(BookmarkNotice.FULL,h.controller.state.notice); assertEquals(0,h.store.saves); assertEquals(200,h.controller.state.items.size)
    }
    @Test fun staleOrAlteredRemoveAndOpenCannotTargetReplacement() {
        val h=Harness(); h.store.disk=listOf(item()); h.ready(); val old=h.controller.state.items.single()
        assertNull(h.controller.addressForOpen(old.copy(title="changed"))); h.controller.remove(old.copy(title="changed")); assertTrue(h.work.pending.isEmpty())
        h.controller.remove(old); assertNull(h.controller.addressForOpen(old)); h.finish(); assertNull(h.controller.addressForOpen(old))
        h.controller.add(token()); h.finish(); assertNotEquals(old.id,h.controller.state.items.single().id)
        h.controller.remove(old); assertTrue(h.work.pending.isEmpty()); assertNull(h.controller.addressForOpen(old)); assertEquals("https://example.test/1",h.controller.addressForOpen(h.controller.state.items.single()))
    }
    @Test fun failedReadBlocksAllWritesAndRetriesReadOnly() {
        val h=Harness(); h.store.failRead=true; h.controller.load(); h.finish(); assertFalse(h.controller.state.ready); assertEquals(BookmarkNotice.UNAVAILABLE,h.controller.state.notice)
        h.controller.add(token()); h.controller.remove(item()); assertEquals(0,h.store.saves); assertTrue(h.work.pending.isEmpty())
        h.store.failRead=false; h.store.disk=listOf(item()); h.controller.load(); h.finish(); assertEquals(0,h.store.saves); assertTrue(h.controller.state.ready)
    }
    @Test fun failedWriteDoesNotPublishOrEnableAnotherWrite() {
        val h=Harness(); h.ready(); h.store.failWrite=true; h.controller.add(token()); h.finish()
        assertFalse(h.controller.state.ready); assertTrue(h.controller.state.items.isEmpty()); assertEquals(BookmarkNotice.UNAVAILABLE,h.controller.state.notice); assertNull(h.controller.addressForOpen(item()))
        h.controller.add(token()); assertTrue(h.work.pending.isEmpty()); assertEquals(1,h.store.saves)
    }
    @Test fun uncertainCommitReloadReconcilesExactDurableResult() {
        val h=Harness(); h.ready(); h.store.commitThenFail=true; h.controller.add(token()); h.finish()
        assertFalse(h.controller.state.ready); assertTrue(h.controller.state.items.isEmpty()); assertEquals(1,h.store.disk.size)
        h.controller.load(); h.finish(); assertEquals(h.store.disk,h.controller.state.items); assertTrue(h.controller.state.ready); assertNull(h.controller.state.notice)
    }
    @Test fun slowOperationDoesNotReleaseWorkerOrLoseLateCompletion() {
        val h=Harness(); h.ready(); h.controller.add(token()); val gen=h.controller.operationGeneration()!!; h.controller.slow(gen)
        assertTrue(h.controller.state.busy); assertFalse(h.controller.state.ready); assertEquals(BookmarkNotice.SLOW,h.controller.state.notice)
        h.controller.load(); h.controller.add(token()); assertEquals(1,h.work.pending.size); h.finish(); assertEquals(BookmarkNotice.ADDED,h.controller.state.notice); assertTrue(h.controller.state.ready)
        h.controller.slow(gen); assertEquals(BookmarkNotice.ADDED,h.controller.state.notice)
    }
    @Test fun closedUiDoesNotCancelAcceptedDiskCommitOrPublishLateState() {
        val h=Harness(); h.ready(); h.controller.add(token()); val delivered=h.states.size; h.controller.close(); h.finish()
        assertEquals(1,h.store.disk.size); assertEquals(delivered,h.states.size); assertNull(h.controller.addressForOpen(h.store.disk.single()))
        h.controller.load(); h.controller.remove(h.store.disk.single()); assertTrue(h.work.pending.isEmpty())
    }
    @Test fun rejectedWorkerFailsClosedAndInvalidLoadedListNeverPublished() {
        val bad=BookmarkController(Store(),{throw java.util.concurrent.RejectedExecutionException()}, {it()}, {id(3)},{})
        bad.load(); assertFalse(bad.state.ready); assertFalse(bad.state.busy); assertEquals(BookmarkNotice.UNAVAILABLE,bad.state.notice)
        val h=Harness(); h.store.disk=listOf(item(),item()); h.controller.load(); h.finish(); assertFalse(h.controller.state.ready); assertTrue(h.controller.state.items.isEmpty())
    }
    @Test fun externalLoadedListChangesDoNotChangePublishedSnapshot() {
        val list=mutableListOf(item()); val h=Harness(); h.store.disk=list; h.ready(); list.clear(); assertEquals(1,h.controller.state.items.size)
    }
    @Test fun repeatedDeliveryAndStaleSlowCallbackCannotAffectNewerOperation() {
        val h=Harness(); h.ready(); h.controller.add(token()); val old=h.controller.operationGeneration()!!; h.work.run(); val completion=h.main.pending.first(); h.main.run(); completion()
        assertEquals(1,h.controller.state.items.size); h.controller.remove(h.controller.state.items.single()); h.controller.slow(old); assertNull(h.controller.state.notice); assertTrue(h.controller.state.busy); h.finish(); assertTrue(h.controller.state.items.isEmpty())
    }
    @Test fun hostStorageColdRestartAndBackupOnlyRecovery() {
        withStorage { dir, storage -> assertTrue(storage.load().isEmpty()); storage.save(listOf(item())); assertEquals(listOf(item()),AndroidBookmarkStorage(Context(dir)).load())
            val base=File(dir,"browser-bookmarks.bin"); assertTrue(base.renameTo(File(base.path+".bak"))); assertEquals(listOf(item()),AndroidBookmarkStorage(Context(dir)).load()) }
    }
    @Test fun hostStorageUnknownOrCorruptFileIsPreserved() {
        withStorage { dir, storage -> val bytes=modifiedHeader(BookmarkCodec.encode(listOf(item())),4,99); val file=File(dir,"browser-bookmarks.bin"); file.writeBytes(bytes)
            rejected { storage.load() }; assertArrayEquals(bytes,file.readBytes()); assertEquals(0,AtomicFile.writes) }
    }
    @Test fun hostStorageSilentRenameFailureCannotBecomeSuccess() {
        withStorage { _, storage -> storage.save(listOf(item())); AtomicFile.silentRenameFailure=true; rejected { storage.save(listOf(item(2))) }; assertEquals(listOf(item()),storage.load()) }
    }
    @Test fun hostStorageFirstWriteSilentRenameFailureCannotBecomeSuccess() {
        withStorage { _, storage -> AtomicFile.silentRenameFailure=true; rejected { storage.save(listOf(item())) }; assertTrue(storage.load().isEmpty()) }
    }
    @Test fun hostStorageCorruptReadbackAndOversizedFileFailClosed() {
        withStorage { dir, storage -> AtomicFile.corruptCommit=true; rejected { storage.save(listOf(item())) }; AtomicFile.corruptCommit=false
            val file=File(dir,"browser-bookmarks.bin"); file.writeBytes(ByteArray(BookmarkCodec.MAX_BYTES+100_000)); AtomicFile.bytesRead=0; rejected { storage.load() }; assertTrue(AtomicFile.bytesRead <= BookmarkCodec.MAX_BYTES+1); assertEquals((BookmarkCodec.MAX_BYTES+100_000).toLong(),file.length()) }
    }
    @Test fun hostStorageZeroProgressAndUnreadableExistingFileFailClosed() {
        withStorage { _, storage -> storage.save(listOf(item())); AtomicFile.zeroRead=true; rejected { storage.load() }; AtomicFile.zeroRead=false; AtomicFile.denyReads=true; rejected { storage.load() } }
    }
    @Test fun hostStorageFailedStartWriteKeepsPreviousBytes() {
        withStorage { dir,storage -> storage.save(listOf(item())); val file=File(dir,"browser-bookmarks.bin"); val old=file.readBytes(); AtomicFile.denyWrites=true; rejected { storage.save(listOf(item(2))) }; assertArrayEquals(old,file.readBytes()) }
    }
    @Test fun hostStorageNonDirectoryPrivateRootDoesNotPretendToBeEmpty() {
        withStorage { dir,_ -> val notDirectory=File(dir,"not-a-directory"); notDirectory.writeText("keep")
            rejected { AndroidBookmarkStorage(Context(notDirectory)).load() }; assertEquals("keep",notDirectory.readText()) }
    }
    @Test fun hostStorageInaccessiblePrivateDirectoryFailsClosed() {
        withStorage { dir, storage ->
            storage.save(listOf(item()))
            val original=Files.getPosixFilePermissions(dir.toPath())
            try {
                Files.setPosixFilePermissions(dir.toPath(),emptySet())
                assertFalse("Host privileges bypass test permissions",dir.canRead())
                rejected { storage.load() }
            } finally { Files.setPosixFilePermissions(dir.toPath(),original) }
            assertEquals(listOf(item()),storage.load())
        }
    }
    private fun withStorage(test: (File,AndroidBookmarkStorage)->Unit) {
        AtomicFile.reset(); val dir=Files.createTempDirectory("esv-independent-bookmark-").toFile()
        try { test(dir,AndroidBookmarkStorage(Context(dir))) } finally { AtomicFile.reset(); dir.deleteRecursively() }
    }
}
