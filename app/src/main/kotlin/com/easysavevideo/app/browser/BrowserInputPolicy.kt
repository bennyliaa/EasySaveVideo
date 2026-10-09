package com.easysavevideo.app.browser

import com.easysavevideo.core.model.PageAddress
import java.net.IDN
import java.net.URI

/** Shared by address entry, WebView navigation and origin-bound system requests. */
object BrowserInputPolicy {
    fun address(input: String, allowBareHost: Boolean = false): PageAddress? {
        val trimmed = input.trim()
        val candidate = if (allowBareHost && !trimmed.contains("://") && !trimmed.contains(':')) "https://$trimmed" else trimmed
        return runCatching {
            require(candidate.length in 1..8192 && candidate.none { it.isWhitespace() || it.code < 32 } && '\\' !in candidate)
            val uri = URI(candidate)
            require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))
            require(uri.rawUserInfo == null && uri.host != null && uri.host.isNotBlank())
            require(uri.port in -1..65535 && uri.port != 0)
            PageAddress(candidate)
        }.getOrNull()
    }
    fun origin(input: String?): String? = input?.let { address(it) }?.let {
        val uri = URI(it.value)
        val scheme = uri.scheme.lowercase()
        val rawHost = uri.host.lowercase()
        val host = if (rawHost.contains(':')) rawHost else IDN.toASCII(rawHost)
        val port = uri.port.takeUnless { p -> p == -1 || (scheme == "https" && p == 443) || (scheme == "http" && p == 80) }
        "$scheme://$host${port?.let { p -> ":$p" } ?: ""}"
    }
    fun safeAcceptTypes(types: Array<String>): Array<String> = types.flatMap { it.split(',') }
        .map { it.trim().lowercase() }
        .filter { it.matches(Regex("[a-z0-9][a-z0-9!#&^_.+-]*/(?:\\*|[a-z0-9][a-z0-9!#&^_.+-]*)")) }
        .distinct().take(16).toTypedArray()
}
