package com.easysavevideo.app.save

import com.easysavevideo.core.model.BrowserContextRef
import java.lang.ref.WeakReference
import java.net.URI
import java.util.Locale

private const val AUTHENTICATION_ORIGIN_CAPACITY = 100
private const val AUTHENTICATION_ORIGIN_TTL_MILLIS = 15 * 60_000L

/** Click-time browser freshness is independent of the captured document's identity. */
internal fun authenticationOriginInteractionCurrent(expected: Long, current: Long): Boolean =
    expected >= 0 && expected == current

/** Canonical comparison data only; paths, queries and credentials never enter a token. */
private class AuthenticationHttpsOrigin(private val host: String, private val port: Int) {
    fun matches(other: AuthenticationHttpsOrigin) = host == other.host && port == other.port

    companion object {
        fun parse(address: String): AuthenticationHttpsOrigin? = try {
            if (address.length > 8192 || address.any { it.isWhitespace() || it.isISOControl() } || '\\' in address) null
            else {
                val uri = URI(address)
                val host = uri.host
                if (!uri.scheme.equals("https", true) || uri.isOpaque || host.isNullOrBlank() ||
                    uri.rawUserInfo != null || uri.rawAuthority?.endsWith(':') == true ||
                    uri.port != -1 && uri.port !in 1..65535) null
                else AuthenticationHttpsOrigin(host.lowercase(Locale.ROOT), if (uri.port == -1) 443 else uri.port)
            }
        } catch (_: Exception) { null }
    }

    override fun toString() = "AuthenticationHttpsOrigin([redacted])"
}

/** Opaque, revocable process-local handle. This does not carry a document, renderer or address. */
internal class AuthenticationOriginToken private constructor(
    private val origin: AuthenticationHttpsOrigin,
    private val clock: () -> Long,
    private val issuedAt: Long,
    private val ttlMillis: Long,
) {
    @Volatile private var retired = false

    fun matchesSource(source: String): Boolean {
        if (!isLive()) return false
        val candidate = AuthenticationHttpsOrigin.parse(source) ?: return false
        return origin.matches(candidate) && isLive()
    }

    /** The issuer's clock remains authoritative, including when checked by a worker. */
    internal fun isLive(): Boolean {
        if (retired) return false
        val elapsed = try { clock() - issuedAt } catch (_: Exception) { retire(); return false }
        if (elapsed < 0 || elapsed >= ttlMillis) { retire(); return false }
        return !retired
    }

    internal fun retire() { retired = true }

    companion object {
        internal fun issue(pageAddress: String, clock: () -> Long, ttlMillis: Long): AuthenticationOriginToken? {
            val origin = AuthenticationHttpsOrigin.parse(pageAddress) ?: return null
            val now = try { clock() } catch (_: Exception) { return null }
            return AuthenticationOriginToken(origin, clock, now, ttlMillis)
        }
    }

    override fun toString() = "AuthenticationOriginToken([redacted])"
}

/** A host-owned snapshot. Even an outstanding snapshot must not retain an Entry or Android View. */
internal class AuthenticationOriginDocument(
    val context: BrowserContextRef,
    val epoch: Long,
    val documentRevision: Long,
    entryIdentity: Any,
    viewIdentity: Any,
    private val pageAddress: String,
    private val ready: Boolean,
    private val incognito: Boolean,
) {
    private val entry = WeakReference(entryIdentity)
    private val view = WeakReference(viewIdentity)

    internal fun isUsable(): Boolean = ready && !incognito && epoch > 0 && documentRevision >= 0 &&
        entry.get() != null && view.get() != null

    internal fun sameDocument(other: AuthenticationOriginDocument): Boolean {
        val liveEntry = entry.get() ?: return false
        val liveView = view.get() ?: return false
        return isUsable() && other.isUsable() && liveEntry === other.entry.get() && liveView === other.view.get() &&
            context == other.context && epoch == other.epoch && documentRevision == other.documentRevision &&
            pageAddress == other.pageAddress
    }

    internal fun belongsTo(entryIdentity: Any): Boolean = entry.get() === entryIdentity

    internal fun issue(clock: () -> Long, ttlMillis: Long): AuthenticationOriginToken? =
        if (isUsable()) AuthenticationOriginToken.issue(pageAddress, clock, ttlMillis) else null

    override fun toString() = "AuthenticationOriginDocument([redacted])"
}

/** Main-thread-owned registry. Resolution only checks the supplied exact existing document. */
internal class AuthenticationOriginDocuments(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val capacity: Int = AUTHENTICATION_ORIGIN_CAPACITY,
    private val ttlMillis: Long = AUTHENTICATION_ORIGIN_TTL_MILLIS,
) {
    private val documents = LinkedHashMap<AuthenticationOriginToken, AuthenticationOriginDocument>()

    init {
        require(capacity in 1..AUTHENTICATION_ORIGIN_CAPACITY)
        require(ttlMillis in 1..AUTHENTICATION_ORIGIN_TTL_MILLIS)
    }

    fun capture(document: AuthenticationOriginDocument, source: String): AuthenticationOriginToken? {
        prune()
        if (documents.size >= capacity) return null
        val token = document.issue(clock, ttlMillis) ?: return null
        if (!token.matchesSource(source)) { token.retire(); return null }
        documents[token] = document
        return token
    }

    fun context(token: AuthenticationOriginToken): BrowserContextRef? {
        prune()
        return documents[token]?.context
    }

    fun resolve(token: AuthenticationOriginToken, currentDocument: AuthenticationOriginDocument): Boolean {
        prune()
        val captured = documents[token] ?: return false
        if (!captured.sameDocument(currentDocument)) { discard(token); return false }
        return true
    }

    fun discard(token: AuthenticationOriginToken) {
        // A foreign registry cannot revoke its issuer's capability.
        if (documents.remove(token) != null) token.retire()
    }

    fun invalidate(entryIdentity: Any) {
        val iterator = documents.iterator()
        while (iterator.hasNext()) {
            val (token, document) = iterator.next()
            if (document.belongsTo(entryIdentity) || !document.isUsable() || !token.isLive()) {
                token.retire(); iterator.remove()
            }
        }
    }

    fun clear() {
        documents.keys.forEach(AuthenticationOriginToken::retire)
        documents.clear()
    }

    private fun prune() {
        val iterator = documents.iterator()
        while (iterator.hasNext()) {
            val (token, document) = iterator.next()
            if (!token.isLive() || !document.isUsable()) { token.retire(); iterator.remove() }
        }
    }

    override fun toString() = "AuthenticationOriginDocuments([redacted])"
}

/** Single submission claim. Owner and generation are identity values, never lifecycle counters. */
internal class AuthenticationOriginCapture internal constructor(
    private val owner: Any,
    private val generation: Any,
    private val origin: AuthenticationOriginToken,
) {
    private var consumed = false

    @Synchronized internal fun consume(expectedOwner: Any): AuthenticationOriginToken? {
        if (owner !== expectedOwner || consumed) return null
        consumed = true
        return origin
    }

    internal fun matchesGeneration(expected: Any): Boolean = generation === expected
    override fun toString() = "AuthenticationOriginCapture([redacted])"
}

/** Worker-safe, bounded associations for newly accepted tasks only. No recovery can create one. */
internal class AuthenticationTaskOrigins(private val capacity: Int = AUTHENTICATION_ORIGIN_CAPACITY) {
    private val lock = Any()
    private val owner = Any()
    private var generation = Any()
    private val links = LinkedHashMap<String, AuthenticationOriginToken>()

    init { require(capacity in 1..AUTHENTICATION_ORIGIN_CAPACITY) }

    fun capture(token: AuthenticationOriginToken): AuthenticationOriginCapture = synchronized(lock) {
        AuthenticationOriginCapture(owner, generation, token)
    }

    fun accepted(taskId: String, capture: AuthenticationOriginCapture, existing: Boolean, finalSource: String): Boolean =
        synchronized(lock) {
            prune()
            val token = capture.consume(owner) ?: return@synchronized false
            if (!capture.matchesGeneration(generation) || existing || taskId.isBlank() || taskId in links ||
                links.size >= capacity || links.values.any { it === token } || !token.matchesSource(finalSource)) {
                retireIfUnlinked(token)
                return@synchronized false
            }
            links[taskId] = token
            true
        }

    fun token(taskId: String): AuthenticationOriginToken? = synchronized(lock) { prune(); links[taskId] }

    fun discard(capture: AuthenticationOriginCapture?) = synchronized(lock) {
        val token = capture?.consume(owner) ?: return@synchronized
        retireIfUnlinked(token)
    }

    fun cancel(taskId: String) = synchronized(lock) { links.remove(taskId)?.retire(); Unit }

    fun clear() = synchronized(lock) {
        generation = Any()
        links.values.forEach(AuthenticationOriginToken::retire)
        links.clear()
    }

    /** The caller supplies a complete current snapshot's nonterminal IDs, including auth waits. */
    fun retainNonterminal(taskIds: Set<String>) = synchronized(lock) {
        val iterator = links.iterator()
        while (iterator.hasNext()) {
            val (taskId, token) = iterator.next()
            if (taskId !in taskIds || !token.isLive()) { token.retire(); iterator.remove() }
        }
    }

    private fun retireIfUnlinked(token: AuthenticationOriginToken) {
        if (links.values.none { it === token }) token.retire()
    }

    private fun prune() {
        val iterator = links.iterator()
        while (iterator.hasNext()) if (!iterator.next().value.isLive()) iterator.remove()
    }

    override fun toString() = "AuthenticationTaskOrigins([redacted])"
}
