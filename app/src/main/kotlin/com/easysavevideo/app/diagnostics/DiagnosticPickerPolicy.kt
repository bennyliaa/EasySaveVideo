package com.easysavevideo.app.diagnostics

/** Pure policy over facts measured by Android; no URI parsing or grant assumptions in the core. */
internal data class DiagnosticDestinationFacts(
    val scheme: String?, val authority: String?, val hasFragment: Boolean,
    val utf8Bytes: Int, val hasClipData: Boolean, val isDocument: Boolean,
    val returnedWrite: Boolean, val actualWrite: Boolean,
)
internal fun diagnosticDestinationAllowed(f: DiagnosticDestinationFacts): Boolean =
    f.scheme == "content" && !f.authority.isNullOrBlank() && !f.hasFragment &&
        f.utf8Bytes in 1..8192 && !f.hasClipData && f.isDocument && f.returnedWrite && f.actualWrite

/** Main-thread, memory-only picker inbox. First current result only, delivered once while resumed. */
internal class DiagnosticPickerDelivery<T> {
    private var current: String? = null
    private var pending: T? = null
    private var received = false
    private var resumed = false
    private var closed = false
    fun begin(id: String) { check(!closed && current == null); current = id; received = false; pending = null }
    fun receive(id: String, value: T) {
        if (closed || current != id || received) return
        received = true; pending = value
    }
    fun setResumed(value: Boolean) { resumed = value }
    fun take(): Pair<String,T>? {
        if (closed || !resumed) return null
        val id = current ?: return null
        val value = pending ?: return null
        pending = null
        return id to value
    }
    fun dismiss(id: String) { if (current == id) { current = null; pending = null; received = false } }
    fun close() { closed = true; resumed = false; current = null; pending = null }
}
