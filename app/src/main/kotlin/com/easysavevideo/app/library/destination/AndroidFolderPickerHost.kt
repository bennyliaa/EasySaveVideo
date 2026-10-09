package com.easysavevideo.app.library.destination

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference

/** A folder-only, non-restored result bridge. No URI/Intent/callback enters saved result state.
 * A durable correlation counter is consumed before launch, never used as grant authority and never
 * reused across process death. Rotation abandons the callback; old results are consumed/discarded. */
internal class AndroidFolderPickerHost(activity: ComponentActivity) : AutoCloseable {
    private val activity = WeakReference(activity)
    private var closed = false
    @Suppress("DEPRECATION")
    fun launch(intent: Intent, callback: (Int, Intent?) -> Unit) {
        check(!closed)
        val host = requireNotNull(activity.get())
        check(host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        FolderPickerResults.launch(this, {
            val preferences = host.applicationContext.getSharedPreferences("ordinary_folder_picker_sequence_v1", Context.MODE_PRIVATE)
            val next = try { preferences.getInt("next", FolderPickerRequestCodes.FIRST) }
                catch (_: Exception) { throw FolderPickerFailure(FolderPickerError.INVALID_STATE) }
            FolderPickerRequestCodes.consume(next) {
                preferences.edit().putInt("next", it).commit()
            }
        }, { code -> host.startActivityForResult(intent, code) }, callback)
    }
    private fun owns(host: ComponentActivity): Boolean = !closed && activity.get() === host
    override fun close() {
        closed = true; FolderPickerResults.abandon(this); activity.clear()
    }
    companion object {
        /** MainActivity must intercept this reserved range BEFORE its saved AndroidX registry. */
        fun dispatchActivityResult(host: ComponentActivity, requestCode: Int, resultCode: Int, data: Intent?): Boolean =
            FolderPickerResults.dispatch(host, requestCode, resultCode, data)
    }
    private object FolderPickerResults {
        private class Pending(val code: Int, var owner: AndroidFolderPickerHost?, var callback: ((Int, Intent?) -> Unit)?)
        private var pending: Pending? = null
        @Synchronized fun launch(owner: AndroidFolderPickerHost, allocate: () -> Int,
            launch: (Int) -> Unit, callback: (Int, Intent?) -> Unit) {
            if (pending != null) throw FolderPickerFailure(FolderPickerError.PENDING)
            val code = allocate()
            val request = Pending(code, owner, callback)
            pending = request
            try { launch(code) } catch (failure: Exception) {
                // No future request will reuse this code, including an uncertain launch outcome.
                if (pending === request) pending = null
                throw failure
            }
        }
        @Synchronized fun abandon(owner: AndroidFolderPickerHost) {
            pending?.takeIf { it.owner === owner }?.let { it.owner = null; it.callback = null }
        }
        fun dispatch(host: ComponentActivity, code: Int, result: Int, data: Intent?): Boolean {
            if (!FolderPickerRequestCodes.owns(code)) return false
            val callback = synchronized(this) {
                val request = pending
                if (request?.code != code) null else {
                    pending = null
                    if (request.owner?.owns(host) == true) request.callback else null
                }
            }
            callback?.invoke(result, data)
            return true // Cold-process and abandoned results never reach a restored result registry.
        }
    }
}
