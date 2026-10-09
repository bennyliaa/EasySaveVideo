package com.easysavevideo.app.library.share

import org.junit.Assert.*
import org.junit.Test

class SharePolicyTest {
    @Test fun onlyCanonicalRandomTokenShape() {
        assertTrue(SharePolicy.validToken("01234567-89ab-4def-8123-456789abcdef"))
        listOf("", "../original", "01234567-89AB-4def-8123-456789abcdef", "x/01234567-89ab-4def-8123-456789abcdef", "01234567-89ab-4def-8123-456789abcdef%2f").forEach { assertFalse(SharePolicy.validToken(it)) }
    }
    @Test fun copyCountAndBytesAreBothBounded() {
        assertTrue(SharePolicy.admit(0, 0, SharePolicy.MAX_COPY_BYTES))
        assertFalse(SharePolicy.admit(SharePolicy.MAX_COPIES, 0, 1))
        assertFalse(SharePolicy.admit(0, 0, SharePolicy.MAX_COPY_BYTES + 1))
        assertFalse(SharePolicy.admit(1, SharePolicy.MAX_TOTAL_BYTES, 1))
        assertFalse(SharePolicy.admit(1, 0, 0))
        assertFalse(SharePolicy.admit(1, -1, 1))
    }
    @Test fun zeroAndExpiryAreNotActive() {
        assertTrue(SharePolicy.canOpen(true, false, 0, 10, 0, 0))
        assertFalse(SharePolicy.canOpen(true, false, 10, 10, 0, 0))
        assertFalse(SharePolicy.canOpen(true, false, 11, 10, 0, 0))
        assertFalse(SharePolicy.canOpen(true, true, 0, 10, 0, 0))
        assertFalse(SharePolicy.canOpen(false, false, 0, 10, 0, 0))
    }
    @Test fun finiteOpenAndTransferCapacity() {
        assertFalse(SharePolicy.canOpen(true, false, 0, 10, SharePolicy.MAX_OPENS, 0))
        assertFalse(SharePolicy.canOpen(true, false, 0, 10, 0, SharePolicy.MAX_TRANSFERS))
    }
    @Test fun rejectsAllWritingAndVariantModes() {
        assertTrue(SharePolicy.readOnly("r"))
        listOf("rw", "w", "wt", "wa", "rwt", "R", "", " r").forEach { assertFalse(SharePolicy.readOnly(it)) }
    }
}
