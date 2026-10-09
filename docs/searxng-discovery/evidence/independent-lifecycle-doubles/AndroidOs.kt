package android.os
/** Compile-only Android doubles. Tests always inject their own queued publication. */
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(@Suppress("UNUSED_PARAMETER") looper: Looper) {
    fun post(@Suppress("UNUSED_PARAMETER") action: () -> Unit): Boolean = error("Unexpected Android Handler path")
}
