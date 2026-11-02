package com.easysavevideo.commons

import java.security.MessageDigest
import java.util.Base64

internal object CommonsCursor {
    private val allowedKeys = setOf("continue", "gsroffset", "vicontinue")
    fun queryKey(request: CommonsSearchRequest, pageSize: Int): String {
        val framed = listOf(request.query, request.language, pageSize.toString(), request.safeSearchRequired.toString(), request.queryIdentity.orEmpty(), CommonsIdentity.VERSION)
            .joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(framed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    /** Preserve every returned supported continuation key and its scalar value verbatim. No +offset. */
    fun fields(value: Json): Map<String, Json> {
        val fields = value.objectValue()
        if (fields.isEmpty() || fields.keys.any { it !in allowedKeys }) invalidResponse()
        for ((_, item) in fields) {
            val scalar = when (item) {
                is Json.Str -> item.value
                is Json.Num -> item.raw
                else -> invalidResponse()
            }
            if (scalar.length !in 1..2048 || scalar.any { it.isISOControl() }) invalidResponse()
        }
        return fields.toMap()
    }
    fun encode(fields: Map<String, Json>, queryKey: String, expires: Long): String {
        val json = Json.Obj(linkedMapOf("v" to Json.Num("1"), "q" to Json.Str(queryKey),
            "expires" to Json.Num(expires.toString()), "fields" to Json.Obj(fields)))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(StrictJson.write(json).toByteArray(Charsets.UTF_8))
            .also { if (it.length > 8192) invalidResponse() }
    }
    fun decode(token: String, queryKey: String, now: Long): Map<String, String> = try {
        if (token.length !in 1..8192 || !token.matches(Regex("[A-Za-z0-9_-]+"))) fail()
        val objectValue = StrictJson.read(Base64.getUrlDecoder().decode(token)).objectValue()
        if (objectValue.keys != setOf("v", "q", "expires", "fields") || objectValue["v"]?.longOrNull() != 1L ||
            objectValue["q"]?.stringOrNull() != queryKey || (objectValue["expires"]?.longOrNull() ?: 0) <= now) fail()
        fields(objectValue["fields"] ?: fail()).mapValues { (_, value) ->
            when (value) { is Json.Str -> value.value; is Json.Num -> value.raw; else -> fail() }
        }
    } catch (_: Exception) { fail() }
    private fun fail(): Nothing = throw CommonsFailure(CommonsError.INVALID_CURSOR)
}
