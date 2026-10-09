package com.easysavevideo.core.model

enum class RecoveryObjectKind { BROWSER_SESSION, SETTINGS, TASK_JOURNAL, PROVIDER, VAULT }
enum class RecoveryDisposition { READY, WAITING_USER, ISOLATED, DEFERRED }
data class RecoveryOutcome(
    val kind: RecoveryObjectKind,
    val disposition: RecoveryDisposition,
    val error: ErrorEnvelope? = null,
)
/** A probe must observe the absolute deadline and cancellation; this port cannot preempt blocking IO. */
interface RecoveryProbe {
    val kind: RecoveryObjectKind
    fun check(operation: OperationContext): RecoveryOutcome
}
data class RecoveryReport(
    val outcomes: List<RecoveryOutcome>,
    val deadlineReached: Boolean,
) {
    /** Recovery never removes the address bar, even when another domain is isolated. */
    val browserShellAllowed: Boolean get() = true
}
