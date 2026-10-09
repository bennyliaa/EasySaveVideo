package com.easysavevideo.app.recording.nativeregion

/** Pure bounded ownership policy. No resource destructor can race an outstanding reader. */
internal class RegionPreviewPixelLease<T : Any>(value: T) : AutoCloseable {
    private var value: T? = value
    private var closed = false
    private var readers = 0

    @Synchronized fun acquire(): Reader<T>? {
        if (closed || readers >= 2) return null
        val pixels = value ?: return null
        readers++
        return Reader(pixels) { releaseReader() }
    }
    @Synchronized override fun close() {
        closed = true
        if (readers == 0) value = null
    }
    @Synchronized private fun releaseReader() {
        check(readers > 0)
        readers--
        if (closed && readers == 0) value = null
    }
    @Synchronized internal fun retainedByOwner(): Boolean = value != null

    class Reader<T : Any> internal constructor(value: T, private val released: () -> Unit) : AutoCloseable {
        private var current: T? = value
        val value: T @Synchronized get() = checkNotNull(current)
        @Synchronized override fun close() {
            if (current == null) return
            current = null
            released()
        }
    }
}
