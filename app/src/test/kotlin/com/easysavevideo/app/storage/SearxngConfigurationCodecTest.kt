package com.easysavevideo.app.storage

import com.easysavevideo.app.discovery.SearxngConfigurationPolicy
import com.easysavevideo.app.discovery.SearxngSourceConfiguration
import com.easysavevideo.app.discovery.SearxngSourceSlot
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class SearxngConfigurationCodecTest {
    private fun config() = SearxngSourceConfiguration(7, SearxngConfigurationPolicy.SLOT_IDS.mapIndexed { index, id ->
        SearxngSourceSlot(id, "https://search$index.example.org/search", enabled = index != 1)
    })
    private fun reject(bytes: ByteArray) {
        try { SearxngConfigurationCodec.decode(bytes); fail("Expected bounded decoding rejection") }
        catch (_: IllegalArgumentException) { } catch (_: java.io.IOException) { }
    }
    @Test fun roundTripPreservesExactUrlsEnabledStateAndGeneration() {
        val original = config(); val restored = SearxngConfigurationCodec.decode(SearxngConfigurationCodec.encode(original))
        assertEquals(original, restored); assertEquals(original.generation, restored.generation)
        assertEquals(2, restored.enabledEndpoints.size)
        assertEquals(SearxngConfigurationPolicy.empty(), SearxngConfigurationCodec.decode(SearxngConfigurationCodec.encode(SearxngConfigurationPolicy.empty())))
    }
    @Test fun truncationTrailingBytesAndOversizedInputAreRejected() {
        val bytes = SearxngConfigurationCodec.encode(config())
        for (end in bytes.indices) reject(bytes.copyOf(end))
        reject(bytes + 0); reject(ByteArray(SearxngConfigurationCodec.MAX_BYTES + 1))
    }
    @Test fun unknownVersionNegativeRevisionAndOversizedRecordCountAreRejected() {
        val bytes = SearxngConfigurationCodec.encode(config())
        reject(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) })
        reject(bytes.copyOf().also { ByteBuffer.wrap(it).putLong(8, -1) })
        for (count in listOf(-1, 0, 2, 4, Int.MAX_VALUE))
            reject(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(16, count) })
    }
    @Test fun invalidSlotOrderBooleanAndMalformedUtf8AreRejected() {
        val bytes = SearxngConfigurationCodec.encode(config())
        // Header: 20 bytes, then id length 4 + nine-byte ASCII id, enabled byte, presence byte.
        reject(bytes.copyOf().also { it[32] = '2'.code.toByte() })
        reject(bytes.copyOf().also { it[33] = 2 })
        reject(bytes.copyOf().also { it[34] = 2 })
        reject(bytes.copyOf().also { it[24] = 0xff.toByte() })
        reject(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(35, Int.MAX_VALUE) })
        reject(bytes.copyOf().also { it[39] = 0xff.toByte() })
    }
    @Test fun semanticallyUnsafeRestoredEndpointIsRejected() {
        val bytes = SearxngConfigurationCodec.encode(config())
        // Independently corrupt protocol without changing any encoded length.
        val protocol = bytes.copyOf(); val index = String(protocol, Charsets.ISO_8859_1).indexOf("https://")
        protocol[index] = 'f'.code.toByte(); reject(protocol)
    }
}
