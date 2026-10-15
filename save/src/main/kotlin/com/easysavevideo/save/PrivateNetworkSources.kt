package com.easysavevideo.save

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Output-only source constraints. No URL, credential or browser authority is exposed here. */
interface NetworkSourceMetadata {
    val sourceIdentity: String
    val expectedAudio: Boolean
}

/** An opaque value, never a browser/session or execution capability. */
class PrivateTaskSourceIdentity private constructor(val encoded: String) {
    override fun equals(other: Any?) = other is PrivateTaskSourceIdentity && encoded == other.encoded
    override fun hashCode() = encoded.hashCode()
    override fun toString() = "PrivateTaskSourceIdentity([redacted])"
    companion object {
        fun fromDurable(encoded: String): PrivateTaskSourceIdentity {
            require(encoded.matches(Regex("[a-f0-9]{64}")))
            return PrivateTaskSourceIdentity(encoded)
        }
        internal fun create(): PrivateTaskSourceIdentity {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return fromDurable(bytes.joinToString("") { "%02x".format(it) })
        }
    }
}

class PrivateSelectionIdentity private constructor(val encoded: String) {
    override fun equals(other: Any?) = other is PrivateSelectionIdentity && encoded == other.encoded
    override fun hashCode() = encoded.hashCode()
    override fun toString() = "PrivateSelectionIdentity([redacted])"
    companion object {
        fun fromDurable(encoded: String): PrivateSelectionIdentity {
            require(encoded.length == 36 && UUID.fromString(encoded).toString() == encoded)
            return PrivateSelectionIdentity(encoded)
        }
        internal fun create() = fromDurable(UUID.randomUUID().toString())
    }
}

/** Deliberately cannot represent a URL, source expiry, request lease or browser profile. */
data class PrivateDurableSource(
    val identity: PrivateTaskSourceIdentity,
    val candidateId: PrivateSelectionIdentity,
    val variantId: PrivateSelectionIdentity,
    override val expectedAudio: Boolean,
) : NetworkSourceMetadata {
    override val sourceIdentity: String get() = identity.encoded
    override fun toString() = "PrivateDurableSource([redacted])"
}

sealed interface PrivateNetworkSource {
    val sourceIdentity: PrivateTaskSourceIdentity
    val candidateId: PrivateSelectionIdentity
    val variantId: PrivateSelectionIdentity
    fun isLive(): Boolean
}

/** Identity/fence only: production must separately register the actual PrivateSessionLease. */
class PrivateNetworkSourceSeed private constructor() : PrivateNetworkSource {
    private enum class State { UNBOUND, BOUND, REVOKED }
    private val state = AtomicReference(State.UNBOUND)
    override val sourceIdentity = PrivateTaskSourceIdentity.create()
    override val candidateId = PrivateSelectionIdentity.create()
    override val variantId = PrivateSelectionIdentity.create()
    override fun isLive() = state.get() != State.REVOKED
    fun revoke() { state.set(State.REVOKED) }
    fun bindTask(taskId: String): PrivateNetworkSourceBinding = PrivateNetworkSourceBinding.issue(this, taskId)
    internal fun claimBinding(): Boolean = state.compareAndSet(State.UNBOUND, State.BOUND)
    override fun toString() = "PrivateNetworkSourceSeed([redacted])"
    companion object { fun create() = PrivateNetworkSourceSeed() }
}

class PrivateNetworkSourceBinding private constructor(
    private val seed: PrivateNetworkSourceSeed,
    val taskId: String,
) : PrivateNetworkSource {
    override val sourceIdentity: PrivateTaskSourceIdentity get() = seed.sourceIdentity
    override val candidateId: PrivateSelectionIdentity get() = seed.candidateId
    override val variantId: PrivateSelectionIdentity get() = seed.variantId
    override fun isLive() = seed.isLive()
    fun isFrom(seed: PrivateNetworkSourceSeed) = this.seed === seed
    fun revoke() = seed.revoke()
    internal fun durable(expectedAudio: Boolean) = PrivateDurableSource(sourceIdentity, candidateId, variantId, expectedAudio)
    override fun toString() = "PrivateNetworkSourceBinding([redacted])"
    internal companion object {
        fun issue(seed: PrivateNetworkSourceSeed, taskId: String): PrivateNetworkSourceBinding {
            requireSaveId(taskId)
            if (!seed.claimBinding()) throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            return PrivateNetworkSourceBinding(seed, taskId)
        }
    }
}

/** A source-exit request is not evidence of source release or output/native drain. */
enum class PrivateSourceExitResult { STOP_REQUESTED, OUTPUT_ONLY, NOT_OWNED }

/** Notification only; the app must match its exact registered owner and positive port release. */
fun interface PrivateSourceDetachmentObserver {
    fun onPrivateSourceDetached(taskId: String, operationId: String, binding: PrivateNetworkSourceBinding)
}
