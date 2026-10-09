package com.easysavevideo.app.discovery.network

import com.easysavevideo.verification.VerificationError
import com.easysavevideo.verification.VerificationFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class StrictVerificationHttpTest {
    @Test fun fixedLengthCloseDelimitedAndChunkedBodiesRoundTripWithoutTransformation() {
        val body = "{\"name\":\"é\"}".toByteArray(Charsets.UTF_8)
        val fixed = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
        val close = "HTTP/1.0 200 OK\r\n\r\n".toByteArray() + body
        val chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n${body.size.toString(16)}\r\n".toByteArray() +
            body + "\r\n0\r\nX-Trace: inert\r\n\r\n".toByteArray()
        for (wire in listOf(fixed, close, chunked)) {
            val response = parse(wire)
            assertArrayEquals(body, response.body)
            assertEquals(200, response.status)
            assertFalse(response.headers.containsKey("x-trace"))
        }
    }
    @Test fun statusAndHeadersRemainAvailableWithoutAutomaticRetryOrAuthentication() {
        for (status in listOf(401, 403, 429, 500, 503)) {
            val response = parse("HTTP/1.1 $status Error\r\nRetry-After: 9999999999999999999999999\r\nWWW-Authenticate: Basic\r\nContent-Length: 0\r\n\r\n")
            assertEquals(status, response.status)
            assertEquals(listOf("9999999999999999999999999"), response.headers["retry-after"])
        }
    }
    @Test fun rejectsMalformedStatusInterimUpgradeAndBareLf() {
        for (wire in listOf("HTTP/2 200 OK\r\n\r\n", "HTTP/1.1 200\r\n\r\n", "HTTP/1.1 099 Weird\r\n\r\n",
            "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\n\r\n", "HTTP/1.1 101 Switching Protocols\r\n\r\n",
            "HTTP/1.1 600 Weird\r\n\r\n", "HTTP/1.1 200 OK\n\n", "HTTP/1.1 200 OK\rX")) invalid(wire)
    }
    @Test fun rejectsFoldingControlCharactersWhitespaceBeforeColonAndDuplicateSingletons() {
        for (header in listOf(" Content-Length: 2", "\tFolded: value", "Content-Length : 2", "Bad Name: value",
            "Header: bad\u0001", "Header: bad\u007f", "Header: bad\u0080", "Header: bad\u0000", "HeaderWithoutColon",
            "Content-Length: 2\r\ncontent-length: 2", "Content-Encoding: identity\r\nCONTENT-ENCODING: identity",
            "Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked", "Content-Type: application/json\r\nContent-Type: text/html",
            "Retry-After: 1\r\nRetry-After: 2", "Cache-Control: max-age=5\r\nCache-Control: no-store"))
            invalid("HTTP/1.1 200 OK\r\n$header\r\n\r\n{}")
    }
    @Test fun rejectsConflictingOrMalformedFramingAndCompression() {
        for (header in listOf("Content-Length: 2\r\nTransfer-Encoding: chunked", "Transfer-Encoding: gzip, chunked",
            "Transfer-Encoding: chunked, chunked", "Transfer-Encoding: identity", "Transfer-Encoding:",
            "Content-Length: +2", "Content-Length: -2", "Content-Length: 2, 2", "Content-Length:",
            "Content-Length: 2.0", "Content-Encoding: gzip", "Content-Encoding: br", "Content-Encoding: identity, gzip"))
            invalid("HTTP/1.1 200 OK\r\n$header\r\n\r\n{}")
        invalid("HTTP/1.0 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
    }
    @Test fun rejectsTruncatedFixedOrChunkedBodiesAndUnexpectedBytesAfterBody() {
        for (body in listOf("", "x", "xyz")) invalid("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n$body")
        for (body in listOf("", "2\r\nx", "2\r\nxy", "2\r\nxy\r", "2\r\nxy\rX", "2\r\nxy\r\n",
            "2\r\nxy\r\n0\r\n", "0\r\n\r\nextra", "0\r\n")) invalid(CHUNKS + body)
    }
    @Test fun rejectsInvalidChunkSizeExtensionsAndUnboundedNumericClaims() {
        for (size in listOf("", "+2", "-2", " 2", "2 ", "0x2", "g", "2;x=y", "00000000000000000"))
            invalid(CHUNKS + "$size\r\nxy\r\n0\r\n\r\n")
        tooLarge(CHUNKS + "7fffffffffffffff\r\n")
        tooLarge(CHUNKS + "ffffffffffffffff\r\n")
        tooLarge("HTTP/1.1 200 OK\r\nContent-Length: 99999999999999999999999999\r\n\r\n")
    }
    @Test fun rejectsFramingAndMetadataOverridesInTrailersAndConnectionNominatedTrailers() {
        for (name in listOf("Content-Length", "Transfer-Encoding", "Content-Encoding", "Content-Type", "Retry-After",
            "Cache-Control", "Cookie", "Set-Cookie", "Location", "Host", "Authorization", "Connection"))
            invalid(CHUNKS + "0\r\n$name: value\r\n\r\n")
        invalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: X-Private\r\n\r\n0\r\nX-Private: value\r\n\r\n")
    }
    @Test fun headerBytesAndFieldsIncludeRepeatedHeadersAndTrailers() {
        parse("HTTP/1.1 200 OK\r\n" + "X: value\r\n".repeat(100) + "\r\n")
        invalid("HTTP/1.1 200 OK\r\n" + "X: value\r\n".repeat(101) + "\r\n")
        invalid("HTTP/1.1 200 OK\r\nX: " + "a".repeat(32_768) + "\r\n\r\n")
        invalid(CHUNKS + "0\r\n" + "X: value\r\n".repeat(100) + "\r\n")
        val prefix = "HTTP/1.1 200 OK\r\nX: "
        val suffix = "\r\n\r\n"
        parse(prefix + "a".repeat(32_768 - prefix.length - suffix.length) + suffix)
        invalid(prefix + "a".repeat(32_769 - prefix.length - suffix.length) + suffix)
    }
    @Test fun decodedBodyBudgetIsIndependentOfFiniteFramingBudget() {
        val wire = CHUNKS + "1\r\nx\r\n0\r\n\r\n"
        assertEquals("x", parse(wire, 1).body.toString(Charsets.UTF_8))
        val body = "x".repeat(32_768)
        assertEquals(32_768, parse("HTTP/1.1 200 OK\r\nContent-Length: 32768\r\n\r\n$body").body.size)
        tooLarge("HTTP/1.1 200 OK\r\nContent-Length: 32769\r\n\r\n")
        tooLarge("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n0123456789", 9)
        tooLarge("HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(100), 40)
        tooLarge(CHUNKS + "1\r\nx\r\n".repeat(20_000) + "0\r\n\r\n")
    }
    @Test fun budgetProbeReadsAtMostOneExtraBodyByteWithoutReadAhead() {
        val prefix = "HTTP/1.1 200 OK\r\n\r\n"
        val wire = (prefix + "x".repeat(1_000)).toByteArray()
        val consumed = AtomicInteger()
        val input = object : InputStream() {
            override fun read(): Int = if (consumed.get() >= wire.size) -1 else wire[consumed.getAndIncrement()].toInt() and 255
        }
        expect(VerificationError.RESPONSE_TOO_LARGE) { StrictVerificationHttp.read(input, 64) {} }
        assertEquals(prefix.length + 65, consumed.get())
    }
    @Test fun zeroReturningStreamIsNotAnUnboundedSpinOrInventedEof() {
        val prefix = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n".toByteArray())
        val input = object : InputStream() {
            override fun read() = prefix.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        }
        expect(VerificationError.INVALID_RESPONSE) { StrictVerificationHttp.read(input, 32_768) {} }
    }
    @Test fun everyReadIsGuardedAndCancellationCannotBecomeSuccessfulEof() {
        val wire = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray()
        val input = object : ByteArrayInputStream(wire) {
            var ended = false
            override fun read(): Int { val value = super.read(); if (value < 0) ended = true; return value }
        }
        expect(VerificationError.CANCELLED) { StrictVerificationHttp.read(input, 32_768) {
            if (input.ended) throw VerificationFailure(VerificationError.CANCELLED)
        } }
    }
    @Test fun invalidParserBudgetIsRejectedBeforeAnyRead() {
        for (maximum in listOf(-1L, 0L, 32_769L, Long.MAX_VALUE)) {
            val input = object : InputStream() { override fun read(): Int = error("Unexpected I/O") }
            expect(VerificationError.INVALID_REQUEST) { StrictVerificationHttp.read(input, maximum) {} }
        }
    }
    @Test fun cumulativeChunksCannotExceedDecodedBodyLimit() {
        tooLarge(CHUNKS + "2\r\n{}\r\n1\r\nx\r\n0\r\n\r\n", 2)
    }
    @Test fun unexpectedBodiesOnBodylessResponsesAreRejected() {
        for (status in listOf(204, 205)) {
            parse("HTTP/1.1 $status Empty\r\nContent-Length: 0\r\n\r\n")
            invalid("HTTP/1.1 $status Empty\r\nContent-Length: 1\r\n\r\nx")
            invalid("HTTP/1.1 $status Empty\r\n\r\nx")
        }
    }
    private fun parse(wire: String, maximum: Long = 32_768) = parse(wire.toByteArray(Charsets.ISO_8859_1), maximum)
    private fun parse(wire: ByteArray, maximum: Long = 32_768) = StrictVerificationHttp.read(ByteArrayInputStream(wire), maximum) {}
    private fun invalid(wire: String) = expect(VerificationError.INVALID_RESPONSE) { parse(wire) }
    private fun tooLarge(wire: String, maximum: Long = 32_768) = expect(VerificationError.RESPONSE_TOO_LARGE) { parse(wire, maximum) }
    private fun expect(code: VerificationError, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (failure: VerificationFailure) { assertEquals(code, failure.code) }
    }
    companion object { private const val CHUNKS = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" }
}
