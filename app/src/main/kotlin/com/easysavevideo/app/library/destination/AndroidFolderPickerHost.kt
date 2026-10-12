package com.easysavevideo.app.library.destination

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Durable worker allocation before main-thread launch. Results are never restored after rotation. */
internal class AndroidFolderPickerHost(activity: ComponentActivity) : AutoCloseable {
    private val activity = WeakReference(activity)
    private val application = activity.applicationContext
    private var closed = false
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) results.cancelUnlaunched(this)
    }
    @Suppress("DEPRECATION")
    fun launch(intent: Intent, current: () -> Boolean = { true },
        failed: (Exception) -> Unit = {}, callback: (Int, Intent?) -> Unit) {
        requireMain()
        check(!closed)
        val host = requireNotNull(activity.get())
        check(host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val expected = WeakReference(host)
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        ensureAllocator(application)
        results.launch(this,
            current = { !closed && activity.get()?.let { it === expected.get() } == true && current() },
            launch = { code ->
                check(SystemClock.elapsedRealtime() < deadline)
                check(activity.get()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true)
                requireNotNull(activity.get()).startActivityForResult(intent, code)
            },
            failed = { failure -> failed(failure); callback(Activity.RESULT_CANCELED, null) },
            result = { (code, data) -> callback(code, data) })
    }
    override fun close() {
        requireMain(); closed = true; results.abandon(this)
        activity.get()?.let { it.lifecycle.removeObserver(lifecycleObserver); if (owners[it]?.get() === this) owners.remove(it) }; activity.clear()
    }
    companion object {
        private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
        private val main = Handler(Looper.getMainLooper())
        private val lane = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(8),
            { work -> Thread(work, "esv-folder-picker-allocation").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        private var allocator: DurableFolderPickerAllocator? = null
        private fun ensureAllocator(application: Context) {
            requireMain()
            if (allocator == null) allocator = DurableFolderPickerAllocator(
                read = {
                    try { application.getSharedPreferences("ordinary_folder_picker_sequence_v1", Context.MODE_PRIVATE)
                        .getInt("next", FolderPickerRequestCodes.FIRST) }
                    catch (_: Exception) { throw FolderPickerFailure(FolderPickerError.INVALID_STATE) }
                }, persist = { next ->
                    application.getSharedPreferences("ordinary_folder_picker_sequence_v1", Context.MODE_PRIVATE)
                        .edit().putInt("next", next).commit()
                })
        }
        private val results = FolderPickerAsyncBridge<AndroidFolderPickerHost, Pair<Int, Intent?>>(
            ::requireMain, { work -> lane.execute(work) }, { work -> main.post { work() }; Unit },
            { requireNotNull(allocator).allocate() })
        /** MainActivity intercepts this reserved range before AndroidX's saved registry. */
        fun dispatchActivityResult(host: ComponentActivity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
            requireMain()
            // Match the bridge owner by the exact live Activity, never by a restored request code.
            val owner = owners[host]?.get()
            return results.dispatch(owner, requestCode, resultCode to data)
        }
        private val owners = java.util.WeakHashMap<ComponentActivity, WeakReference<AndroidFolderPickerHost>>()
    }
    init {
        requireMain()
        requireNotNull(this.activity.get()).let {
            owners[it] = WeakReference(this)
            it.lifecycle.addObserver(lifecycleObserver)
        }
    }
}
