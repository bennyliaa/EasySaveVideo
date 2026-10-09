package com.easysavevideo.app.browser.bookmarks

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import java.util.concurrent.Executors

/** Application owner survives Activity rotation. Accepted commits are not cancelled on UI detach. */
class AndroidBookmarkRuntime private constructor(context: Context) {
    var state by mutableStateOf(BookmarkState()); private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var slowCallback: Runnable? = null
    private val controller = BookmarkController(AndroidBookmarkStorage(context),
        worker = { work -> worker.execute(work) }, deliver = { result -> main.post(result) },
        newId = { UUID.randomUUID().toString() }, changed = { state = it })
    init { load() }
    fun load() { requireMain(); controller.load(); watch() }
    fun add(page: BookmarkPageToken) { requireMain(); controller.add(page); watch() }
    fun remove(item: BrowserBookmark) { requireMain(); controller.remove(item); watch() }
    fun addressForOpen(item: BrowserBookmark): String? { requireMain(); return controller.addressForOpen(item) }
    fun stalePage() { requireMain(); controller.stalePage() }
    fun dismissNotice() { requireMain(); controller.dismissNotice() }
    private fun watch() {
        slowCallback?.let(main::removeCallbacks)
        val operation = controller.operationGeneration() ?: return
        slowCallback = Runnable { controller.slow(operation) }.also { main.postDelayed(it, 3_000) }
    }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    companion object {
        @Volatile private var instance: AndroidBookmarkRuntime? = null
        fun get(context: Context): AndroidBookmarkRuntime {
            check(Looper.myLooper() == Looper.getMainLooper())
            return instance ?: AndroidBookmarkRuntime(context.applicationContext).also { instance = it }
        }
    }
}
