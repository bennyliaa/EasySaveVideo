package com.easysavevideo.core.model

/** Pure metadata only. No value in this file is a credential, unlock handle or decryption authority. */
enum class VaultAutoLockPolicy(val durationMillis: Long, val key: String) {
    IMMEDIATE(0, "immediate"), AFTER_1_MINUTE(60_000, "after_1_minute"),
    AFTER_5_MINUTES(300_000, "after_5_minutes"), AFTER_15_MINUTES(900_000, "after_15_minutes");
    companion object {
        fun fromKey(key: String?): VaultAutoLockPolicy = entries.firstOrNull { it.key == key } ?: IMMEDIATE
    }
}
data class VaultTick(val processInstance: Long, val elapsedMillis: Long)
enum class VaultMode { LOCKED, RESERVING_ATTEMPT, AUTHENTICATING, ACCEPTED_PENDING_RETRY_RESET, SESSION_OPEN, RECOVERY_REQUIRED, UNAVAILABLE }
enum class VaultVisibility { COVERED, VISIBLE }
enum class VaultCleanupStatus { PENDING, COMPLETE, FAILED }
enum class VaultRetryStatus { READY, WAIT_UNTIL, PERSISTENCE_PENDING, UNAVAILABLE }
enum class VaultReason {
    NONE, STARTUP, USER_LOCK, BACKGROUND_TIMEOUT, DEVICE_LOCK, CLOCK_INVALID, COUNTER_EXHAUSTED,
    ATTEMPT_CANCELLED, REJECTED_CREDENTIAL, PLATFORM_THROTTLED, AUTHENTICATION_TIMEOUT,
    KEY_INVALIDATED, INTEGRITY_FAILED, CLEANUP_FAILED, RETRY_STORE_UNAVAILABLE, UNSUPPORTED_STATE,
    FORGOTTEN_PASSWORD, BIOMETRIC_CHANGED, APPLICATION_REMOVED, DEVICE_CHANGED,
}
enum class VaultRecoveryChoice { UNDECIDED, DECLINED, OPTED_IN }
enum class VaultRecoveryMaterialStatus { NOT_PROVISIONED, CONFIRMED_AVAILABLE, UNAVAILABLE, UNKNOWN }
enum class VaultRecoveryRoute { TRY_EXISTING_PASSWORD, USER_OFFLINE_RECOVERY, REVIEW_UNRECOVERABLE_STATE }

/** These are injected testable limits, not approved production security parameters. */
class VaultRetryPolicy(
    delaysMillis: List<Long>,
    val maximumDelayMillis: Long,
    val authenticationTimeoutMillis: Long,
    val persistenceTimeoutMillis: Long,
    val cleanupTimeoutMillis: Long,
) {
    private val delays = delaysMillis.toList()
    val delaysMillis: List<Long> get() = delays.toList()
    fun isValid(): Boolean = delays.isNotEmpty() && delays.size <= 64 && maximumDelayMillis >= 0 &&
        delays.all { it in 0..maximumDelayMillis } && delays.zipWithNext().all { (a, b) -> a <= b } &&
        authenticationTimeoutMillis > 0 && persistenceTimeoutMillis > 0 && cleanupTimeoutMillis > 0
    fun tierFor(count: Long): Int = if (count <= 0) -1 else minOf(count - 1, (delays.size - 1).toLong()).toInt()
    fun delayFor(count: Long): Long = if (count == 0L) 0 else delays[tierFor(count)]
}

data class VaultRetryLedger(
    val ledgerRevision: Long,
    val reservedAttemptCount: Long,
    val delayTier: Int,
    val outstandingReservationId: Long?,
)
sealed interface VaultLedgerLoad {
    data object TrustedFirstUse : VaultLedgerLoad
    data class Restored(val ledger: VaultRetryLedger) : VaultLedgerLoad
    data object Unavailable : VaultLedgerLoad
    data object Corrupt : VaultLedgerLoad
}
data class VaultAttemptIdentity(val processInstance: Long, val attemptId: Long, val authEpoch: Long)
enum class VaultAttemptPurpose { UNLOCK, RECOVERY_RESET }
data class VaultAttempt(val identity: VaultAttemptIdentity, val deadline: VaultTick,
    val purpose: VaultAttemptPurpose = VaultAttemptPurpose.UNLOCK)
enum class VaultRetryWriteKind { RESERVATION, RESET }
data class VaultRetryWriteIdentity(val attempt: VaultAttemptIdentity, val writeId: Long)
data class VaultRetryWrite(
    val kind: VaultRetryWriteKind,
    val identity: VaultRetryWriteIdentity,
    val expectedLedgerRevision: Long,
    val proposedLedger: VaultRetryLedger,
    val deadline: VaultTick,
) {
    /** Necessary CAS precondition only. This does NOT implement a store or establish durability. */
    fun matchesStoredRevision(revision: Long): Boolean = revision == expectedLedgerRevision &&
        revision >= 0 && revision < Long.MAX_VALUE && proposedLedger.ledgerRevision == revision + 1
}
data class VaultCleanupBinding(
    val cleanupId: Long,
    val processInstance: Long,
    val authEpoch: Long,
    val presentationEpoch: Long,
    val deadline: VaultTick,
)

data class VaultState(
    val revision: Long,
    val processInstance: Long,
    val lastObservedTick: VaultTick,
    val authEpoch: Long,
    val presentationEpoch: Long,
    val lastIdentifier: Long,
    val mode: VaultMode,
    val reason: VaultReason,
    val foreground: Boolean,
    val visibility: VaultVisibility,
    val autoLockPolicy: VaultAutoLockPolicy,
    val policyRevision: Long,
    val highestWindowId: Long,
    val lockWindowId: Long?,
    val lockWindowStartedAt: VaultTick?,
    val lockDeadline: VaultTick?,
    val activeAttempt: VaultAttempt?,
    val retryStatus: VaultRetryStatus,
    val retryDeadline: VaultTick?,
    val retryLedger: VaultRetryLedger,
    val pendingRetryWrite: VaultRetryWrite?,
    val cleanupStatus: VaultCleanupStatus,
    val currentCleanup: VaultCleanupBinding?,
    val recoveryChoice: VaultRecoveryChoice,
    val recoveryMaterialStatus: VaultRecoveryMaterialStatus,
    /** Null is the legacy explicit-window path; LifecycleObserved activates route-aware policy. */
    val inVaultRoute: Boolean? = null,
) {
    /** Presentation eligibility only; the external owner MUST check actual session authority and current time. */
    fun permitsPresentation(now: VaultTick, auth: Long, presentation: Long): Boolean =
        mode == VaultMode.SESSION_OPEN && foreground && inVaultRoute != false && visibility == VaultVisibility.VISIBLE &&
            cleanupStatus == VaultCleanupStatus.COMPLETE && now.processInstance == processInstance &&
            now.elapsedMillis >= lastObservedTick.elapsedMillis &&
            (lockDeadline == null || now.elapsedMillis < lockDeadline.elapsedMillis) &&
            auth == authEpoch && presentation == presentationEpoch
    override fun toString(): String = "VaultState(mode=$mode, reason=$reason, visibility=$visibility)"
}

enum class VaultAuthenticationOutcome { SESSION_ESTABLISHED, RECOVERY_RESET_VERIFIED, REJECTED, CANCELLED, TEMPORARILY_UNAVAILABLE, KEY_INVALIDATED }
sealed interface VaultEvent {
    /** Complete current route/app observation; an occluding surface is not a route departure. */
    data class LifecycleObserved(val inVaultRoute: Boolean, val foreground: Boolean) : VaultEvent
    data object ForegroundEntered : VaultEvent
    data object BackgroundEntered : VaultEvent
    data object SurfaceCovered : VaultEvent
    data object SurfaceVisible : VaultEvent
    data object ExplicitLock : VaultEvent
    data object DeviceLocked : VaultEvent
    data object ClockObserved : VaultEvent
    data class PolicyChanged(val policy: VaultAutoLockPolicy) : VaultEvent
    data class AutoLockWindowStarted(val windowId: Long, val policyRevision: Long) : VaultEvent
    data class AutoLockWindowCancelled(val windowId: Long) : VaultEvent
    data object UnlockRequested : VaultEvent
    data object RecoveryResetRequested : VaultEvent
    data class RetryWriteStored(val identity: VaultRetryWriteIdentity, val expectedLedgerRevision: Long, val storedLedgerRevision: Long) : VaultEvent
    data class RetryWriteFailed(val identity: VaultRetryWriteIdentity) : VaultEvent
    data class AuthenticationResult(
        val identity: VaultAttemptIdentity,
        val outcome: VaultAuthenticationOutcome,
        val platformRetryAfterMillis: Long = 0,
    ) : VaultEvent
    data class AuthenticationTimedOut(val identity: VaultAttemptIdentity) : VaultEvent
    data class CleanupCompleted(val binding: VaultCleanupBinding) : VaultEvent
    data class CleanupFailed(val binding: VaultCleanupBinding) : VaultEvent
    data class KeyStateObserved(val invalidated: Boolean) : VaultEvent
    data object IntegrityFailureObserved : VaultEvent
    data class RecoveryRequired(val reason: VaultReason) : VaultEvent
    data class RecoveryChoiceRecorded(val choice: VaultRecoveryChoice) : VaultEvent
    data class RecoveryMaterialStatusObserved(val status: VaultRecoveryMaterialStatus) : VaultEvent
}

sealed interface VaultEffect {
    data object MaskPrivatePresentation : VaultEffect
    data object StopPrivatePlaybackAndReaders : VaultEffect
    data class RevokeExternalSession(val authEpoch: Long) : VaultEffect
    data class DiscardAttemptResult(val identity: VaultAttemptIdentity) : VaultEffect
    data object ClearPrivateMemory : VaultEffect
    data class RequestOwnedPlaintextCleanup(val binding: VaultCleanupBinding) : VaultEffect
    data class PersistRetryWrite(val write: VaultRetryWrite) : VaultEffect
    data class BeginExternalAuthentication(val attempt: VaultAttempt) : VaultEffect
    data class ScheduleWake(val deadline: VaultTick) : VaultEffect
    data object CancelWake : VaultEffect
    class RequireUserRecovery(val reason: VaultReason, allowedRoutes: List<VaultRecoveryRoute>) : VaultEffect {
        private val routes = allowedRoutes.toList()
        val allowedRoutes: List<VaultRecoveryRoute> get() = routes.toList()
    }
    data class ReportTechnicalCategory(val reason: VaultReason) : VaultEffect
}
enum class VaultTransitionOutcome { APPLIED, IGNORED, BLOCKED, FAILED_CLOSED }
class VaultTransition(val state: VaultState, effects: List<VaultEffect>, val outcome: VaultTransitionOutcome) {
    private val values = effects.toList()
    val effects: List<VaultEffect> get() = values.toList()
}
