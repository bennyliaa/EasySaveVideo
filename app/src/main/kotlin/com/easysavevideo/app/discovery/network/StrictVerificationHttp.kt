package com.easysavevideo.app.discovery.network

import com.easysavevideo.verification.VerificationError
import com.easysavevideo.verification.VerificationFailure
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/** No buffering/read-ahead, decompression, retry, protocol upgrade, or connection reuse. */
internal object StrictVerificationHttp {
    internal data class Response(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
        override fun toString(): String = "VerificationWireResponse(status=$status, data=[redacted])"
    }

    fun read(input: InputStream, maximum: Long, check: () -> Unit): Response {
        if (maximum !in 1..32_768L) throw VerificationFailure(VerificationError.INVALID_REQUEST)
        return Reader(input, maximum, check).response()
    }

    private class Reader(private val input: InputStream, private val maximum: Long, private val check: () -> Unit) {
        // The protocol limit counts decoded body bytes. Bound framing separately as well, so
        // pathological chunk fragmentation cannot buy unbounded network work or memory.
        private val maximumWire = maximum + 65_536L
        private var wireBytes = 0L
        private var bodyBytes = 0L
        private var headerBytes = 0
        private var headerFields = 0
        private val headers = linkedMapOf<String, MutableList<String>>()

        fun response(): Response {
            val statusLine = line(8_192, header = true)
            if (!statusLine.matches(Regex("HTTP/1\\.[01] [1-5][0-9]{2} [\\x20-\\x7e]*"))) invalid()
            val status = statusLine.substring(9, 12).toInt()
            if (status < 200) invalid()
            readHeaders(trailers = false)
            if (status in 300..399) throw VerificationFailure(VerificationError.REDIRECT_REJECTED)
            val encoding = singleton("content-encoding")
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) invalid()
            val transfer = singleton("transfer-encoding")
            val lengthText = singleton("content-length")
            val length = lengthText?.let {
                if (it.isEmpty() || it.any { character -> character !in '0'..'9' }) invalid()
                it.toLongOrNull() ?: tooLarge()
            }
            if (transfer != null && (length != null || !transfer.equals("chunked", ignoreCase = true) ||
                    !statusLine.startsWith("HTTP/1.1 "))) invalid()
            if (length != null && length > maximum) tooLarge()
            val body = ByteArrayOutputStream()
            when {
                status == 204 || status == 205 || status == 304 -> {
                    if (transfer != null || length != null && length != 0L) invalid()
                }
                transfer != null -> chunks(body)
                length != null -> copyExactly(length, body)
                else -> copyUntilEof(body)
            }
            // Connection: close is explicitly supplied. Reject extra bytes after framed bodies.
            if ((transfer != null || length != null || status in setOf(204, 205, 304)) && octet() != -1) invalid()
            check()
            return Response(status, headers.mapValues { it.value.toList() }, body.toByteArray())
        }

        private fun readHeaders(trailers: Boolean) {
            while (true) {
                val line = line(32_768, header = true)
                if (line.isEmpty()) return
                if (++headerFields > 100 || line[0] == ' ' || line[0] == '\t') invalid()
                val colon = line.indexOf(':')
                if (colon <= 0) invalid()
                val name = line.substring(0, colon)
                if (name.length > 128 || !name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) invalid()
                val value = line.substring(colon + 1).trim(' ', '\t')
                if (value.any { it != '\t' && it.code !in 32..126 }) invalid()
                val key = name.lowercase(Locale.ROOT)
                if (trailers) {
                    if (key in FORBIDDEN_TRAILERS || key in connectionFields()) invalid()
                    // Trailers cannot replace status or metadata headers.
                } else {
                    val values = headers.getOrPut(key) { mutableListOf() }
                    if (key in SINGLETONS && values.isNotEmpty()) invalid()
                    values.add(value)
                }
            }
        }
        private fun connectionFields(): Set<String> = headers["connection"].orEmpty()
            .flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        private fun singleton(name: String): String? {
            val values = headers[name] ?: return null
            if (values.size != 1) invalid()
            return values.single()
        }
        private fun chunks(output: ByteArrayOutputStream) {
            while (true) {
                val size = line(1_024, header = false)
                // This endpoint needs no chunk extensions; reject rather than leniently parse.
                if (size.isEmpty() || size.length > 16 || size.any { it !in "0123456789abcdefABCDEF" }) invalid()
                val count = size.toLongOrNull(16) ?: tooLarge()
                if (count > maximum - bodyBytes) tooLarge()
                if (count == 0L) { readHeaders(trailers = true); return }
                copyExactly(count, output)
                if (octet() != 13 || octet() != 10) invalid()
            }
        }
        private fun copyExactly(length: Long, output: ByteArrayOutputStream) {
            var remaining = length
            val buffer = ByteArray(8_192)
            while (remaining > 0) {
                val count = bytes(buffer, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) invalid()
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        private fun copyUntilEof(output: ByteArrayOutputStream) {
            val buffer = ByteArray(8_192)
            while (true) {
                val count = bytes(buffer, buffer.size)
                if (count < 0) return
                output.write(buffer, 0, count)
            }
        }
        private fun line(limit: Int, header: Boolean): String {
            val output = StringBuilder()
            while (true) {
                val value = octet()
                if (value < 0) invalid()
                if (header && ++headerBytes > 32_768) invalid()
                if (value == 13) {
                    if (octet() != 10) invalid()
                    if (header && ++headerBytes > 32_768) invalid()
                    return output.toString()
                }
                if (value == 10 || value == 0 || output.length >= limit) invalid()
                output.append(value.toChar())
            }
        }
        private fun octet(): Int {
            check()
            val value = input.read()
            check()
            if (value !in -1..255) invalid()
            if (value >= 0 && ++wireBytes > maximumWire) tooLarge()
            return value
        }
        private fun bytes(buffer: ByteArray, requested: Int): Int {
            check()
            // Only one excess-byte probe distinguishes EOF from a false/missing length.
            val allowed = minOf(requested.toLong(), (maximum - bodyBytes + 1).coerceAtLeast(1),
                (maximumWire - wireBytes + 1).coerceAtLeast(1)).toInt()
            val count = input.read(buffer, 0, allowed)
            check()
            if (count < -1 || count == 0 || count > allowed) invalid()
            if (count > 0) {
                wireBytes += count
                bodyBytes += count
                if (wireBytes > maximumWire || bodyBytes > maximum) tooLarge()
            }
            return count
        }
    }
    private val SINGLETONS = setOf("content-length", "content-type", "content-encoding", "transfer-encoding", "retry-after", "cache-control")
    private val FORBIDDEN_TRAILERS = SINGLETONS + setOf("host", "connection", "trailer", "authorization", "proxy-authorization",
        "cookie", "set-cookie", "location", "www-authenticate", "proxy-authenticate", "content-range")
    private fun invalid(): Nothing = throw VerificationFailure(VerificationError.INVALID_RESPONSE)
    private fun tooLarge(): Nothing = throw VerificationFailure(VerificationError.RESPONSE_TOO_LARGE)
}
