package com.easysavevideo.app.library.share

import java.io.IOException

/** Bounded random-access reads for virtual read-only descriptors; no path or descriptor authority. */
internal class ShareReadBudget(private val bytes: Long) {
    private var remaining = bytes * 8
    private var calls = 0
    init { require(bytes in 1..SharePolicy.MAX_COPY_BYTES) }
    @Synchronized fun reserve(offset: Long, requested: Int, bufferBytes: Int): Int {
        if (offset < 0 || requested < 0 || requested > SharePolicy.MAX_PROXY_REQUEST_BYTES || bufferBytes < 0 || requested > bufferBytes) throw IOException("SHARE_INVALID_RANGE")
        if (requested == 0 || offset >= bytes) return 0
        if (++calls > MAX_READ_CALLS || remaining <= 0) throw IOException("SHARE_READ_LIMIT")
        val count = minOf(requested.toLong(), bytes - offset).toInt()
        if (count > remaining) throw IOException("SHARE_READ_LIMIT")
        remaining -= count
        return count
    }
    companion object { const val MAX_READ_CALLS = 65_536 }
}
