package com.easysavevideo.commons.maintenance

/** Local, bounded probation signals, not a claim that a signed package is malicious or defective. */
enum class ProviderHealthOutcome { SUCCESS, STRUCTURE_FAILURE, INCONCLUSIVE }
enum class ProviderHealthPhase { NONE, PROBATION, HEALTHY, SUSPENDED }
enum class ProviderQuarantineReason { FIXED_SELF_TEST_FAILED, STRUCTURE_FAILURES, EXPLICIT_REJECTION }

/** Opaque, one-use, process-local capability. A token alone grants no package authority. */
class ProviderHealthObservation internal constructor() {
    override fun toString(): String = "ProviderHealthObservation([redacted])"
}

data class ProviderHealthSnapshot(
    val phase: ProviderHealthPhase,
    val consecutiveSuccesses: Int = 0,
    val consecutiveStructureFailures: Int = 0,
    val conclusiveObservations: Int = 0,
    val totalObservations: Int = 0,
    val startedAtEpochMillis: Long? = null,
    val deadlineEpochMillis: Long? = null,
)

/** Digests are full signed-envelope SHA-256 hex strings, never mutable caller-owned byte arrays. */
data class ProviderQuarantinedVersion(
    val version: Long,
    val packageDigest: String,
    val policyVersion: Long,
    val policyDigest: String?,
    val reason: ProviderQuarantineReason,
)

/** Fixed local limits. They bound exposure; they do not provide statistical health guarantees. */
object ProviderHealthLimits {
    const val REQUIRED_SUCCESSES = 3
    const val REQUIRED_STRUCTURE_FAILURES = 3
    const val MAX_CONCLUSIVE_OBSERVATIONS = 12
    const val MAX_TOTAL_OBSERVATIONS = 20
    const val MAX_PROBATION_MILLIS = 24L * 60 * 60 * 1_000
    const val MAX_QUARANTINED_VERSIONS = 32
}

internal data class ProviderHealthRecord(
    val startedAtEpochMillis: Long,
    val deadlineEpochMillis: Long,
    val consecutiveSuccesses: Int = 0,
    val consecutiveStructureFailures: Int = 0,
    val conclusiveObservations: Int = 0,
    val totalObservations: Int = 0,
)
