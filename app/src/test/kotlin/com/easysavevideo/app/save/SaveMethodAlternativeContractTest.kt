package com.easysavevideo.app.save

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source/resource wiring contracts only; no WebView, HLS network, Compose or storage execution. */
class SaveMethodAlternativeContractTest {
    private fun source(name: String) = File("src/main/kotlin/com/easysavevideo/app/save/$name.kt").readText()
    private val runtime get() = source("AndroidSaveRuntime")
    @Test fun navigationInstallsOnlyPureEligibleIdentityWithoutReadingSourcesOrWritingTasks() {
        val body = runtime.substringAfter("internal fun returnToBrowserAfterFailure(").substringBefore("fun dismissFailureBrowserReminder")
        assertTrue(body.indexOf("FailureNavigationResult.ACCEPTED") < body.indexOf("alternativeSelection.arm("))
        assertTrue(body.contains("SaveMethodAlternatives.review(identity) == SaveAlternativeReview.NEEDS_FRESH_STREAM_SOURCE"))
        for (forbidden in listOf("coordinator.record(", "sourceForTask(", "leaseFor(", "coordinator.submit(", "coordinator.cancel(")) assertFalse(forbidden, body.contains(forbidden))
    }
    @Test fun oneExplicitInspectionConsumesIntentAndBindsActualCurrentSelection() {
        val body = runtime.substringAfter("fun inspectFromUserClick(").substringBefore("fun dismissSelection()")
        assertTrue(body.indexOf("alternativeSelection.beginInspection(") < body.indexOf("inspectionPage(browser)"))
        assertTrue(body.contains("expectedAlternativeInspection && alternativeInspection == null"))
        assertTrue(body.substringAfter("expectedAlternativeInspection && alternativeInspection == null").substringBefore("val captured").contains("return"))
        assertTrue(body.contains("resolveAlternativeInspection(alternativeSelection, alternativeInspection,"))
        val rejected = body.substringAfter("SaveAlternativeInspectionDelivery.REJECTED -> {").substringBefore("SaveAlternativeInspectionDelivery.ALTERNATIVE")
        assertTrue(rejected.contains("SaveAlternativeFeedback.UNAVAILABLE"))
        assertFalse(rejected.contains("selection =")); assertFalse(rejected.contains(".clear()"))
        assertTrue(body.contains("streamAlternative?.revoke()"))
        assertTrue(runtime.substringAfter("fun setForeground(").substringBefore("internal fun authenticationBrowserAvailable").contains("clearAlternativeSelection()"))
        assertTrue(runtime.substringAfter("fun dismissSelection()").substringBefore("fun dismissNotice()").contains("clearAlternativeSelection()"))
    }
    @Test fun successfulRealPreparationPrecedesProposalAndLateDeliveryCannotBecomeReady() {
        val body = runtime.substringAfter("private fun prepareStream(").substringBefore("fun chooseHlsVariant(")
        assertTrue(body.indexOf("stream.prepare(source, operation)") < body.indexOf("coordinator.prepareStreamAlternative("))
        assertTrue(body.contains("source, stream.operation(prepared), minOf(alternative.expiresAt, captured.expiresAtMillis, prepared.expiresAtMillis)"))
        assertTrue(body.contains("?.generation == alternative.generation"))
        assertTrue(body.contains("networkSaveAllowed() && alternativeCurrent"))
        assertTrue(body.contains("preparedProposal?.revoke()"))
        assertTrue(body.contains("selectionAlternativeExpected && alternative == null"))
    }
    @Test fun existingVariantDestinationAndExclusiveClaimRemainTheConfirmationBoundary() {
        val body = runtime.substringAfter("private fun chooseHlsVariantSelected(").substringBefore("fun cancel(")
        assertTrue(body.contains("stream.select(prepared, identity, binding)"))
        assertTrue(body.contains("val id = admission.creationId"))
        assertTrue(body.contains("val request = SaveRequest(id, id, resource, TaskMethod.STREAM)"))
        assertTrue(body.indexOf("val request =") < body.indexOf("coordinator.confirmStreamAlternative("))
        assertTrue(body.contains("requireNotNull(alternativeGeneration), request, operation, execution, prepared.bytesTransferred"))
        assertTrue(body.contains("generation != executionForegroundGeneration || !canStartSave(TaskMethod.STREAM)"))
        assertTrue(body.contains("streamAlternativeRequired && streamAlternative == null"))
        assertFalse(body.contains("tryAcquireExclusive(")); assertFalse(body.contains("tryAcquireDirect("))
    }
    @Test fun unknownCreationIsDistinctNeverDispatchesOrReplaysAndDoesNotCancelRows() {
        val body = runtime.substringAfter("is SaveAlternativeConfirmation.OutcomeUnknown -> {").substringBefore("when (val result = submission)")
        assertTrue(body.contains("unknownAlternativeTaskId = confirmed.taskId"))
        assertTrue(body.contains("SaveAlternativeFeedback.OUTCOME_UNKNOWN"))
        assertTrue(body.contains("try { publishRecords() } catch (_: Exception)"))
        assertTrue(body.contains("return@command"))
        for (forbidden in listOf("launchExecution(", "coordinator.submit(", "coordinator.cancel(", "coordinator.recover(", "UUID", "delete(")) assertFalse(forbidden, body.contains(forbidden))
        val cleanup = runtime.substringAfter("// No worker handoff occurred, including OUTCOME_UNKNOWN.").substringBefore("fun cancel(")
        assertTrue(cleanup.contains("stream.discard(prepared)")); assertTrue(cleanup.contains("releaseExecution(execution)"))
    }
    @Test fun ordinaryStreamAndIndependentRecordDoNotAcquireAlternativeAuthority() {
        assertTrue(runtime.contains("if (alternative == null) coordinator.submit(request, operation, execution, prepared.bytesTransferred)"))
        val record = runtime.substringAfter("fun prepareRecording(")
        assertFalse(record.contains("confirmStreamAlternative(")); assertFalse(record.contains("prepareStreamAlternative("))
        val renderer = source("SaveMethodAlternativeUi")
        for (forbidden in listOf(".cancel(", ".submit(", ".resume(", ".recover(", "unknownAlternativeTaskId", "LaunchedEffect")) assertFalse(forbidden, renderer.contains(forbidden))
        assertTrue(runtime.contains("internal fun dismissAlternativeFeedback() { alternativeFeedback = null }"))
    }
    @Test fun pairedCopyMakesFreshAttemptAndUncertainCreationVisibleWithoutClaimingCapture() {
        fun strings(folder: String): Map<String, String> {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val nodes = factory.newDocumentBuilder().parse(File("src/main/res/$folder/save_method_alternative.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
        }
        val en = strings("values"); val zh = strings("values-zh-rCN")
        assertEquals(5, en.size); assertEquals(en.keys, zh.keys)
        (en.values + zh.values).forEach { assertTrue(it.isNotBlank()); assertFalse(it.contains("://")) }
        assertTrue(en.getValue("save_alternative_ready").contains("failed task stays unchanged"))
        assertTrue(en.getValue("save_alternative_unknown").contains("Do not submit it again"))
        assertTrue(zh.getValue("save_alternative_unknown").contains("可能已创建"))
        assertTrue(en.getValue("save_alternative_fresh_source").contains("no Stream or recording capability is assumed"))
    }
    @Test fun currentCompositionHasBothFeedbackEntrypointsAndReadyCopyInExistingDialog() {
        assertTrue(source("SaveTaskScreen").contains("SaveMethodAlternativeFeedback(runtime)"))
        assertTrue(source("SaveFailureGuidanceUi").contains("SaveMethodAlternativeFeedback(runtime)"))
        assertTrue(source("SaveTaskScreen").contains("if (runtime.alternativePrepared) EsvText(stringResource(R.string.save_alternative_ready)"))
        val build = File("build.gradle.kts").readText()
        for (required in listOf("SaveAlternativeSelection.kt", "SaveMethodAlternativeUi.kt", "save_method_alternative.xml")) assertTrue(build.contains(required))
    }
}
