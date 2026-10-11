package com.easysavevideo.app.discovery

import com.easysavevideo.app.R
import java.util.Locale

enum class FilterCopy(val resource: Int, val chinese: String) {
    C0(R.string.filter_copy_0, "SearXNG 候选来源 %1\$s"),
    C1(R.string.filter_copy_1, "本地合成来源（隔离演示）"),
    C2(R.string.filter_copy_2, "最短时长须为非负秒数，最多三位小数；留空表示不限。"),
    C3(R.string.filter_copy_3, "最长时长须为非负秒数，最多三位小数；留空表示不限。"),
    C4(R.string.filter_copy_4, "最短时长不能大于最长时长；未改动已应用资料。"),
    C5(R.string.filter_copy_5, "来源筛选无效；未改动已应用资料。"),
    C6(R.string.filter_copy_6, "全部当前启用来源（没有启用来源时仍为零来源）"),
    C7(R.string.filter_copy_7, "%1\$s（未启用或未知；筛选已保留）"),
    C8(R.string.filter_copy_8, " 秒"),
    C9(R.string.filter_copy_9, "不限"),
    C10(R.string.filter_copy_10, "最短 %1\$s；"),
    C11(R.string.filter_copy_11, "最长 %1\$s；"),
    C12(R.string.filter_copy_12, "包含"),
    C13(R.string.filter_copy_13, "排除"),
    C14(R.string.filter_copy_14, "%1\$s未知时长"),
    C15(R.string.filter_copy_15, "本地合成演示：来源与时长筛选只处理合成记录，不能据此证明真实来源能力。"),
    C16(R.string.filter_copy_16, "发布日期未知；安全搜索没有经过真实内容验证。"),
    C17(R.string.filter_copy_17, "%1\$s：安全搜索能力尚未核验；开启时不会向该来源发送查询。"),
    C18(R.string.filter_copy_18, "返回内容仅为来源报告的待验证网页；时长按未知处理，排除未知时长时不展示。"),
    C19(R.string.filter_copy_19, "未验证视频、图片、时长或许可；不会自动获取缩略图或网页。"),
    C20(R.string.filter_copy_20, "%1\$s：不支持安全搜索；开启时不会向此来源发送查询。"),
    C21(R.string.filter_copy_21, "时长来自来源报告，可能未知；最短、最长与未知时长开关在本地筛选，不改写关键词。"),
    C22(R.string.filter_copy_22, "发布日期未知：当前适配器未提供。作者与许可为来源报告，需逐文件核对。"),
    C23(R.string.filter_copy_23, "原生视频的 SAVED（保存）与 PLAYED（播放）仍为 UNKNOWN（未知）。打开网页或来自该页的保存不能证明所选发现视频已保存或播放。"),
}

fun interface FilterCopyText {
    fun get(key: FilterCopy, vararg args: Any?): String
    companion object { val CHINESE = FilterCopyText { key, args -> String.format(Locale.ROOT, key.chinese, *args) } }
}
