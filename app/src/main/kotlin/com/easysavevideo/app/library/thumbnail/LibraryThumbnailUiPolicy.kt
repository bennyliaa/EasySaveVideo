package com.easysavevideo.app.library.thumbnail

internal fun libraryThumbnailAdmission(requested: Boolean, foreground: Boolean, ready: Boolean,
    busy: Boolean, loading: Boolean, modalOpen: Boolean, moveActive: Boolean, nativeReady: Boolean): Boolean =
    requested && foreground && ready && !busy && !loading && !modalOpen && !moveActive && nativeReady

/** Viewport identity only. It grants no media reference or reader. */
internal fun libraryVisibleThumbnailIds(keys: List<Any>): Set<String> = keys.filterIsInstance<String>()
    .filter { it.startsWith("media:") && it.length > 6 }.map { it.removePrefix("media:") }.toSet()
