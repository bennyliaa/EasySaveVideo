package com.easysavevideo.verification

import java.io.InputStream
import java.net.URI

/**
 * create() performs no I/O; execute() performs one anonymous POST to the exact configured endpoint.
 * A production transport pins all-public DNS/TLS, bounds bytes and monotonic/wall deadlines,
 * rejects ambient credentials, proxy, cookies, compression and redirects, and never retries.
 * cancel() must be prompt, non-blocking, thread-safe and idempotent. Retain physical admission
 * until the worker and all resource cleanup actually exit. Never publish a live network stream.
 */
fun interface VerificationTransport { fun create(request: VerificationHttpRequest): VerificationHttpCall }
interface VerificationHttpCall {
    fun execute(): VerificationHttpResponse
    fun cancel()
}
class VerificationHttpRequest(
    val endpoint: VerificationEndpoint,
    val url: URI,
    headers: Map<String, String>,
    body: ByteArray,
    val deadlineEpochMillis: Long,
    val connectTimeoutMillis: Int,
    val maxBodyBytes: Long,
) {
    val headers: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(headers))
    private val bytes = body.copyOf()
    val body: ByteArray get() = bytes.copyOf()
    init {
        VerificationUrlPolicy.requireApi(endpoint, url)
        require(bytes.size in 1..8192) { "Invalid request size" }
    }
    override fun toString(): String = "VerificationHttpRequest([redacted])"
}
class VerificationHttpResponse(
    val status: Int,
    val url: URI,
    val headers: Map<String, List<String>>,
    val body: InputStream,
) : AutoCloseable {
    override fun close() { body.close() }
    override fun toString(): String = "VerificationHttpResponse(status=$status)"
}
