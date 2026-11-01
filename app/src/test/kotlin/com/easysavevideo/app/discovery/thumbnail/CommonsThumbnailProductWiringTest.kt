package com.easysavevideo.app.discovery.thumbnail

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Host source-wiring assertions only; Android pixels, lifecycle and network need platform tests. */
class CommonsThumbnailProductWiringTest {
    private fun source(path: String): String = File("src/main/kotlin/com/easysavevideo/app/$path").readText()
    private fun assertBefore(body: String, first: String, second: String) {
        val firstIndex = body.indexOf(first)
        val secondIndex = body.indexOf(second)
        assertTrue("Missing first operation: $first", firstIndex >= 0)
        assertTrue("Missing second operation: $second", secondIndex >= 0)
        assertTrue("Expected $first before $second", firstIndex < secondIndex)
    }

    @Test fun registrationIssuesOnlyExactCurrentCommonsMetadata() {
        val source = source("discovery/CommonsSourceRegistration.kt")
        val issue = source.substringAfter("internal fun thumbnailCapability(").substringBefore("// Process owner;")
        for (required in listOf("binding != DiscoveryThumbnailBinding.of(binding.session, binding.pageRevision, result)",
            "result.thumbnailUrl?.let(CommonsUrlPolicy::thumbnail)", "!configured", "!maintenanceAvailable",
            "managementRevision != expectedRevision", "!current()", "result.sourceNamespace != CommonsIdentity.PROVIDER_ID",
            "CommonsIdentity.PROVIDER_ID !in result.provenance", "CommonsUrlPolicy.page(result.canonicalPageUrl)",
            "result.rights.snapshots.none", "it.sourceItemId == result.sourceVideoId",
            "it.sourcePageUrl == result.canonicalPageUrl", "DiscoveryThumbnailCapability.fromCommonsRegistration(binding,",
            "managementRevision == expectedRevision && configured && maintenanceAvailable && current()",
            "AndroidDiscoveryThumbnails.read(app, url, cancellation)")) assertTrue(required, issue.contains(required))
        assertFalse(issue.contains("RightsReview.VERIFIED")) // Display does not grant reuse rights.
    }

    @Test fun successfulMaintenanceWithdrawsBeforePollingCanPublishNewState() {
        val registration = source("discovery/CommonsSourceRegistration.kt")
        val change = registration.substringAfter("private fun changed(result:").substringBefore("val maintenanceAvailable")
        assertTrue(change.contains("result is ProviderRepositoryResult.Success"))
        val revision = change.indexOf("changes.incrementAndGet()")
        val withdrawal = change.indexOf("thumbnailWithdrawals.forEach")
        assertTrue("Maintenance revision increment is missing", revision >= 0)
        assertTrue("Thumbnail withdrawal notification is missing", withdrawal >= 0)
        assertTrue(revision < withdrawal)
        assertTrue(change.contains("callback()"))
        val controller = source("discovery/AndroidDiscoveryController.kt")
        assertTrue(controller.contains("CommonsSourceRegistration.subscribeThumbnailWithdrawal(thumbnails::withdraw)"))
        assertTrue(controller.contains("thumbnailMaintenanceSubscription.close(); thumbnails.close()"))
    }

    @Test fun controllerRechecksSourcePageAndPowerBeforeIssuingAndDuringRead() {
        val source = source("discovery/AndroidDiscoveryController.kt")
        val update = source.substringAfter("internal fun updateThumbnailViewport(").substringBefore("private fun profile(")
        for (required in listOf("value.page == page && value.results == results", "!value.demo",
            "value.commonsConfigured && value.commonsEnabled", "value.configurationIntent == source",
            "value.sourceRevision == CommonsSourceRegistration.managementRevision",
            "value.thumbnailGeneration == thumbnails.currentGeneration()", "value.admittedProviderDescriptors.any",
            "thumbnails.update(page, results, visibleIds, allowed, value.thumbnailGeneration) { binding, result, epoch ->",
            "val allowed = thumbnailIntentApplied() && thumbnailNetworkAllowed()",
            "thumbnails.isCurrent(epoch) && thumbnailIntentApplied() && thumbnailNetworkAllowed() && sourceIntent.get() == source",
            "pageLifecycle.get() == lifecycle", "!saveActive && !recordingActive", "desiredPower.get()"))
            assertTrue(required, update.contains(required))
        val sourceFence = source.substringAfter("private fun fenceSourceConfiguration() {").substringBefore("private fun loadedSourceConfiguration")
        assertTrue(sourceFence.contains("thumbnails.withdraw()"))
        val power = source.substringAfter("private fun offerPowerLocked(").substringBefore("fun updateSettings(")
        assertTrue(power.contains("thumbnails.withdraw()"))
    }

    @Test fun settingsSortPageAndValidSearchBeginAnIntentBeforeTheirExistingSubmittedCommands() {
        val controller = source("discovery/AndroidDiscoveryController.kt")
        val settings = controller.substringAfter("fun updateSettings(").substringBefore("fun search(")
        val sort = controller.substringAfter("fun updateSort(").substringBefore("fun movePage(")
        val page = controller.substringAfter("fun movePage(").substringBefore("fun cancelNextPage(")
        val search = controller.substringAfter("fun search(").substringBefore("fun updateSort(")
        for ((name, body) in listOf("settings" to settings, "sort" to sort, "page" to page)) {
            assertTrue(name, body.isNotBlank())
            assertBefore(body, "beginThumbnailIntent()", "submit {")
        }
        assertTrue(settings.contains("val thumbnailIntent = if (CommonsThumbnailPolicy.settingsInputChanged(thumbnailSettingsInput, value))"))
        assertTrue(settings.contains("beginThumbnailIntent() else null"))
        assertTrue(settings.contains("finally { thumbnailIntent?.let(thumbnailAppliedIntent::set) }"))
        assertFalse(settings.contains("thumbnailAppliedIntent.set("))
        for (body in listOf(sort, page, search))
            assertTrue(body.contains("finally { thumbnailAppliedIntent.set(thumbnailIntent) }"))
        val invalidSearch = search.substringBefore("val thumbnailIntent = beginThumbnailIntent()")
        assertTrue(invalidSearch.contains("if (!DiscoveryPresentationPolicy.validQuery(bounded))"))
        assertTrue(invalidSearch.contains("submit { notice = DiscoveryNotice.INVALID_QUERY; refresh(readOnly = true) }"))
        assertFalse(invalidSearch.contains("beginThumbnailIntent()"))
        assertFalse(invalidSearch.contains("allowSessionCandidateVerification"))
        val validSearch = search.substringAfter("val thumbnailIntent = ")
        assertBefore(validSearch, "beginThumbnailIntent()", "submit {")
        val searchCommand = validSearch.substringAfter("submit {")
        for (required in listOf("if (!initialized || unavailable) return@submit",
            "coordinator?.start(SESSION, profile(bounded, filters), allowSessionCandidateVerification = !demo,\n                    diagnosticsCapture = diagnosticsCapture)", "coordinator?.let(::applyLifecycle); refresh()"))
            assertTrue(required, searchCommand.contains(required))
        val settingsCommand = settings.substringAfter("submit {")
        assertFalse(settingsCommand.contains("allowSessionCandidateVerification"))
        for (required in listOf("val old = settings; settings = value", "coordinator?.setMode(value.mode)",
            "coordinator?.reviseView(value.repeatPolicy, value.pageSize, pageIndex)", "refresh()"))
            assertTrue(required, settingsCommand.contains(required))
        val sortCommand = sort.substringAfter("submit {")
        for (required in listOf("if (!initialized || unavailable) return@submit",
            "coordinator?.reviseView(settings.repeatPolicy, settings.pageSize, pageIndex, value)",
            "sortOrder = value; pageIndex = 0; pageDemandPending = true", "refresh()"))
            assertTrue(required, sortCommand.contains(required))
        val pageCommand = page.substringAfter("submit {")
        for (required in listOf("val c = coordinator ?: return@submit", "target != currentControlTarget(c)",
            "from != pageIndex", "lifecycle != pageLifecycle.get()", "!foreground || !visible",
            "managementRevision != CommonsSourceRegistration.managementRevision", "delta !in setOf(-1, 1)",
            "val checkpoint = c.currentCheckpoint() ?: return@submit", "nextPageDemand.begin(target, from, lifecycle)",
            "val page = c.page(from + 1)", "else c.requestMore()", "refresh()"))
            assertTrue(required, pageCommand.contains(required))
    }

    @Test fun publicationCapturesGenerationBeforePageReadsAndOnlyOwnCommandTokensCanBeAcknowledged() {
        val controller = source("discovery/AndroidDiscoveryController.kt")
        for (required in listOf("private val thumbnailRequestedIntent = AtomicLong(0)",
            "private val thumbnailAppliedIntent = AtomicLong(0)",
            "private fun thumbnailIntentApplied(): Boolean = thumbnailRequestedIntent.get() == thumbnailAppliedIntent.get()"))
            assertTrue(required, controller.contains(required))
        val begin = controller.substringAfter("private fun beginThumbnailIntent(): Long {")
            .substringBefore("private fun thumbnailIntentApplied()")
        assertBefore(begin, "val intent = thumbnailRequestedIntent.incrementAndGet()", "thumbnails.withdraw()")
        assertBefore(begin, "thumbnails.withdraw()", "return intent")
        assertFalse(controller.contains("thumbnailAppliedIntent.set(thumbnailRequestedIntent.get())"))
        val refresh = controller.substringAfter("private fun refresh(readOnly: Boolean = false) {")
            .substringBefore("internal fun updateThumbnailViewport(")
        val capture = "val thumbnailSnapshotGeneration = CommonsThumbnailPolicy.publicationGeneration("
        assertTrue(refresh.contains("thumbnails.currentGeneration(), thumbnailRequestedIntent.get(), thumbnailAppliedIntent.get()"))
        for (read in listOf("val c = coordinator ?: return", "val checkpoint = c.currentCheckpoint()",
            "val updated = c.currentCheckpoint()", "store.saveViewport(", "publish(DiscoveryUiState("))
            assertBefore(refresh, capture, read)
        assertTrue(refresh.contains("thumbnailGeneration = thumbnailSnapshotGeneration"))
        assertFalse(refresh.contains("thumbnailGeneration = thumbnails.currentGeneration()"))
        assertEquals(1, Regex(Regex.escape("thumbnails.currentGeneration()")).findAll(refresh).count())
    }

    @Test fun visibleCardUsesActualPixelsWithSeparateLocalizedFailurePlaceholder() {
        val ui = source("ui/DiscoveryScreen.kt")
        for (required in listOf("DisposableEffect(controller)", "controller.clearThumbnailViewport()",
            "snapshotFlow", "grid.layoutInfo.visibleItemsInfo", "controller.updateThumbnailViewport(page, state.results, ids)",
            "controller.thumbnailImage(page, result)", "if (pixels != null) Image(pixels,",
            "contentScale = ContentScale.Fit", "R.string.discovery_thumbnail_loading", "R.string.discovery_thumbnail_failed"))
            assertTrue(required, ui.contains(required))
        assertFalse(ui.contains("AsyncImage(")) // The UI cannot independently authorize arbitrary URLs.
        for (locale in listOf("values", "values-zh-rCN")) {
            val xml = File("src/main/res/$locale/discovery_copy.xml").readText()
            for (name in listOf("discovery_thumbnail_image", "discovery_thumbnail_loading", "discovery_thumbnail_failed"))
                assertTrue(name, xml.contains("name=\"$name\""))
        }
    }
}
