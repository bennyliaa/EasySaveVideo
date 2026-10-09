package com.easysavevideo.core.model

/** Sensitive functional state: never serialize to diagnostics. HTTP(S) only at this boundary. */
data class PageAddress(val value: String) {
    init {
        require(value.length <= 8192)
        require(value.none { it.isWhitespace() || it.code < 32 })
        require(value.startsWith("https://", true) || value.startsWith("http://", true))
        val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        require(authority.isNotBlank() && '@' !in authority && '\\' !in value)
        val host = if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        val port = authority.removePrefix(host)
        require(host.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")) ||
            host.matches(Regex("\\[[0-9A-Fa-f:.]+\\]")))
        require(".." !in host && !host.startsWith('.') && !host.endsWith('.'))
        require(port.isEmpty() || port.matches(Regex(":\\d{1,5}")) && port.drop(1).toIntOrNull()?.let { it in 1..65535 } == true)
        require(Regex("%(?![0-9A-Fa-f]{2})").find(value) == null)
    }
    override fun toString(): String = "PageAddress([redacted])"
}

enum class BrowserLoadState { IDLE, LOADING, READY, FAILED, CANCELLED, RENDERER_UNAVAILABLE }
data class BrowserContextRef(val tabId: String, val sessionId: String) {
    init { require(tabId.isNotBlank()); require(sessionId.isNotBlank()) }
}
data class BrowserRequest(
    val context: BrowserContextRef,
    val epoch: Long,
    val address: PageAddress?,
    val operation: OperationContext,
) { init { require(epoch > 0) } }
data class BrowserTab(
    val context: BrowserContextRef,
    val incognito: Boolean = false,
    val epoch: Long = 0,
    val address: PageAddress? = null,
    val title: String? = null,
    val loadState: BrowserLoadState = BrowserLoadState.IDLE,
    val progressPercent: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: ErrorEnvelope? = null,
) {
    init { require(epoch >= 0); require(progressPercent in 0..100) }
}
data class BrowserSessionState(
    val tabs: List<BrowserTab> = emptyList(),
    val selectedTabId: String? = null,
) {
    init {
        require(tabs.map { it.context.tabId }.distinct().size == tabs.size)
        require(selectedTabId == null || tabs.any { it.context.tabId == selectedTabId })
        require(tabs.isEmpty() == (selectedTabId == null))
    }
    val selectedTab: BrowserTab? get() = tabs.firstOrNull { it.context.tabId == selectedTabId }
}

/** No cookies, tokens, WebView history blobs, or incognito tabs are part of this port. */
data class RestorableTab(val tabId: String, val address: PageAddress?)
data class BrowserSessionCheckpoint(val tabs: List<RestorableTab>, val selectedTabId: String?)
interface BrowserSessionStore {
    fun load(): BrowserSessionCheckpoint?
    fun save(checkpoint: BrowserSessionCheckpoint)
}

/** Adapter owns renderer groups, real cancellation, history and system permission prompts. */
interface BrowserAdapter {
    fun load(request: BrowserRequest)
    fun stop(context: BrowserContextRef)
    fun destroy(context: BrowserContextRef)
}
