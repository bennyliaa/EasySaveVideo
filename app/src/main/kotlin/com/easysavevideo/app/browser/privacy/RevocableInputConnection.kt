package com.easysavevideo.app.browser.privacy

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.SurroundingText

/**
 * Original API31 baseline. Newer signatures exist only in guarded API33/API34 implementations.
 * Every SDK36 InputConnection operation is explicitly fenced across this class and its two
 * subclasses. Implement the interface directly: no mutable InputConnectionWrapper.setTarget.
 */
internal open class RevocableInputConnection protected constructor(
    protected val delegate: InputConnection,
    protected val token: PrivateInputToken,
    ownerHandler: Handler,
    private val delegateLease: PrivateInputDelegateLease,
) : InputConnection {
    // Preserve WebView's dedicated InputConnection thread, including the meaning of null.
    private val capturedHandler = delegate.handler
    private val cleanupHandler = capturedHandler ?: ownerHandler
    private val cleanup = PrivateInputCleanup(token, { action ->
        if (Looper.myLooper() === cleanupHandler.looper) {
            action.run()
            true
        } else cleanupHandler.post(action)
    }) {
        // Object identity alone is insufficient: WebView providers can reuse this delegate.
        // Once claimed, no replacement wrapper for it can publish until this cleanup returns.
        delegateLease.cleanupIfOwned {
            try { delegate.requestCursorUpdates(0) } catch (_: RuntimeException) { /* no sensitive logs */ }
            try { delegate.closeConnection() } catch (_: RuntimeException) { /* no sensitive logs */ }
        }
    }

    final override fun getHandler(): Handler? = capturedHandler
    final override fun closeConnection() = cleanup.close()
    override fun beginBatchEdit() = token.mutate { delegate.beginBatchEdit() }
    override fun endBatchEdit() = token.mutate { delegate.endBatchEdit() }
    override fun clearMetaKeyStates(states: Int) = token.mutate { delegate.clearMetaKeyStates(states) }
    override fun commitCompletion(text: CompletionInfo?) = token.mutate { delegate.commitCompletion(text) }
    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?) =
        token.mutate { delegate.commitContent(inputContentInfo, flags, opts) }
    override fun commitCorrection(correctionInfo: CorrectionInfo?) =
        token.mutate { delegate.commitCorrection(correctionInfo) }
    override fun commitText(text: CharSequence, newCursorPosition: Int) =
        token.mutate { delegate.commitText(text, newCursorPosition) }
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
        token.mutate { delegate.deleteSurroundingText(beforeLength, afterLength) }
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
        token.mutate { delegate.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }
    override fun finishComposingText() = token.mutate { delegate.finishComposingText() }
    override fun performContextMenuAction(id: Int) = token.mutate { delegate.performContextMenuAction(id) }
    override fun performEditorAction(editorAction: Int) = token.mutate { delegate.performEditorAction(editorAction) }
    override fun performPrivateCommand(action: String?, data: Bundle?) =
        token.mutate { delegate.performPrivateCommand(action, data) }
    override fun performSpellCheck() = token.mutate { delegate.performSpellCheck() }
    override fun reportFullscreenMode(enabled: Boolean) = token.mutate { delegate.reportFullscreenMode(enabled) }
    override fun requestCursorUpdates(cursorUpdateMode: Int) =
        token.mutate { delegate.requestCursorUpdates(cursorUpdateMode) }
    override fun sendKeyEvent(event: KeyEvent) = token.mutate { delegate.sendKeyEvent(event) }
    override fun setComposingRegion(start: Int, end: Int) = token.mutate { delegate.setComposingRegion(start, end) }
    override fun setComposingText(text: CharSequence, newCursorPosition: Int) =
        token.mutate { delegate.setComposingText(text, newCursorPosition) }
    override fun setImeConsumesInput(imeConsumesInput: Boolean) =
        token.mutate { delegate.setImeConsumesInput(imeConsumesInput) }
    override fun setSelection(start: Int, end: Int) = token.mutate { delegate.setSelection(start, end) }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        token.read(null) { delegate.getTextBeforeCursor(n, flags) }
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
        token.read(null) { delegate.getTextAfterCursor(n, flags) }
    override fun getSelectedText(flags: Int): CharSequence? = token.read(null) { delegate.getSelectedText(flags) }
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
        token.read(null) { delegate.getExtractedText(request, flags) }
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? =
        token.read(null) { delegate.getSurroundingText(beforeLength, afterLength, flags) }
    override fun getCursorCapsMode(reqModes: Int): Int = token.read(0) { delegate.getCursorCapsMode(reqModes) }

    companion object {
        /** Owner Handler is captured at editor creation; never substitute the current focus/root. */
        fun create(delegate: InputConnection, token: PrivateInputToken, ownerHandler: Handler): InputConnection? {
            val lease = privateInputDelegateLeases.acquire(delegate, token) ?: return null
            val leasedToken = token.fork(lease::isCurrent)
            if (leasedToken == null) { lease.abandon(); return null }
            return try {
                val connection = when {
                    Build.VERSION.SDK_INT >= 34 -> RevocableInputConnectionApi34Factory.create(delegate, leasedToken, ownerHandler, lease)
                    Build.VERSION.SDK_INT >= 33 -> RevocableInputConnectionApi33Factory.create(delegate, leasedToken, ownerHandler, lease)
                    else -> RevocableInputConnection(delegate, leasedToken, ownerHandler, lease)
                }
                // Capturing the provider's Handler may block or reenter creation. Do not return
                // the old wrapper if a newer lease or presentation won during construction.
                if (leasedToken.isCurrent()) connection else {
                    connection.closeConnection()
                    null
                }
            } catch (failure: Throwable) {
                leasedToken.revoke(); lease.abandon()
                throw failure
            }
        }
    }
}
