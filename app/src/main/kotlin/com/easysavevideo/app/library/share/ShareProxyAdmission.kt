package com.easysavevideo.app.library.share

/** Framework open may synchronously await callbacks that need this same ownership lock. */
internal object ShareProxyAdmission {
    fun <T, R> open(lock: Any, reserve: () -> T, open: (T) -> R): R {
        val owner = synchronized(lock) { reserve() }
        return open(owner)
    }
}
