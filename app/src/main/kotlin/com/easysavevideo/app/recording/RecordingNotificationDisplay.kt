package com.easysavevideo.app.recording

import com.easysavevideo.core.model.AppLanguage

/** Display-only cache. It never owns a Service, recording command, projection or locale preference. */
internal class RecordingNotificationLanguage {
    var current: AppLanguage = AppLanguage.SYSTEM; private set
    fun update(language: AppLanguage): Boolean {
        if (current == language) return false
        current = language
        return true
    }
}

internal data class RecordingNotificationSnapshot(val requestId: String, val status: RecordingNotificationStatus) {
    override fun toString() = "RecordingNotificationSnapshot([redacted])"
}

/** Main-thread display state. Promotion is reported only after the existing startForeground succeeds. */
internal class RecordingNotificationDisplay {
    private var snapshot: RecordingNotificationSnapshot? = null
    private var retired = false

    fun promoted(requestId: String, status: RecordingNotificationStatus): Boolean {
        if (retired || snapshot != null || !requestId.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) return false
        snapshot = RecordingNotificationSnapshot(requestId, status)
        return true
    }
    fun current(requestId: String): RecordingNotificationSnapshot? =
        snapshot?.takeIf { !retired && it.requestId == requestId }

    fun update(requestId: String, status: RecordingNotificationStatus): RecordingNotificationSnapshot? {
        if (current(requestId) == null) return null
        return RecordingNotificationSnapshot(requestId, status).also { snapshot = it }
    }
    fun retire() { retired = true; snapshot = null }
}
