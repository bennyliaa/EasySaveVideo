package com.easysavevideo.library.thumbnail

/**
 * Strongly held by the process native-owner gate before any resource is acquired. A native reader
 * may duplicate the input descriptor, so native release must return before closing that descriptor
 * and the catalog fence. Failure retains the failed handle and everything later in the chain.
 */
class ThumbnailReadResources {
    var nativeReader: AutoCloseable? = null
    var input: AutoCloseable? = null
    var catalogFence: AutoCloseable? = null
    private var uncertain = false
    var released: Boolean = false; private set

    fun releaseConfirmed(): Boolean {
        if (released) return true
        if (uncertain) return false // An uncertain close is never speculatively retried.
        return try {
            nativeReader?.close(); nativeReader = null
            input?.close(); input = null
            catalogFence?.close(); catalogFence = null
            released = true
            true
        } catch (_: Exception) { uncertain = true; false }
    }
}
