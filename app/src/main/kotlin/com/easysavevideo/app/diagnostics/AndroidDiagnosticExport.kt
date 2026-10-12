package com.easysavevideo.app.diagnostics

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors

/** Only constructed for the current one-shot CREATE_DOCUMENT result; never a path or durable grant. */
internal class AndroidDiagnosticExportPort private constructor(private val context: Context, private val uri: Uri) : DiagnosticExportPort {
    override fun open(): OutputStream {
        require(context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED)
        // One attempt, no append/retry/delete and no claim of provider-level atomicity.
        return requireNotNull(context.contentResolver.openOutputStream(uri, "w"))
    }
    companion object {
        fun fromResult(context: Context, data: Intent?): AndroidDiagnosticExportPort {
            requireNotNull(data)
            val uri = requireNotNull(data.data)
            require(diagnosticDestinationAllowed(DiagnosticDestinationFacts(
                scheme = uri.scheme, authority = uri.authority, hasFragment = uri.fragment != null,
                utf8Bytes = uri.toString().toByteArray(Charsets.UTF_8).size, hasClipData = data.clipData != null,
                isDocument = DocumentsContract.isDocumentUri(context, uri),
                returnedWrite = data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
                actualWrite = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED,
            )))
            return AndroidDiagnosticExportPort(context.applicationContext, uri)
        }
    }
}

/** Activity-owned runtime, deliberately never saved/restored. Each picker has a fresh registry key. */
internal class AndroidDiagnosticExportRuntime(
    private val activity: ComponentActivity,
    changed: (DiagnosticExportState) -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var closed = false
    private var pickerId: String? = null
    private var picker: ActivityResultLauncher<Intent>? = null
    private val delivery = DiagnosticPickerDelivery<ActivityResult>()
    val controller = DiagnosticExportController(
        read = DiagnosticSnapshotReader(File(activity.noBackupFilesDir, "technical_errors"))::read,
        worker = worker, dispatch = { main.post { it() } }, changed = changed,
        fence = workFence, monotonicMillis = android.os.SystemClock::elapsedRealtime,
        schedule = { delay, callback ->
            val task = Runnable { callback() }
            main.postDelayed(task, delay)
            AutoCloseable { main.removeCallbacks(task) }
        },
        choose = ::choose, dismiss = ::dismiss,
    )
    private fun choose(id: String) {
        check(!closed && resumed)
        check(picker == null)
        pickerId = id
        delivery.begin(id)
        // Lifecycle-less registration supports user-triggered registration after STARTED.
        // A new key on every attempt means an old/recreated Activity result cannot authorize a new export.
        picker = activity.activityResultRegistry.register("esv-diagnostics-$id", ActivityResultContracts.StartActivityForResult()) { result ->
            if (!closed && pickerId == id) {
                delivery.receive(id, result)
                deliver()
            }
        }
        try {
            requireNotNull(picker).launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "EasySaveVideo-technical-errors.txt")
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            })
        } catch (_: Exception) { controller.selectionFailed(id) }
    }
    private fun dismiss(id: String) {
        if (pickerId != id) return
        picker?.unregister(); picker = null; pickerId = null; delivery.dismiss(id)
    }
    private fun deliver() {
        if (!resumed || closed) return
        val (id, result) = delivery.take() ?: return
        if (pickerId != id) return
        if (result.resultCode != Activity.RESULT_OK) controller.selected(id, null)
        else {
            val port = try { AndroidDiagnosticExportPort.fromResult(activity, result.data) }
                catch (_: Exception) { controller.selectionFailed(id); return }
            controller.selected(id, port)
        }
    }
    fun onPostResume() { resumed = true; delivery.setResumed(true); controller.checkDeadline(); deliver() }
    fun onPause() { resumed = false; delivery.setResumed(false); controller.background() }
    override fun close() {
        if (closed) return
        closed = true; resumed = false; controller.close()
        picker?.unregister(); picker = null; pickerId = null; delivery.close()
        // The process-scoped worker/fence survives recreation. A stuck or unconfirmed close
        // cannot create more workers, replay the export, or admit a second operation.
    }
    companion object {
        private val worker = Executors.newSingleThreadExecutor()
        private val workFence = DiagnosticWorkFence()
    }
}
