package com.easysavevideo.app.library.share

/** Pure bounds and URI path policy. Only issued random capabilities have authority. */
internal object SharePolicy {
    const val MAX_COPIES = 4
    const val MAX_COPY_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_TRANSFERS = 2
    const val MAX_OPENS = 8
    const val TTL_MILLIS = 10L * 60 * 1000
    const val BUFFER_BYTES = 64 * 1024
    const val MAX_PROXY_REQUEST_BYTES = 128 * 1024
    const val MAX_PROXY_ISSUANCES = 512
    private val token = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun validToken(value: String): Boolean = token.matches(value)
    fun admit(count: Int, reserved: Long, bytes: Long): Boolean = count in 0 until MAX_COPIES &&
        reserved in 0..MAX_TOTAL_BYTES && bytes in 1..MAX_COPY_BYTES && bytes <= MAX_TOTAL_BYTES - reserved
    fun canOpen(ready: Boolean, revoked: Boolean, now: Long, expires: Long, opens: Int, active: Int): Boolean =
        ready && !revoked && now >= 0 && now < expires && opens in 0 until MAX_OPENS && active in 0 until MAX_TRANSFERS
    fun readOnly(mode: String): Boolean = mode == "r"
}
