package com.easysavevideo.app.storage

import com.easysavevideo.app.discovery.SearxngConfigurationPolicy
import com.easysavevideo.app.discovery.SearxngSourceConfiguration
import com.easysavevideo.app.discovery.SearxngSourceSlot
import com.easysavevideo.searxng.SearxngResultFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded, versioned, strict UTF-8 functional data. Not Java serialization or a log payload. */
object SearxngConfigurationCodec {
    const val MAX_BYTES = 36 * 1024
    private const val MAGIC = 0x45535358
    private const val VERSION = 3
    fun encode(config: SearxngSourceConfiguration): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeLong(config.revision)
            out.writeLong(config.nextInstanceOrdinal); out.writeInt(config.slots.size)
            config.slots.forEach { slot ->
                out.text(slot.id)
                out.writeBoolean(slot.enabled)
                out.writeBoolean(slot.searchUrl != null)
                slot.searchUrl?.let { out.text(it) }
                out.writeByte(when (slot.format) {
                    SearxngResultFormat.JSON_VIDEOS -> 0
                    SearxngResultFormat.HTML_WEB -> 1
                })
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): SearxngSourceConfiguration {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            val version = input.readInt()
            require(version in 1..VERSION)
            val revision = input.readLong()
            // Historical files always reserved IDs 1..3, including their empty placeholders.
            val nextOrdinal = if (version == VERSION) input.readLong() else 4L
            val count = input.readInt()
            require(if (version == VERSION) count in 0..SearxngConfigurationPolicy.MAX_INSTANCES
                else count == SearxngConfigurationPolicy.LEGACY_SLOT_IDS.size)
            val slots = List(count) { index ->
                val id = input.text(SearxngConfigurationPolicy.MAX_ID_BYTES)
                if (version != VERSION) require(id == SearxngConfigurationPolicy.LEGACY_SLOT_IDS[index])
                val enabled = input.boolean()
                val url = if (input.boolean()) input.text(SearxngConfigurationPolicy.MAX_URL_BYTES) else null
                // Legacy reads are pure: they do not rewrite the file or enable a new format.
                val format = if (version == 1) SearxngResultFormat.JSON_VIDEOS else when (input.readUnsignedByte()) {
                    0 -> SearxngResultFormat.JSON_VIDEOS
                    1 -> SearxngResultFormat.HTML_WEB
                    else -> throw IllegalArgumentException("CONFIG_FORMAT")
                }
                SearxngSourceSlot(id, url, enabled, format)
            }
            require(input.available() == 0)
            // Only historical placeholders are removed; v3 requires every row to be configured.
            SearxngSourceConfiguration(revision, if (version == VERSION) slots else slots.filter { it.searchUrl != null }, nextOrdinal)
        }
    }

    private fun DataOutputStream.text(value: String) {
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.text(maxBytes: Int): String {
        val size = readInt(); require(size in 1..maxBytes && size <= available())
        val bytes = ByteArray(size); readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun DataInputStream.boolean(): Boolean = when (readUnsignedByte()) {
        0 -> false; 1 -> true; else -> throw IllegalArgumentException("CONFIG_BOOLEAN")
    }
}
