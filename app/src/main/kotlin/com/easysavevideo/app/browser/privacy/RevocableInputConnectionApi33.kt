package com.easysavevideo.app.browser.privacy

import android.os.Handler
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextAttribute
import android.view.inputmethod.TextSnapshot
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi

@RequiresApi(33)
internal open class RevocableInputConnectionApi33(
    delegate: InputConnection,
    token: PrivateInputToken,
    ownerHandler: Handler,
    delegateLease: PrivateInputDelegateLease,
) : RevocableInputConnection(delegate, token, ownerHandler, delegateLease) {
    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
        token.mutate { delegate.commitText(text, newCursorPosition, textAttribute) }
    override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?) =
        token.mutate { delegate.setComposingRegion(start, end, textAttribute) }
    override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
        token.mutate { delegate.setComposingText(text, newCursorPosition, textAttribute) }
    override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int) =
        token.mutate { delegate.requestCursorUpdates(cursorUpdateMode, cursorUpdateFilter) }
    override fun takeSnapshot(): TextSnapshot? = token.read(null) { delegate.takeSnapshot() }
}

/** Neutral-signature factory prevents unconditional resolution of API33 signatures on API31/32. */
@RequiresApi(33)
internal object RevocableInputConnectionApi33Factory {
    @DoNotInline
    fun create(delegate: InputConnection, token: PrivateInputToken, ownerHandler: Handler,
        delegateLease: PrivateInputDelegateLease): InputConnection =
        RevocableInputConnectionApi33(delegate, token, ownerHandler, delegateLease)
}
