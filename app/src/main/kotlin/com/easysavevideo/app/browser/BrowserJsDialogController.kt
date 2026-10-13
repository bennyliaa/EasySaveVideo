package com.easysavevideo.app.browser

/** No Android objects, persistence, logging, or generated toString containing page/input text. */
internal enum class BrowserJsDialogKind { ALERT, CONFIRM, PROMPT }

internal class BrowserJsDialogRequest(
    val kind: BrowserJsDialogKind,
    val origin: String,
    val message: String,
    val defaultValue: String,
)

internal object BrowserJsDialogPolicy {
    const val MAX_MESSAGE = 4096
    const val MAX_INPUT = 1024
    const val MAX_ORIGIN = 300
    const val MAX_PER_DOCUMENT = 3
    const val MAX_PER_WINDOW = 3
    const val WINDOW_MILLIS = 30_000L

    fun origin(callbackUrl: String?, pageUrl: String?): String? {
        val origin = safeOrigin(callbackUrl) ?: return null
        return origin.takeIf { it == safeOrigin(pageUrl) }
    }
    private fun safeOrigin(url: String?): String? = runCatching { BrowserInputPolicy.origin(url) }
        .getOrNull()?.takeIf { it.length <= MAX_ORIGIN }
    fun message(raw: String): String {
        val limit = if (raw.length > MAX_MESSAGE && raw[MAX_MESSAGE - 1].isHighSurrogate()) MAX_MESSAGE - 1 else MAX_MESSAGE
        val bounded = raw.take(limit).map { char ->
            if ((char.isISOControl() && char != '\n' && char != '\t') ||
                char in '\u202a'..'\u202e' || char in '\u2066'..'\u2069' || char == '\u200e' || char == '\u200f') '\ufffd' else char
        }.joinToString("")
        return if (raw.length > limit) "$bounded…" else bounded
    }
    fun validInput(value: String) = value.length <= MAX_INPUT
}

/** Main-thread only. One visible request, no pending-request queue, finite global rate budget. */
internal class BrowserJsDialogController(
    private val now: () -> Long,
    private val changed: (BrowserJsDialogRequest?) -> Unit,
) {
    private class Pending(val request: BrowserJsDialogRequest, val owner: Any?, val valid: () -> Boolean,
        val blockDocument: () -> Unit, val complete: (Boolean, String?) -> Unit)
    private var pending: Pending? = null
    private var completing = false
    private var windowStart: Long? = null
    private var windowCount = 0

    fun offer(kind: BrowserJsDialogKind, origin: String?, message: String, defaultValue: String,
        allowed: Boolean, valid: () -> Boolean, blockDocument: () -> Unit, owner: Any? = null,
        complete: (Boolean, String?) -> Unit): Boolean {
        val time = now()
        val start = windowStart
        if (start == null || time < start || time - start >= BrowserJsDialogPolicy.WINDOW_MILLIS) {
            windowStart = time; windowCount = 0
        }
        if (!allowed || completing || pending != null || origin == null ||
            !BrowserJsDialogPolicy.validInput(defaultValue) || windowCount >= BrowserJsDialogPolicy.MAX_PER_WINDOW || !valid()) {
            // Result adapters must only complete the native result; never execute page scripts here.
            terminal { complete(false, null) }
            return false
        }
        windowCount++
        val request = BrowserJsDialogRequest(kind, origin, BrowserJsDialogPolicy.message(message),
            if (kind == BrowserJsDialogKind.PROMPT) defaultValue else "")
        pending = Pending(request, owner, valid, blockDocument, complete)
        changed(request)
        return true
    }

    fun finish(request: BrowserJsDialogRequest, accepted: Boolean, input: String? = null, block: Boolean = false) {
        val active = pending?.takeIf { it.request === request } ?: return
        // Invalid input is not silently truncated or transmitted; keep the dialog available.
        if (accepted && request.kind == BrowserJsDialogKind.PROMPT &&
            (input == null || !BrowserJsDialogPolicy.validInput(input))) return
        pending = null
        terminal {
            changed(null)
            if (block) active.blockDocument()
            val confirmed = accepted && !block && active.valid()
            active.complete(confirmed, if (confirmed && request.kind == BrowserJsDialogKind.PROMPT) input else null)
        }
    }
    fun cancel() { pending?.let { finish(it.request, false) } }
    fun cancel(owner: Any) { pending?.takeIf { it.owner === owner }?.let { finish(it.request, false) } }

    private inline fun terminal(action: () -> Unit) {
        val previous = completing; completing = true
        try { action() } finally { completing = previous }
    }
}
