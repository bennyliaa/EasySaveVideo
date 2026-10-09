package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*

/** Explicitly opt-in, synthetic local fixtures. This file cannot enter the Release source set. */
object BuildDiscovery {
    const val supportsDemo = true
    fun registry(demoEnabled: Boolean, commonsEnabled: Boolean = true, searxng: SearxngSourceConfiguration? = null): ProviderRegistry =
        ProviderRegistry(if (demoEnabled) listOf(DemoVideoProvider()) else SearxngSourceRegistration.bindGeneration(CommonsSourceRegistration.providers(commonsEnabled) + SearxngSourceRegistration.providers(searxng), searxng, commonsEnabled))
}
private class DemoVideoProvider : VideoSearchProvider {
    override val descriptor = ProviderDescriptor("local-demo", "1")
    override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage {
        operation.check(System.currentTimeMillis())
        val offset = request.progress?.cursor?.toIntOrNull() ?: 0
        val end = minOf(offset + 25, 160)
        return ProviderPage(results = (offset until end).map { index ->
            RawVideoResult("https://example.invalid/esv-demo/$index", true, "演示数据 · 视频卡片 ${index + 1}",
                "本地合成来源", "local-demo", "demo-$index", durationMs = if (index % 5 == 0) null else (index + 1L) * 10_000,
                description = "仅测试分页与导航，不是真实视频，也没有发出搜索网络请求。")
        }, nextCursor = if (end < 160) end.toString() else null, exhausted = end >= 160)
    }
}
