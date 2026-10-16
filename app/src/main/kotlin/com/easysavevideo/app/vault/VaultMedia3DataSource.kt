package com.easysavevideo.app.vault

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** A non-address placeholder is required by Media3. Authority comes only from [read]. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class VaultMedia3DataSource(private val read: VaultPlaybackReadPort) : DataSource {
    internal class Factory(private val read: VaultPlaybackReadPort) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultMedia3DataSource(read)
    }
    private val transport = VaultPlaybackTransport(read)
    private val listeners = mutableListOf<TransferListener>()
    private var spec: DataSpec? = null
    private var started = false

    override fun addTransferListener(transferListener: TransferListener) {
        if (listeners.none { it === transferListener }) listeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (spec != null) throw safePlaybackIo("ALREADY_OPEN")
        VaultPlaybackDataSpecPolicy.requireAllowed(dataSpec.uri == Uri.EMPTY, dataSpec.httpMethod,
            dataSpec.httpBody != null, dataSpec.key != null, dataSpec.customData != null,
            dataSpec.uriPositionOffset, dataSpec.httpRequestHeaders, dataSpec.flags)
        try {
            listeners.forEach { it.onTransferInitializing(this, dataSpec, false) }
            val length = transport.open(dataSpec.position, dataSpec.length)
            spec = dataSpec
            started = true
            listeners.forEach { it.onTransferStart(this, dataSpec, false) }
            return length
        } catch (error: Exception) {
            // Partial open stays owned until mandatory close. Never publish a cause/path/URI.
            throw safePlaybackError(error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        val count = transport.read(buffer, offset, length)
        if (count > 0) spec?.let { current -> listeners.forEach { it.onBytesTransferred(this, current, false, count) } }
        count
    } catch (error: Exception) { throw safePlaybackError(error) }

    override fun getUri(): Uri? = if (transport.isOpen) Uri.EMPTY else null
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
    override fun close() {
        val current = spec
        val notify = started
        started = false; spec = null
        try {
            transport.close()
            if (notify && current != null) listeners.forEach { it.onTransferEnd(this, current, false) }
        } catch (error: Exception) { throw safePlaybackError(error) }
    }
}

/** Exact 1.11.1 ProgressiveMediaPeriod technical fields; none is routed to network/cache. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object VaultPlaybackDataSpecPolicy {
    fun requireAllowed(emptyUri: Boolean, method: Int, hasBody: Boolean, hasKey: Boolean,
        hasCustomData: Boolean, uriPositionOffset: Long, headers: Map<String, String>, flags: Int) {
        val ordinary = headers.isEmpty() && flags == 0
        val progressive = headers == mapOf("Icy-MetaData" to "1") &&
            flags == (DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
        if (!emptyUri || method != DataSpec.HTTP_METHOD_GET || hasBody || hasKey || hasCustomData ||
            uriPositionOffset != 0L || (!ordinary && !progressive)) throw safePlaybackIo("SPEC_REJECTED")
    }
}

/** Production transport logic, separately executable without an Android Uri implementation. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class VaultPlaybackTransport(private val read: VaultPlaybackReadPort) {
    private var opened = false
    private var position = 0L
    private var remaining = 0L
    val isOpen: Boolean get() = opened

    fun open(position: Long, requestedLength: Long): Long {
        if (opened) throw safePlaybackIo("ALREADY_OPEN")
        read.requireCurrent()
        val size = read.authenticatedSize
        if (position < 0 || requestedLength != C.LENGTH_UNSET.toLong() && requestedLength <= 0 ||
            size !in 1..VaultMediaLimits.MAX_BYTES) throw safePlaybackIo("RANGE_REJECTED")
        if (position > size) throw DataSourceException("VAULT_POSITION_OUT_OF_RANGE",
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        read.transportOpened() // Same runtime gate excludes every second concurrently open source.
        opened = true
        this.position = position
        val available = size - position // Never add caller-controlled position + length.
        remaining = if (requestedLength == C.LENGTH_UNSET.toLong()) available else minOf(available, requestedLength)
        return if (requestedLength == C.LENGTH_UNSET.toLong()) available else requestedLength
    }

    fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (!opened) throw safePlaybackIo("NOT_OPEN")
        if (offset < 0 || length < 0 || offset > destination.size - length) throw safePlaybackIo("RANGE_REJECTED")
        read.requireCurrent() // Even zero-length/EOF/cache hits must not revive a revoked capability.
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = minOf(length.toLong(), remaining, VaultMediaLimits.BUFFER_BYTES.toLong()).toInt()
        val count = read.readAt(position, destination, offset, requested)
        if (count !in 1..requested) throw safePlaybackIo("READ_FAILED")
        position += count
        remaining -= count
        return count
    }

    fun close() {
        if (!opened) return
        opened = false; position = 0; remaining = 0
        read.transportClosed() // Parser close does not close the shared reader or file.
    }
}

internal fun safePlaybackIo(code: String): IOException = IOException("VAULT_$code")
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun safePlaybackError(error: Exception): IOException =
    if (error is DataSourceException && error.reason == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        DataSourceException("VAULT_POSITION_OUT_OF_RANGE", PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
    else safePlaybackIo("READ_FAILED")
