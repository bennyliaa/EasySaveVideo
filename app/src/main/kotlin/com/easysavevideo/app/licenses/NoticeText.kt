package com.easysavevideo.app.licenses

import java.io.Reader

/** Offline notice growth remains bounded before allocation, including stalled readers. */
internal fun readNoticeText(reader: Reader, limit: Int = 1024 * 1024): String {
    require(limit > 0)
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        val count = reader.read(buffer, 0, minOf(buffer.size, limit - result.length + 1))
        if (count == -1) return result.toString()
        check(count > 0 && result.length + count <= limit) { "NOTICE_TEXT_UNAVAILABLE" }
        result.append(buffer, 0, count)
    }
}
