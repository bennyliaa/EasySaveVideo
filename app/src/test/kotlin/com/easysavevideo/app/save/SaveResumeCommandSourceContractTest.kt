package com.easysavevideo.app.save

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

/** Complements production gate tests and real-coordinator queue tests; not Android device evidence. */
class SaveResumeCommandSourceContractTest {
    private fun source(name: String) = String(Files.readAllBytes(
        Paths.get("src/main/kotlin/com/easysavevideo/app/save", name)), Charsets.UTF_8)
    private val runtime get() = source("AndroidSaveRuntime.kt")
    private fun section(start: String, end: String) = runtime.substringAfter(start).substringBefore(end)
    private fun ordered(text: String, vararg pieces: String) {
        var offset = 0
        pieces.forEach { piece ->
            val found = text.indexOf(piece, offset)
            assertTrue("Missing or out of order: $piece", found >= 0)
            offset = found + piece.length
        }
    }

    @Test fun realResumeClaimsProductionGateBeforeWorkerDiskAdmissionAndCredentials() {
        val resume = section("fun resume(taskId:", "private fun finishTaskCommand(")
        ordered(resume, "requireMain()", "records.singleOrNull", "record.task.state != TaskState.WAITING_USER",
            "taskCommands.tryResume(record.task) ?: return", "pendingCommands = taskCommands.pendingTaskIds()",
            "command(mediaWork = true", "executionAdmission.tryAcquireDirect()",
            "leaseFor(it.url, browser)", "command(mediaWork = true")
        assertTrue(runtime.contains("private val taskCommands = SaveTaskCommandGate()"))
        assertFalse(resume.contains("pendingCommands = pendingCommands +"))
    }

    @Test fun controlLaneReservesExactDirectOrExclusiveClaimBeforeResume() {
        val resume = section("fun resume(taskId:", "private fun finishTaskCommand(")
        ordered(resume, "executionAdmission.tryAcquireDirect()", "reserveExecution()",
            ".copy(cancelToken = attempt.cancelToken)", "leaseFor(it.url, browser)", "val execution = SaveResumeExecutionReservation { releaseExecution(claim) }",
            "val current = coordinator.record(taskId)", "taskCommands.canResume(attempt, current.task)",
            "canResumeFinalized(current)", "executionAdmission.owns(claim)", "!executionReleaseUnconfirmed()",
            "val operation = resumedOperation", "coordinator.resume(taskId, operation, claim, lease)",
            "attempt.ownsResumed(resumed, operation)", "launchResumedExecution(requireNotNull(resumed), operation, attempt, lease, execution, claim)")
        assertTrue(runtime.contains("val claim = executionAdmission.tryAcquireExclusive() ?: return false"))
        assertTrue(runtime.contains("ThreadPoolExecutor(2, 2"))
        assertTrue(resume.contains("generation == executionForegroundGeneration"))
        assertFalse(resume.contains("resumed?.task?.state == TaskState.RUNNING"))
    }

    @Test fun explicitCancelSupersedesResumeAndSignalsBeforeQueueWithoutPendingUiGate() {
        val cancel = section("fun cancel(taskId:", "fun resume(taskId:")
        ordered(cancel, "requireMain()", "coordinator.signalUserCancel(taskId)",
            "taskCommands.tryCancel(taskId) ?: return", "command(mediaWork = true", "coordinator.cancel(taskId)")
        assertFalse(cancel.contains("in pendingCommands"))
        assertTrue(cancel.contains("onRejected = { finishTaskCommand(attempt) }"))
        assertTrue(cancel.contains("finally { main.post { finishTaskCommand(attempt) } }"))
        val resume = section("fun resume(taskId:", "private fun finishTaskCommand(")
        ordered(resume, "attempt.ownsResumed(resumed, operation)",
            "attempt.cancelToken.isCancelled -> coordinator.cancel(taskId)",
            "launchResumedExecution(")
        assertTrue(source("SaveResumeHandoff.kt").contains("!foreground() || !commands.isCurrent(attempt) -> SaveWaitReason.BACKGROUND"))
    }

    @Test fun everyResumeFailureReleasesItsOwnPendingAndLeaseAndReservation() {
        val resume = section("fun resume(taskId:", "private fun finishTaskCommand(")
        assertTrue(resume.contains("catch (_: Exception) {\n            releaseExecution(claim); finishTaskCommand(attempt)"))
        assertTrue(resume.contains("execution.release(); finishTaskCommand(attempt); lease?.let(leases::revoke)"))
        assertTrue(resume.contains("if (!launched) { execution.release(); lease?.let(leases::revoke) }"))
        assertTrue(resume.contains("main.post { finishTaskCommand(attempt) }"))
        val finish = section("private fun finishTaskCommand(", "private fun launchExecution(")
        assertTrue(finish.contains("requireMain()"))
        assertTrue(finish.contains("if (taskCommands.release(attempt)) pendingCommands = taskCommands.pendingTaskIds()"))
        assertFalse(runtime.contains("pendingCommands = pendingCommands - taskId"))
    }

    @Test fun launchReleaseCapabilityCoversAllExistingReleaseSitesAndWorkerFinally() {
        val launch = section("private fun launchExecution(", "/** Scheduling deferral")
        assertTrue(launch.contains("claim: SaveExecutionAdmission.Claim"))
        assertFalse(launch.contains("setExecuting(false)"))
        assertTrue(launch.contains("runSaveExecution(taskId, lease, claim, evaluationBinding) { releaseExecution(claim) }"))
        assertTrue(launch.contains("SaveWorkerHandoff().launch({ worker.execute(it) })"))
        assertTrue(launch.contains("finally { releaseExecution(); command { publishRecords() } }"))
        assertTrue(launch.contains("execution.release(); lease?.let(leases::revoke)"))
        val gate = source("SaveTaskCommandGate.kt")
        assertTrue(gate.contains("released.compareAndSet(false, true)"))
        assertTrue(gate.contains("owners[attempt.taskId] !== attempt"))
        assertTrue(gate.contains("record.operationId == operation.operationId"))
    }

    @Test fun foregroundLossRetiresQueuedClicksBeforeExistingBackgroundSignalWithoutAutoResume() {
        val lifecycle = section("fun setForeground(value:", "internal fun authenticationBrowserAvailable")
        ordered(lifecycle, "if (value) { main.post(poll) }", "else {", "taskCommands.retireResumesForBackground()",
            "coordinator.signalBackgroundPauseAll()", "coordinator.pauseForBackground(it.task.taskId)")
        assertFalse(lifecycle.contains("coordinator.resume("))
        val retire = source("SaveTaskCommandGate.kt").substringAfter("fun retireResumesForBackground()")
            .substringBefore("/** A delayed completion")
        assertFalse(retire.contains("cancellation.cancel()"))
        assertFalse(retire.contains("owners.remove"))
    }

    @Test fun sourceContractsIncludeGateAndExistingTaskButtonsStillUseRuntimeCommands() {
        val inputs = String(Files.readAllBytes(Paths.get("build.gradle.kts")), Charsets.UTF_8)
            .substringAfter("val contractSourceInputs = files(").substringBefore("tasks.withType<Test>()")
        for (name in listOf("SaveTaskCommandGate.kt", "AndroidSaveRuntime.kt", "SaveTaskScreen.kt")) assertTrue(inputs.contains(name))
        val screen = source("SaveTaskScreen.kt")
        assertTrue(screen.contains("runtime.resume(record.task.taskId, browser)"))
        assertTrue(screen.contains("runtime.cancel(record.task.taskId)"))
        assertTrue(screen.contains("record.task.taskId !in runtime.pendingCommands"))
    }
}
