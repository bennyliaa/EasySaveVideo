package com.easysavevideo.app.library.share

/** Stop every owned capability before any potentially failing framework/filesystem operation. */
internal fun <T> revokeShareGroup(owned:List<T>,deny:(T)->Unit,revoke:(T)->Unit,cleanup:(T)->Unit) {
    owned.forEach(deny)
    owned.forEach { copy ->
        try { revoke(copy) } catch(_:Exception) { }
        try { cleanup(copy) } catch(_:Exception) { }
    }
}
