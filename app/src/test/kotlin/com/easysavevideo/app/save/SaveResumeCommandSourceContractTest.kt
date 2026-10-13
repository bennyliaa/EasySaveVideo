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

    @Test fun realResumeClaimsProductionGateBeforeCredentialsAndControlQueue() {
        val resume = section("fun resume(taskId:", "private fun finishTaskCommand(")
        ordered(resume, "requireMain()", "records.singleOrNull", "record.task.state != TaskState.WAITING_USER",
            "taskCommands.tryResume(record.task) ?: return", "pendingCommands = taskCommands.pendingTaskIds()",
            "leaseFor(it.url, browser)", "command(mediaWork = true")
        assertTrue(runtime.contains("private val taskCommands = SaveTaskCommandGate()"))
        assertFalse(resume.contains("pendingCommands = pendingCommands +"))
    }

    @Test fun controlLaneChecksCurrentWaitingSnapshotAndReservesExistingSingleLaneBeforeResume() {
        val resume = section("fun resume(taskId:", "private fun finishTaskCommand(")
        ordered(resume, "val current = coordinator.record(taskId)",
            "taskCommands.canResume(attempt, current.task)", "canResumeFinalized(current)", "!resourcesDraining()",
            "reserveExecution()", "val execution = SaveResumeExecutionReservation { setExecuting(false) }",
            ".copy(cancelToken = attempt.cancelToken)", "coordinator.resume(taskId, operation, lease)",
            "attempt.ownsResumed(resumed, operation)", "launchResumedExecution(requireNotNull(resumed), operation, attempt, lease, execution)")
        assertTrue(runtime.contains("private fun reserveExecution(): Boolean = executing.compareAndSet(false, true)"))
        assertTrue(runtime.contains("private val worker = executor(\"esv-save-transfer\", 1)"))
        assertFalse(resume.contains("resumed?.task?.state == TaskState.RUNNING"))
    }

    @Test fun explicitCancelSupersedesResumeAndSignalsBeforeQueueWithoutPendingUiGate() {
        val cancel = section("fun cancel(taskId:", "fun resume(taskId:")
        ordered(cancel, "requireMain()", "taskCommands.tryCancel(taskId) ?: return",
            "coordinator.signalUserCancel(taskId)", "command(mediaWork = true", "coordinator.cancel(taskId)")
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
        assertTrue(resume.contains("catch (_: Exception) {\n            finishTaskCommand(attempt)"))
        assertTrue(resume.contains("onRejected = { finishTaskCommand(attempt); lease?.let(leases::revoke) }"))
        assertTrue(resume.contains("if (!launched) { reservation?.release(); lease?.let(leases::revoke) }"))
        assertTrue(resume.contains("main.post { finishTaskCommand(attempt) }"))
        val finish = section("private fun finishTaskCommand(", "private fun launchExecution(")
        assertTrue(finish.contains("requireMain()"))
        assertTrue(finish.contains("if (taskCommands.release(attempt)) pendingCommands = taskCommands.pendingTaskIds()"))
        assertFalse(runtime.contains("pendingCommands = pendingCommands - taskId"))
    }

    @Test fun launchReleaseCapabilityCoversAllExistingReleaseSitesAndWorkerFinally() {
        val launch = section("private fun launchExecution(", "/** Scheduling deferral")
        assertTrue(launch.contains("releaseExecution: () -> Unit = { setExecuting(false) }"))
        assertEquals(1, Regex("setExecuting\\(false\\)").findAll(launch).count())
        assertEquals(4, Regex("releaseExecution\\(\\)").findAll(launch).count())
        assertTrue(launch.contains("finally { releaseExecution(); command { publishRecords() } }"))
        assertTrue(launch.contains("releaseExecution(); lease?.let(leases::revoke); deferUnstartedExecution(taskId)"))
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
