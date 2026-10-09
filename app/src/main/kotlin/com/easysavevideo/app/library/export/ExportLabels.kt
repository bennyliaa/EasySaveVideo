package com.easysavevideo.app.library.export

/** Provider labels are display-only and never become URI/file authority. Strip control/bidi text. */
internal fun safeExportLabel(value: CharSequence, maxChars: Int): String {
    require(maxChars in 1..255)
    val result = StringBuilder(maxChars)
    var offset = 0
    while (offset < value.length && result.length < maxChars) {
        val codePoint = Character.codePointAt(value, offset)
        offset += Character.charCount(codePoint)
        if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT.toInt()) continue
        val width = Character.charCount(codePoint)
        if (result.length + width > maxChars) break
        if (codePoint in 0xd800..0xdfff) continue
        result.appendCodePoint(codePoint)
    }
    return result.toString().trim()
}
