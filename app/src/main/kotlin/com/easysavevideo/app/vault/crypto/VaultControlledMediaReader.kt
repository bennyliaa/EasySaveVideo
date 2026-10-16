package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.library.export.ExportRelease
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.atomic.AtomicBoolean

/** No plaintext stream/FD escapes. One mutex serializes position+read; the runtime gate
 * only admits IO and publishes one bounded copy. Actual close never runs under that gate.
 */
internal class VaultControlledMediaReader(
    override val authenticatedSize: Long,
    private val channel: SeekableByteChannel,
    private val gate: VaultMediaPublicationGate,
    private val operation: VaultOperationContext,
    private val requireOwner: () -> Unit,
    private val onReleased: () -> Unit,
) : VaultMediaReadCapability {
    private val ioLock = Any()
    private val revoked = AtomicBoolean(false)
    private val scratch = ByteArray(VaultMediaLimits.BUFFER_BYTES)
    private var released = false
    private var closeStarted = false
    init { require(authenticatedSize in 1..VaultMediaLimits.MAX_BYTES) }

    /** Authenticate a nonempty final segment and actual EOF before returning any read capability. */
    fun authenticateFinalSegment(context: VaultOperationContext) = synchronized(ioLock) {
        check(context)
        val permit = gate.admit()
        try {
            channel.position(authenticatedSize - 1)
            val last = readProgress(ByteBuffer.wrap(scratch, 0, 1), context)
            require(last == 1) { "media-final-segment" }
            check(context)
            val eof = readProgress(ByteBuffer.wrap(scratch, 0, 1), context)
            require(eof == -1 && channel.size() == authenticatedSize) { "media-plaintext-extent" }
            check(context)
        } finally { scratch.fill(0); permit.close() }
    }

    override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int,
        context: VaultOperationContext): Int = synchronized(ioLock) {
        require(position >= 0 && position <= authenticatedSize && offset >= 0 && length >= 0 &&
            length <= VaultMediaLimits.BUFFER_BYTES && offset <= destination.size - length) { "media-read-range" }
        check(context)
        if (length == 0) return@synchronized 0
        if (position == authenticatedSize) return@synchronized -1
        val count = minOf(length.toLong(), authenticatedSize - position).toInt()
        val permit = gate.admit()
        try {
            check(context)
            channel.position(position)
            val read = readProgress(ByteBuffer.wrap(scratch, 0, count), context)
            require(read in 1..count) { "media-read-truncated" }
            check(context)
            // Lock/cancel has the final atomic say. Failed reads never mutate caller memory.
            permit.publish(scratch, 0, destination, offset, read)
            read
        } finally { scratch.fill(0); permit.close() }
    }

    private fun readProgress(buffer: ByteBuffer, context: VaultOperationContext): Int {
        var zero = 0
        while (true) {
            check(context)
            val count = channel.read(buffer)
            if (count != 0) return count
            if (++zero > VaultMediaLimits.MAX_ZERO_PROGRESS) throw VaultOperationException(VaultFailure.CORRUPT)
        }
    }
    private fun check(context: VaultOperationContext) {
        if (revoked.get() || released) throw VaultOperationException(VaultFailure.CANCELLED)
        operation.checkActive(); context.checkActive()
        if (!operation.sharesOperation(context) || context.deadlineElapsedMillis > operation.deadlineElapsedMillis)
            throw VaultOperationException(VaultFailure.REJECTED)
        requireOwner(); gate.requireCurrent()
    }
    override fun revoke() { revoked.set(true) }
    override fun closeAndObserve(): ExportRelease {
        revoke()
        return synchronized(ioLock) {
            if (released) return@synchronized ExportRelease.RELEASED
            if (closeStarted) return@synchronized ExportRelease.UNCONFIRMED
            closeStarted = true
            // Acquiring ioLock proves every admitted position/read has returned and cleared scratch.
            // A close exception may follow a real platform close. Its UNKNOWN obligation is
            // sticky: repeated observation must never blindly issue a second underlying close.
            try {
                channel.close()
                if (channel.isOpen) return@synchronized ExportRelease.UNCONFIRMED
                scratch.fill(0); onReleased(); released = true; ExportRelease.RELEASED
            } catch (_: Exception) { scratch.fill(0); ExportRelease.UNCONFIRMED }
        }
    }
    override fun toString() = "VaultMediaReadCapability(REDACTED)"
}

/** Non-owning per-pass channel: the store anchor is retired by its workflow, never by Tink. */
internal class VaultCiphertextReadChannel(
    private val file: VaultOwnedCiphertext,
    private val expectedExtent: Long,
    private val gate: VaultMediaPublicationGate,
    private val context: VaultOperationContext,
    private val requireOwner: () -> Unit,
) : SeekableByteChannel {
    private var open = true
    private var position = 0L
    private val transfer = ByteArray(VaultMediaLimits.BUFFER_BYTES)
    override fun isOpen() = open
    override fun close() { open = false; transfer.fill(0) }
    private fun check() {
        check(open) { "media-cipher-channel-closed" }; context.checkActive(); requireOwner(); gate.requireCurrent()
    }
    override fun position(): Long { check(); return position }
    override fun position(newPosition: Long): SeekableByteChannel {
        check(); require(newPosition in 0..expectedExtent) { "media-cipher-position" }; position = newPosition; return this
    }
    override fun size(): Long {
        check(); val permit = gate.admit()
        try { require(file.size() == expectedExtent) { "media-cipher-extent" }; return expectedExtent }
        finally { permit.close() }
    }
    override fun read(destination: ByteBuffer): Int {
        check(); if (!destination.hasRemaining()) return 0
        if (position == expectedExtent) return -1
        val requested = minOf(destination.remaining().toLong(), transfer.size.toLong(), expectedExtent - position).toInt()
        var zero = 0
        while (true) {
            check()
            val permit = gate.admit()
            try {
                val count = file.readAt(position, transfer, 0, requested)
                require(count in 0..requested) { "media-cipher-truncated" }
                if (count == 0) {
                    require(++zero <= VaultMediaLimits.MAX_ZERO_PROGRESS) { "media-cipher-zero-progress" }
                    continue
                }
                check(); destination.put(transfer, 0, count); position += count
                return count
            } finally { transfer.fill(0); permit.close() }
        }
    }
    override fun write(source: ByteBuffer): Int = throw UnsupportedOperationException("media-read-only")
    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException("media-read-only")
}
