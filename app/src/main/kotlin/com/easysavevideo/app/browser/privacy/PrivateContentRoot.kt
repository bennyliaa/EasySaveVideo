package com.easysavevideo.app.browser.privacy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewStructure
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/**
 * The real parent of app content. Its initial state is opaque and structure-suppressed, including
 * before the first composition. Separate windows and native surfaces require their own admission.
 * The lifetime revokes installed connection adapters; it cannot drain provider-internal work.
 */
internal class PrivateContentRoot(context: Context) : FrameLayout(context), AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val protection = PrivateRootProtectionState()
    val inputLifetime = PrivateInputLifetime(this)
    private var identityNow: (() -> PrivateRootIdentity?)? = null
    private var onCoverCommitted: ((PrivateProtectionTurn) -> Unit)? = null
    private var onCoverSubmitting: ((PrivateProtectionTurn) -> Unit)? = null
    private var onCoverDrawn: (() -> Unit)? = null
    private var onInputRevoked: ((Long) -> Unit)? = null
    private var pendingFrame: Pair<ViewTreeObserver, Runnable>? = null
    private var lastTouch: MotionEvent? = null
    private var discardGestureRemainder = false
    private var barrierFailed = false
    private var vaultPlaybackReleaseWarning = false
    /** Fixed non-sensitive safety copy only; never a title, error, path or caller-supplied text. */
    fun showVaultPlaybackReleaseWarning(show: Boolean) {
        requireMain()
        if (vaultPlaybackReleaseWarning == show) return
        vaultPlaybackReleaseWarning = show
        invalidate()
    }
    private fun drawVaultPlaybackReleaseWarning(canvas: Canvas) {
        if (!vaultPlaybackReleaseWarning || !protection.covered || width <= 0 || height <= 0) return
        val margin = (24f * resources.displayMetrics.density).toInt()
        val lineWidth = (width - margin * 2).coerceAtLeast(1)
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f * resources.displayMetrics.scaledDensity
        }
        val text = context.getString(com.easysavevideo.app.R.string.vault_playback_release_warning)
        val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, lineWidth)
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setMaxLines(4).build()
        val save = canvas.save()
        try {
            canvas.translate(margin.toFloat(), ((height - layout.height) / 2f).coerceAtLeast(0f))
            layout.draw(canvas)
        } finally { canvas.restoreToCount(save) }
    }
    private val ordinaryDescendantFocusability = descendantFocusability
    private val heldKeys = mutableSetOf<Int>()
    private val blockedKeys = mutableSetOf<Int>()
    val isCovered get() = protection.covered
    val presentationState get() = protection.presentationState
    val visibleIdentity get() = protection.visibleIdentity
    val inputGeneration get() = protection.generation
    val structureSuppressed get() = protection.suppressStructure
    val hasCommittedCoverFrame get() = protection.coverFrameCommitted
    val hasDrawnCover get() = protection.coverDrawn
    val currentProtectionTurn get() = protection.current
    val localBarrierHealthy get() = !barrierFailed && !protection.disposed

    init {
        setWillNotDraw(false)
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        // Insets continue through the root to the single ComposeView, without being consumed here.
        fitsSystemWindows = false
    }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }

    /**
     * The owner must synchronously invalidate its live edit/action generations in revokeInput.
     * Installing this callback is not evidence that every Compose/WebView InputConnection is gated.
     */
    fun bindOwner(identity: () -> PrivateRootIdentity?, revokeInput: (Long) -> Unit,
        coverDrawn: () -> Unit, coverSubmitting: (PrivateProtectionTurn) -> Unit = {},
        coverCommitted: (PrivateProtectionTurn) -> Unit) {
        requireMain(); check(identityNow == null && !protection.disposed)
        identityNow = identity; onInputRevoked = revokeInput
        onCoverDrawn = coverDrawn; onCoverSubmitting = coverSubmitting; onCoverCommitted = coverCommitted
    }

    fun beginProtection(identity: PrivateRootIdentity): PrivateProtectionTurn? = beginProtectionTurn(identity, false)
    fun beginOrdinaryProtection(identity: PrivateRootIdentity): PrivateProtectionTurn? = beginProtectionTurn(identity, true)
    private fun beginProtectionTurn(identity: PrivateRootIdentity, ordinaryDeparture: Boolean): PrivateProtectionTurn? {
        requireMain()
        // Set the admission/input generation BEFORE calling any focus, input or owner callback.
        try { inputLifetime.revoke() } catch (_: Exception) { barrierFailed = true }
        val turn = (if (ordinaryDeparture) protection.beginOrdinaryDeparture(identity) else protection.begin(identity)) ?: return null
        cancelFrameCallback()
        revokeLocalInput()
        invalidate()
        return turn
    }
    private fun revokeLocalInput() {
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        blockedKeys.addAll(heldKeys); heldKeys.clear()
        try { onInputRevoked?.invoke(protection.generation) } catch (_: Exception) { barrierFailed = true }
        try { cancelPendingInputEvents() } catch (_: Exception) { barrierFailed = true }
        lastTouch?.let { previous ->
            discardGestureRemainder = true
            val cancel = MotionEvent.obtain(previous)
            cancel.action = MotionEvent.ACTION_CANCEL
            try { super.dispatchTouchEvent(cancel) } catch (_: Exception) { barrierFailed = true }
            finally { cancel.recycle(); previous.recycle(); lastTouch = null }
        }
        try { findFocus()?.clearFocus(); clearFocus() } catch (_: Exception) { /* Cleanup is not input revocation. */ }
    }

    fun reservePrivate() { requireMain(); protection.reservePrivate() }

    fun acknowledgeAppliedComposition(turn: PrivateProtectionTurn, identity: PrivateRootIdentity): Boolean {
        requireMain()
        val first = !protection.compositionApplied
        if (!protection.acknowledgeAppliedComposition(turn, identity)) return false
        if (first) { cancelFrameCallback(); invalidate() }
        return true
    }

    fun revealProtectedPrivate(turn: PrivateProtectionTurn, identity: PrivateRootIdentity,
        readback: PrivateRevealReadback): PrivateRevealResult {
        requireMain()
        if (!localBarrierHealthy) return PrivateRevealResult.BLOCKED
        val result = protection.revealProtectedPrivate(turn, identity, readback)
        if (result == PrivateRevealResult.REVEALED) {
            cancelFrameCallback()
            descendantFocusability = ordinaryDescendantFocusability
            invalidate()
            // New immutable admission only after the exact secure presentation is unmasked.
            if (!inputLifetime.publish(identity.presentation)) {
                beginProtection(identity)
                return PrivateRevealResult.BLOCKED
            }
        }
        return result
    }

    /** User-authorized pixels retain normal current input; later transitions revoke stale tokens. */
    fun revealCapture(turn: PrivateProtectionTurn, identity: PrivateRootIdentity, view: View): Boolean {
        requireMain()
        if (!localBarrierHealthy || inputLifetime.isAdmitted || !protection.revealCapture(turn, identity)) return false
        cancelFrameCallback(); descendantFocusability = ordinaryDescendantFocusability
        if (!inputLifetime.publish(identity.presentation)) { beginProtection(identity); return false }
        invalidate()
        return true
    }
    fun revokeCaptureInput(): Boolean {
        requireMain()
        return try { inputLifetime.revoke(); revokeLocalInput(); localBarrierHealthy } catch (_: Exception) { false }
    }
    fun releasePresentedOrdinary(turn: PrivateProtectionTurn, identity: PrivateRootIdentity,
        readback: PrivateOrdinaryDepartureReadback): Boolean {
        requireMain()
        if (!protection.releasePresentedOrdinary(turn, identity, readback)) return false
        cancelFrameCallback(); descendantFocusability = ordinaryDescendantFocusability
        invalidate()
        return true
    }

    fun releaseInitialOrdinary(turn: PrivateProtectionTurn, identity: PrivateRootIdentity,
        ordinaryIsCurrent: Boolean, privateContentAttached: Boolean): Boolean {
        requireMain()
        if (!protection.releaseOrdinary(turn, identity, ordinaryIsCurrent, privateContentAttached)) return false
        cancelFrameCallback()
        descendantFocusability = ordinaryDescendantFocusability
        invalidate()
        return true
    }

    override fun dispatchProvideAutofillStructure(structure: ViewStructure, flags: Int) {
        if (!protection.suppressStructure) { super.dispatchProvideAutofillStructure(structure, flags); return }
        // No super call, regardless of AUTOFILL_FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.
        structure.setAutofillId(autofillId)
        structure.setAutofillType(View.AUTOFILL_TYPE_NONE)
        structure.setChildCount(0)
    }
    override fun dispatchProvideStructure(structure: ViewStructure) {
        if (!protection.suppressStructure) { super.dispatchProvideStructure(structure); return }
        structure.setChildCount(0)
    }

    override fun draw(canvas: Canvas) {
        val checkpoint = canvas.save()
        try { super.draw(canvas) }
        catch (failure: Exception) {
            if (!protection.covered) throw failure
            barrierFailed = true
            hideContentAfterFailure()
        } finally { canvas.restoreToCount(checkpoint) }
        if (!protection.covered) return
        try { canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC) }
        catch (_: Exception) { barrierFailed = true; hideContentAfterFailure(); return }
        if (barrierFailed) { hideContentAfterFailure(); return }
        // Draw fixed safe guidance above the opaque cover without revealing children or accepting input.
        try { drawVaultPlaybackReleaseWarning(canvas) } catch (_: Exception) { /* Keep the opaque safety cover. */ }
        val turn = protection.current ?: return
        val identity = try { identityNow?.invoke() } catch (_: Exception) { null } ?: return
        val draw = protection.drawn(turn, identity, isAttachedToWindow,
            canvas.isHardwareAccelerated && isHardwareAccelerated, width > 0 && height > 0)
        // Posted after the actual covered root draw. Private reveal additionally requires the
        // matching hardware commit below; a SideEffect/pre-draw cannot substitute for that frame.
        main.post { if (protection.isCurrent(turn, identity)) onCoverDrawn?.invoke() }
        if (draw == null) return
        try { onCoverSubmitting?.invoke(turn) } catch (_: Exception) {
            protection.registrationUnavailable(draw); return
        }
        if (!protection.isCurrent(turn, identity)) return
        val observer = viewTreeObserver
        if (!observer.isAlive) { protection.registrationUnavailable(draw); return }
        val commit = Runnable {
            // Renderer callbacks need not run on the owner thread.
            main.post {
                val currentIdentity = try { identityNow?.invoke() } catch (_: Exception) { null } ?: return@post
                if (protection.commit(draw, currentIdentity, isAttachedToWindow, isHardwareAccelerated)) {
                    pendingFrame = null
                    onCoverCommitted?.invoke(turn)
                }
            }
        }
        try {
            observer.registerFrameCommitCallback(commit)
            pendingFrame = observer to commit
        } catch (_: Exception) {
            protection.registrationUnavailable(draw) // Pending, not success and not a timeout.
        }
    }

    private fun hideContentAfterFailure() {
        for (index in 0 until childCount) getChildAt(index).visibility = View.INVISIBLE
    }
    private fun cancelFrameCallback() {
        val pending = pendingFrame; pendingFrame = null
        if (pending != null && pending.first.isAlive) try {
            pending.first.unregisterFrameCommitCallback(pending.second)
        } catch (_: Exception) { /* Generation fencing still rejects the old callback. */ }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (protection.covered) return true
        if (discardGestureRemainder) {
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
            discardGestureRemainder = false
        }
        lastTouch?.recycle()
        lastTouch = if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
            null else MotionEvent.obtain(event)
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent) = protection.covered || super.dispatchGenericMotionEvent(event)
    override fun dispatchTrackballEvent(event: MotionEvent) = protection.covered || super.dispatchTrackballEvent(event)
    override fun dispatchHoverEvent(event: MotionEvent) = protection.covered || super.dispatchHoverEvent(event)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (protection.covered) {
            if (event.action == KeyEvent.ACTION_DOWN) blockedKeys.add(event.keyCode)
            if (event.action == KeyEvent.ACTION_UP) blockedKeys.remove(event.keyCode)
            return true
        }
        if (event.keyCode in blockedKeys) {
            if (event.action == KeyEvent.ACTION_UP) blockedKeys.remove(event.keyCode)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) heldKeys.add(event.keyCode)
        if (event.action == KeyEvent.ACTION_UP) heldKeys.remove(event.keyCode)
        return super.dispatchKeyEvent(event)
    }
    override fun dispatchKeyEventPreIme(event: KeyEvent) = protection.covered || super.dispatchKeyEventPreIme(event)
    override fun dispatchKeyShortcutEvent(event: KeyEvent) = protection.covered || super.dispatchKeyShortcutEvent(event)

    override fun onDetachedFromWindow() {
        inputLifetime.revoke()
        protection.invalidate(); cancelFrameCallback(); revokeLocalInput()
        lastTouch?.recycle(); lastTouch = null
        super.onDetachedFromWindow()
    }
    override fun close() {
        requireMain()
        inputLifetime.dispose()
        protection.close(); cancelFrameCallback(); revokeLocalInput()
        lastTouch?.recycle(); lastTouch = null
        onCoverSubmitting = null; onCoverCommitted = null; onCoverDrawn = null; onInputRevoked = null; identityNow = null
        invalidate()
    }
}
