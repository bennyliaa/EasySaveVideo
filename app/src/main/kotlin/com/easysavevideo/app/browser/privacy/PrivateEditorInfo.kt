package com.easysavevideo.app.browser.privacy

import android.os.Build
import android.os.Handler
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** Clear filled editor payloads on denied, null or late-stale creation. Never serialize extras. */
internal fun sanitizePrivateEditorInfo(editorInfo: EditorInfo) {
    editorInfo.initialSelStart = -1
    editorInfo.initialSelEnd = -1
    editorInfo.initialCapsMode = 0
    editorInfo.setInitialSurroundingSubText("", 0)
    editorInfo.inputType = InputType.TYPE_NULL
    editorInfo.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    editorInfo.actionId = 0
    editorInfo.actionLabel = null
    editorInfo.hintText = null
    editorInfo.label = null
    editorInfo.fieldId = 0
    editorInfo.fieldName = null
    editorInfo.packageName = null
    editorInfo.privateImeOptions = null
    editorInfo.extras = null
    editorInfo.hintLocales = null
    editorInfo.contentMimeTypes = null
    if (Build.VERSION.SDK_INT >= 34) PrivateEditorInfoApi34.clear(editorInfo)
    if (Build.VERSION.SDK_INT >= 35) PrivateEditorInfoApi35.clear(editorInfo)
    if (Build.VERSION.SDK_INT >= 36) PrivateEditorInfoApi36.clear(editorInfo)
}

/**
 * Nullable Android boundary, shared by WebView and the private Compose request bridge. The caller
 * supplies the exact owning View and its Handler, with root binding established before first focus.
 * Compose 1.8.3 may wrap a null request result; callers must not claim this changes its outer return.
 */
internal fun createPrivateInputConnection(
    editorInfo: EditorInfo,
    lifetime: PrivateInputLifetime,
    owner: Any,
    ownerHandler: Handler,
    createDelegate: () -> InputConnection?,
): InputConnection? {
    val admission = lifetime.acquire(owner)
    if (admission == null) {
        sanitizePrivateEditorInfo(editorInfo)
        return null
    }
    return createPrivateInputConnection(editorInfo, admission, ownerHandler, createDelegate)
}

/**
 * Use this overload for a retained Compose text-input request: capture admission when the session
 * starts. A late create may not reacquire a new generation for an old request/owner. Closing one
 * connection revokes only its fork; revoking the captured session also revokes all its children.
 */
internal fun createPrivateInputConnection(
    editorInfo: EditorInfo,
    admission: PrivateInputToken,
    ownerHandler: Handler,
    createDelegate: () -> InputConnection?,
): InputConnection? {
    val token = admission.fork()
    if (token == null) {
        sanitizePrivateEditorInfo(editorInfo)
        return null
    }
    val creation = if (token.hasRetirementTracking) token.beginConnectionCreation() else null
    if (token.hasRetirementTracking && creation == null) {
        token.revoke()
        sanitizePrivateEditorInfo(editorInfo)
        return null
    }
    val connectionToken = creation?.let { token.forConnection(it.connection) } ?: token
    var published = false
    try {
        // Never request a provider when the root's publication has already been revoked.
        if (!token.isCurrent()) {
            // The provider factory has not run, so there is no native connection to retire.
            creation?.connection?.confirmClosed()
            return null
        }
        val delegate = createDelegate() ?: run {
            creation?.connection?.confirmClosed()
            return null
        }
        val connection = RevocableInputConnection.create(delegate, connectionToken, ownerHandler) ?: return null
        editorInfo.imeOptions = editorInfo.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        if (!token.isCurrent()) {
            connection.closeConnection()
            return null
        }
        published = true
        return connection
    } finally {
        if (!published) {
            token.revoke()
            sanitizePrivateEditorInfo(editorInfo)
        }
        // Includes factory, wrapper/getHandler and unsuccessful construction. A thrown factory or
        // an unowned returned delegate keeps its connection debt even after invocation return.
        creation?.invocation?.returned()
    }
}
