package com.easysavevideo.discovery

import java.net.IDN
import java.net.URI
import java.util.Collections

enum class SourceAdmissionStatus { UNCONFIGURED, READY, UNSUPPORTED, DENIED }

/** Policies are supplied by trusted application code, never URLs or executable rules from a server. */
fun interface VerificationSourcePolicy {
    fun network(candidate: CandidateUrl): EngineNetworkConstraints
}

object VerificationOriginPolicy {
    /** No DNS/I/O. Exact normalized origin; no subdomain or implicit redirect grant. */
    fun origin(value: String): String? { return try {
        if (value.length > 4096 || value.any { it.isWhitespace() || it.isISOControl() }) null else {
            val uri = URI(value)
            val authority = uri.rawAuthority ?: return null
            if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null || uri.rawFragment != null ||
                authority.any { it in "@%[]\\" } || authority.count { it == ':' } > 1) return null
            val pieces = authority.split(':')
            val host = IDN.toASCII(pieces[0], IDN.USE_STD3_ASCII_RULES).lowercase(java.util.Locale.ROOT)
            val port = if (pieces.size == 1) 443 else pieces[1].takeIf { it.matches(Regex("[0-9]{1,5}")) }?.toIntOrNull() ?: return null
            if (port !in 1..65535 || host.length > 253 || host.endsWith('.') || !host.contains('.') ||
                host.matches(Regex("[0-9.]+")) || host.endsWith(".localhost") || host.endsWith(".local") ||
                host.split('.').any { it.isEmpty() || it.length > 63 }) null
            else "https://$host:$port"
        }
    } catch (_: Exception) { null } }

    /** Input is already-resolved address bytes, never a hostname; no resolution occurs here. */
    fun publicAddress(bytes: ByteArray): Boolean {
        fun n(i: Int) = bytes[i].toInt() and 255
        return when (bytes.size) {
            4 -> n(0) !in setOf(0, 10, 127) && n(0) < 224 &&
                !(n(0) == 169 && n(1) == 254) && !(n(0) == 172 && n(1) in 16..31) &&
                !(n(0) == 192 && n(1) == 168) && !(n(0) == 100 && n(1) in 64..127) &&
                !(n(0) == 198 && n(1) in 18..19)
            16 -> (n(0) and 0xe0) == 0x20 // globally routed unicast only; rejects mapped/ULA/link/multicast
            else -> false
        }
    }
}

/** Finite compiled catalogue. A user can select IDs, never add an origin or widen matching. */
class CompiledVerificationSources(entries: Map<String, Set<String>> = emptyMap()) {
    private val sources: Map<String, Set<String>> = entries.mapValues { (id, origins) ->
        require(id.matches(Regex("[a-z][a-z0-9-]{0,63}")) && origins.size in 1..16)
        Collections.unmodifiableSet(origins.map { origin ->
            val normalized = requireNotNull(VerificationOriginPolicy.origin(origin))
            val uri = URI(origin)
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            require(uri.rawQuery == null)
            normalized
        }.toSet())
    }.also { require(it.size <= 16) }
    val availableIds: Set<String> get() = sources.keys.toSet()
    fun configure(ids: Set<String>, acknowledged: Boolean): VerificationSourceSelection? {
        if (ids.size > 16 || !sources.keys.containsAll(ids) || ids.isNotEmpty() && !acknowledged) return null
        val origins = ids.flatMap { sources.getValue(it) }.toSet()
        if (origins.size > 16) return null
        return VerificationSourceSelection(ids, origins)
    }
}

class VerificationSourceSelection internal constructor(ids: Set<String>, origins: Set<String>) : VerificationSourcePolicy {
    val ids: Set<String> = Collections.unmodifiableSet(LinkedHashSet(ids))
    private val origins: Set<String> = Collections.unmodifiableSet(LinkedHashSet(origins))
    val status: SourceAdmissionStatus get() = if (origins.isEmpty()) SourceAdmissionStatus.UNCONFIGURED else SourceAdmissionStatus.READY
    fun status(candidate: CandidateUrl): SourceAdmissionStatus = if (origins.isEmpty()) SourceAdmissionStatus.UNCONFIGURED
        else if (VerificationOriginPolicy.origin(candidate.pageUrl) == null) SourceAdmissionStatus.UNSUPPORTED
        else if (VerificationOriginPolicy.origin(candidate.pageUrl) !in origins) SourceAdmissionStatus.DENIED else SourceAdmissionStatus.READY
    override fun network(candidate: CandidateUrl): EngineNetworkConstraints {
        val origin = VerificationOriginPolicy.origin(candidate.pageUrl)
        return EngineNetworkConstraints(if (origin != null && origin in origins) setOf(origin) else emptySet(),
            requireTls = true, maxRedirects = 0, sourceAdmissionRequired = true)
    }
    override fun toString() = "VerificationSourceSelection(status=$status,[redacted])"
}
