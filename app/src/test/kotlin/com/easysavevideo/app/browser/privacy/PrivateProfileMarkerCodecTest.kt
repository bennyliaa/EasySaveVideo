package com.easysavevideo.app.browser.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivateProfileMarkerCodecTest {
    private fun marker(n: Int) = PrivateProfileMarker(
        PrivateProfileName.parse("esv_private_v1_00000000-0000-0000-0000-${n.toString().padStart(12, '0')}")!!,
        if (n % 2 == 0) PrivateMarkerPhase.ACTIVE else PrivateMarkerPhase.DATA_CLEARED)
    @Test fun emptyAndFullBoundedRoundTrip() {
        for (count in listOf(0, 1, PrivateBrowsingPolicy.MAX_MARKERS)) {
            val records = (0 until count).map(::marker)
            val bytes = PrivateProfileMarkerCodec.encode(records)
            assertTrue(bytes.size <= PrivateProfileMarkerCodec.MAX_BYTES)
            assertEquals(records, PrivateProfileMarkerCodec.decode(bytes))
        }
    }
    @Test fun duplicatesExcessTrailingAndTruncatedFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { PrivateProfileMarkerCodec.encode(listOf(marker(1), marker(1))) }
        assertThrows(IllegalArgumentException::class.java) { PrivateProfileMarkerCodec.encode((0..PrivateBrowsingPolicy.MAX_MARKERS).map(::marker)) }
        val bytes = PrivateProfileMarkerCodec.encode(listOf(marker(1)))
        assertThrows(IllegalArgumentException::class.java) { PrivateProfileMarkerCodec.decode(bytes + 0.toByte()) }
        for (length in 0 until bytes.size) assertThrows(Exception::class.java) { PrivateProfileMarkerCodec.decode(bytes.copyOf(length)) }
    }
    @Test fun unknownPhaseAndCorruptMagicFailClosed() {
        val bytes = PrivateProfileMarkerCodec.encode(listOf(marker(1)))
        assertThrows(IllegalArgumentException::class.java) { PrivateProfileMarkerCodec.decode(bytes.copyOf().also { it[0] = 0 }) }
        assertThrows(IllegalArgumentException::class.java) { PrivateProfileMarkerCodec.decode(bytes.copyOf().also { it[it.lastIndex] = 127 }) }
    }
}
