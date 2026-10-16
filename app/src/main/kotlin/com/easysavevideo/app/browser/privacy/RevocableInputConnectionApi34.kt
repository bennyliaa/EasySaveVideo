package com.easysavevideo.app.browser.privacy

import android.graphics.RectF
import android.os.CancellationSignal
import android.os.Handler
import android.view.inputmethod.HandwritingGesture
import android.view.inputmethod.InputConnection
import android.view.inputmethod.PreviewableHandwritingGesture
import android.view.inputmethod.TextAttribute
import android.view.inputmethod.TextBoundsInfoResult
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.function.Consumer
import java.util.function.IntConsumer

@RequiresApi(34)
internal class RevocableInputConnectionApi34(
    delegate: InputConnection,
    token: PrivateInputToken,
    ownerHandler: Handler,
    delegateLease: PrivateInputDelegateLease,
) : RevocableInputConnectionApi33(delegate, token, ownerHandler, delegateLease) {
    override fun previewHandwritingGesture(gesture: PreviewableHandwritingGesture, cancellationSignal: CancellationSignal?) =
        !token.hasRetirementTracking && token.mutate { delegate.previewHandwritingGesture(gesture, cancellationSignal) }
    override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
        token.mutate { delegate.replaceText(start, end, text, newCursorPosition, textAttribute) }

    override fun performHandwritingGesture(gesture: HandwritingGesture, executor: Executor?, consumer: IntConsumer?) {
        if (consumer == null) {
            if (!token.hasRetirementTracking && token.isCurrent()) delegate.performHandwritingGesture(gesture, executor, null)
            return
        }
        val completion = PrivateInputCompletion(token, executor ?: directExecutor,
            { InputConnection.HANDWRITING_GESTURE_RESULT_CANCELLED }, consumer::accept)
        // Vault password input does not admit provider-owned asynchronous payload work. Its
        // method return is not evidence that handwriting/bounds work has actually retired.
        if (token.hasRetirementTracking || !token.isCurrent()) {
            completion.complete(InputConnection.HANDWRITING_GESTURE_RESULT_CANCELLED)
            return
        }
        // The private bridge never delivers a result externally. The gate alone dispatches on the
        // caller's Executor and rechecks then; this does not move the provider operation's thread.
        delegate.performHandwritingGesture(gesture, directExecutor, IntConsumer(completion::complete))
    }

    override fun requestTextBoundsInfo(bounds: RectF, executor: Executor, consumer: Consumer<TextBoundsInfoResult>) {
        val completion = PrivateInputCompletion(token, executor,
            { TextBoundsInfoResult(TextBoundsInfoResult.CODE_CANCELLED) }, consumer::accept)
        // Vault password input does not admit provider-owned asynchronous payload work. Its
        // method return is not evidence that handwriting/bounds work has actually retired.
        if (token.hasRetirementTracking || !token.isCurrent()) {
            completion.complete(TextBoundsInfoResult(TextBoundsInfoResult.CODE_CANCELLED))
            return
        }
        delegate.requestTextBoundsInfo(bounds, directExecutor, Consumer(completion::complete))
    }

    private companion object {
        val directExecutor = Executor { it.run() }
    }
}

@RequiresApi(34)
internal object RevocableInputConnectionApi34Factory {
    @DoNotInline
    fun create(delegate: InputConnection, token: PrivateInputToken, ownerHandler: Handler,
        delegateLease: PrivateInputDelegateLease): InputConnection =
        RevocableInputConnectionApi34(delegate, token, ownerHandler, delegateLease)
}
