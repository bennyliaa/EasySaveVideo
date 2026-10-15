package com.easysavevideo.app.save

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Production source contracts complement real registry/Core fixtures, never Android acceptance. */
class PrivateNetworkRuntimeWiringContractTest {
    private fun source(path: String) = File("src/main/kotlin/com/easysavevideo/app/$path").readText()
    private val runtime get() = source("save/AndroidSaveRuntime.kt")
    private val registry get() = source("save/PrivateNetworkRuntimeSources.kt")
    private val hls get() = source("save/stream/AndroidHlsStreamPort.kt")
    private fun ordered(text: String, vararg parts: String) {
        var after = 0
        for (part in parts) {
            val at = text.indexOf(part, after)
            assertTrue("Missing/out of order: $part", at >= 0); after = at + part.length
        }
    }

    @Test fun newDirectAndHlsCaptureOnlyTheExactHostPartitionAndMintPrivateSourceBeforeQueueing() {
        val capture = runtime.substringAfter("private fun captureNetworkSource(").substringBefore("/** Legacy ordinary")
        ordered(capture, "requireMain()", "browser.captureSaveRequestContext(captured, url.value)",
            "privateSources.register(context, url, claim, stopPreparation)", "entry?.issueLease(claim, context)")
        assertFalse(capture.contains("CookieManager")); assertFalse(capture.contains("selectedView"))
        val direct = runtime.substringAfter("private fun continueDirectAdmission(").substringBefore("private fun prepareStream(")
        ordered(direct, "captureNetworkSource(candidate.url, captured, browser, claim)", "it.privateSource?.bindTask(id, context.operationId)",
            "sourceIdentity = capturedSource.privateSource?.seed?.sourceIdentity?.encoded", "command(mediaWork = true", "coordinator.submit(request, context, claim)")
        val prepare = runtime.substringAfter("private fun prepareStream(").substringBefore("fun chooseHlsVariant(")
        ordered(prepare, "val cancellation = CancellationSource()", "captureNetworkSource(candidate.url, captured, browser, execution, cancellation::cancel)",
            "sourceIdentity = privateSource?.seed?.sourceIdentity?.encoded", "worker.execute", "stream.prepare(source, operation)",
            "privateSource?.attachPreparation(prepared)", "coordinator.prepareStreamAlternative(")
        assertFalse(direct.contains("leaseFor(")); assertFalse(prepare.contains("leaseFor("))
    }

    @Test fun hlsDestinationBindsSamePreparationSeedBeforeExactVariantSelectionAndKeepsExistingReselectPolicy() {
        val choose = runtime.substringAfter("private fun chooseHlsVariantSelected(").substringBefore("fun cancel(taskId:")
        ordered(choose, "val id = admission.creationId", "privateSources.forClaim(execution)",
            "sourceOwner.seed !== prepared.source.privateSource", "sourceOwner.ownsPreparation(prepared)",
            "sourceOwner?.bindTask(id, operation.operationId)", "stream.select(prepared, identity, binding)",
            "coordinator.confirmStreamAlternative(")
        val select = hls.substringAfter("fun select(").substringBefore("fun operation(")
        assertTrue(select.contains("binding.isFrom(seed)")); assertTrue(select.contains("!binding.isLive()"))
        assertTrue(select.contains("value.source.copy(variantId = selected.identity, privateSource = binding"))
        ordered(select, "value.privateSelection?.attach(resource)", "value.selected = selected", "return resource")
        assertTrue(hls.contains("it.privateSelection?.matches(resource)"))
        assertTrue(hls.contains("value.privateSelection.requireTransfer(resource, artifact, context, clock.nowMillis())"))
        assertTrue(runtime.contains("record.request.method == TaskMethod.STREAM && record.transfer == null"))
        assertTrue(runtime.contains("STREAM_RESELECT_REQUIRED"))
    }

    @Test fun corePositiveDetachDropsOnlyExactReleasedHlsSessionAndNeverCancelsOutputContext() {
        assertTrue(runtime.contains("privateSourceDetachmentObserver = PrivateSourceDetachmentObserver(privateSources::sourceDetached)"))
        assertTrue(runtime.contains("clearReleasedPreparation = { stream.detachReleased(it) }"))
        assertTrue(runtime.contains("signalSourceExit = { taskId, binding -> coordinator.signalPrivateSourceExit(taskId, binding); Unit }"))
        val detach = hls.substringAfter("internal fun detachReleased(").substringBefore("fun cancelPreparation()")
        for (forbidden in listOf("cancel()", "discard(", "await", "storage.", "engine.remux", "leases::revoke")) assertFalse(forbidden, detach.contains(forbidden))
        assertTrue(detach.contains("value.fetcher.isTransferActive")); assertTrue(detach.contains("sessions.clear(value)"))
        val clear = registry.substringAfter("private fun retireSourceReferences()").substringBefore("override fun toString()")
        ordered(clear, "seed.revoke()", "refs.forEach(leases::revoke)", "resumeAuthority?.retire()", "clearReleasedPreparation(it)", "source = null")
        assertFalse(clear.contains("cancel()")); assertFalse(clear.contains("complete()"))
    }

    @Test fun retirementIsActualClaimCallbackAndOnlyExactLiveWaitingDirectSourceCanStayDormant() {
        val actual = runtime.substringAfter("private val executionRetirement =").substringBefore("private val refreshing")
        ordered(actual, "release = executionAdmission::release", "retired = { claim ->", "privateSources.executionRetired(claim)")
        for (part in listOf("TaskState.WAITING_USER", "it.method == TaskMethod.DIRECT", "coordinator.sourceForTask(binding.taskId)?.privateSource === binding")) assertTrue(part, actual.contains(part))
        val retired = registry.substringAfter("fun executionRetired(").substringBefore("/** Cancelling/expiring")
        ordered(retired, "claims[claim] ?: return", "entry.revokeClaimCredentials(claim)",
            "if (!dormant) entry.owner.requestSourceRetirement()", "entry.owner.executionRetired(claim", "claims.remove(claim, entry)")
        val work = runtime.substringAfter("private fun runSaveExecution(").substringBefore("/** Scheduling deferral")
        assertTrue(work.contains("privateSources.forClaim(claim) == null) stream.discardIdle()"))
        assertFalse(work.contains("privateSources.executionRetired("))
    }

    @Test fun privateResumeUsesRetainedSourceCapabilityAndNewClaimBeforeAnyFreshLease() {
        val resumed = runtime.substringAfter("private fun continueResumedAdmission(").substringBefore("private fun finishTaskCommand(")
        ordered(resumed, "val resumedOperation", "record.request.isPrivateNetwork && record.transfer == null",
            "privateSources.forTask(taskId)", "source?.privateSource !== entry.binding",
            "privateSources.resumeQueued(entry, claim, resumedOperation.operationId)", "entry.refreshForExplicitResume()",
            "entry.issueLease(claim, refreshed)", "command(mediaWork = true", "coordinator.resume(taskId, operation, claim, lease)")
        assertFalse(resumed.contains("PageSaveSelection(")); assertFalse(resumed.contains("expiresAtMillis ="))
        val ordinary = runtime.substringAfter("private fun leaseFor(").substringBefore("fun canSave(")
        ordered(ordinary, "if (browser.isPrivateMode) throw", "CookieManager.getInstance()")
        val lease = registry.substringAfter("fun issueLease(").substringBefore("fun attachPreparation(")
        for (part in listOf("claims[claim] !== this", "?.lease !== session", "context.resumeAuthority !== resumeAuthority",
            "RequestContextLiveAuthority { session.isLive() && seed.isLive() }")) assertTrue(part, lease.contains(part))
    }

    @Test fun explicitCancelAndExpiredDormantSourcesUseSourceOwnerStopWithoutForgedRetirement() {
        assertTrue(runtime.substringAfter("fun cancel(taskId:").substringBefore("fun resume(taskId:").contains("privateSources.retireTask(taskId)"))
        val publish = runtime.substringAfter("private fun publishRecords(scheduleExpiry:").substringBefore("private fun resourcesDraining()")
        assertTrue(publish.contains("privateSources.retireUnavailable")); assertTrue(publish.contains("coordinator.sourceForTask(taskId)?.privateSource !== binding"))
        val unavailable = registry.substringAfter("fun retireTask(")
        assertTrue(unavailable.contains("owner?.requestSourceRetirement()")); assertFalse(unavailable.contains("executionRetired("))
    }
}
