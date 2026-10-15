package com.easysavevideo.app.save

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Wiring inspection only; real admission, overlap and retirement are separately production-model tested. */
class DirectConcurrencyRuntimeContractTest {
    private val runtime get()=File("src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt").readText()
    @Test fun fixedTwoTransportsAndOneSerialNativeVerifierShareTheCoreAdmission() {
        val s=runtime
        assertTrue(s.contains("transport = FixedDirectTransferPool("))
        assertEquals(2,Regex("SystemHttpsDirectTransfer\\(staging, leases, networkPolicy = networkPolicy, movAdmissionEnabled = movAdmissionEnabled\\)").findAll(s).count())
        assertTrue(s.contains("SerialArtifactVerifier(actualVerifier, clock)"))
        assertTrue(s.contains("admission = executionAdmission,"))
        assertTrue(s.contains("private val movAdmissionEnabled = false"))
        assertTrue(s.contains("controlledDirect = ControlledDirectPipeline(directLocalGate, direct.storage, movAdmissionEnabled, conversionExecutionEnabled = false)"))
        assertTrue(s.contains("ThreadPoolExecutor(2, 2"))
        assertFalse(s.contains("private val executing = AtomicBoolean"))
    }
    @Test fun directDiskProbePrecedesLeaseAndDurableTaskCreationOffMain() {
        val s=runtime.substringAfter("private fun chooseDirect(").substringBefore("private fun prepareStream(")
        assertTrue(s.indexOf("command(mediaWork = true)")<s.indexOf("executionAdmission.tryAcquireDirect()"))
        assertTrue(s.indexOf("executionAdmission.tryAcquireDirect()")<s.indexOf("captureNetworkSource(candidate.url, captured, browser, claim)"))
        assertTrue(s.indexOf("captureNetworkSource(candidate.url, captured, browser, claim)")<s.indexOf("coordinator.submit(request, context, claim)"))
        assertTrue(s.contains("executionForegroundGeneration != generation"))
        assertTrue(s.contains("if (!result.existing)"))
    }
    @Test fun resumeKeepsOriginalCommandAndAcceptanceRepairOwner() {
        val s=runtime.substringAfter("fun resume(taskId:").substringBefore("private fun finishTaskCommand")
        assertTrue(s.indexOf("executionAdmission.tryAcquireDirect()")<s.indexOf("leaseFor(it.url, browser)"))
        assertTrue(s.contains("taskCommands.canResume(attempt, current.task)"))
        assertTrue(s.contains("coordinator.resume(taskId, operation, claim, lease)"))
        assertTrue(s.contains("SaveResumeExecutionReservation { releaseExecution(claim) }"))
        assertTrue(runtime.contains("resumeHandoff.launch(record, operation, attempt,"))
    }
    @Test fun newWorkerUsesOneShotHandoffAndExactClaimUntilFinally() {
        val s=runtime.substringAfter("private fun launchExecution(").substringBefore("/** Only the exact newly resumed operation")
        assertTrue(s.contains("SaveWorkerHandoff().launch({ worker.execute(it) })"))
        assertTrue(s.contains("runSaveExecution(taskId, lease, claim) { releaseExecution(claim) }"))
        val body=runtime.substringAfter("private fun runSaveExecution(").substringBefore("/** Scheduling deferral")
        assertTrue(body.contains("coordinator.execute(taskId, claim)"))
        assertTrue(body.contains("finally { releaseExecution(); command { publishRecords() } }"))
        assertTrue(body.contains("!executionAdmission.isDirect(claim) && ::stream.isInitialized"))
    }
    @Test fun completedHlsPreparationRetainsItsOwnerUntilSelectionOrDiscard() {
        val s=runtime.substringAfter("private fun prepareStream(").substringBefore("fun cancel(taskId:")
        assertTrue(s.contains("preparationExecution = execution"))
        assertTrue(s.contains("preparationExecution !== execution || hlsPreparation == null"))
        assertTrue(s.contains("val execution = preparationExecution?.takeIf(executionAdmission::owns)"))
        assertTrue(s.contains("coordinator.submit(request, operation, execution, prepared.bytesTransferred)"))
        assertTrue(s.contains("val generation = executionForegroundGeneration"))
        assertTrue(s.contains("!foreground || generation != executionForegroundGeneration || !canSave(TaskMethod.STREAM)"))
        assertTrue(s.contains("launchExecution(result.record.task.taskId, resource.requestContextRef, execution)"))
    }
    @Test fun exactNativeBatchIsClaimedAndReleasedThroughEvidenceRetirement() {
        val s=runtime
        assertTrue(s.contains("OrdinaryMediaNativeResources.claim(owner)"))
        assertTrue(s.contains("directBatchOwner = owner"))
        assertTrue(s.contains("directBatchOwner?.let(OrdinaryMediaNativeResources::released)"))
        assertTrue(s.contains("OrdinaryMediaNativeResources.isOccupied && directBatchOwner == null"))
        assertTrue(s.contains("executionRetirement.request(claim)"))
        assertTrue(s.contains("exclusiveOwner.compareAndSet(claim, null)"))
        assertTrue(s.contains("exclusiveOwner.set(claim)"))
        assertFalse(s.contains("if (exclusiveExecution === claim) exclusiveExecution = null"))
        assertTrue(s.contains("executionRetirement.retry()"))
        assertFalse(s.contains("executionAdmission.markRecoveryRequired()"))
    }
    @Test fun freeSnapshotPreservesExistingProtocolReserveWithoutCreatingArtifacts() {
        val s=File("src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt").readText()
        assertTrue(s.contains("fun availableBytesForSaveAdmission(): Long = fileCall { protocol.availableBytes() }"))
        assertTrue(runtime.contains("storage.availableBytesForSaveAdmission()"))
        val protocol=File("src/main/kotlin/com/easysavevideo/app/save/storage/PrivateCommitProtocol.kt").readText()
        assertTrue(protocol.contains("JOURNAL_RESERVE_BYTES"))
    }
    @Test fun operationBudgetRemainsPerTaskOneAndRecoveryDoesNotQueueDownloads() {
        assertTrue(runtime.contains("resourceBudget = ResourceBudget(1, limits.maxTotalTransferBytes)"))
        assertEquals(1,Regex("coordinator.recover\\(").findAll(runtime).count())
        assertTrue(runtime.contains("coordinator.recover(operation(2_000))"))
        assertFalse(runtime.contains("setExecuting(false); lease"))
    }
}
