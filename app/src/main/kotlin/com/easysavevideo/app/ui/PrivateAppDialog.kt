package com.easysavevideo.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.easysavevideo.app.browser.privacy.PrivateContentRoot
import com.easysavevideo.app.browser.privacy.PrivateDialogRegistration
import com.easysavevideo.app.browser.privacy.PrivateInputLifetime
import com.easysavevideo.app.browser.privacy.PrivateInputToken
import com.easysavevideo.app.browser.privacy.PrivatePlatformTextInputMethodRequest
import com.easysavevideo.app.browser.privacy.PrivateRevealResult
import com.easysavevideo.app.browser.privacy.PrivateWindowGuard
import com.easysavevideo.app.browser.privacy.PrivateWindowPresentation
import com.easysavevideo.app.browser.privacy.createPrivateInputConnection
import com.easysavevideo.app.browser.privacy.sanitizePrivateEditorInfo

/** The Activity owns this exact modal request; independent projection does not block admission. */
internal class PrivateAppDialogEnvironment(
    val activity: ComponentActivity,
    val parentGuard: PrivateWindowGuard,
    val protectForModal: (Any) -> Boolean,
    val isRequestCurrent: (Any) -> Boolean,
    val captureDrainedForModal: () -> Boolean,
    val independentRecordingOwner: () -> Boolean = { false },
)

internal val LocalPrivateAppDialogEnvironment = staticCompositionLocalOf<PrivateAppDialogEnvironment?> { null }
internal val LocalPrivatePresentation = staticCompositionLocalOf { false }

/** Capture this value at the editable/action call site, never fetch a replacement on a late event. */
internal val LocalPrivateInputAdmission = staticCompositionLocalOf<() -> Boolean> { { true } }

/**
 * Ordinary mode is the unchanged path. A private call with no owning root fails closed.
 * A generation change replaces the interceptor, cancelling/restarting its text-input session;
 * it does not key/recreate the content, so private drafts keep their existing ephemeral lifetime.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PrivateInputBoundary(
    privateMode: Boolean,
    lifetime: PrivateInputLifetime?,
    generation: Long,
    content: @Composable () -> Unit,
) {
    if (!privateMode) {
        CompositionLocalProvider(LocalPrivateInputAdmission provides { true }, content = content)
        return
    }
    val owner = LocalView.current
    val handler = remember(owner) { owner.handler ?: Handler(Looper.getMainLooper()) }
    val actionToken = remember(lifetime, generation, owner) { lifetime?.acquire(owner) }
    val admission = remember(actionToken) { { actionToken?.isCurrent() == true } }
    DisposableEffect(actionToken) { onDispose { actionToken?.revoke() } }
    val interceptor = remember(lifetime, generation, owner, actionToken) {
        PlatformTextInputInterceptor { request, next ->
            // Parent CompositionContext carries this interceptor into a separate ComposeView.
            // Its editor belongs to that view's independent boundary, never this parent lifetime.
            if (next.view !== owner) return@PlatformTextInputInterceptor next.startInputMethod(request)
            // Freeze the exact session publication. A late request must never acquire a new one.
            val sessionToken = actionToken?.fork()
            try {
                next.startInputMethod(PrivatePlatformTextInputMethodRequest { info ->
                    if (sessionToken == null) {
                        sanitizePrivateEditorInfo(info)
                        null
                    } else createPrivateInputConnection(info, sessionToken, handler) {
                        request.createInputConnection(info)
                    }
                })
            } finally { sessionToken?.revoke() }
        }
    }
    CompositionLocalProvider(LocalPrivateInputAdmission provides admission) {
        InterceptPlatformTextInput(interceptor, content)
    }
}

/** Exact identity keys: equality on a request's payload cannot retain an older dialog owner. */
private class PrivateReferenceKey(private val reference: Any) {
    override fun equals(other: Any?) = other is PrivateReferenceKey && reference === other.reference
    override fun hashCode() = System.identityHashCode(reference)
}

/**
 * A finite, app-owned secure dialog. Absent/invalid ownership fails closed without composing the
 * private payload. All native allocation happens inside the owning effect: abandoned composition
 * cannot strand a remembered, never-shown Dialog. Ordinary callers retain their Compose Dialog.
 */
@Composable
internal fun PrivateAppDialog(
    requestIdentity: Any,
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    isRequestCurrent: () -> Boolean,
    content: @Composable () -> Unit,
) {
    val environment = LocalPrivateAppDialogEnvironment.current ?: return
    val parent = LocalView.current
    val composition = rememberCompositionContext()
    val direction = LocalLayoutDirection.current
    key(PrivateReferenceKey(requestIdentity), environment, parent, composition) {
        // These holders cannot be reused by a replacement request before the old effect disposes.
        val currentContent = rememberUpdatedState(content)
        val currentDismiss = rememberUpdatedState(onDismissRequest)
        val currentRequest = rememberUpdatedState(isRequestCurrent)
        val holder = remember { PrivateDialogHolder() }
        DisposableEffect(holder) {
            val host = OwnedPrivateDialog(environment, requestIdentity, parent, composition,
                localRequestIsCurrent = { currentRequest.value() },
                onDismissRequest = { currentDismiss.value() }, content = { currentContent.value() })
            holder.host = host
            host.prepare(properties, direction)
            onDispose {
                holder.host = null
                host.close()
            }
        }
        SideEffect { holder.host?.update(properties, direction) }
    }
}

private class PrivateDialogHolder { var host: OwnedPrivateDialog? = null }

/** Public provider contract, not Compose's internal DialogLayout or a reparented Compose Dialog. */
private class PrivateDialogContent(context: Context, override val window: Window) :
    FrameLayout(context), DialogWindowProvider

private class OwnedPrivateComponentDialog(
    context: Context,
    private val outsideDismiss: () -> Unit,
    private val backDismiss: () -> Unit,
    private val focusChanged: (Boolean) -> Unit,
    private val beforeNativeDismiss: () -> Unit,
    private val afterNativeDismiss: () -> Unit,
) : ComponentDialog(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar) {
    init {
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = backDismiss()
        })
        // Android retains its native outside-touch bounds/slop. cancel() below requests owner
        // dismissal; it cannot directly destroy the window or run an unguarded callback.
        setCancelable(true)
        setCanceledOnTouchOutside(true)
    }
    override fun cancel() = outsideDismiss()
    override fun dismiss() {
        beforeNativeDismiss() // Revoke/cover only; registration survives the native attempt.
        try { super.dismiss() } finally { afterNativeDismiss() }
    }
    fun dismissOwned() = super.dismiss()
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) focusChanged(false)
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusChanged(true)
    }
}

private class OwnedPrivateDialog(
    private val environment: PrivateAppDialogEnvironment,
    private val request: Any,
    private val parent: View,
    private val parentComposition: CompositionContext,
    private val localRequestIsCurrent: () -> Boolean,
    private val onDismissRequest: () -> Unit,
    private val content: @Composable () -> Unit,
) {
    private val lifetime = PrivateDialogLifetime()
    private val inputGeneration = mutableLongStateOf(0L)
    private var properties by mutableStateOf(DialogProperties())
    private var dialog: OwnedPrivateComponentDialog? = null
    private var root: PrivateContentRoot? = null
    private var contentParent: PrivateDialogContent? = null
    private var composeView: ComposeView? = null
    private var registration: PrivateDialogRegistration? = null
    private var actionToken: PrivateInputToken? = null
    private var compositionApplied = false
    private var prepared = false
    private var showing = false
    private var ownerObserverInstalled = false
    private var parentListenerInstalled = false
    private val main = Handler(Looper.getMainLooper())
    private var ownedDecor: View? = null
    private var nativeReadbackPosted = false
    private val teardownAttachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (lifetime.isClosed) close()
        }
        override fun onViewDetachedFromWindow(view: View) = afterNativeTeardownEvent()
    }
    private val teardown = PrivateDialogTeardown(lifetime,
        revoke = {
            actionToken?.revoke(); actionToken = null
            root?.inputLifetime?.dispose()
            compositionApplied = false; prepared = false
        },
        protect = { registration?.guard?.installProtectionBarrier() },
        dismiss = { dialog?.dismissOwned() },
        disposeComposition = { composeView?.disposeComposition() },
        removeContentChildren = { contentParent?.removeAllViews() },
        removeRootChildren = { root?.removeAllViews() },
        detachedAndEmpty = ::teardownReadback,
        release = ::releaseDetachedOwner)
    private val parentAttachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = advance()
        override fun onViewDetachedFromWindow(view: View) = close()
    }
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_DESTROY -> close()
            Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> cover()
            Lifecycle.Event.ON_RESUME -> advance()
            else -> Unit
        }
    }

    private fun safe(check: () -> Boolean): Boolean = try { check() } catch (_: Exception) { false }
    private fun ownersMatch(view: View) = view.findViewTreeLifecycleOwner() === environment.activity &&
        view.findViewTreeViewModelStoreOwner() === environment.activity &&
        view.findViewTreeSavedStateRegistryOwner() === environment.activity
    private fun requestCurrent(): Boolean = !lifetime.isClosed && !environment.activity.isFinishing &&
        !environment.activity.isDestroyed && parent.isAttachedToWindow && ownersMatch(parent) &&
        safe(localRequestIsCurrent) && safe { environment.isRequestCurrent(request) }
    private fun registered(): Boolean = registration?.let {
        environment.parentGuard.isRegisteredPrivateDialog(it)
    } == true
    private fun setOwners(view: View) {
        // The bounded Activity owner is verified, rather than inferred from Context or a cast.
        check(ownersMatch(parent))
        view.setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
        view.setViewTreeViewModelStoreOwner(parent.findViewTreeViewModelStoreOwner())
        view.setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
        check(ownersMatch(view))
    }
    private fun exclude(view: View) {
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        view.isSaveEnabled = false
    }
    private fun excluded(view: View) =
        view.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS &&
            view.importantForContentCapture == View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS

    fun prepare(initialProperties: DialogProperties, direction: LayoutDirection) {
        if (lifetime.isClosed || prepared) return
        try {
            if (!requestCurrent() || !safe { environment.protectForModal(request) }) { close(); return }
            // Local protection/rebinding is independent of the continuous projection owner.
            val ownedDialog = OwnedPrivateComponentDialog(environment.activity,
                outsideDismiss = { if (properties.dismissOnClickOutside) requestDismiss() },
                backDismiss = { if (properties.dismissOnBackPress) requestDismiss() },
                focusChanged = { focused ->
                    if (lifetime.isClosed) close() else if (focused) advance() else cover()
                },
                beforeNativeDismiss = { teardown.beforeNativeDismiss() },
                afterNativeDismiss = { teardown.afterNativeDismiss(); afterNativeTeardownEvent() })
            dialog = ownedDialog
            ownedDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val window = checkNotNull(ownedDialog.window)
            ownedDecor = window.decorView
            window.decorView.addOnAttachStateChangeListener(teardownAttachment)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window.setTitle("EasySaveVideo")
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setGravity(Gravity.CENTER)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val ownedRoot = PrivateContentRoot(ownedDialog.context)
            root = ownedRoot
            ownedRoot.addOnAttachStateChangeListener(teardownAttachment)
            ownedDialog.setContentView(ownedRoot,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val nativeContent = checkNotNull(window.findViewById<ViewGroup>(android.R.id.content))
            check(nativeContent.childCount == 1 && nativeContent.getChildAt(0) === ownedRoot)
            listOf(window.decorView, nativeContent, ownedRoot).forEach(::exclude)
            // Empty root registration precedes EVERY attachment of sensitive Compose content.
            registration = checkNotNull(environment.parentGuard.registerPrivateDialog(
                window, ownedRoot, request, ::requestCurrent, onOwnerClosed = { close() },
                teardownIsComplete = ::teardownReadback,
                revokeInput = { generation ->
                    actionToken?.revoke(); actionToken = null
                    compositionApplied = false
                    inputGeneration.longValue = generation
                }))
            environment.activity.lifecycle.addObserver(lifecycleObserver)
            ownerObserverInstalled = true
            parent.addOnAttachStateChangeListener(parentAttachment)
            parentListenerInstalled = true
            val provider = PrivateDialogContent(ownedDialog.context, window)
            contentParent = provider
            val compose = ComposeView(ownedDialog.context)
            composeView = compose
            listOf(ownedRoot, provider, compose).forEach { setOwners(it); exclude(it) }
            compose.setParentCompositionContext(parentComposition)
            compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            ownedRoot.addView(provider, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            provider.addView(compose, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            ownedDialog.setOnDismissListener { afterNativeTeardownEvent() }
            properties = initialProperties
            updateWindow(initialProperties, direction)
            compose.setContent {
                val generation by inputGeneration
                PrivateInputBoundary(true, ownedRoot.inputLifetime, generation) {
                    val inputAllowed = LocalPrivateInputAdmission.current
                    val admitted = remember(inputAllowed) { { inputAllowed() && acceptsAction() } }
                    CompositionLocalProvider(LocalPrivateInputAdmission provides admitted) {
                        val insets = if (properties.decorFitsSystemWindows) Modifier else Modifier.imePadding()
                        Box(insets.semantics { this.dialog(); paneTitle = "EasySaveVideo" }) { content() }
                    }
                    SideEffect { acknowledgeAppliedComposition() }
                }
            }
            prepared = true
            advance()
        } catch (_: Exception) {
            // Fail closed; never log private payloads, EditorInfo or platform exception messages.
            close()
        }
    }

    fun update(nextProperties: DialogProperties, direction: LayoutDirection) {
        if (lifetime.isClosed) return
        properties = nextProperties
        try { updateWindow(nextProperties, direction); advance() } catch (_: Exception) { close() }
    }
    private fun updateWindow(nextProperties: DialogProperties, direction: LayoutDirection) {
        val ownedDialog = dialog ?: return
        val window = checkNotNull(ownedDialog.window)
        // The exact registered guard owns pixel exposure. DialogProperties cannot grant it.
        if (registration?.guard?.hasContinuousCaptureAuthorization != true)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setTitle("EasySaveVideo")
        WindowCompat.setDecorFitsSystemWindows(window, nextProperties.decorFitsSystemWindows)
        window.setLayout(if (nextProperties.usePlatformDefaultWidth) ViewGroup.LayoutParams.WRAP_CONTENT
            else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.decorView.layoutDirection = if (direction == LayoutDirection.Rtl) View.LAYOUT_DIRECTION_RTL
            else View.LAYOUT_DIRECTION_LTR
        ownedDialog.setCanceledOnTouchOutside(nextProperties.dismissOnClickOutside)
    }

    private fun actualCompositionCurrent(): Boolean {
        val ownedRoot = root ?: return false
        val provider = contentParent ?: return false
        val compose = composeView ?: return false
        return compositionApplied && prepared && compose.hasComposition &&
            ownedRoot.childCount == 1 && ownedRoot.getChildAt(0) === provider &&
            provider.childCount == 1 && provider.getChildAt(0) === compose &&
            compose.parent === provider && provider.parent === ownedRoot &&
            listOf(ownedRoot, provider, compose).all { ownersMatch(it) && excluded(it) }
    }
    private fun presentationClaim() = PrivateWindowPresentation(request, request, composeView, contentParent,
        isCurrent = { requestCurrent() && registered() },
        compositionIsCurrent = ::actualCompositionCurrent,
        captureExceptionInactive = { safe(environment.captureDrainedForModal) || safe(environment.independentRecordingOwner) })

    /** Only the applied dialog composition can acknowledge a new covered generation. */
    private fun acknowledgeAppliedComposition() {
        if (lifetime.isClosed) return
        compositionApplied = true
        val child = registration?.guard ?: return
        child.currentProtectionTurn?.let { turn ->
            child.acknowledgePrivateComposition(turn, presentationClaim())
        }
        advance()
    }

    private fun acceptsAction(): Boolean = actionToken?.isCurrent() == true && requestCurrent() &&
        registered() && actualCompositionCurrent() && dialog?.window?.decorView?.hasWindowFocus() == true &&
        registration?.guard?.hasVisiblePrivatePresentation(request, request) == true

    private fun requestDismiss() {
        // Native Back/outside events are synchronous; composed controls additionally capture their
        // own LocalPrivateInputAdmission token so a queued old click cannot target a fresh reveal.
        if (!acceptsAction()) return
        close()
        onDismissRequest() // Captured request owner only; disposal cannot redirect this callback.
    }
    private fun cover() {
        if (lifetime.isClosed) return
        actionToken?.revoke(); actionToken = null
        registration?.guard?.installProtectionBarrier()
    }
    private fun advance() {
        if (lifetime.isClosed || !prepared) return
        if (!requestCurrent() || !registered()) { close(); return }
        val ownedDialog = dialog ?: return
        val ownedRoot = root ?: return
        val child = registration?.guard ?: return
        // Truthful drain and independently owned projection are separate compatibility cases.
        if ((!safe(environment.captureDrainedForModal) && !safe(environment.independentRecordingOwner) &&
                !child.hasContinuousCaptureAuthorization) ||
            !environment.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!showing) {
            // Recheck directly before show, with an installed native cover and exact registration.
            if (!requestCurrent() || !registered() || !ownedRoot.isCovered) { close(); return }
            try {
                showing = true
                ownedDialog.show()
            } catch (_: Exception) { close(); return }
            if (lifetime.isClosed) return
        }
        if (!ownedRoot.isCovered) return
        val turn = child.currentProtectionTurn ?: return
        when (child.revealProtectedPrivate(turn, presentationClaim())) {
            PrivateRevealResult.REVEALED -> {
                actionToken?.revoke()
                actionToken = ownedRoot.inputLifetime.acquire(checkNotNull(composeView))
                inputGeneration.longValue = ownedRoot.inputGeneration
            }
            PrivateRevealResult.PENDING -> if (!ownedRoot.hasCommittedCoverFrame) {
                child.afterOwnedCoverFrameSubmitted(turn) { advance() }
            }
            PrivateRevealResult.STALE -> close()
            PrivateRevealResult.BLOCKED -> Unit // Integration/platform gates remain explicitly shut.
        }
    }

    /** Read the originally captured Views, never whichever window/root a later request owns. */
    private fun teardownReadback(): Boolean {
        fun detached(view: View?) = view == null || (!view.isAttachedToWindow && view.windowToken == null)
        return lifetime.isClosed && detached(ownedDecor) && detached(root) && detached(contentParent) && detached(composeView) &&
            (root?.childCount ?: 0) == 0 && (contentParent?.childCount ?: 0) == 0 &&
            contentParent?.parent == null && composeView?.parent == null && composeView?.hasComposition != true
    }

    private fun releaseDetachedOwner(): Boolean {
        val ownedRegistration = registration
        val released = if (ownedRegistration != null) {
            environment.parentGuard.unregisterPrivateDialog(ownedRegistration)
        } else {
            root?.close()
            true
        }
        if (!released) return false
        registration = null
        // Only successful native readback retires these exact callbacks. Their late duplicates
        // are harmless; keeping them during a failed teardown permits genuine detach to finish it.
        runCatching { ownedDecor?.removeOnAttachStateChangeListener(teardownAttachment) }
        runCatching { root?.removeOnAttachStateChangeListener(teardownAttachment) }
        if (parentListenerInstalled) runCatching { parent.removeOnAttachStateChangeListener(parentAttachment) }
        parentListenerInstalled = false
        if (ownerObserverInstalled) runCatching { environment.activity.lifecycle.removeObserver(lifecycleObserver) }
        ownerObserverInstalled = false
        showing = false
        return true
    }

    private fun afterNativeTeardownEvent() {
        close(dismissWindow = false)
        // Android may dispatch onDetached before clearing attachInfo/windowToken. Recheck after
        // that dispatch returns; this is event-driven readback, never a timeout/success fallback.
        if (teardown.isComplete || nativeReadbackPosted) return
        nativeReadbackPosted = true
        if (!main.post {
            nativeReadbackPosted = false
            close(dismissWindow = false)
        }) nativeReadbackPosted = false
    }

    fun close(dismissWindow: Boolean = true) = teardown.close(dismissWindow)
}
