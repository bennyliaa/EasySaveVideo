package com.easysavevideo.app.storage
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
class BrowserSessionCodecTest {
    @Test fun privateCheckpointRoundTripsWithNoHistoryOrCookieFields() {
        val checkpoint = BrowserSessionCheckpoint(listOf(RestorableTab("one", PageAddress("https://example.com/a?q=1")), RestorableTab("two", null)), "one")
        assertEquals(checkpoint, BrowserSessionCodec.decode(BrowserSessionCodec.encode(checkpoint)))
    }
    @Test fun emptyCheckpointClearsAddresses() {
        val empty = BrowserSessionCheckpoint(emptyList(), null)
        assertEquals(empty, BrowserSessionCodec.decode(BrowserSessionCodec.encode(empty)))
    }
    @Test fun rejectsOversizedInputBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) { BrowserSessionCodec.decode(ByteArray(BrowserSessionCodec.MAX_BYTES + 1)) }
    }
    @Test fun rejectsTruncatedAndTrailingData() {
        val bytes = BrowserSessionCodec.encode(BrowserSessionCheckpoint(emptyList(), null))
        assertThrows(Exception::class.java) { BrowserSessionCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { BrowserSessionCodec.decode(bytes + byteArrayOf(1)) }
    }
    @Test fun rejectsDuplicateTabsAndUnknownSelection() {
        val tab = RestorableTab("one", null)
        assertThrows(IllegalArgumentException::class.java) { BrowserSessionCodec.encode(BrowserSessionCheckpoint(listOf(tab, tab), "one")) }
        assertThrows(IllegalArgumentException::class.java) { BrowserSessionCodec.encode(BrowserSessionCheckpoint(listOf(tab), "unknown")) }
    }
    @Test fun rejectsMoreThanTwelveTabs() {
        assertThrows(IllegalArgumentException::class.java) { BrowserSessionCodec.encode(BrowserSessionCheckpoint(List(13) { RestorableTab("t$it", null) }, "t0")) }
    }
}
