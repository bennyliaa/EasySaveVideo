package com.easysavevideo.commons

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class StrictJsonUnicodeTest {
    @Test fun fourAsciiHexDigitsDecodeControlsBmpAndMixedCase() {
        for ((digits, expected) in listOf(
            "0000" to '\u0000', "0009" to '\t', "001F" to '\u001f', "0022" to '"',
            "005C" to '\\', "0041" to 'A', "00aF" to '\u00af', "4e2D" to '中',
            "d7Ff" to '\ud7ff', "E000" to '\ue000', "fFfF" to '\uffff',
        )) assertEquals(Json.Str(expected.toString()), read("\"\\u$digits\""))
        // A fifth character is ordinary string content, not part of the escape.
        assertEquals(Json.Str("AF"), read("\"\\u0041F\""))
    }

    @Test fun signedAndNonAsciiDigitsAreRejectedInEveryPosition() {
        for (digits in listOf("+041", "-001", "+000", "-000", "0x41", " 041"))
            invalid("\"\\u$digits\"")
        for (bad in listOf('+', '-', 'g', 'G', ' ', '\t', '０', 'Ａ', '١')) {
            for (position in 0..3) {
                val digits = "0041".toCharArray().also { it[position] = bad }.concatToString()
                invalid("\"\\u$digits\"")
            }
        }
    }

    @Test fun incompleteEscapesFailAtEndOfInputAndAtClosingQuote() {
        for (digits in listOf("", "0", "00", "004")) {
            invalid("\"\\u$digits")
            invalid("\"\\u$digits\"")
        }
        invalid("\"\\U0041\"")
    }

    @Test fun surrogatePairsKeepTheirExistingStrictContract() {
        assertEquals(Json.Str("😃"), read("\"\\uD83d\\ude03\""))
        assertEquals(Json.Str("\uD800\uDC00\uDBFF\uDFFF"), read("\"\\uD800\\uDC00\\uDBFF\\uDFFF\""))
        assertEquals(Json.Str("😃"), read("\"😃\""))
        for (body in listOf("\\uD800", "\\uDC00", "\\uDC00\\uD800", "\\uD800\\u0041",
            "\\uD800\\uD800", "\\uD800\\u+001", "\\uD800\\uＤC00", "\\uD800x", "\\uD800\\uDC0"))
            invalid("\"$body\"")
    }

    @Test fun nestedValuesAndObjectKeysUseTheSameValidation() {
        val parsed = read("""{"\u006b":[{"value":"\u4E2D\uD83D\uDE03"}]}""")
        assertEquals(Json.Obj(mapOf("k" to Json.Arr(listOf(Json.Obj(mapOf("value" to Json.Str("中😃"))))))), parsed)
        for (digits in listOf("+041", "-001", "００４１")) {
            invalid("""{"outer":[{"value":"\u$digits"}]}""")
            invalid("""{"outer":[{"\u$digits":true}]}""")
        }
        invalid("""{"A":1,"\u0041":2}""")
    }

    @Test fun decodedStringAndNestingLimitsRemainBounded() {
        val maximum = "\\u0041".repeat(65_536)
        assertEquals(Json.Str("A".repeat(65_536)), read("\"$maximum\""))
        invalid("\"${maximum}\\u0041\"")
        read("[".repeat(16) + "\"\\u0041\"" + "]".repeat(16))
        invalid("[".repeat(17) + "\"\\u0041\"" + "]".repeat(17))
    }

    @Test fun byteBudgetAndCancellationAreUnchanged() {
        val prefix = "\"\\u0041\""
        val bounded = prefix + " ".repeat(2 * 1024 * 1024 - prefix.length)
        assertEquals(Json.Str("A"), read(bounded))
        fails(CommonsError.RESPONSE_TOO_LARGE) { read(bounded + " ") }
        var checks = 0
        fails(CommonsError.CANCELLED) {
            StrictJson.read("""{"nested":["\u0041"]}""".toByteArray()) {
                if (++checks == 3) throw CommonsFailure(CommonsError.CANCELLED)
            }
        }
        assertEquals(3, checks)
    }

    @Test fun malformedNestedStreamIsClosedAndNeverCachedBeforeRetry() {
        for (digits in listOf("+041", "-001", "００４１")) {
            val bytes = envelope(digits)
            val stream = FragmentedStream(bytes)
            val transport = TestTransport(Reply(stream = stream), Reply(envelope("0041")))
            val client = provider(transport)
            val failure = fails(CommonsError.INVALID_RESPONSE) {
                client.search(request, operation(maxBytes = bytes.size.toLong()))
            }
            assertFalse(failure.structureFailure)
            assertEquals("INVALID_RESPONSE", failure.message)
            assertNull(failure.cause)
            assertTrue(stream.closed)
            assertEquals(bytes.size, stream.bytesRead)
            assertEquals(1, transport.cancellations.get())
            val page = client.search(request, operation())
            assertTrue(page.results.isEmpty())
            assertTrue(page.exhausted)
            assertFalse(page.fromCache)
            assertEquals(2, transport.requests.size)
            assertEquals(2, transport.cancellations.get())
        }
    }

    @Test fun fragmentedValidStreamRespectsExactByteBudgetAndCloses() {
        val bytes = envelope("4e2D\\uD83d\\ude03")
        for (budget in listOf(bytes.size.toLong(), bytes.size.toLong() - 1)) {
            val stream = FragmentedStream(bytes)
            val transport = TestTransport(Reply(stream = stream))
            if (budget == bytes.size.toLong()) {
                val page = provider(transport).search(request, operation(maxBytes = budget))
                assertEquals(budget, page.responseBytes)
                assertTrue(page.results.isEmpty())
                assertTrue(page.exhausted)
            } else {
                fails(CommonsError.RESPONSE_TOO_LARGE) {
                    provider(transport).search(request, operation(maxBytes = budget))
                }
            }
            assertTrue(stream.closed)
            assertTrue(stream.bytesRead <= budget + 1)
            assertEquals(1, transport.cancellations.get())
        }
    }

    private fun read(input: String) = StrictJson.read(input.toByteArray(Charsets.UTF_8))
    private fun invalid(input: String) { fails(CommonsError.INVALID_RESPONSE) { read(input) } }
    private fun envelope(digits: String) =
        """{"batchcomplete":true,"extra":[{"value":"\u$digits"}]}""".toByteArray(Charsets.UTF_8)

    private class FragmentedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var bytesRead = 0
        var closed = false
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, 1)).also { if (it > 0) bytesRead += it }
        override fun close() { closed = true; super.close() }
    }
}
