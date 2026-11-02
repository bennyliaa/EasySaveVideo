package com.easysavevideo.core.model

/** Closed, content-free diagnostics. Never put URLs, titles, file paths or exception text here. */
enum class ErrorModule { BROWSER, SETTINGS, ENTITLEMENTS, RECOVERY, TASK, PLATFORM }
enum class ErrorCode {
    INVALID_ADDRESS, NETWORK_UNAVAILABLE, NETWORK_TIMEOUT, HTTP_FAILURE, TLS_REJECTED,
    PAGE_LOAD_FAILED, WEBVIEW_UNAVAILABLE, RENDERER_GONE, SESSION_UNAVAILABLE,
    SETTINGS_UNAVAILABLE, STORE_UNAVAILABLE, ENTITLEMENT_REQUIRED, PLATFORM_UNAVAILABLE,
    POLICY_BLOCKED, OPERATION_CANCELLED, DEADLINE_EXCEEDED, RETRY_EXHAUSTED,
    INTERRUPTED, RECOVERY_FAILED, INVALID_STATE, UNKNOWN_TECHNICAL_ERROR,
}
enum class UserAction { NONE, RETRY, ENTER_ADDRESS, OPEN_SYSTEM_BROWSER, CANCEL, REVIEW_SETTINGS }

/** Whitelisted numeric technical fields, not an arbitrary key/value escape hatch. */
data class SafeContext(
    val httpStatus: Int? = null,
    val platformApi: Int? = null,
    val attempt: Int? = null,
) {
    init {
        require(httpStatus == null || httpStatus in 100..599)
        require(platformApi == null || platformApi > 0)
        require(attempt == null || attempt >= 0)
    }
}

data class ErrorEnvelope(
    val code: ErrorCode,
    val module: ErrorModule,
    val retryable: Boolean,
    val userAction: UserAction,
    val safeContext: SafeContext = SafeContext(),
)

/** ERROR only. Implementations must bound retention and swallow their own write failures. */
fun interface ErrorSink { fun report(error: ErrorEnvelope) }
