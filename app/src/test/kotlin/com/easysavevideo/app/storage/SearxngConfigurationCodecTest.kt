package com.easysavevideo.app.storage

import com.easysavevideo.app.discovery.SearxngConfigurationEdit
import com.easysavevideo.app.discovery.SearxngConfigurationPolicy
import com.easysavevideo.app.discovery.SearxngSourceConfiguration
import com.easysavevideo.app.discovery.SearxngSourceSlot
import com.easysavevideo.searxng.SearxngResultFormat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class SearxngConfigurationCodecTest {
    private fun config() = SearxngSourceConfiguration(7, listOf(1, 2, 9).mapIndexed { index, ordinal ->
        SearxngSourceSlot("searxng-$ordinal", "https://search$index.example.org/search", enabled = index != 1,
            format = if (index == 1) SearxngResultFormat.HTML_WEB else SearxngResultFormat.JSON_VIDEOS)
    }, 10)
    private fun reject(bytes: ByteArray) {
        try { SearxngConfigurationCodec.decode(bytes); fail("Expected bounded decoding rejection") }
        catch (_: IllegalArgumentException) { } catch (_: java.io.IOException) { }
    }
    @Test fun versionThreeRoundTripPreservesExactUrlsEnabledStateIdentifiersAllocatorAndGeneration() {
        val original = config(); val bytes = SearxngConfigurationCodec.encode(original)
        val restored = SearxngConfigurationCodec.decode(bytes)
        assertEquals(3, ByteBuffer.wrap(bytes).getInt(4)); assertEquals(original, restored)
        assertEquals(original.generation, restored.generation); assertEquals(2, restored.enabledEndpoints.size)
        assertEquals(listOf("searxng-1", "searxng-2", "searxng-9"), restored.slots.map { it.id })
        assertEquals(10L, restored.nextInstanceOrdinal)
        assertEquals(SearxngConfigurationPolicy.empty(), SearxngConfigurationCodec.decode(SearxngConfigurationCodec.encode(SearxngConfigurationPolicy.empty())))
    }
    @Test fun truncationTrailingBytesAndOversizedInputAreRejected() {
        val bytes = SearxngConfigurationCodec.encode(config())
        for (end in bytes.indices) reject(bytes.copyOf(end))
        reject(bytes + 0); reject(ByteArray(SearxngConfigurationCodec.MAX_BYTES + 1))
    }
    @Test fun unknownVersionNegativeRevisionUnsafeAllocatorAndOversizedRecordCountAreRejected() {
        val bytes = SearxngConfigurationCodec.encode(config())
        for (version in listOf(0, 4, Int.MAX_VALUE)) reject(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, version) })
        reject(bytes.copyOf().also { ByteBuffer.wrap(it).putLong(8, -1) })
        for (next in listOf(-1L, 0L, 9L)) reject(bytes.copyOf().also { ByteBuffer.wrap(it).putLong(16, next) })
        for (count in listOf(-1, 0, 2, 17, Int.MAX_VALUE))
            reject(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(24, count) })
    }
    @Test fun duplicateIdsNoncanonicalIdsBooleanAndMalformedUtf8AreRejected() {
        val bytes = SearxngConfigurationCodec.encode(config())
        // v3 header:28 bytes; id length4 + nine-byte ASCII id; enabled/presence; URL length4.
        reject(bytes.copyOf().also { it[40] = '2'.code.toByte() })
        reject(bytes.copyOf().also { it[40] = '0'.code.toByte() })
        reject(bytes.copyOf().also { it[41] = 2 })
        reject(bytes.copyOf().also { it[42] = 2 })
        reject(bytes.copyOf().also { it[32] = 0xff.toByte() })
        reject(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(43, Int.MAX_VALUE) })
        reject(bytes.copyOf().also { it[47] = 0xff.toByte() })
    }
    @Test fun semanticallyUnsafeRestoredEndpointIsRejected() {
        val bytes = SearxngConfigurationCodec.encode(config())
        val protocol = bytes.copyOf(); val index = String(protocol, Charsets.ISO_8859_1).indexOf("https://")
        protocol[index] = 'f'.code.toByte(); reject(protocol)
    }
    @Test fun versionOneAndTwoMigratePurelyPreservingConfiguredRowsAndReservingOldIdentifiers() {
        for (version in 1..2) {
            val restored = SearxngConfigurationCodec.decode(legacySearxngConfiguration(version = version))
            assertEquals(7L, restored.revision); assertEquals(4L, restored.nextInstanceOrdinal)
            assertEquals(listOf("searxng-1", "searxng-2"), restored.slots.map { it.id })
            assertEquals(listOf("https://search0.example.org/search", "https://search1.example.org/search"),
                restored.slots.map { it.searchUrl })
            assertEquals(listOf(true, false), restored.slots.map { it.enabled })
            assertEquals(SearxngResultFormat.JSON_VIDEOS, restored.slots[0].format)
            assertEquals(if (version == 1) SearxngResultFormat.JSON_VIDEOS else SearxngResultFormat.HTML_WEB, restored.slots[1].format)
            assertEquals(1, restored.enabledEndpoints.size)
            assertEquals(3, ByteBuffer.wrap(SearxngConfigurationCodec.encode(restored)).getInt(4))
            val added = (SearxngConfigurationPolicy.add(restored, "https://next.example.org/search", acknowledged = true)
                as SearxngConfigurationEdit.Accepted).config
            assertEquals("searxng-4", added.slots.last().id)
        }
    }
    @Test fun legacyEmptyPlaceholdersAreNotConfiguredEntriesAndDoNotRecycleTheirIds() {
        for (version in 1..2) {
            val empty = SearxngConfigurationCodec.decode(legacySearxngConfiguration(version = version, empty = true))
            assertTrue(empty.slots.isEmpty()); assertEquals(4L, empty.nextInstanceOrdinal)
            val added = (SearxngConfigurationPolicy.add(empty, "https://next.example.org/search", acknowledged = true)
                as SearxngConfigurationEdit.Accepted).config
            assertEquals("searxng-4", added.slots.single().id)
        }
    }
    @Test fun unknownFormatByteAndLegacyOrCurrentDuplicateOriginsFailClosed() {
        val bytes = SearxngConfigurationCodec.encode(config())
        val firstFormatOffset = 47 + "https://search0.example.org/search".toByteArray(Charsets.UTF_8).size
        reject(bytes.copyOf().also { it[firstFormatOffset] = 2 })
        for (version in 1..2) reject(legacySearxngConfiguration(duplicateOrigin = true, version = version))
        val replaced = bytes.toString(Charsets.ISO_8859_1).replace("https://search1.example.org/search", "https://search0.example.org/search")
        reject(replaced.toByteArray(Charsets.ISO_8859_1))
    }
    @Test fun versionThreeNeverAcceptsPlaceholderRowsOrOverflowIdentifiers() {
        fun fixture(id: String, hasUrl: Boolean): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(0x45535358); out.writeInt(3); out.writeLong(1); out.writeLong(Long.MAX_VALUE); out.writeInt(1)
                out.writeInt(id.length); out.write(id.toByteArray(Charsets.UTF_8)); out.writeBoolean(false); out.writeBoolean(hasUrl)
                if (hasUrl) { val url = "https://search.example.org/search".toByteArray(); out.writeInt(url.size); out.write(url) }
                out.writeByte(0)
            }
            return bytes.toByteArray()
        }
        reject(fixture("searxng-1", false)); reject(fixture("searxng-9223372036854775808", true))
        reject(fixture("searxng-${Long.MAX_VALUE}", true)); reject(fixture("searxng-01", true))
    }
    @Test fun fullCapacityNearMaximumLengthUrlsAndIdsRemainWithinBoundedStoreEnvelope() {
        val config = SearxngSourceConfiguration(1, (1..16).map { index ->
            val prefix = "https://s$index.example.org/"
            val url = prefix + "x".repeat(2048 - prefix.length - 7) + "/search"
            SearxngSourceSlot("searxng-${Long.MAX_VALUE - index}", url)
        }, Long.MAX_VALUE)
        val bytes = SearxngConfigurationCodec.encode(config)
        assertTrue(bytes.size > 8192); assertTrue(bytes.size <= SearxngConfigurationCodec.MAX_BYTES)
        assertEquals(config, SearxngConfigurationCodec.decode(bytes))
    }
}
