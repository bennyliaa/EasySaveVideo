package com.easysavevideo.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.easysavevideo.app.MainActivity
import com.easysavevideo.app.R
import com.easysavevideo.app.save.AndroidSaveRuntime
import com.easysavevideo.recording.RecordingStopReason

/** User-started, non-sticky owner. Service commands carry identifiers only, never projection grants. */
class RecordingForegroundService : Service() {
    private var requestId: String? = null
    private var receiverRegistered = false
    private var finishing = false
    private val runtime get() = AndroidSaveRuntime.get(applicationContext)
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) requestId?.let {
                runtime.safetyStopRecording(it, RecordingStopReason.SYSTEM_STOPPED)
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(REQUEST_ID)
        if (id == null || !id.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) {
            if (requestId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            if (requestId == id) runtime.stopRecording(id)
            else if (requestId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action != ACTION_START || requestId != null || !runtime.hasPendingRecording(id)) {
            if (requestId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        requestId = id
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "屏幕录制", NotificationManager.IMPORTANCE_LOW))
            // FGS promotion precedes the only getMediaProjection call in the recording port.
            startForeground(NOTIFICATION_ID, notification(id, "正在准备录制"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            receiverRegistered = true
            runtime.onRecordingServiceReady(this, id)
        } catch (_: RuntimeException) {
            runtime.recordingStartRejected(id)
            finishSession(id)
        }
        return START_NOT_STICKY
    }
    fun showStatus(id: String, status: String) {
        if (requestId != id || finishing) return
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(id, status)) }
        catch (_: RuntimeException) { /* Missing notification permission never becomes a capture success. */ }
    }
    fun finishSession(id: String) {
        if (requestId != id || finishing) return
        finishing = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        if (receiverRegistered) { unregisterReceiver(screenOff); receiverRegistered = false }
        if (!finishing) requestId?.let(runtime::onRecordingServiceLost)
        requestId?.let { runtime.onRecordingServiceDestroyed(this, it) }
        requestId = null
        super.onDestroy()
    }
    private fun notification(id: String, status: String): Notification {
        val stop = Intent(this, RecordingForegroundService::class.java).setAction(ACTION_STOP).putExtra(REQUEST_ID, id)
        // Unique identifier is part of Intent identity; an old action cannot stop a newer session.
        stop.identifier = id
        val stopIntent = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.esv_launcher)
            .setContentTitle("③ 屏幕录制").setContentText(status).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止并保存", stopIntent).build()).build()
    }
    companion object {
        private const val CHANNEL = "esv_recording"
        private const val NOTIFICATION_ID = 4301
        private const val ACTION_START = "com.easysavevideo.app.recording.START"
        private const val ACTION_STOP = "com.easysavevideo.app.recording.STOP"
        private const val REQUEST_ID = "request_id"
        fun start(context: Context, requestId: String) {
            context.startForegroundService(Intent(context, RecordingForegroundService::class.java)
                .setAction(ACTION_START).putExtra(REQUEST_ID, requestId))
        }
    }
}
