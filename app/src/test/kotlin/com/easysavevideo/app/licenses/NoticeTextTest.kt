package com.easysavevideo.app.licenses

import java.io.Reader
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class NoticeTextTest {
    @Test fun previousHalfMegabyteLimitDoesNotHideNewAttribution() {
        val text = "x".repeat(550003)
        assertEquals(text, readNoticeText(StringReader(text)))
    }
    @Test fun exactBoundIsReadableButOversizedNoticeFailsClosed() {
        assertEquals("abc", readNoticeText(StringReader("abc"), 3))
        try { readNoticeText(StringReader("abcd"), 3); fail("oversize") }
        catch (_: IllegalStateException) { }
    }
    @Test fun zeroProgressCannotSpinForever() {
        val stalled = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int) = 0
            override fun close() = Unit
        }
        try { readNoticeText(stalled); fail("zero progress") }
        catch (_: IllegalStateException) { }
    }
}
