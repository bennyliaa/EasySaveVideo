package com.easysavevideo.app.save

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.easysavevideo.app.BuildConfig
import com.easysavevideo.app.diagnostics.*
import com.easysavevideo.discovery.SaveEvaluationSessionReport
import java.util.concurrent.Executors

/** One current user-selected CREATE_DOCUMENT URI, no persisted grant/path/overwrite/truncation. */
internal class AndroidEvaluationDocumentPort private constructor(private val context: Context, private val uri: Uri) : DiagnosticExportPort {
    override fun open() = EvaluationEmptyDocumentPort {
        check(context.checkUriPermission(uri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED)
        val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri,"rw"))
        object : EvaluationAppendDocument {
            private var initialized = false
            override fun size(): Long {
                val fd = descriptor.fileDescriptor
                check(OsConstants.S_ISREG(Os.fstat(fd).st_mode))
                if (!initialized) {
                    // rw never requests truncation. O_APPEND prevents overwrite between size-check and write.
                    Os.fcntlInt(fd,OsConstants.F_SETFL,Os.fcntlInt(fd,OsConstants.F_GETFL,0) or OsConstants.O_APPEND)
                    check(Os.fcntlInt(fd,OsConstants.F_GETFL,0) and OsConstants.O_APPEND != 0)
                    initialized = true
                }
                return Os.fstat(fd).st_size.also { check(it >= 0) }
            }
            override fun append(bytes: ByteArray, offset: Int, length: Int) {
                var written = 0
                while (written < length) {
                    val count = Os.write(descriptor.fileDescriptor,bytes,offset + written,length - written)
                    check(count > 0);written += count
                }
            }
            override fun flush() { Os.fsync(descriptor.fileDescriptor) }
            override fun close() { descriptor.close() }
        }
    }.open()
    companion object {
        fun fromResult(context: Context, data: Intent?): AndroidEvaluationDocumentPort {
            requireNotNull(data);val uri=requireNotNull(data.data)
            require(uri.query == null && !DocumentsContract.isTreeUri(uri))
            require(data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
                context.checkUriPermission(uri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED)
            require(diagnosticDestinationAllowed(DiagnosticDestinationFacts(uri.scheme,uri.authority,uri.fragment!=null,
                uri.toString().toByteArray(Charsets.UTF_8).size,data.clipData!=null,DocumentsContract.isDocumentUri(context,uri),
                data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION!=0,
                context.checkUriPermission(uri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED)))
            return AndroidEvaluationDocumentPort(context.applicationContext,uri)
        }
    }
}

/** Activity-owned picker with fresh keys and a process-wide worker/fence, using the existing lifecycle. */
internal class AndroidEvaluationReportExport(
    private val activity: ComponentActivity, currentReport: () -> SaveEvaluationSessionReport?, changed: (DiagnosticExportState) -> Unit,
) : AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private var resumed=false
    private var closed=false
    private var pickerId:String?=null
    private var picker:ActivityResultLauncher<Intent>?=null
    private val delivery=DiagnosticPickerDelivery<ActivityResult>()
    val export=SealedEvaluationExport(BuildConfig.DEBUG,currentReport,worker,{ main.post { it() } },changed,
        choose=::choose,dismiss=::dismiss,fence=fence,monotonicMillis=android.os.SystemClock::elapsedRealtime,
        schedule={ delay,callback -> val task=Runnable { callback() };main.postDelayed(task,delay);AutoCloseable { main.removeCallbacks(task) } })
    fun begin(report: SaveEvaluationSessionReport?) = if (!closed && resumed) export.begin(report) else false
    private fun choose(id:String) {
        check(BuildConfig.DEBUG && !closed && resumed && picker==null)
        pickerId=id;delivery.begin(id)
        picker=activity.activityResultRegistry.register("esv-evaluation-$id",ActivityResultContracts.StartActivityForResult()) { result ->
            if(!closed && pickerId==id) { delivery.receive(id,result);deliver() }
        }
        try { requireNotNull(picker).launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="application/json"
            putExtra(Intent.EXTRA_TITLE,"EasySaveVideo-evaluation-$id.json")
            putExtra(Intent.EXTRA_LOCAL_ONLY,true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }) } catch (_:Exception) { export.controller.selectionFailed(id) }
    }
    private fun dismiss(id:String) {
        if(pickerId!=id)return
        picker?.unregister();picker=null;pickerId=null;delivery.dismiss(id)
    }
    private fun deliver() {
        if(!resumed || closed)return
        val (id,result)=delivery.take()?:return
        if(pickerId!=id)return
        if(result.resultCode!=Activity.RESULT_OK)export.controller.selected(id,null)
        else {
            val port=try { AndroidEvaluationDocumentPort.fromResult(activity,result.data) }
                catch (_:Exception) { export.controller.selectionFailed(id);return }
            export.controller.selected(id,port)
        }
    }
    fun onPostResume() { resumed=true;delivery.setResumed(true);export.controller.checkDeadline();deliver() }
    fun onPause() { resumed=false;delivery.setResumed(false);export.controller.background() }
    override fun close() {
        if(closed)return
        closed=true;resumed=false;export.close();picker?.unregister();picker=null;pickerId=null;delivery.close()
    }
    companion object {
        private val worker=Executors.newSingleThreadExecutor()
        private val fence=DiagnosticWorkFence()
    }
}
