package com.easysavevideo.app.browser.history

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.core.model.BrowserContextRef
import java.util.UUID
import java.util.concurrent.Executors

/** Process owner: Activity detach never closes the controller or creates another disk writer.
 * Retention is enforced on startup, foreground/list refresh and accepted visits. There is no
 * periodic/background alarm or promise of exact-time deletion while the app is inactive. */
class AndroidHistoryRuntime private constructor(context: Context) {
    var state by mutableStateOf(HistoryState()); private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val navigation = HistoryNavigationGate()
    private var slowCallback: Runnable? = null
    private var watchedOperation: Long? = null
    private val controller = HistoryController(AndroidHistoryStorage(context), navigation,
        HistoryRetentionPolicy(200, HistoryPolicy.THIRTY_DAYS_MILLIS),
        worker = { work -> worker.execute(work) },
        deliver = { result ->
            // A UI detachment must not discard this delivery or leave a submitted clear busy.
            check(main.post { result(); watch() })
        },
        newId = { UUID.randomUUID().toString() }, now = System::currentTimeMillis,
        changed = { state = it })

    init { load() }
    fun load() { requireMain(); controller.load(); watch() }
    fun row(item: BrowserHistoryItem): HistoryRowToken? { requireMain(); return controller.row(item) }
    fun addressForOpen(token: HistoryRowToken): String? { requireMain(); return controller.addressForOpen(token) }
    fun requestRemove(token: HistoryRowToken): HistoryClearConfirmation? { requireMain(); return controller.requestRemove(token) }
    fun requestClear(): HistoryClearConfirmation? { requireMain(); return controller.requestClear() }
    fun cancel(confirmation: HistoryClearConfirmation) { requireMain(); controller.cancel(confirmation) }
    fun confirm(confirmation: HistoryClearConfirmation) { requireMain(); controller.confirm(confirmation); watch() }
    fun dismissNotice() { requireMain(); controller.dismissNotice() }

    internal fun recorder(context: BrowserContextRef, view: Any): HistoryRecordingAdapter {
        requireMain()
        return HistoryRecordingAdapter(context, view, navigation) { visit ->
            requireMain(); controller.record(visit); watch()
        }
    }
    private fun watch() {
        val operation = controller.operationGeneration()
        if (operation == watchedOperation) return
        slowCallback?.let(main::removeCallbacks)
        slowCallback = null
        watchedOperation = operation
        if (operation == null) return
        slowCallback = Runnable { controller.slow(operation) }.also { main.postDelayed(it, 3_000) }
    }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }

    companion object {
        @Volatile private var instance: AndroidHistoryRuntime? = null
        fun get(context: Context): AndroidHistoryRuntime {
            check(Looper.myLooper() == Looper.getMainLooper())
            return instance ?: AndroidHistoryRuntime(context.applicationContext).also { instance = it }
        }
    }
}
