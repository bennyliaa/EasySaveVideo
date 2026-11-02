package com.easysavevideo.core.model

fun interface EpochClock { fun nowMillis(): Long }
interface CancellationToken { val isCancelled: Boolean }

/** Domain owners serialize writes; adapters propagate this token to the real platform operation. */
class CancellationSource : CancellationToken {
    @Volatile
    override var isCancelled: Boolean = false
        private set
    fun cancel() { isCancelled = true }
}

data class ResourceBudget(val maxConcurrentWork: Int = 1, val maxBytes: Long? = null) {
    init { require(maxConcurrentWork > 0); require(maxBytes == null || maxBytes > 0) }
}

enum class OperationStopReason { CANCELLED, DEADLINE_EXCEEDED, RETRY_EXHAUSTED }

data class OperationContext(
    val operationId: String,
    val deadlineEpochMillis: Long,
    val cancelToken: CancellationToken,
    val retryBudget: Int = 0,
    val attempt: Int = 0,
    val resourceBudget: ResourceBudget = ResourceBudget(),
) {
    init {
        require(operationId.isNotBlank())
        require(deadlineEpochMillis >= 0)
        require(retryBudget in 0..2)
        require(attempt in 0..retryBudget)
    }
    fun stopReason(nowMillis: Long): OperationStopReason? = when {
        cancelToken.isCancelled -> OperationStopReason.CANCELLED
        nowMillis >= deadlineEpochMillis -> OperationStopReason.DEADLINE_EXCEEDED
        else -> null
    }
    /** Retrying never renews the overall deadline, cancellation token, or resource budget. */
    fun nextAttempt(nowMillis: Long): OperationContext? =
        if (stopReason(nowMillis) != null || attempt >= retryBudget) null else copy(attempt = attempt + 1)
}

enum class TaskState {
    QUEUED, RUNNING, WAITING_USER, INTERRUPTED, SUCCEEDED, FAILED, CANCELLED;
    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}
enum class TaskMethod { BROWSER, DIRECT, STREAM, RECORD, EDIT }
enum class ResumeAction { RETRY, REVIEW_RECOVERY, RESTORE_PERMISSION }
data class UserWait(val expiresAtEpochMillis: Long, val resumeAction: ResumeAction) {
    init { require(expiresAtEpochMillis >= 0) }
}
data class TaskSnapshot(
    val taskId: String,
    val revision: Long,
    val state: TaskState,
    val method: TaskMethod,
    val progressPercent: Int? = null,
    val errorCode: ErrorCode? = null,
    val waitingUser: UserWait? = null,
) {
    init {
        require(taskId.isNotBlank()); require(revision >= 0)
        require(progressPercent == null || progressPercent in 0..100)
        require((state == TaskState.WAITING_USER) == (waitingUser != null))
    }
}
enum class CommandOrigin { USER, AUTHORIZED_AUTOMATION }
data class CommandRequest(
    val commandId: String,
    val idempotencyKey: String,
    val origin: CommandOrigin,
    val operation: OperationContext,
    val requiresUserAction: Boolean = false,
) { init { require(commandId.isNotBlank()); require(idempotencyKey.isNotBlank()) } }

/** Reserved command port only: M1 does not implement background discovery or a media executor. */
fun interface TaskObserver { fun onSnapshot(snapshot: TaskSnapshot) }
fun interface TaskObservation { fun cancel() }

interface TaskController {
    fun cancel(taskId: String): TaskSnapshot?
    fun snapshot(taskId: String): TaskSnapshot?
    fun observe(taskId: String, observer: TaskObserver): TaskObservation
}
