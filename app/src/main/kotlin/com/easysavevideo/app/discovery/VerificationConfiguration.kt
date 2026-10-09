package com.easysavevideo.app.discovery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.verification.VerificationEndpoint
import java.security.MessageDigest
import java.util.UUID

/** Session-only consent: neither service endpoint nor acknowledgement is persisted or restored. */
class VerificationConfiguration internal constructor(
    val revision: Long,
    val endpoint: VerificationEndpoint?,
    val dynamicAllowed: Boolean,
    val generation: String,
) {
    override fun toString() = "VerificationConfiguration([redacted])"
}

data class VerificationConfigurationClaim(val revision: Long, val generation: String) {
    override fun toString() = "VerificationConfigurationClaim([redacted])"
}

object VerificationConfigurationPolicy {
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun empty() = VerificationConfiguration(0, null, false, digest("verification-disabled-v1"))
    fun endpoint(url: String): VerificationEndpoint? = try { VerificationEndpoint(url) } catch (_: IllegalArgumentException) { null }
    fun configurationKey(sourceKey: String, configuration: VerificationConfiguration): String =
        digest("optional-verification-v1|$sourceKey|${configuration.generation}")
}

/** Main-thread, compare-and-set controls. Fence happens before a changed value can become visible. */
class VerificationConfigurationController(
    private val onChanging: () -> Unit,
    private val onChanged: (VerificationConfiguration) -> Unit,
) {
    @Volatile var current = VerificationConfigurationPolicy.empty()
        private set
    @Volatile var busy = false
        private set
    var state by mutableStateOf(current)
        private set
    private var closed = false
    private var changing = false
    fun claim() = VerificationConfigurationClaim(state.revision, state.generation)
    fun isCurrent(claim: VerificationConfigurationClaim): Boolean = !closed && !changing &&
        claim.revision == state.revision && claim.generation == state.generation
    fun enable(claim: VerificationConfigurationClaim, url: String, dynamicAllowed: Boolean,
        acknowledged: Boolean): Boolean {
        if (!isCurrent(claim) || !acknowledged || state.revision == Long.MAX_VALUE) return false
        val endpoint = VerificationConfigurationPolicy.endpoint(url) ?: return false
        return change(VerificationConfiguration(state.revision + 1, endpoint, dynamicAllowed,
            VerificationConfigurationPolicy.digest("verification-v1|${UUID.randomUUID()}")))
    }
    fun disable(claim: VerificationConfigurationClaim): Boolean {
        if (!isCurrent(claim) || state.endpoint == null || state.revision == Long.MAX_VALUE) return false
        return change(VerificationConfiguration(state.revision + 1, null, false,
            VerificationConfigurationPolicy.digest("verification-off-v1|${UUID.randomUUID()}")))
    }
    private fun change(value: VerificationConfiguration): Boolean {
        changing = true; busy = true
        try {
            try { onChanging() } catch (_: Exception) {
                val disabled = VerificationConfiguration(value.revision, null, false,
                    VerificationConfigurationPolicy.digest("verification-failed-v1|${UUID.randomUUID()}"))
                current = disabled; state = disabled; busy = false
                try { onChanged(disabled) } catch (_: Exception) { }
                return false
            }
            current = value
            state = value
            busy = false
            try { onChanged(value) } catch (_: Exception) { return false }
            return true
        } finally { busy = false; changing = false }
    }
    fun close() { closed = true }
}
