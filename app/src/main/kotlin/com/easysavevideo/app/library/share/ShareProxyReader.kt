package com.easysavevideo.app.library.share

import java.io.IOException

/** Actual byte/read-close policy used by Android's proxy callback, with no Android/file authority. */
internal class ShareProxyReader(
    bytes: Long,
    private val readAt: (data: ByteArray, fileOffset: Long, bufferOffset: Int, count: Int) -> Int,
    private val closeInput: () -> Boolean,
    private val allowed: () -> Boolean,
) {
    private val budget = ShareReadBudget(bytes)
    private var releaseAttempted = false
    private var released = false
    @Synchronized fun read(offset: Long, requested: Int, data: ByteArray): Int {
        active()
        val count = budget.reserve(offset, requested, data.size)
        var filled = 0
        while (filled < count) {
            active()
            val chunk = minOf(SharePolicy.BUFFER_BYTES, count - filled)
            val next = readAt(data, offset + filled, filled, chunk)
            if (next <= 0 || next > chunk) throw IOException("SHARE_READ_FAILED")
            filled += next
        }
        active()
        return filled
    }
    /** A callback/timeout cannot substitute for verified closure of the owned input. */
    @Synchronized fun release(): Boolean {
        if (releaseAttempted) return released
        releaseAttempted = true
        released = try { closeInput() } catch (_: Exception) { false }
        return released
    }
    private fun active() {
        if (releaseAttempted || !allowed()) throw IOException("SHARE_REVOKED")
    }
}
