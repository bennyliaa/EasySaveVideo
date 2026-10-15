package com.easysavevideo.app.browser.privacy

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Default-dispatch and source contracts; no Android lifecycle/consent execution evidence. */
class PrivateBrowserLifecycleContractTest {
    @Test fun lifecycleDefaultProtectsPresentationAndPropagatesRealProtectionFailure() {
        var protections = 0
        var allowed = true
        val integration = object : PrivateBrowserIntegration {
            override fun ready() = true
            override fun canEnterPrivateMode() = true
            override fun protectForPresentationTransition(): Boolean { protections++; return allowed }
            override fun allowVisibleAudio(view: android.webkit.WebView) = false
        }
        assertTrue(integration.protectForLifecycle(PrivateBrowserLifecycleTransition.PAUSE))
        allowed = false
        assertFalse(integration.protectForLifecycle(PrivateBrowserLifecycleTransition.RESUME))
        assertEquals(2, protections)
    }
    @Test fun onlyPauseAndResumeUseLifecycleExceptionAndStillProtectBeforeNativeWork() {
        val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
        val host = File(root, "src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt").readText()
        val resume = host.substringAfter("fun resume()").substringBefore("fun pause()")
        val pause = host.substringAfter("fun pause()").substringBefore("fun hideFullscreen")
        assertTrue(resume.contains("protectPrivateTransition(PrivateBrowserLifecycleTransition.RESUME)"))
        assertTrue(pause.contains("protectPrivateTransition(PrivateBrowserLifecycleTransition.PAUSE)"))
        assertEquals(2, Regex("protectPrivateTransition\\(PrivateBrowserLifecycleTransition\\.").findAll(host).count())
        assertTrue(resume.indexOf("protectPrivateTransition(") < resume.indexOf("foreground = true"))
        assertTrue(pause.indexOf("protectPrivateTransition(") < pause.indexOf("privateRuntime.mute(view)"))
        val dispatch = host.substringAfter("private fun protectPrivateTransition(").substringBefore("private fun cancelPrivateOpening")
        assertTrue(dispatch.contains("if (lifecycle == null) integration.protectForPresentationTransition()"))
        assertTrue(dispatch.contains("else integration.protectForLifecycle(lifecycle)"))
        assertTrue(dispatch.contains("protected && !privacyBlocked"))
        assertTrue(dispatch.contains("catch (_: Exception) { false }"))
    }
    @Test fun captureAudioRefreshUsesTheExistingExactViewGate() {
        val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
        val host = File(root, "src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt").readText()
        val refresh = host.substringAfter("internal fun refreshPrivateCaptureAudio()").substringBefore("private fun resumeSelectedPrivateAudio()")
        assertTrue(refresh.contains("requireMain()")); assertTrue(refresh.contains("resumeSelectedPrivateAudio()"))
        assertFalse(refresh.contains("unmuteVisible"))
        val gate = host.substringAfter("private fun resumeSelectedPrivateAudio()").substringBefore("private fun resumeSelectedView(")
        assertTrue(gate.contains("privateIntegration?.allowVisibleAudio(view) == true"))
        assertTrue(gate.contains("selected.view === view"))
        assertTrue(gate.contains("privateLease?.isLive() == true"))
        assertTrue(gate.contains("else privateRuntime.mute(view)"))
    }

    @Test fun directMuteLoopDoesNotQueryGrantAndFailureEntersExistingRetirement() {
        val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
        val host = File(root, "src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt").readText()
        val mute = host.substringAfter("internal fun mutePrivateCaptureAudio()").substringBefore("internal fun refreshPrivateCaptureAudio()")
        assertTrue(mute.contains("requireMain()"))
        assertTrue(mute.contains("privateViews.keys.toList().forEach"))
        assertTrue(mute.contains("privateRuntime.mute(view)"))
        assertTrue(mute.contains("catch (_: Exception) { success = false }"))
        assertTrue(mute.contains("if (!success) blockPrivateExposure()"))
        val block = host.substringAfter("private fun blockPrivateExposure(").substringBefore("internal fun mutePrivateCaptureAudio()")
        assertTrue(block.contains("if (retire && !transitioning && !retiringPrivate) exitPrivateSession()"))
        // Direct body only: blockPrivateExposure can synchronously reenter the integration on failure.
        for (forbidden in listOf("privateIntegration", "unmuteVisible", "allowVisibleAudio", "CookieManager", "protectPrivateTransition("))
            assertFalse(forbidden, mute.contains(forbidden))
    }

}
