package com.easysavevideo.app.browser

import com.easysavevideo.app.discovery.DiscoveryPresentationPolicy
import com.easysavevideo.core.model.BrowserTab

enum class NewTabInputMode { AUTO, ADDRESS, VIDEO }

sealed interface NewTabInput {
    data class Address(val value: String) : NewTabInput { override fun toString() = "Address([redacted])" }
    data class Query(val value: String) : NewTabInput { override fun toString() = "Query([redacted])" }
    data object Invalid : NewTabInput
}

/** Local interpretation only. No Provider lookup, URL load, persistence or logging. */
object NewTabInputPolicy {
    fun canSubmit(expected: BrowserTab?, current: BrowserTab?, interactive: Boolean): Boolean =
        interactive && expected != null && current != null && expected.context == current.context &&
            expected.epoch == current.epoch && expected.address == null && current.address == null

    fun interpret(input: String, mode: NewTabInputMode): NewTabInput {
        if (input.length > 8192 || input.any { it.code < 32 || it.code == 127 }) return NewTabInput.Invalid
        val value = input.trim()
        if (value.isEmpty()) return NewTabInput.Invalid
        if (mode == NewTabInputMode.VIDEO) return query(value)
        val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value)
        val hostPart = value.substringBefore('/').substringBefore('?').substringBefore('#')
        val looksLikeAddress = explicitScheme || ('.' in hostPart && value.none { it.isWhitespace() }) || '@' in hostPart ||
            hostPart.equals("localhost", true) || hostPart.startsWith('[') || '\\' in value
        if (mode == NewTabInputMode.ADDRESS || looksLikeAddress) {
            val address = BrowserInputPolicy.address(value, allowBareHost = true) ?: return NewTabInput.Invalid
            return NewTabInput.Address(address.value)
        }
        return query(value)
    }

    private fun query(value: String): NewTabInput =
        if (DiscoveryPresentationPolicy.validQuery(value)) NewTabInput.Query(value) else NewTabInput.Invalid
}

/** One composition-owned blank-tab action; retired callbacks cannot replay after returning. */
class NewTabSubmissionGate {
    private var claimed = false
    fun retire() { claimed = true }
    fun claim(expected: BrowserTab?, current: BrowserTab?, interactive: Boolean): Boolean {
        if (claimed || !NewTabInputPolicy.canSubmit(expected, current, interactive)) return false
        claimed = true
        return true
    }
}
