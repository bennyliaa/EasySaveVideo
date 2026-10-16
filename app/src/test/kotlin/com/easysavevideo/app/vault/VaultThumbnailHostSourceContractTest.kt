package com.easysavevideo.app.vault

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring evidence only. This does not execute Android rendering, native decode or GPU retirement. */
class VaultThumbnailHostSourceContractTest {
    private fun source(name: String): String = File(System.getProperty("esv.vault.host.source")
        ?: "src/main/kotlin/com/easysavevideo/app").resolve("vault/$name").readText()
    private fun requiredIndex(text: String, marker: String): Int = text.indexOf(marker).also {
        assertTrue("Required source marker must exist", it >= 0)
    }
    private fun section(text: String, first: String, last: String): String {
        val a = text.indexOf(first); val b = text.indexOf(last, a + first.length)
        assertTrue("Source section must exist in order", a >= 0 && b > a)
        return text.substring(a, b)
    }
    @Test fun actualMeasuredViewportIsTheOnlyUiDemandPath() {
        val ui = source("VaultRuntimeUi.kt")
        assertTrue(ui.contains("LazyColumn(state = listState"))
        assertTrue(ui.contains("remember(host, state.coverGeneration, state.revision) { LazyListState() }"))
        assertTrue(ui.contains("listState.layoutInfo.visibleItemsInfo.map { it.contentType }"))
        assertTrue(ui.contains("itemsIndexed(state.items, contentType = { index, _ -> viewportRows[index] })"))
        assertTrue(ui.contains("collectLatest { host.updateThumbnailViewport(it) }"))
        listOf("rememberLazyListState", "rememberSaveable", "verticalScroll", "state.items.forEach", "key = { it.ref", "key = { it.name").forEach {
            assertFalse(it, ui.contains(it))
        }
        val thumbnail = source("VaultPrivateThumbnail.kt")
        listOf("LaunchedEffect", "SideEffect", "request(", "openThumbnail", "readAt").forEach { assertFalse(it, thumbnail.contains(it)) }
    }
    @Test fun componentOnlyDrawsOpaqueGuardedHandleWithGenericLocalizedSemantics() {
        val ui = source("VaultPrivateThumbnail.kt")
        assertTrue(ui.contains("it.display.isCurrent()"))
        assertTrue(ui.contains("ready.drawIfCurrent { opaque ->"))
        assertTrue(ui.contains("(opaque as? AndroidThumbnailFrame)?.draw"))
        assertTrue(ui.contains("clearAndSetSemantics { contentDescription = label; stateDescription = description }"))
        assertTrue(ui.contains("stringResource(R.string.vault_thumbnail_description)"))
        listOf("android.graphics.Bitmap", "ImageBitmap", "item.name", "displayName", "Uri", "SavedState", "Log.").forEach {
            assertFalse(it, ui.contains(it))
        }
    }
    @Test fun hostUsesSharedTypedSessionAndTheSoleRuntimeAdmission() {
        val host = source("VaultProtectedHost.kt")
        assertTrue(host.contains("TypedBoundedThumbnailSession<VaultMediaReadRef, VaultMediaReadRef, VaultThumbnailFrame>"))
        assertTrue(host.contains("runtime.openThumbnail(ref, token) { result, settled ->"))
        assertTrue(host.contains("complete(result) {"))
        assertTrue(host.contains("if (thumbnailAdmission === admission) thumbnailAdmission = null"))
        assertTrue(host.contains("worker = ThumbnailWorkLane { task -> requireMain(); task.run(); true }"))
        listOf("Executors.", "ThreadPoolExecutor", "MediaMetadataRetriever", "VaultControlledMediaReader", "FileInputStream", "AndroidLibraryThumbnails(").forEach {
            assertFalse(it, host.contains(it))
        }
    }
    @Test fun everyMainHandoffAndTimerEnqueueChecksAcceptance() {
        val host = source("VaultProtectedHost.kt")
        assertTrue(host.contains("override fun post(task: Runnable) { check(main.post(task)) }"))
        assertTrue(host.contains("override fun later(task: Runnable, delayMillis: Long) { check(main.postDelayed(task, delayMillis)) }"))
        assertTrue(host.contains("if (!main.post {"))
        assertTrue(host.contains("!thumbnailHostFailure.get() && !thumbnailProgressPending && runtime.thumbnailCleanupComplete()"))
    }
    @Test fun coverFactRetiresExactVisualMarkersBeforeAggregateResourceReceipt() {
        val host = source("VaultProtectedHost.kt")
        val hierarchy = section(host, "fun hierarchyChanged()", "fun ordinaryReleaseAllowed()")
        val observed = requiredIndex(hierarchy, "if (coveredGeneration == generation) {")
        val marker = requiredIndex(hierarchy, "runtime.thumbnailHostCovered(token)")
        val resources = requiredIndex(hierarchy, "if (coveredGeneration == generation && hostResourcesRetired())")
        val receipt = requiredIndex(hierarchy, "exact.second()")
        assertTrue(observed >= 0 && marker > observed && resources > marker && receipt > resources)
        assertTrue(host.contains("thumbnailHosts.filter { !it.isCurrent() }"))
        assertTrue(host.contains("thumbnailResourcesRetired() && !playbackAcquisitionUnconfirmed"))
        assertTrue(host.contains("runtime.thumbnailCleanupComplete()"))
        assertTrue(host.contains("!thumbnailSession.hasActiveJob && !thumbnailSession.hasUnconfirmedResources && thumbnailSession.retainedCount == 0"))
    }
    @Test fun cancellationAndCompositionRemovalNeverAssertActualCover() {
        val host = source("VaultProtectedHost.kt")
        val stopping = section(host, "private fun stopThumbnails", "private fun retireThumbnailsBeforeOperation")
        assertTrue(stopping.contains("runtime::revokeThumbnails"))
        assertTrue(stopping.contains("thumbnailSession.updatePage(emptyList(), enabled = false)"))
        assertTrue(stopping.contains("fun thumbnailCompositionRemoved()"))
        assertFalse(stopping.contains("thumbnailHostCovered"))
        assertTrue(host.contains("thumbnailAdmission?.ticket?.let(runtime::closeThumbnail)"))
    }
    @Test fun intentMustBeExplicitlyStartedAndEveryAuthorityLossClearsIt() {
        val host = source("VaultProtectedHost.kt")
        assertEquals(1, Regex("thumbnailIntent\\.set\\(true\\)").findAll(host).count())
        assertTrue(section(host, "fun invalidateActionsFromAnyThread", "fun presentationChanged").contains("thumbnailIntent.set(false)"))
        listOf("fun deviceLocked()", "fun surfaceCovered()", "fun background()").forEach { start ->
            assertTrue(host.substringAfter(start).substringBefore('\n').contains("stopThumbnails(clearViewport = true)"))
        }
        assertTrue(section(host, "fun protectBeforeDeparture()", "fun deviceLocked()").contains("stopThumbnails(clearViewport = true)"))
        assertTrue(section(host, "fun playbackProtectionLost()", "fun playbackCompositionRemoved()").contains("stopThumbnails(clearViewport = true)"))
        assertTrue(host.substringAfter("fun detach() {").contains("stopThumbnails(clearViewport = true)"))
    }
    @Test fun selectedOperationsRetireFirstAndDoNotQueueBehindUnknownCleanup() {
        val host = source("VaultProtectedHost.kt")
        assertTrue(section(host, "fun openPlayback(ref:", "fun playPlayback").contains("if (!retireThumbnailsBeforeOperation()) return"))
        listOf("confirmMoveToVault", "copyMediaToPhone", "copyMediaBatchToPhone", "confirmCopyToPhone", "confirmBatchCopy", "reconcileMedia").forEach { name ->
            assertTrue(name, host.substringAfter("fun $name(").substringBefore('\n').contains("retireThumbnailsBeforeOperation()"))
        }
        val retire = section(host, "private fun retireThumbnailsBeforeOperation", "private fun thumbnailResourcesRetired")
        assertTrue(requiredIndex(retire, "stopThumbnails()") < requiredIndex(retire, "runtime.thumbnailWorkComplete()"))
        val lock = host.substringAfter("fun lock()").substringBefore('\n')
        assertTrue(lock.contains("runtime.lock()")); assertFalse(lock.contains("thumbnailWorkComplete"))
    }
    @Test fun actualRootDetachInvalidatesBeforePostingAndNewSessionRequiresFreshCoveredGeneration() {
        val host = source("VaultProtectedHost.kt")
        val detach = section(host, "override fun onViewDetachedFromWindow", "private fun requireMain")
        assertTrue(requiredIndex(detach, "invalidateActionsFromAnyThread()") < requiredIndex(detach, "main.post"))
        assertTrue(detach.contains("thumbnailHostFailure.set(true)"))
        assertTrue(host.contains("thumbnailSessionGeneration != generation && thumbnailResourcesRetired()"))
        assertEquals(2, Regex("= createThumbnailSession\\(\\)").findAll(host).count())
    }
    @Test fun captureConflictDoesNotStopOrdinaryRecording() {
        val host = source("VaultProtectedHost.kt")
        assertTrue(host.contains("!guard.hasContinuousCaptureAuthorization && captureDrained()"))
        listOf("stopRecording", "cancelRecording", "revokeRecording", "recycle(", "GPU_ERASED").forEach { assertFalse(it, host.contains(it)) }
    }
    @Test fun existingSelectionBatchExportAndPlaybackControlsRemainReachable() {
        val ui = source("VaultRuntimeUi.kt")
        listOf("VaultPlaybackControls(host, state.playback, accepts)", "host.openPlayback(item.ref)",
            "host.copyMediaToPhone(item.ref)", "host.copyMediaBatchToPhone(plan)", "host.confirmBatchCopy(token)",
            "host.keepCurrentBatchOutputAndContinue(token)", "host.keepCurrentSingleOutput(token)", "host.cancelMedia()", "host.lock()",
            "selected - item.ref", "VaultCopyBatchLimits.MAX_ITEMS").forEach { assertTrue(it, ui.contains(it)) }
    }
}
