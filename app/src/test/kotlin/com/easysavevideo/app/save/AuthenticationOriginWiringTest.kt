package com.easysavevideo.app.save

import java.nio.file.Files
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source contracts supplement pure identity tests; they do not execute Android WebView. */
class AuthenticationOriginWiringTest {
    private fun source(path: String) = String(Files.readAllBytes(Paths.get("src/main/kotlin/com/easysavevideo/app", path)), Charsets.UTF_8)
    private val runtime get() = source("save/AndroidSaveRuntime.kt")
    private val host get() = source("browser/SystemBrowserHost.kt")
    @Test fun directAndFinalHlsBindOnlyInAcceptedBranchesWithActualResource() {
        val direct = runtime.substringAfter("private fun chooseDirect(").substringBefore("private fun prepareStream(")
        assertTrue(direct.contains("captureAuthenticationOrigin(captured, browser, candidate.url.value)"))
        val accepted = direct.substringAfter("is SaveSubmission.Accepted -> {").substringBefore("is SaveSubmission.Rejected")
        assertTrue(accepted.contains("bindAuthenticationOrigin(result.record.task.taskId, authenticationOrigin, result.existing, candidate.url.value)"))
        val stream = runtime.substringAfter("private fun chooseHlsVariantSelected(").substringBefore("fun cancel(")
        assertTrue(stream.contains("val authenticationOrigin = streamAuthenticationOrigin"))
        assertTrue(stream.contains("streamAuthenticationOrigin = null"))
        assertTrue(stream.substringAfter("is SaveSubmission.Accepted -> {").substringBefore("is SaveSubmission.Rejected")
            .contains("bindAuthenticationOrigin(result.record.task.taskId, authenticationOrigin, result.existing, resource.url.value)"))
        assertEquals(2, Regex("bindAuthenticationOrigin\\(result\\.record\\.task\\.taskId").findAll(runtime).count())
    }
    @Test fun captureIsHostOwnedFreshSelectionRatherThanDownloadSourceLookup() {
        val capture = host.substringAfter("internal fun captureAuthenticationOrigin(").substringBefore("internal fun canSelectAuthenticationOrigin(")
        for (required in listOf("requireMain()", "saveInspectionPage()", "selection.isCurrent()", "page.isUsable()",
            "page.context != selection.context", "page.epoch != selection.epoch", "page.pageUrl != selection.pageUrl",
            "authenticationDocument(selection.context)", "authenticationDocuments.capture(document, source)")) assertTrue(required, capture.contains(required))
        val lookup = host.substringAfter("private fun authenticationEntry(").substringBefore("/** Process-local document identity")
        assertTrue(lookup.contains("entries.entries.firstOrNull"));assertFalse(lookup.contains("entries["))
        val document = host.substringAfter("private fun authenticationDocument(").substringBefore("internal fun captureAuthenticationOrigin(")
        for (required in listOf("state.tabs.singleOrNull", "authenticationEntry(context)", "entry.inspectionDocument.address",
            "!foreground", "entry.suppressed", "!controller.isCurrent(request)", "entry.admission?.hasStarted()", "address != entry.view.url",
            "entry, entry.view", "entry.inspectionDocument.revision", "tab.epoch", "tab.incognito", "BrowserLoadState.READY")) assertTrue(required, document.contains(required))
    }
    @Test fun selectingLiveEntryCannotCreateViewRestoreHistoryOrLoadUrl() {
        val selector = host.substringAfter("internal fun selectAuthenticationOrigin(").substringBefore("private val timeout")
        assertTrue(selector.contains("authenticationOriginInteractionCurrent(expectedBrowserRevision, interactionRevision)"))
        assertTrue(selector.contains("authenticationOriginInteractionCurrent(selectionRevision, interactionRevision)"))
        assertTrue(selector.contains("requireMain()"));assertTrue(selector.contains("authenticationEntry(context) ?: return false"))
        assertEquals(2, Regex("canSelectAuthenticationOrigin\\(token\\)").findAll(selector).count())
        assertTrue(selector.contains("authenticationEntry(context) !== entry"))
        assertTrue(selector.contains("controller.selectTab(context.tabId)"));assertTrue(selector.contains("entry.view.onResume()"))
        for (forbidden in listOf("ensureEntry", "history", "navigate(", "load(", "restore(", "newTab(", "Cookie", "sourceForTask", "resume(task"))
            assertFalse(forbidden, selector.contains(forbidden))
    }
    @Test fun hostSelectionChecksCapabilityForegroundAndNativeBlockers() {
        val gate = host.substringAfter("internal fun canSelectAuthenticationOrigin(").substringBefore("/** Explicit selection")
        for (required in listOf("closed", "!foreground", "!platformAvailable", "!browserCommandAllowed()", "pickerInFlight",
            "fileChoice != null", "jsDialog != null", "fullScreenView != null", "authenticationDocuments.context(token)",
            "authenticationDocuments.resolve(token, document)")) assertTrue(required, gate.contains(required))
    }
    @Test fun backgroundCloseEvictionAndCancellationRetireAuthority() {
        val pause = host.substringAfter("fun pause()").substringBefore("fun hideFullscreen")
        assertTrue(pause.contains("authenticationDocuments.clear()"))
        assertTrue(host.substringAfter("override fun close()").contains("authenticationDocuments.clear()"))
        assertTrue(host.substringAfter("private fun trimViews(").substringBefore("override fun destroy(").contains("authenticationDocuments.invalidate(entry)"))
        assertTrue(host.substringAfter("override fun destroy(").substringBefore("private fun releaseView(").contains("authenticationDocuments.invalidate(it)"))
        assertTrue(runtime.substringAfter("fun setForeground(").substringBefore("internal fun authenticationBrowserAvailable")
            .contains("authenticationOrigins.clear(); streamAuthenticationOrigin = null"))
        assertTrue(runtime.substringAfter("fun cancel(taskId:").substringBefore("fun resume(").contains("authenticationOrigins.cancel(taskId)"))
        assertTrue(runtime.substringAfter("private fun publishRecords()").substringBefore("private fun resourcesDraining")
            .contains("authenticationOrigins.retainNonterminal(next.filterNot { it.task.state.isTerminal }"))
    }
    @Test fun preparationAndSubmitFailurePathsDiscardWithoutChangingSaveAuthority() {
        val dismiss = runtime.substringAfter("fun dismissSelection()").substringBefore("fun dismissNotice()")
        assertTrue(dismiss.contains("authenticationOrigins.discard(streamAuthenticationOrigin); streamAuthenticationOrigin = null"))
        val prepare = runtime.substringAfter("private fun prepareStream(").substringBefore("fun chooseHlsVariant(")
        assertTrue(prepare.contains("authenticationOrigins.discard(authenticationOrigin)"))
        assertTrue(prepare.contains("if (streamAuthenticationOrigin === authenticationOrigin) streamAuthenticationOrigin = null"))
        for (block in listOf(runtime.substringAfter("private fun chooseDirect(").substringBefore("private fun prepareStream("),
            runtime.substringAfter("private fun chooseHlsVariantSelected(").substringBefore("fun cancel("))) {
            assertTrue(block.contains("acceptedTaskId?.let(authenticationOrigins::cancel)"))
            assertTrue(block.contains("authenticationOrigins.discard(authenticationOrigin)"))
        }
    }
    @Test fun originCommandUsesExistingFreshTaskGateAndNeverReadsCredentialsOrSource() {
        val bridge = runtime.substringAfter("internal fun returnToOriginForAuthentication(").substringBefore("private fun captureAuthenticationOrigin(")
        assertTrue(bridge.contains("authenticationOrigins.token(command.taskId)"))
        assertTrue(bridge.contains("authenticationOriginInteractionCurrent(expectedBrowserRevision, browser.saveInspectionRevision)"))
        assertTrue(bridge.contains("browser.canSelectAuthenticationOrigin(token)"))
        assertTrue(bridge.contains("returnToBrowserForAuthentication(command)"))
        assertTrue(bridge.contains("if (accepted != AuthenticationNavigationResult.ACCEPTED) return accepted"))
        assertTrue(bridge.contains("browser.selectAuthenticationOrigin(token, expectedBrowserRevision)"))
        for (forbidden in listOf("sourceForTask", "Cookie", "leaseFor", "coordinator.", "submit(", "resume(", "navigate(", "record("))
            assertFalse(forbidden, bridge.contains(forbidden))
    }
    @Test fun explicitOriginActionHasIndependentFreshCommandAndGenericFallbackStillWired() {
        val ui = source("save/SaveAuthenticationOriginGuidance.kt")
        for (required in listOf("remember(identity, generation, browserRevision)", "AuthenticationBrowserCommand(identity, generation)",
            "onDispose { command.retire() }", "rememberUpdatedState(canOpenBrowser)", "rememberUpdatedState(onBrowser)",
            "if (open == null || !currentCanOpen())", "runtime.returnToOriginForAuthentication(command, browser, browserRevision)",
            "if (result == AuthenticationNavigationResult.ACCEPTED) open?.invoke()", "EsvButton(stringResource(R.string.save_auth_origin_open)"))
            assertTrue(required, ui.contains(required))
        for (forbidden in listOf("LaunchedEffect", "sourceForTask", "Cookie", "navigate(", "resume(")) assertFalse(forbidden, ui.contains(forbidden))
        assertTrue(source("theme/EsvComposeTheme.kt").contains(".sizeIn(minWidth = 48.dp, minHeight = 48.dp)"))
        val screen = source("save/SaveTaskScreen.kt")
        assertTrue(screen.contains("SaveAuthenticationGuidance(record, runtime, onBrowser, canOpenBrowserForAuthentication)"))
        assertTrue(screen.contains("SaveAuthenticationOriginGuidance(record, runtime, browser, onBrowser, canOpenBrowserForAuthentication)"))
    }
    @Test fun pairedResourcesUseCurrentCompositionAndExplainUnavailableWithoutLeakingUrls() {
        fun strings(locale: String): Map<String,String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Paths.get("src/main/res", locale, "save_authentication_origin.xml").toFile())
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate { i -> nodes.item(i).let { it.attributes.getNamedItem("name").nodeValue to it.textContent } }
        }
        val en = strings("values");val zh = strings("values-zh-rCN")
        assertEquals(setOf("save_auth_origin_open", "save_auth_origin_boundary", "save_auth_origin_unavailable"), en.keys)
        assertEquals(en.keys, zh.keys)
        for (value in en.values + zh.values) { assertTrue(value.isNotBlank());assertFalse(value.contains("%"));assertFalse(value.contains("://")) }
        assertTrue(en.getValue("save_auth_origin_unavailable").contains("No page will be recreated"))
        assertTrue(zh.getValue("save_auth_origin_unavailable").contains("不会重建网页"))
        assertTrue(source("save/SaveAuthenticationOriginGuidance.kt").contains("stringResource(if (available)"))
    }
}
