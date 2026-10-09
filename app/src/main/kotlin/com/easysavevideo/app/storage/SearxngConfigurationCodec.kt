package com.easysavevideo.app.storage

import com.easysavevideo.app.discovery.SearxngConfigurationPolicy
import com.easysavevideo.app.discovery.SearxngSourceConfiguration
import com.easysavevideo.app.discovery.SearxngSourceSlot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded, versioned, strict UTF-8 functional data. Not Java serialization or a log payload. */
object SearxngConfigurationCodec {
    const val MAX_BYTES = 8192
    private const val MAGIC = 0x45535358
    private const val VERSION = 1
    fun encode(config: SearxngSourceConfiguration): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeLong(config.revision); out.writeInt(config.slots.size)
            config.slots.forEach { slot ->
                out.text(slot.id)
                out.writeBoolean(slot.enabled)
                out.writeBoolean(slot.searchUrl != null)
                slot.searchUrl?.let { out.text(it) }
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): SearxngSourceConfiguration {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val revision = input.readLong()
            require(input.readInt() == SearxngConfigurationPolicy.SLOT_IDS.size)
            val slots = SearxngConfigurationPolicy.SLOT_IDS.map { expectedId ->
                val id = input.text(16); require(id == expectedId)
                val enabled = input.boolean()
                val url = if (input.boolean()) input.text(SearxngConfigurationPolicy.MAX_URL_BYTES) else null
                SearxngSourceSlot(id, url, enabled)
            }
            require(input.available() == 0)
            SearxngSourceConfiguration(revision, slots)
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
