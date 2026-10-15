package com.easysavevideo.app.browser.privacy

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * SOURCE CONTRACTS ONLY. These inspect the production adapter surface, not Android behavior,
 * verifier loading, real EditorInfo clearing, Compose input restart or provider-internal drain.
 */
class RevocableInputConnectionSourceContractTest {
    private fun source(name: String): String {
        val relative = "src/main/kotlin/com/easysavevideo/app/browser/privacy/$name"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }
    private fun bridge(): String {
        val relative = "src/main/java/com/easysavevideo/app/browser/privacy/PrivatePlatformTextInputMethodRequest.java"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }

    @Test fun editorInfoOptionalApisUseTheirActualSdkIntroductionLevels() {
        val entry = source("PrivateEditorInfo.kt")
        assertFalse(entry.contains("PrivateEditorInfoApi33"))
        assertTrue(entry.contains("if (Build.VERSION.SDK_INT >= 35) PrivateEditorInfoApi35.clear(editorInfo)"))
        assertTrue(entry.contains("if (Build.VERSION.SDK_INT >= 36) PrivateEditorInfoApi36.clear(editorInfo)"))
        assertTrue(source("PrivateEditorInfoApi35.kt").contains("setStylusHandwritingEnabled(false)"))
        assertFalse(source("PrivateEditorInfoApi35.kt").contains("setWritingToolsEnabled"))
        assertTrue(source("PrivateEditorInfoApi36.kt").contains("setWritingToolsEnabled(false)"))
        assertTrue(source("PrivateEditorInfoApi34.kt").contains("setInitialToolType(MotionEvent.TOOL_TYPE_UNKNOWN)"))
    }

    @Test fun all38InstalledSdk36PublicOperationsHaveExplicitOriginalOverrides() {
        // Enumerated using javap on the admitted SDK36 android.jar, not inferred from a wrapper.
        val expected = setOf(
            "beginBatchEdit/0", "clearMetaKeyStates/1", "closeConnection/0", "commitCompletion/1",
            "commitContent/3", "commitCorrection/1", "commitText/2", "commitText/3",
            "deleteSurroundingText/2", "deleteSurroundingTextInCodePoints/2", "endBatchEdit/0",
            "finishComposingText/0", "getCursorCapsMode/1", "getExtractedText/2", "getHandler/0",
            "getSelectedText/1", "getSurroundingText/3", "getTextAfterCursor/2", "getTextBeforeCursor/2",
            "performContextMenuAction/1", "performEditorAction/1", "performHandwritingGesture/3",
            "performPrivateCommand/2", "performSpellCheck/0", "previewHandwritingGesture/2",
            "replaceText/5", "reportFullscreenMode/1", "requestCursorUpdates/1", "requestCursorUpdates/2",
            "requestTextBoundsInfo/3", "sendKeyEvent/1", "setComposingRegion/2", "setComposingRegion/3",
            "setComposingText/2", "setComposingText/3", "setImeConsumesInput/1", "setSelection/2", "takeSnapshot/0",
        )
        val all = listOf("RevocableInputConnection.kt", "RevocableInputConnectionApi33.kt", "RevocableInputConnectionApi34.kt")
            .joinToString("\n", transform = ::source)
        val actual = Regex("override fun (\\w+)\\(([^)]*)\\)").findAll(all).map {
            val params = it.groupValues[2].trim()
            "${it.groupValues[1]}/${if (params.isEmpty()) 0 else params.split(',').size}"
        }.toList()
        assertEquals(38, actual.size); assertEquals(38, expected.size)
        assertEquals(expected, actual.toSet())
    }

    @Test fun all27MutatingAndControlOperationsUseTokenAdmission() {
        val all = listOf("RevocableInputConnection.kt", "RevocableInputConnectionApi33.kt", "RevocableInputConnectionApi34.kt")
            .joinToString("\n", transform = ::source)
        assertEquals(27, Regex("token.mutate").findAll(all).count())
    }

    @Test fun api31BaselineDoesNotReferenceUnsupportedTypeSignaturesOrMutableTarget() {
        val base = source("RevocableInputConnection.kt")
        listOf("import android.view.inputmethod.TextAttribute", "import android.view.inputmethod.TextSnapshot",
            "import android.view.inputmethod.HandwritingGesture", "import android.view.inputmethod.TextBoundsInfoResult",
            ": InputConnectionWrapper", "fun setTarget(").forEach { assertFalse(base.contains(it)) }
        assertTrue(base.contains("Build.VERSION.SDK_INT >= 34"))
        assertTrue(base.contains("Build.VERSION.SDK_INT >= 33"))
        assertTrue(source("RevocableInputConnectionApi33.kt").contains("@DoNotInline"))
        assertTrue(source("RevocableInputConnectionApi34.kt").contains("@DoNotInline"))
    }

    @Test fun everyPublicTextReadAndCapsReadUsesProductionPreAndPostFence() {
        val all = source("RevocableInputConnection.kt") + source("RevocableInputConnectionApi33.kt")
        listOf("getTextBeforeCursor", "getTextAfterCursor", "getSelectedText", "getExtractedText",
            "getSurroundingText", "takeSnapshot", "getCursorCapsMode").forEach { name ->
            assertTrue(name, Regex("override fun $name\\([^)]*\\)[^{=]*=\\s*token.read\\(").containsMatchIn(all))
        }
        val lifetime = source("PrivateInputLifetime.kt")
        assertTrue(lifetime.contains("if (!isCurrent()) return rejected"))
        assertTrue(lifetime.contains("return if (isCurrent()) value else rejected"))
        assertFalse(lifetime.contains("synchronized("))
    }

    @Test fun cleanupKeepsCapturedHandlerContractAndDoesNotFlushStaleBatchOrComposition() {
        val base = source("RevocableInputConnection.kt")
        assertTrue(base.contains("private val capturedHandler = delegate.handler"))
        assertTrue(base.contains("private val cleanupHandler = capturedHandler ?: ownerHandler"))
        assertTrue(base.contains("getHandler(): Handler? = capturedHandler"))
        assertTrue(base.contains("cleanupHandler.post(action)"))
        assertTrue(base.contains("delegate.requestCursorUpdates(0)"))
        assertTrue(base.contains("endBatchEdit() = token.mutate"))
        assertTrue(base.contains("finishComposingText() = token.mutate"))
        assertFalse(base.contains("Handler(Looper.getMainLooper())"))
    }

    @Test fun delegateCleanupAndEveryApiFactoryShareExactRevocableLease() {
        val base = source("RevocableInputConnection.kt")
        assertTrue(base.contains("privateInputDelegateLeases.acquire(delegate, token) ?: return null"))
        assertTrue(base.contains("token.fork(lease::isCurrent)"))
        assertTrue(base.contains("if (leasedToken.isCurrent()) connection else"))
        val claim = base.indexOf("delegateLease.cleanupIfOwned {")
        val cursorStop = base.indexOf("delegate.requestCursorUpdates(0)")
        val close = base.indexOf("delegate.closeConnection()")
        assertTrue(claim > 0); assertTrue(cursorStop > claim); assertTrue(close > cursorStop)
        assertEquals(1, Regex("delegate\\.requestCursorUpdates\\(0\\)").findAll(base).count())
        assertEquals(1, Regex("delegate\\.closeConnection\\(\\)").findAll(base).count())
        listOf("RevocableInputConnectionApi33.kt", "RevocableInputConnectionApi34.kt").forEach {
            val api = source(it)
            assertTrue(api.contains("delegateLease: PrivateInputDelegateLease"))
            assertTrue(api.contains("delegate, token, ownerHandler, delegateLease"))
        }
        val registry = source("PrivateInputDelegateLease.kt")
        assertTrue(registry.contains("it.reference.get() === delegate"))
        assertTrue(registry.contains("if (!admission.isCurrent()) return null"))
        assertTrue(registry.contains("if (previous?.cleaning == true) return null"))
        assertTrue(registry.contains("if (!lease.cleaning) entries.remove(lease)"))
        assertTrue(registry.contains("try { cleanup() } finally { registry.finishCleanup(this) }"))
        assertFalse(registry.contains("android."))
    }

    @Test fun bothAsyncMethodsUseSingleCompletionGateAndPayloadFreeCancelledBounds() {
        val api34 = source("RevocableInputConnectionApi34.kt")
        assertEquals(2, Regex("val completion = PrivateInputCompletion").findAll(api34).count())
        assertTrue(api34.contains("TextBoundsInfoResult(TextBoundsInfoResult.CODE_CANCELLED)"))
        assertFalse(api34.contains("TextBoundsInfoResult.CODE_CANCELLED,"))
        val state = source("RevocableInputConnectionState.kt")
        assertTrue(state.contains("executor.execute"))
        assertTrue(state.contains("catch (_: RejectedExecutionException)"))
        assertTrue(state.contains("consumer(if (token.isCurrent()) value else rejected())"))
    }

    @Test fun deniedEditorInfoClearsTextIdentityExtrasAndApiIsolatedModernFields() {
        val sanitizer = source("PrivateEditorInfo.kt")
        listOf("initialSelStart = -1", "initialSelEnd = -1", "initialCapsMode = 0",
            "setInitialSurroundingSubText(\"\", 0)", "actionLabel = null", "hintText = null", "label = null",
            "fieldName = null", "packageName = null", "privateImeOptions = null", "extras = null",
            "hintLocales = null", "contentMimeTypes = null", "InputType.TYPE_NULL").forEach {
            assertTrue(it, sanitizer.contains(it))
        }
        assertTrue(source("PrivateEditorInfoApi34.kt").contains("setSupportedHandwritingGestures(emptyList())"))
        assertTrue(source("PrivateEditorInfoApi36.kt").contains("setAutofillId(null)"))
    }

    @Test fun creationRetainsSessionTokenAndRechecksAfterDelegateAndWrapperCreation() {
        val helper = source("PrivateEditorInfo.kt")
        assertTrue(helper.contains("admission: PrivateInputToken"))
        assertTrue(helper.contains("val token = admission.fork()"))
        val delegate = helper.indexOf("val delegate = createDelegate()")
        val wrap = helper.indexOf("val connection = RevocableInputConnection.create")
        val lateCheck = helper.indexOf("if (!token.isCurrent()) {", wrap)
        assertTrue(delegate > 0); assertTrue(wrap > delegate); assertTrue(lateCheck > wrap)
        assertTrue(helper.contains("connection.closeConnection()"))
        assertTrue(helper.contains("sanitizePrivateEditorInfo(editorInfo)"))
    }

    @Test fun composeBridgeDocumentsItsNonnullOuterWrapperLimitationWithoutFakeDelegate() {
        val java = bridge()
        assertTrue(java.contains("implements PlatformTextInputMethodRequest"))
        assertTrue(java.contains("return factory.create(editorInfo)"))
        assertTrue(java.contains("inert outer wrapper"))
        assertTrue(java.contains("does NOT establish a true-null"))
        assertFalse(java.contains("new InputConnectionWrapper"))
    }
}
