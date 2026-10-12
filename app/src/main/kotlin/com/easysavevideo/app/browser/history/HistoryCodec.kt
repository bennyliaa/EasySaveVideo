package com.easysavevideo.app.browser.history

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Bounded versioned format. No imports from tab checkpoints, Discovery, Seen, cookies or logs. */
object HistoryCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAGIC = 0x45534831
    private const val VERSION = 1
    private const val CHECKSUM_BYTES = 32
    fun encode(items: List<BrowserHistoryItem>): ByteArray {
        HistoryPolicy.validate(items)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(items.size)
            items.forEach { item ->
                write(out, item.id); write(out, item.address); write(out, item.title); out.writeLong(item.visitedAtMillis)
            }
        }
        val payload = bytes.toByteArray()
        require(payload.size <= MAX_BYTES - CHECKSUM_BYTES)
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }
    fun decode(bytes: ByteArray): List<BrowserHistoryItem> {
        require(bytes.size in 12 + CHECKSUM_BYTES..MAX_BYTES)
        val size = bytes.size - CHECKSUM_BYTES
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(0, size)),
            bytes.copyOfRange(size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(bytes, 0, size)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val count = input.readInt(); require(count in 0..HistoryPolicy.MAX_ITEMS)
            List(count) {
                BrowserHistoryItem(read(input, 36), read(input, 8192), read(input, 1024), input.readLong())
            }.also { require(input.available() == 0); HistoryPolicy.validate(it) }
        }
    }
    private fun write(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes)
    }
    private fun read(input: DataInputStream, limit: Int): String {
        val length = input.readInt(); require(length in 1..limit && length <= input.available())
        val bytes = ByteArray(length); input.readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}
