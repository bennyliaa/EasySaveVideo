package com.easysavevideo.app.save

/** Overflow-safe seek bounds. A player with unknown duration stays at the start. */
internal object PreviewSeekPolicy {
    fun target(positionMillis: Int, durationMillis: Int, deltaMillis: Int): Long =
        (positionMillis.toLong().coerceAtLeast(0) + deltaMillis.toLong())
            .coerceIn(0L, durationMillis.toLong().coerceAtLeast(0))
}
