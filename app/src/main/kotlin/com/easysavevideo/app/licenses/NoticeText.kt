package com.easysavevideo.app.licenses

import java.io.Reader

/** Offline notice growth remains bounded before allocation, including stalled readers. */
internal fun readNoticeText(reader: Reader, limit: Int = 1024 * 1024): String {
    require(limit > 0)
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        val count = reader.read(buffer, 0, minOf(buffer.size, limit - result.length + 1))
        if (count == -1) return result.toString()
        check(count > 0 && result.length + count <= limit) { "NOTICE_TEXT_UNAVAILABLE" }
        result.append(buffer, 0, count)
    }
}

/** Lossless display chunks. Do not split UTF-16 surrogate pairs or CRLF; prefer existing newlines. */
internal fun noticeSections(text:String,maxChars:Int=4096):List<String> {
    require(maxChars>=2)
    val sections=ArrayList<String>()
    var start=0
    while(start<text.length) {
        var end=(start.toLong()+maxChars).coerceAtMost(text.length.toLong()).toInt()
        if(end<text.length) {
            if(text[end-1].isHighSurrogate() && text[end].isLowSurrogate())end--
            if(end>start && text[end-1]=='\r' && text[end]=='\n')end--
            val newline=text.lastIndexOf('\n',end-1)
            if(newline>=start)end=newline+1
        }
        check(end>start)
        sections+=text.substring(start,end)
        start=end
    }
    return sections
}

/** Empty/corrupt bundled content is unavailable, not an endless loading state. */
internal fun noticeLoadUnavailable(sections:List<String>?)=sections.isNullOrEmpty()

internal fun noticeContentVisible(unavailable:Boolean,sections:List<String>)=!unavailable && sections.isNotEmpty()
