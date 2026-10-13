package com.easysavevideo.app.browser

/** Advisory DOM evidence only; never proof of playability, exportability, rights, or identity. */
internal enum class BrowserVideoPresence { PRESENT, NOT_OBSERVED, UNKNOWN }

/** Process-local lifetime of one actual document; switching away never refunds its budget. */
internal class BrowserVideoPresenceBudget { var attempts: Int = 0; internal set }

/** No page strings. Owner/document identity prevents same-URL reload and switch-back borrowing. */
internal data class BrowserVideoPresenceTarget(
    val owner: Any,
    val document: BrowserVideoPresenceBudget,
    val epoch: Long,
    val interaction: Long,
    val navigation: Long,
)

/** Main-thread coordinator with injected native scheduling. Nothing is installed into the page. */
internal class BrowserVideoPresenceController(
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val evaluate: (BrowserVideoPresenceTarget, (String?) -> Unit) -> Unit,
    private val changed: (BrowserVideoPresence) -> Unit,
) {
    private var target: BrowserVideoPresenceTarget? = null
    private var generation = 0L
    private var cancelScheduled: (() -> Unit)? = null
    private var cancelTimeout: (() -> Unit)? = null
    private val recentAttempts = ArrayList<Long>(MAX_PER_WINDOW)
    private var lastAttempt: Long? = null
    private var closed = false

    fun update(value: BrowserVideoPresenceTarget?) {
        if (closed || value == target) return
        generation++
        cancelPending()
        target = value
        changed(BrowserVideoPresence.UNKNOWN)
        if (value != null) queue(value, generation)
    }

    private fun queue(value: BrowserVideoPresenceTarget, token: Long) {
        if (value.document.attempts >= MAX_PER_DOCUMENT) return
        val time = now()
        val last = lastAttempt
        val delay = if (last == null || time < last) 0L else (MIN_INTERVAL_MILLIS - (time - last)).coerceAtLeast(0)
        cancelScheduled = schedule(delay) {
            cancelScheduled = null
            if (closed || generation != token || target != value) return@schedule
            val currentTime = now()
            recentAttempts.removeAll { currentTime < it || currentTime - it >= WINDOW_MILLIS }
            // Budget exhaustion does not schedule an endless cooldown/retry loop.
            if (recentAttempts.size >= MAX_PER_WINDOW || value.document.attempts >= MAX_PER_DOCUMENT) return@schedule
            recentAttempts.add(currentTime); value.document.attempts++; lastAttempt = currentTime
            var delivered = false
            fun deliver(raw: String?) {
                if (closed || token != generation || target != value || delivered) return
                delivered = true
                cancelTimeout?.invoke(); cancelTimeout = null
                val result = decode(raw)
                changed(result)
                if (result != BrowserVideoPresence.PRESENT) queue(value, token)
            }
            cancelTimeout = schedule(TIMEOUT_MILLIS) { deliver(null) }
            try { evaluate(value, ::deliver) } catch (_: RuntimeException) { deliver(null) }
        }
    }

    fun close() {
        if (closed) return
        closed = true; generation++; target = null; cancelPending()
        changed(BrowserVideoPresence.UNKNOWN)
    }
    private fun cancelPending() {
        cancelScheduled?.invoke(); cancelScheduled = null
        cancelTimeout?.invoke(); cancelTimeout = null
    }

    companion object {
        // Conservative engineering defaults, not externally specified product settings.
        const val MAX_PER_DOCUMENT = 2
        const val MAX_PER_WINDOW = 4
        const val WINDOW_MILLIS = 30_000L
        const val MIN_INTERVAL_MILLIS = 1_500L
        const val TIMEOUT_MILLIS = 1_000L
        fun decode(raw: String?): BrowserVideoPresence = when (raw) {
            "true" -> BrowserVideoPresence.PRESENT
            "false" -> BrowserVideoPresence.NOT_OBSERVED
            else -> BrowserVideoPresence.UNKNOWN
        }
    }
}
