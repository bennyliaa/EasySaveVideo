package com.easysavevideo.searxng

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Original, bounded JSON reader. No external parser or SearXNG implementation is copied. */
internal sealed interface Json {
    data class Obj(val fields: Map<String, Json>) : Json
    data class Arr(val values: List<Json>) : Json
    data class Str(val value: String) : Json
    data class Num(val raw: String) : Json
    data class Bool(val value: Boolean) : Json
    data object Nil : Json
}
internal fun Json.objectValue(): Map<String, Json> = (this as? Json.Obj)?.fields ?: invalidResponse()
internal fun Json.stringOrNull(): String? = (this as? Json.Str)?.value
internal fun Json.longOrNull(): Long? = (this as? Json.Num)?.raw?.let {
    try { BigDecimal(it).longValueExact() } catch (_: ArithmeticException) { null } catch (_: NumberFormatException) { null }
}
internal fun invalidResponse(): Nothing = throw SearxngFailure(SearxngError.INVALID_RESPONSE)

internal class StrictJson private constructor(private val input: String, private val check: () -> Unit) {
    private var offset = 0
    private var nodes = 0
    fun read(): Json {
        val result = value(0); whitespace()
        if (offset != input.length) invalidResponse()
        return result
    }
    private fun value(depth: Int): Json {
        check()
        if (depth > 16 || ++nodes > 20_000) invalidResponse()
        whitespace()
        if (offset == input.length) invalidResponse()
        return when (input[offset]) {
            '{' -> {
                offset++; whitespace(); val fields = linkedMapOf<String, Json>()
                if (take('}')) Json.Obj(fields) else {
                    do {
                        whitespace(); val key = string()
                        if (key.length > 128 || key in fields || fields.size >= 1024) invalidResponse()
                        whitespace(); expect(':'); fields[key] = value(depth + 1); whitespace()
                    } while (take(','))
                    expect('}'); Json.Obj(fields)
                }
            }
            '[' -> {
                offset++; whitespace(); val values = mutableListOf<Json>()
                if (take(']')) Json.Arr(values) else {
                    do { values += value(depth + 1); whitespace() } while (take(','))
                    expect(']'); Json.Arr(values)
                }
            }
            '"' -> Json.Str(string())
            't' -> { literal("true"); Json.Bool(true) }
            'f' -> { literal("false"); Json.Bool(false) }
            'n' -> { literal("null"); Json.Nil }
            '-', in '0'..'9' -> number()
            else -> invalidResponse()
        }
    }
    private fun string(): String {
        expect('"'); val out = StringBuilder()
        var checkedAt = offset
        while (offset < input.length) {
            if (offset - checkedAt >= 256) { check(); checkedAt = offset }
            val char = input[offset++]
            when {
                char == '"' -> return out.toString()
                char == '\\' -> {
                    if (offset == input.length) invalidResponse()
                    when (val escaped = input[offset++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val unit = unicode()
                            when {
                                unit.isHighSurrogate() -> {
                                    expect('\\'); expect('u'); val low = unicode()
                                    if (!low.isLowSurrogate()) invalidResponse()
                                    out.append(unit).append(low)
                                }
                                unit.isLowSurrogate() -> invalidResponse()
                                else -> out.append(unit)
                            }
                        }
                        else -> invalidResponse()
                    }
                }
                char.code < 32 -> invalidResponse()
                char.isHighSurrogate() -> {
                    if (offset == input.length || !input[offset].isLowSurrogate()) invalidResponse()
                    out.append(char).append(input[offset++])
                }
                char.isLowSurrogate() -> invalidResponse()
                else -> out.append(char)
            }
            if (out.length > 65_536) invalidResponse()
        }
        invalidResponse()
    }
    private fun unicode(): Char {
        if (input.length - offset < 4) invalidResponse()
        val digits = input.substring(offset, offset + 4)
        if (digits.any { it !in "0123456789abcdefABCDEF" }) invalidResponse()
        val number = digits.toIntOrNull(16) ?: invalidResponse()
        offset += 4; return number.toChar()
    }
    private fun number(): Json.Num {
        val start = offset; take('-')
        if (!take('0')) {
            if (offset == input.length || input[offset] !in '1'..'9') invalidResponse()
            while (offset < input.length && input[offset] in '0'..'9') { if (offset - start > 64) invalidResponse(); offset++ }
        }
        if (take('.')) {
            val digits = offset
            while (offset < input.length && input[offset] in '0'..'9') { if (offset - start > 64) invalidResponse(); offset++ }
            if (digits == offset) invalidResponse()
        }
        if (offset < input.length && input[offset] in "eE") {
            offset++; if (offset < input.length && input[offset] in "+-") offset++
            val digits = offset
            while (offset < input.length && input[offset] in '0'..'9') { if (offset - start > 64) invalidResponse(); offset++ }
            if (digits == offset) invalidResponse()
        }
        if (offset - start > 64) invalidResponse()
        return Json.Num(input.substring(start, offset))
    }
    private fun whitespace() { while (offset < input.length && input[offset] in " \r\n\t") { if (offset % 256 == 0) check(); offset++ } }
    private fun literal(text: String) { if (!input.startsWith(text, offset)) invalidResponse(); offset += text.length }
    private fun take(char: Char): Boolean = if (offset < input.length && input[offset] == char) { offset++; true } else false
    private fun expect(char: Char) { if (!take(char)) invalidResponse() }
    companion object {
        fun read(bytes: ByteArray, check: () -> Unit = {}): Json {
            check()
            if (bytes.size > 2 * 1024 * 1024) throw SearxngFailure(SearxngError.RESPONSE_TOO_LARGE)
            val decoded = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { invalidResponse() }
            check()
            return StrictJson(decoded, check).read()
        }
        fun write(value: Json): String = when (value) {
            is Json.Obj -> value.fields.entries.joinToString(",", "{", "}") { write(Json.Str(it.key)) + ":" + write(it.value) }
            is Json.Arr -> value.values.joinToString(",", "[", "]", transform = ::write)
            is Json.Str -> buildString {
                append('"'); value.value.forEach { char ->
                    when (char) {
                        '"' -> append("\\\""); '\\' -> append("\\\\")
                        '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                        else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
                    }
                }; append('"')
            }
            is Json.Num -> value.raw
            is Json.Bool -> value.value.toString()
            Json.Nil -> "null"
        }
    }
}
