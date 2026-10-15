package com.easysavevideo.app.browser

/** A removal callback's false value is successful no-removal, never a platform error flag. */
internal enum class BrowserCookieClearResult {
    REJECTED, NOT_STARTED, REMOVED, NONE_REMOVED, UNCONFIRMED;

    /** Legacy callback: true only after removal and flush both complete successfully. */
    val removed: Boolean get() = this == REMOVED
}

/** One captured ordinary/default manager. No profile selection, retry or data other than cookies. */
internal interface BrowserCookieClearPort {
    fun removeAll(onRemoved: (Boolean) -> Unit)
    fun flush()
}

/**
 * Main-thread operation, including the platform callback (the submitting Android Looper).
 * Missing callbacks stay pending. Losing UI ownership does not cancel already submitted removal.
 * Only platform Exception/LinkageError are contained; observer and programmer errors propagate.
 */
internal fun requestBrowserCookieClear(
    isAllowed: () -> Boolean,
    prepare: () -> Unit,
    acquire: () -> BrowserCookieClearPort,
    onComplete: (BrowserCookieClearResult) -> Unit,
) {
    if (!isAllowed()) { onComplete(BrowserCookieClearResult.REJECTED); return }
    try { prepare() }
    catch (_: Exception) { onComplete(BrowserCookieClearResult.NOT_STARTED); return }
    catch (_: LinkageError) { onComplete(BrowserCookieClearResult.NOT_STARTED); return }
    // A file callback or manager factory may synchronously change the Host/presentation.
    if (!isAllowed()) { onComplete(BrowserCookieClearResult.REJECTED); return }
    val port = try { acquire() }
    catch (_: Exception) { onComplete(BrowserCookieClearResult.NOT_STARTED); return }
    catch (_: LinkageError) { onComplete(BrowserCookieClearResult.NOT_STARTED); return }
    if (!isAllowed()) { onComplete(BrowserCookieClearResult.REJECTED); return }

    var claimed = false
    var submitting = true
    var pending: BrowserCookieClearResult? = null
    var observer: ((BrowserCookieClearResult) -> Unit)? = onComplete
    fun deliver(result: BrowserCookieClearResult) {
        val completion = observer ?: return
        observer = null // Consume and release the Host/UI callback before calling user code.
        completion(result)
    }
    fun submissionFailed() {
        if (!claimed) {
            claimed = true
            // The platform may have started removal before throwing. Never promise rollback.
            pending = BrowserCookieClearResult.UNCONFIRMED
        }
    }
    try {
        port.removeAll { changed ->
            if (!claimed) {
                claimed = true // Duplicate/reentrant callbacks cannot flush or finish again.
                val result = try {
                    port.flush()
                    if (changed) BrowserCookieClearResult.REMOVED else BrowserCookieClearResult.NONE_REMOVED
                } catch (_: Exception) { BrowserCookieClearResult.UNCONFIRMED }
                catch (_: LinkageError) { BrowserCookieClearResult.UNCONFIRMED }
                // Keep observer failures outside the submission catch even for synchronous callbacks.
                if (submitting) pending = result else deliver(result)
            }
        }
    } catch (_: Exception) { submissionFailed() }
    catch (_: LinkageError) { submissionFailed() }
    submitting = false
    val result = pending
    pending = null
    if (result != null) deliver(result)
}

/** Latest confirmed request owns feedback only; this neither queues nor cancels native work. */
internal class BrowserCookieClearFeedback {
    private var latest: Any? = null
    fun begin(): Any = Any().also { latest = it }
    fun isCurrent(request: Any): Boolean = latest === request
    fun consume(request: Any, presentationCurrent: () -> Boolean = { true }): Boolean {
        if (latest !== request) return false
        val allowed = presentationCurrent()
        if (latest !== request) return false // The presentation predicate may have begun a newer request.
        latest = null
        return allowed
    }
}
