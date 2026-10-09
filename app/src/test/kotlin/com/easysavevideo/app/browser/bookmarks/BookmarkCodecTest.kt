package com.easysavevideo.app.browser.bookmarks

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class BookmarkCodecTest {
    private fun item(n: Int = 1) = BrowserBookmark("00000000-0000-0000-0000-${n.toString().padStart(12, '0')}", "https://example.com/$n", "Title $n")
    private fun reject(bytes: ByteArray) { assertThrows(Exception::class.java) { BookmarkCodec.decode(bytes) } }
    private fun signed(payload: ByteArray) = payload + MessageDigest.getInstance("SHA-256").digest(payload)
    private fun changeInt(bytes: ByteArray, offset: Int, value: Int): ByteArray { val p = bytes.copyOf(bytes.size - 32); ByteBuffer.wrap(p).putInt(offset, value); return signed(p) }
    @Test fun emptyRoundtrip() { assertEquals(emptyList<BrowserBookmark>(), BookmarkCodec.decode(BookmarkCodec.encode(emptyList()))) }
    @Test fun ordinaryOrderedRoundtrip() { val all = listOf(item(2), item(1)); assertEquals(all, BookmarkCodec.decode(BookmarkCodec.encode(all))) }
    @Test fun maximumCountRoundtrip() { val all = (1..200).map(::item); assertEquals(all, BookmarkCodec.decode(BookmarkCodec.encode(all))) }
    @Test fun unicodeTitlesRoundtrip() { val one = item().copy(title = "演示😀"); assertEquals(listOf(one), BookmarkCodec.decode(BookmarkCodec.encode(listOf(one)))) }
    @Test fun overCountEncodeRejected() { assertThrows(Exception::class.java) { BookmarkCodec.encode((1..201).map(::item)) } }
    @Test fun duplicateAddressesRejected() { assertThrows(Exception::class.java) { BookmarkCodec.encode(listOf(item(), item(2).copy(address = item().address))) } }
    @Test fun duplicateIdsRejected() { assertThrows(Exception::class.java) { BookmarkCodec.encode(listOf(item(), item(2).copy(id = item().id))) } }
    @Test fun unsafeAndNoncanonicalRecordsRejected() { listOf(item().copy(address = "HTTPS://EXAMPLE.com/"), item().copy(title = "bad\nvalue"), item().copy(id = "../bad"), item().copy(address = "https://u@example.com/")).forEach { bad -> assertThrows(Exception::class.java) { BookmarkCodec.encode(listOf(bad)) } } }
    @Test fun unknownSchemaAndMagicRejectedWithoutMigration() { val bytes = BookmarkCodec.encode(listOf(item())); reject(changeInt(bytes, 4, 2)); reject(changeInt(bytes, 4, 0)); reject(changeInt(bytes, 0, 0)) }
    @Test fun invalidCountsRejectedBeforeAllocation() { val bytes = BookmarkCodec.encode(emptyList()); listOf(-1, 201, Int.MAX_VALUE).forEach { reject(changeInt(bytes, 8, it)) } }
    @Test fun allTruncatedPrefixesRejected() { val bytes = BookmarkCodec.encode(listOf(item())); (0 until bytes.size).forEach { reject(bytes.copyOf(it)) } }
    @Test fun allSingleBitCorruptionRejected() { val bytes = BookmarkCodec.encode(listOf(item())); bytes.indices.forEach { index -> val c = bytes.copyOf(); c[index] = (c[index].toInt() xor 1).toByte(); reject(c) } }
    @Test fun trailingBytesRejectedEvenWithValidChecksum() { val bytes = BookmarkCodec.encode(emptyList()); reject(signed(bytes.copyOf(bytes.size - 32) + byteArrayOf(1))) }
    @Test fun maliciousLengthsRejectedBeforeAllocation() { val bytes = BookmarkCodec.encode(listOf(item())); listOf(-1, 0, 37, Int.MAX_VALUE).forEach { reject(changeInt(bytes, 12, it)) } }
    @Test fun malformedUtf8Rejected() { val bytes = BookmarkCodec.encode(listOf(item())); val p = bytes.copyOf(bytes.size - 32); p[16] = 0xff.toByte(); reject(signed(p)) }
    @Test fun oversizedStoreRejected() { reject(ByteArray(BookmarkCodec.MAX_BYTES + 1)) }
    @Test fun corruptPayloadCannotBecomeEmptyWritableStore() { val bytes = BookmarkCodec.encode(emptyList()); bytes[0] = 0; reject(bytes) }
    @Test fun nearMaximumBudgetFitsWithinBound() { val all = (1..200).map { n -> item(n).copy(address = "https://example.com/$n?" + "a".repeat(8100), title = "😀".repeat(160)) }; val bytes = BookmarkCodec.encode(all); assertTrue(bytes.size < BookmarkCodec.MAX_BYTES); assertEquals(all, BookmarkCodec.decode(bytes)) }
}
