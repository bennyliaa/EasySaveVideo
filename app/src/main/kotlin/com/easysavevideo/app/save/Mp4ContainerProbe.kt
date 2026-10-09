package com.easysavevideo.app.save

import java.io.RandomAccessFile

/** Bounded structural preflight. This is never playback/decode proof by itself. */
internal object Mp4ContainerProbe {
    private val brands = setOf("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1")
    fun isCompleteMp4(file: RandomAccessFile, checkpoint: () -> Unit = {}): Boolean {
        val length = file.length()
        if (length < 32) return false
        var offset = 0L
        var boxes = 0
        var ftyp = false
        var moov = false
        var mdat = false
        while (offset < length) {
            checkpoint()
            if (++boxes > 4096 || length - offset < 8) return false
            file.seek(offset)
            val shortSize = file.readInt().toLong() and 0xffff_ffffL
            val type = file.readInt()
            var header = 8L
            val size = when (shortSize) {
                0L -> length - offset
                1L -> {
                    if (length - offset < 16) return false
                    header = 16
                    file.readLong()
                }
                else -> shortSize
            }
            if (size < header || size > length - offset) return false
            when (type) {
                tag("ftyp") -> {
                    if (ftyp || offset > 4096 || size - header !in 8L..4096L || (size - header) % 4 != 0L) return false
                    val major = readBrand(file)
                    file.readInt() // Minor version is not a codec or validity signal.
                    if (major == "qt  " || major !in brands) return false
                    var remaining = size - header - 8
                    while (remaining > 0) { if (readBrand(file) == "qt  ") return false; remaining -= 4 }
                    ftyp = true
                }
                tag("moov") -> { if (size <= header || moov) return false; moov = true }
                tag("mdat") -> { if (size <= header) return false; mdat = true }
            }
            offset += size
        }
        return ftyp && moov && mdat && offset == length
    }
    private fun readBrand(file: RandomAccessFile): String = CharArray(4) { file.readUnsignedByte().toChar() }.concatToString()
    private fun tag(value: String) = value.fold(0) { acc, c -> (acc shl 8) or c.code }
}
