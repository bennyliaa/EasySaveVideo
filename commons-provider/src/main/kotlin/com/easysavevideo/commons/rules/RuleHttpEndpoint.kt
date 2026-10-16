package com.easysavevideo.commons.rules

import com.easysavevideo.commons.isWellFormedUnicode
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections

/** Host-owned format admission. Neither value is verification or playback evidence. */
enum class RuleResponseFormat { JSON, HTML }
enum class RuleInputSlot { QUERY, LOCALE, CURSOR }

/** Fixed host admission for a query name; downloaded rules may only select within this set. */
sealed class RuleQueryAdmission {
    class Input(val slot: RuleInputSlot) : RuleQueryAdmission()
    class Literals(values: Set<String>) : RuleQueryAdmission() {
        val values: Set<String> = Collections.unmodifiableSet(LinkedHashSet(values))
        init {
            require(this.values.size in 1..8)
            require(this.values.all { validRuleText(it, 128) })
        }
    }
    final override fun toString(): String = "RuleQueryAdmission([redacted])"
}

/** Content-addressed, immutable host catalog entry. Production construction is HTTPS/443 only. */
class RuleHttpEndpoint private constructor(
    internal val origin: HttpUrl,
    internal val path: String,
    val format: RuleResponseFormat,
    locales: Set<String>,
    admissions: Map<String, RuleQueryAdmission>,
) {
    internal val allowedLocales: Set<String> = Collections.unmodifiableSet(LinkedHashSet(locales.sorted()))
    internal val queryAdmissions: Map<String, RuleQueryAdmission> = Collections.unmodifiableMap(
        admissions.toSortedMap().mapValues { (_, value) -> when (value) {
            is RuleQueryAdmission.Input -> RuleQueryAdmission.Input(value.slot)
            is RuleQueryAdmission.Literals -> RuleQueryAdmission.Literals(value.values)
        } })
    internal val accept: String get() = if (format == RuleResponseFormat.JSON) "application/json" else "text/html"
    val endpointId: String = MessageDigest.getInstance("SHA-256").digest(canonicalFrame())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    internal fun validateTemplate(endpointId: String, path: String, query: List<RuleHttpQueryValue>, check: () -> Unit = {}) {
        check()
        if (endpointId != this.endpointId || path != this.path || query.size !in 1..8 ||
            query.map { it.name }.distinct().size != query.size) invalidHttpTemplate()
        for (value in query) {
            check()
            val allowed = queryAdmissions[value.name] ?: invalidHttpTemplate()
            when (allowed) {
                is RuleQueryAdmission.Input -> if (value.input != allowed.slot || value.literal != null) invalidHttpTemplate()
                is RuleQueryAdmission.Literals -> if (value.input != null || value.literal !in allowed.values) invalidHttpTemplate()
            }
        }
    }

    internal fun bind(endpointId: String, path: String, query: List<RuleHttpQueryValue>, queryInput: String,
        localeInput: String, cursorInput: String?, check: () -> Unit = {}): RuleHttpBinding {
        validateTemplate(endpointId, path, query, check)
        if (queryInput.length > 512 || !validRuleText(queryInput, 2048))
            throw DiscoveryFailure(DiscoveryError.INVALID_QUERY)
        if (localeInput !in allowedLocales) throw DiscoveryFailure(DiscoveryError.INVALID_QUERY)
        if (cursorInput != null && !validRuleText(cursorInput, 2048))
            throw DiscoveryFailure(DiscoveryError.INVALID_RESULT)
        val builder = origin.newBuilder().encodedPath(path)
        val expectedNames = mutableListOf<String>()
        for (entry in query) {
            check()
            val value = when (entry.input) {
                RuleInputSlot.QUERY -> queryInput
                RuleInputSlot.LOCALE -> localeInput
                RuleInputSlot.CURSOR -> cursorInput
                null -> entry.literal
            } ?: continue
            builder.addQueryParameter(entry.name, value)
            expectedNames += entry.name
        }
        val url = builder.build()
        if (url.toString().length > MAX_URL_BYTES || !url.isHttps || url.host != origin.host ||
            url.port != 443 || url.encodedPath != path || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null || url.querySize != expectedNames.size ||
            expectedNames.indices.any { url.queryParameterName(it) != expectedNames[it] }) invalidHttpTemplate()
        check()
        return RuleHttpBinding(this, url.toString(), format,
            url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""))
    }

    /** Fixed local frame v1; every string has a big-endian UTF-8 byte length. No authority is minted. */
    internal fun canonicalFrame(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            fun text(value: String) { val raw = value.toByteArray(Charsets.UTF_8); out.writeInt(raw.size); out.write(raw) }
            text("esv-http-endpoint"); out.writeInt(1)
            text("GET"); text(origin.scheme); text(origin.host); out.writeInt(origin.port); text(path)
            out.writeByte(if (format == RuleResponseFormat.JSON) 1 else 2)
            text(accept); text("identity"); text("HTTP/1.1"); text(USER_AGENT)
            out.writeInt(allowedLocales.size); allowedLocales.forEach(::text)
            out.writeInt(queryAdmissions.size)
            queryAdmissions.forEach { (name, admission) ->
                text(name)
                when (admission) {
                    is RuleQueryAdmission.Input -> {
                        out.writeByte(1)
                        out.writeByte(when (admission.slot) { RuleInputSlot.QUERY -> 1; RuleInputSlot.LOCALE -> 2; RuleInputSlot.CURSOR -> 3 })
                    }
                    is RuleQueryAdmission.Literals -> {
                        out.writeByte(2); out.writeInt(admission.values.size)
                        admission.values.sortedWith(::compareUtf8Unsigned).forEach(::text)
                    }
                }
            }
        }
    }.toByteArray()

    override fun toString(): String = "RuleHttpEndpoint([redacted])"

    companion object {
        internal const val MAX_URL_BYTES = 16_384
        internal const val USER_AGENT = "EasySaveVideo/rules-http-v1"
        fun https(host: String, path: String, format: RuleResponseFormat, allowedLocales: Set<String>,
            query: Map<String, RuleQueryAdmission>): RuleHttpEndpoint {
            require(host.length in 1..253 && host.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' })
            require(host.any { it in 'a'..'z' } && !host.endsWith('.') && !numericHostSpelling(host))
            val origin = "https://$host/".toHttpUrl()
            require(origin.host == host && origin.port == 443 && origin.username.isEmpty() && origin.password.isEmpty())
            require(path.length in 1..256 && path.startsWith('/') && path.all {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "/._-"
            } && path.split('/').none { it == "." || it == ".." })
            require(origin.newBuilder().encodedPath(path).build().encodedPath == path)
            require(allowedLocales.size in 1..16 && allowedLocales.all { locale ->
                locale.length in 1..64 && locale.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' }
            })
            require(query.size in 1..8 && query.keys.all(::validQueryName))
            return RuleHttpEndpoint(origin, path, format, allowedLocales, query)
        }
        // Reject legacy dotted/undotted decimal, octal and hexadecimal address spellings before DNS.
        private fun numericHostSpelling(value: String): Boolean = value.split('.').all { part ->
            part.isNotEmpty() && (part.all { it in '0'..'9' } ||
                (part.startsWith("0x") && part.length > 2 && part.substring(2).all { it in '0'..'9' || it in 'a'..'f' }))
        }
        private fun validQueryName(value: String): Boolean = value.length in 1..32 && value.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-'
        }
        private fun compareUtf8Unsigned(a: String, b: String): Int {
            val left = a.toByteArray(Charsets.UTF_8); val right = b.toByteArray(Charsets.UTF_8)
            for (i in 0 until minOf(left.size, right.size)) {
                val comparison = (left[i].toInt() and 255).compareTo(right[i].toInt() and 255)
                if (comparison != 0) return comparison
            }
            return left.size.compareTo(right.size)
        }
    }
}

internal class RuleHttpQueryValue(val name: String, val input: RuleInputSlot? = null, val literal: String? = null) {
    init { require((input == null) != (literal == null)) }
    override fun toString(): String = "RuleHttpQueryValue([redacted])"
}
internal class RuleHttpBinding internal constructor(val endpoint: RuleHttpEndpoint, val url: String,
    val format: RuleResponseFormat, val encodedPathAndQuery: String) {
    override fun toString(): String = "RuleHttpBinding([redacted])"
}
private fun invalidHttpTemplate(): Nothing = throw DiscoveryFailure(DiscoveryError.SOURCE_CONFIGURATION)
private fun validRuleText(value: String, maxBytes: Int, allowBlank: Boolean = false): Boolean =
    value.length <= maxBytes && isWellFormedUnicode(value) && value.none { it.isISOControl() } &&
        (allowBlank || value.isNotBlank()) && value.toByteArray(Charsets.UTF_8).size <= maxBytes
