package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*
import java.net.URI

/** Every string is inert text. Only dedicated, user-initiated validated link actions navigate. */
object RightsPresentationPolicy {
    fun summary(rights: ResultRights): List<String> = if (rights.snapshots.isEmpty())
        listOf("作者未知", "许可资料未知，需核对") else buildList {
        val snapshot = rights.snapshots.first()
        add("作者：${snapshot.author ?: "未知"}")
        add("许可：${snapshot.licenseName ?: "资料不完整"}（需核对）")
        snapshot.customAttribution?.let { add("指定署名：$it") }
        if (rights.snapshots.size > 1) add("另有 ${rights.snapshots.size - 1} 份来源记录")
        if (rights.warnings.isNotEmpty()) add("来源记录存在冲突或较早警示，请查看完整资料")
    }
    fun details(snapshot: RightsSnapshot): List<String> = listOf(
        "来源：${snapshot.sourceName}",
        "来源标识：${snapshot.providerId} / ${snapshot.sourceNamespace} / ${snapshot.sourceItemId}",
        "作者：${snapshot.author ?: "未知"}", "来源说明/鸣谢：${snapshot.credit ?: "未知"}",
        "指定署名：${snapshot.customAttribution ?: "未提供"}", "许可：${snapshot.licenseName ?: "未知"}",
        "使用条款：${snapshot.usageTerms ?: "未提供"}", "版权声明：${triState(snapshot.copyrighted)}",
        "需要署名：${triState(snapshot.attributionRequired)}", "非自由标记：${triState(snapshot.nonFree)}",
        "限制：${snapshot.restrictions.takeIf { it.isNotEmpty() }?.joinToString("；") ?: "未提供"}",
        "删除警示：${snapshot.deletionReason ?: "未提供"}",
        "资料状态：${when (snapshot.review) {
            RightsReview.INCOMPLETE -> "资料不完整，需核对"
            RightsReview.REPORTED_REQUIRES_REVIEW -> "来源报告，需核对"
            RightsReview.FLAGGED_REQUIRES_REVIEW -> "来源有警示，需核对"
        }}",
        "来源页面修订：${snapshot.sourcePageRevisionId ?: "未知"}",
        "观察时间（UTC）：${java.time.Instant.ofEpochMilli(snapshot.retrievedAtEpochMillis)}",
    ) + snapshot.provenance.map {
        "字段 ${it.field}：来源 ${it.apiReportedSource ?: "未知"}；" +
            (if (it.kind == RightsMetadataKind.EXTRACTED_UNSTRUCTURED_TEXT) "提取的非结构化文本" else "结构化元数据") +
            "；HTML转文本 ${triState(it.htmlConvertedToText)}；已截断 ${triState(it.truncated)}"
    }
    fun safeLink(value: String?): String? = try {
        value?.takeIf { v -> v.length in 1..8192 && v.none { it.isISOControl() } }?.let { URI(it) }
            ?.takeIf { it.scheme == "https" && it.host != null && it.userInfo == null && it.port == -1 && !it.isOpaque }
            ?.toASCIIString()
    } catch (_: Exception) { null }
    private fun triState(value: Boolean?): String = when(value) { true -> "是"; false -> "否"; null -> "未知" }
}
