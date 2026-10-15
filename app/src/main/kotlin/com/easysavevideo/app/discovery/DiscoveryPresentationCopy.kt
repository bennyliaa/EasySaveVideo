package com.easysavevideo.app.discovery

import java.text.NumberFormat
import java.util.Locale

/**
 * Immutable, presentation-only snapshots. The Android caller supplies every resource field and its
 * current locale. Defaults retain the historical Chinese output for non-UI callers only.
 * No Resources, controller, mutable formatter, callback or external content is retained here.
 */
data class DiscoveryPresentationCopy(
    val locale: Locale = Locale.ROOT,
    val localizedCounts: Boolean = false,
    val sortRelevance: String = "来源顺序",
    val sortNewest: String = "发布日期：新到旧",
    val sortShortest: String = "时长：短到长",
    val sortLongest: String = "时长：长到短",
    val retention: String = "本地容量上限：%1\$s 个资料、%2\$s 条 Seen 别名、%3\$s 条来源进度、%4\$s 个会话检查点。按容量保留，不按天数过期；优先清理最久未在本地变更且未受保护的记录，当前已生成页面的数据受保护。记录被清理后，旧结果可能再次出现。Seen 历史与来源进度独立保存，不会因此补记展示、打开、播放或保存事件，也不会删除媒体文件或 Vault。",
    val statistics: String = "%1\$s：%2\$s 个资料 · %3\$s 个会话检查点 · %4\$s 个 Seen 身份 · %5\$s 条 Seen 别名 · %6\$s 条来源进度",
    val providerProgress: String = "来源 %1\$s：%2\$s%3\$s%4\$s",
    val providerNotStarted: String = "尚未查询",
    val providerRequesting: String = "正在请求",
    val providerRetryWait: String = "等待本次重试",
    val providerCooldown: String = "来源冷却，稍后可重试",
    val providerUnsupportedFilter: String = "不支持当前筛选",
    val providerInvalidQuery: String = "关键词不受支持",
    val providerUnavailable: String = "来源配置不可用",
    val providerReady: String = "可按需继续",
    val providerExhausted: String = "当前查询已无后续页",
    val providerCursorExpired: String = "续页已过期，下次从头检查",
    val providerCursorIncompatible: String = "续页版本不兼容，下次从头检查",
    val providerSuspended: String = "已暂停",
    val providerStorageUnavailable: String = "本地进度不可读",
    val committedPage: String = " · 有已提交结果页",
    val savedContinuation: String = " · 续页已保存",
    val noticeSourceConfigurationChecking: String = "正在核对来源配置；原查询不会自动发送。",
    val noticeVerificationNotStarted: String = "当前未启动验证：请确认前台状态、来源及剩余预算；候选仍保留。",
    val noticeViewSettingsChanged: String = "已保留当前页；新的重复策略和每页数量应用于后续页。",
    val noticeInvalidQuery: String = "关键词含无效字符，请修改后重试。",
    val noticeSortChanged: String = "已保留当前页；新的本地排序应用于后续页。未知日期或时长排在已知值之后。",
    val noticeStorageRetryUnavailable: String = "本地发现数据暂不可读，原数据已保留。地址栏仍可使用。",
    val noticeStorageRecoveryUnavailable: String = "发现数据暂不可读，原始数据已保留。仍可使用浏览器地址栏。",
    val noticeControlStale: String = "查询或本地视图已变化，未执行操作。请重新打开确认。",
    val noticeControlFailed: String = "本地操作未成功，未确认任何清除或重置。请检查本地存储状态后重试。",
    val noticeControlCurrentHistoryCleared: String = "已清除当前资料的 Seen 历史，来源进度和媒体文件保留。",
    val noticeControlAllHistoryCleared: String = "已清除全部资料的 Seen 历史，来源进度和媒体文件保留。",
    val noticeControlProviderProgressReset: String = "已重置所选来源在当前资料的进度，Seen 历史和其他来源进度保留。",
    val duration: String = "%1\$d:%2\$02d",
) {
    fun format(template: String, vararg arguments: Any): String = String.format(locale, template, *arguments)
    fun count(value: Int): String = if (localizedCounts) NumberFormat.getIntegerInstance(locale).format(value) else value.toString()
}

data class RightsPresentationCopy(
    val locale: Locale = Locale.ROOT,
    val localizedCounts: Boolean = false,
    val authorUnknown: String = "作者未知",
    val licenseUnknown: String = "许可资料未知，需核对",
    val author: String = "作者：%1\$s",
    val summaryLicense: String = "许可：%1\$s（需核对）",
    val customAttribution: String = "指定署名：%1\$s",
    val otherRecords: String = "另有 %1\$s 份来源记录",
    val warnings: String = "来源记录存在冲突或较早警示，请查看完整资料",
    val source: String = "来源：%1\$s",
    val sourceIdentity: String = "来源标识：%1\$s / %2\$s / %3\$s",
    val credit: String = "来源说明/鸣谢：%1\$s",
    val license: String = "许可：%1\$s",
    val usageTerms: String = "使用条款：%1\$s",
    val copyrighted: String = "版权声明：%1\$s",
    val attributionRequired: String = "需要署名：%1\$s",
    val nonFree: String = "非自由标记：%1\$s",
    val restrictions: String = "限制：%1\$s",
    val restrictionSeparator: String = "；",
    val deletionReason: String = "删除警示：%1\$s",
    val review: String = "资料状态：%1\$s",
    val reviewIncomplete: String = "资料不完整，需核对",
    val reviewReported: String = "来源报告，需核对",
    val reviewFlagged: String = "来源有警示，需核对",
    val sourceRevision: String = "来源页面修订：%1\$s",
    val observedAtUtc: String = "观察时间（UTC）：%1\$s",
    val provenance: String = "字段 %1\$s：来源 %2\$s；%3\$s；HTML转文本 %4\$s；已截断 %5\$s",
    val unstructured: String = "提取的非结构化文本",
    val structured: String = "结构化元数据",
    val yes: String = "是",
    val no: String = "否",
    val unknown: String = "未知",
    val notProvided: String = "未提供",
    val incomplete: String = "资料不完整",
) {
    fun format(template: String, vararg arguments: Any): String = String.format(locale, template, *arguments)
    fun count(value: Int): String = if (localizedCounts) NumberFormat.getIntegerInstance(locale).format(value) else value.toString()
}
