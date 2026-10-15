package com.easysavevideo.app.save.conversion

/** Original bounded literal-header check extracted from the executed external r1 mechanism. */
internal object WebmElementHeader {
    data class Header(val width: Int, val size: Long, val unknown: Boolean)

    fun read(binding: WebmInputBinding, id: Int, contentPosition: Long): Header {
        binding.headerChecks++
        val suffix = binding.suffix(contentPosition)
        val idSize = when { id > 0xFFFFFF -> 4; id > 0xFFFF -> 3; id > 0xFF -> 2; else -> 1 }
        val expectedId = ByteArray(idSize) { index -> (id ushr (8 * (idSize - index - 1))).toByte() }
        var count = 0
        var candidate: Header? = null
        for (width in 1..8) {
            val at = suffix.size - width - expectedId.size
            if (at < 0) continue
            var matches = true
            for (i in expectedId.indices) matches = matches && suffix[at + i] == expectedId[i]
            val first = suffix[at + expectedId.size].toInt() and 255
            val marker = 1 shl (8 - width)
            if (!matches || first < marker || first >= marker * 2) continue
            var size = (first and (marker - 1)).toLong()
            for (i in 1 until width) size = (size shl 8) or (suffix[at + expectedId.size + i].toLong() and 255)
            count++
            candidate = Header(width, size, size == (1L shl (7 * width)) - 1)
        }
        // No extent, budget, int, or unknown-kind filtering before complete syntax uniqueness.
        requireWebm(count == 1, WebmProbeFailure.HEADER_CANDIDATES)
        return requireNotNull(candidate)
    }
}
