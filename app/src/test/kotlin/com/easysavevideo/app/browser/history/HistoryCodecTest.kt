package com.easysavevideo.app.browser.history

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class HistoryCodecTest {
    private fun resigned(bytes: ByteArray): ByteArray {
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }
    @Test fun roundTripEmptyAndFullPreservesIdentityOrderTimestampAndUnicode() {
        assertEquals(emptyList<BrowserHistoryItem>(), HistoryCodec.decode(HistoryCodec.encode(emptyList())))
        val items = (1..200).map { historyItem(it).copy(title = "合成网页 🎞 $it") }
        assertEquals(items, HistoryCodec.decode(HistoryCodec.encode(items)))
    }
    @Test fun allTruncatedInputsRejectWithoutPartialRecovery() {
        val bytes = HistoryCodec.encode(listOf(historyItem()))
        for (n in bytes.indices) assertThrows(IllegalArgumentException::class.java) { HistoryCodec.decode(bytes.copyOf(n)) }
    }
    @Test fun corruptionUnknownVersionTrailingBytesOversizedAndNegativeLengthReject() {
        val bytes = HistoryCodec.encode(listOf(historyItem()))
        val changed = bytes.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        val version = resigned(bytes.copyOf().also { it[7] = 2 })
        val negativeLength = resigned(bytes.copyOf().also { for (n in 12..15) it[n] = 0xff.toByte() })
        for (bad in listOf(changed, version, negativeLength, bytes + 0, ByteArray(HistoryCodec.MAX_BYTES + 1)))
            assertThrows(IllegalArgumentException::class.java) { HistoryCodec.decode(bad) }
    }
    @Test fun duplicateIdentityDuplicateAddressAndUnsafeValuesRejectEncoding() {
        val item = historyItem()
        for (bad in listOf(listOf(item, item.copy(address = "https://example.com/2")),
            listOf(item, item.copy(id = historyId(2))), listOf(item.copy(address = "javascript:1")),
            listOf(item.copy(visitedAtMillis = -1)), listOf(item.copy(title = "bad\ntitle"))))
            assertThrows(IllegalArgumentException::class.java) { HistoryCodec.encode(bad) }
    }
    @Test fun checksumIsNotPresentedAsEncryption() {
        val address = historyItem().address
        assertTrue(HistoryCodec.encode(listOf(historyItem())).toString(Charsets.ISO_8859_1).contains(address))
    }
}
