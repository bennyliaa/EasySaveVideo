package com.easysavevideo.app.browser.privacy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Process-lifetime cleanup owner. Host disposal must cancel its pending open callback. Private Views remain strongly owned until verified release; uncertain owners stay quarantined for process lifetime. */
internal class PrivateBrowsingRuntime private constructor(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(4), { work -> Thread(work, "esv-private-markers").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val backend = AndroidPrivateProfileBackend()
    private val viewOwnership = PrivateResourceRegistry<WebView>(16)
    private val mutedViews = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<WebView, Boolean>())
    var status by mutableStateOf(PrivateBrowsingStatus(PrivateBrowsingPhase.COLD)); private set
    private val policy = PrivateBrowsingPolicy(backend,
        PrivateProfileMarkerStore(context.applicationContext, worker),
        PrivatePolicyScheduler { delay, action ->
            val runnable = Runnable { action() }
            main.postDelayed(runnable, delay)
            val cancel: () -> Unit = { main.removeCallbacks(runnable) }; cancel
        }, changed = { status = it })
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    fun recover() { requireMain(); policy.recover() }
    fun open(done: (PrivateBrowsingSession?) -> Unit): Boolean { requireMain(); return viewOwnership.hasCapacity() && policy.open(done) }
    fun cancelOpening() { requireMain(); policy.cancelOpening() }
    fun owns(session: PrivateBrowsingSession): Boolean { requireMain(); return policy.owns(session) }
    fun retire(session: PrivateBrowsingSession, retirePages: (() -> Unit) -> Unit, drainSave: (() -> Unit) -> Unit): Boolean {
        requireMain()
        fun owner(done: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) done() else main.post { done() }
        }
        return policy.retire(session,
            retirePages = { released -> retirePages { owner(released) } },
            drainSave = { released -> drainSave { owner(released) } })
    }
    /** Owns construction until successful binding; failed/unpublished Views are destroyed here exactly once. */
    fun createView(context: Context, session: PrivateBrowsingSession, inputLifetime: PrivateInputLifetime): WebView {
        requireMain(); check(policy.owns(session))
        val reservation = checkNotNull(viewOwnership.reserve())
        var created: WebView? = null
        try {
            // A private View is root-bound and input-denied before profile binding or publication.
            val view = PrivateProfileWebView(context, inputLifetime); created = view; viewOwnership.publish(reservation, view)
            check(policy.owns(session)); backend.bindNewView(session, view); check(policy.owns(session))
            return view
        } catch (failure: Exception) {
            val view = created
            if (view == null) viewOwnership.cancel(reservation)
            else try { view.destroy(); viewOwnership.released(view) }
            catch (_: Exception) { backend.unpublishedReleaseFailed(session); viewOwnership.quarantine(view) }
            throw failure
        }
    }
    /** Actual successful destroy only; quarantined ownership is never retired by a late claim. */
    fun viewReleased(view: WebView) { requireMain(); revokeViewInput(view); mutedViews.remove(view); viewOwnership.released(view) }
    /** Keep uncertain native owners alive beyond Activity disposal; never signal drain or recycle slots. */
    fun quarantineView(session: PrivateBrowsingSession, view: WebView) {
        requireMain(); revokeViewInput(view); mutedViews.remove(view)
        backend.unpublishedReleaseFailed(session); viewOwnership.quarantine(view)
    }

    fun mute(view: WebView) {
        requireMain(); mutedViews.remove(view)
        backend.mute(view, true); mutedViews.add(view)
    }
    /** Successful local mute invocation only; not media/input/native resource drain evidence. */
    fun isMuted(view: WebView): Boolean { requireMain(); return view in mutedViews }
    fun bindViewInput(session: PrivateBrowsingSession, view: WebView, presentation: Any) {
        requireMain(); check(policy.owns(session)); backend.requireBound(session, view)
        checkNotNull(view as? PrivateProfileWebView).bindInput(presentation)
        check(policy.owns(session))
    }
    fun hasViewInputBinding(view: WebView): Boolean {
        requireMain(); return (view as? PrivateProfileWebView)?.hasInputBinding == true
    }
    /** Safe during retirement: revocation neither reads the profile nor invokes the provider. */
    fun revokeViewInput(view: WebView) { requireMain(); (view as? PrivateProfileWebView)?.revokeInput() }
    /** Host separately proves exact visible foreground ownership or the exact active capture-audio owner. */
    fun unmuteVisible(session: PrivateBrowsingSession, view: WebView) {
        requireMain(); check(policy.owns(session)); backend.requireBound(session, view)
        mutedViews.remove(view); backend.mute(view, false); check(policy.owns(session))
    }
    fun configureCookies(session: PrivateBrowsingSession, view: WebView, acceptThirdParty: Boolean) {
        requireMain(); check(policy.owns(session)); backend.requireBound(session, view)
        backend.cookieManager(session).apply { setAcceptCookie(true); setAcceptThirdPartyCookies(view, acceptThirdParty) }
        check(policy.owns(session))
    }
    /** Host has already checked document identity and same-origin cookie scope. */
    fun cookie(session: PrivateBrowsingSession, source: String): String? {
        requireMain(); check(policy.owns(session)); val result = backend.cookieManager(session).getCookie(source)
        check(policy.owns(session)); return result
    }
    companion object {
        private var instance: PrivateBrowsingRuntime? = null
        fun get(context: Context): PrivateBrowsingRuntime {
            check(Looper.myLooper() == Looper.getMainLooper())
            return instance ?: PrivateBrowsingRuntime(context.applicationContext).also { instance = it }
        }
    }
}

/** Main-thread bounded ownership. Reservations prevent allocation from exceeding the strong-owner cap. */
internal class PrivateResourceRegistry<T : Any>(private val capacity: Int) {
    init { require(capacity > 0) }
    private val pending = mutableSetOf<Any>()
    private val resources = java.util.IdentityHashMap<T, Boolean>()
    fun hasCapacity() = pending.size + resources.size < capacity
    fun reserve(): Any? = if (!hasCapacity()) null else Any().also { pending += it }
    fun publish(reservation: Any, resource: T) {
        check(reservation in pending && !resources.containsKey(resource))
        pending.remove(reservation); resources[resource] = false
    }
    fun cancel(reservation: Any) { pending.remove(reservation) }
    fun quarantine(resource: T) {
        check(resources.containsKey(resource))
        resources[resource] = true
    }
    fun released(resource: T) {
        if (resources[resource] == false) resources.remove(resource)
    }
    fun isQuarantined(resource: T): Boolean = resources[resource] == true
}

/** Official request to the chosen IME; untrusted keyboards are not guaranteed to honor it. */
internal fun privateProfileImeFlags(existing: Int): Int = existing or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

/**
 * One immutable View/presentation binding. Its retained parent admission lets detach/rebind revoke
 * every connection fork without a provider call, unbounded connection registry or Host/Compose read
 * on an IME thread. A new root publication is acquired only by a new connection creation request.
 */
internal class PrivateWebViewInputBinding(
    private val lifetime: PrivateInputLifetime,
    private val owner: Any,
    val presentation: Any,
) {
    private class State(val active: Boolean, val admission: PrivateInputToken?)
    private val current = AtomicReference(State(true, null))
    val isActive: Boolean get() = current.get().active
    fun acquire(): PrivateInputToken? {
        while (true) {
            val old = current.get()
            if (!old.active) return null
            old.admission?.takeIf { it.isCurrent() }?.let { return it }
            val admission = lifetime.acquire(owner) ?: return null
            if (admission.snapshot.presentation !== presentation) { admission.revoke(); return null }
            val next = State(true, admission)
            if (current.compareAndSet(old, next)) {
                old.admission?.revoke()
                return admission.takeIf { it.isCurrent() }
            }
            admission.revoke()
        }
    }
    fun revoke() { current.getAndSet(State(false, null)).admission?.revoke() }
    override fun toString() = "PrivateWebViewInputBinding([redacted])"
}

private class PrivateProfileWebView(
    context: Context,
    private val inputLifetime: PrivateInputLifetime,
) : WebView(context) {
    // This is the View owner Handler, not a replacement for the provider's getHandler contract.
    private val inputOwnerHandler = Handler(Looper.getMainLooper())
    private val inputBinding = AtomicReference<PrivateWebViewInputBinding?>(null)
    fun bindInput(presentation: Any) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val old = inputBinding.get()
        if (old != null && old.presentation === presentation && old.isActive) return
        inputBinding.getAndSet(PrivateWebViewInputBinding(inputLifetime, this, presentation))?.revoke()
    }
    val hasInputBinding: Boolean get() = inputBinding.get()?.isActive == true
    fun revokeInput() { inputBinding.getAndSet(null)?.revoke() }
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // Capture once; a late super call may not retarget to a replacement binding or generation.
        val binding = inputBinding.get()
        val admission = binding?.acquire()
        if (admission == null) { sanitizePrivateEditorInfo(outAttrs); return null }
        return createPrivateInputConnection(outAttrs, admission, inputOwnerHandler) {
            super.onCreateInputConnection(outAttrs)
        }
    }
    override fun onDetachedFromWindow() {
        revokeInput()
        super.onDetachedFromWindow()
    }
    override fun destroy() {
        revokeInput()
        super.destroy()
    }
}
