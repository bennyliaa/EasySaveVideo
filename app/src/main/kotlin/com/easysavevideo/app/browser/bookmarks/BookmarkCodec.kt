package com.easysavevideo.app.browser.bookmarks

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Version 1 is a new store. No migration imports session/history or discards unknown versions. */
object BookmarkCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAGIC = 0x4553424D
    private const val VERSION = 1
    private const val CHECKSUM_BYTES = 32
    fun encode(items: List<BrowserBookmark>): ByteArray {
        BookmarkPolicy.validate(items)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC); data.writeInt(VERSION); data.writeInt(items.size)
            items.forEach { item ->
                write(data, item.id); write(data, item.address); write(data, item.title)
            }
        }
        val payload = output.toByteArray()
        require(payload.size <= MAX_BYTES - CHECKSUM_BYTES)
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }
    fun decode(bytes: ByteArray): List<BrowserBookmark> {
        require(bytes.size in 12 + CHECKSUM_BYTES..MAX_BYTES)
        val payloadSize = bytes.size - CHECKSUM_BYTES
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(0, payloadSize))
        require(MessageDigest.isEqual(expected, bytes.copyOfRange(payloadSize, bytes.size)))
        return DataInputStream(ByteArrayInputStream(bytes, 0, payloadSize)).use { data ->
            require(data.readInt() == MAGIC && data.readInt() == VERSION)
            val count = data.readInt(); require(count in 0..BookmarkPolicy.MAX_ITEMS)
            val items = List(count) {
                BrowserBookmark(read(data, 36), read(data, BookmarkPolicy.MAX_ADDRESS_BYTES), read(data, 1024))
            }
            require(data.available() == 0)
            BookmarkPolicy.validate(items)
            items
        }
    }
    private fun write(data: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        data.writeInt(bytes.size); data.write(bytes)
    }
    private fun read(data: DataInputStream, max: Int): String {
        val length = data.readInt(); require(length in 1..max && length <= data.available())
        val bytes = ByteArray(length); data.readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}
