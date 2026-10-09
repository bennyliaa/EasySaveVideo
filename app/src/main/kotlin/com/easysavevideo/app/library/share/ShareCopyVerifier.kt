package com.easysavevideo.app.library.share

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal object ShareCopyVerifier {
    /** Reads full EOF including one-byte overrun. No byte-array artifact allocation. */
    fun verify(input: InputStream, expectedBytes: Long, sha256: String, cancelled: () -> Boolean = { false }) {
        require(expectedBytes in 1..SharePolicy.MAX_COPY_BYTES && sha256.matches(Regex("[a-f0-9]{64}")))
        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(SharePolicy.BUFFER_BYTES)
        while (true) {
            if (cancelled()) throw IOException("SHARE_CANCELLED")
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), expectedBytes - total + 1).toInt())
            if (count == -1) break
            if (count <= 0 || count > expectedBytes - total) throw IOException("SHARE_COPY_MISMATCH")
            digest.update(buffer, 0, count); total += count
        }
        if (cancelled()) throw IOException("SHARE_CANCELLED")
        if (total != expectedBytes || digest.digest().joinToString("") { "%02x".format(it) } != sha256) throw IOException("SHARE_COPY_MISMATCH")
    }
}
