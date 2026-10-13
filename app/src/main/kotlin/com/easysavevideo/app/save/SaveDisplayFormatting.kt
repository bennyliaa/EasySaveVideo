package com.easysavevideo.app.save

import java.util.Locale

internal enum class SaveDisplaySizeUnit { KIB, MIB, GIB }
internal data class SaveDisplaySize(val amount: String, val unit: SaveDisplaySizeUnit)

/** Presentation only: preserve existing binary units, precision and sub-MiB truncation. */
internal fun saveDisplaySize(bytes: Long, locale: Locale): SaveDisplaySize = when {
    bytes >= 1024L * 1024 * 1024 -> SaveDisplaySize(String.format(locale, "%.2f", bytes / (1024.0 * 1024 * 1024)), SaveDisplaySizeUnit.GIB)
    bytes >= 1024L * 1024 -> SaveDisplaySize(String.format(locale, "%.1f", bytes / (1024.0 * 1024)), SaveDisplaySizeUnit.MIB)
    else -> SaveDisplaySize(saveDisplayInteger(bytes / 1024, locale), SaveDisplaySizeUnit.KIB)
}
internal fun saveDisplayInteger(value: Long, locale: Locale): String = String.format(locale, "%d", value)

/** Same elapsed whole-second display as the existing recording helper; never changes timing. */
internal fun saveDisplayDuration(durationUs: Long, locale: Locale): String {
    val seconds = (durationUs / 1_000_000).coerceAtLeast(0)
    return String.format(locale, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/** Android plural selection takes Int; the displayed Long remains unmodified. */
internal fun saveDisplayQuantity(count: Long): Int = count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
