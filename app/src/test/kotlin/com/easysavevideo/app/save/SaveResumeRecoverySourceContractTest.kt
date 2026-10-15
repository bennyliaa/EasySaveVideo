package com.easysavevideo.app.save

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Production call-site contracts complement real-coordinator fixtures; no device/Compose claim. */
class SaveResumeRecoverySourceContractTest {
    private fun source(name: String) = File("src/main/kotlin/com/easysavevideo/app/save/$name.kt").readText()
    private val runtime get() = source("AndroidSaveRuntime")
    private val helper get() = source("SaveResumeHandoff")
    private fun ordered(text: String, vararg parts: String) {
        var offset = 0
        for (part in parts) {
            val found = text.indexOf(part, offset)
            assertTrue("Missing or out of order: $part", found >= 0)
            offset = found + part.length
        }
    }

    @Test fun actualResumeUsesProductionHandoffAndExactPreflightWithSharedWorker() {
        val resume = runtime.substringAfter("fun resume(taskId:").substringBefore("private fun finishTaskCommand")
        ordered(resume, "coordinator.resume(taskId, operation, claim, lease)", "attempt.ownsResumed(resumed, operation)",
            "launchResumedExecution(requireNotNull(resumed), operation, attempt, lease, execution, claim)")
        val handoff = runtime.substringAfter("private fun launchResumedExecution").substringBefore("private fun runSaveExecution")
        assertTrue(handoff.contains("resumeHandoff.launch(record, operation, attempt"))
        assertTrue(handoff.contains("submit = { job -> worker.execute(job) }"))
        assertTrue(handoff.contains("work = { runSaveExecution(record.task.taskId, lease, claim, execution::release) }"))
        assertTrue(handoff.contains("execution.release(); lease?.let(leases::revoke)"))
        ordered(helper, "val deferral = try", "coordinator.record(ticket.taskId)?.needsNetworkTransfer()", "submit(Runnable {", "admitted(ticket)", "work()", "admitted(ticket)")
        assertEquals(1, Regex("coordinator.execute\\(taskId, claim\\)").findAll(
            runtime.substringAfter("private fun runSaveExecution").substringBefore("/** Scheduling deferral")).count())
    }

    @Test fun acceptedWorkerFailuresDoNotEnterRecoveryAndArbitrarySubmissionErrorsAreNotRejection() {
        val work = runtime.substringAfter("private fun runSaveExecution").substringBefore("/** Scheduling deferral")
        assertFalse(work.contains("resumeHandoff"))
        assertFalse(work.contains("recoverUnadmittedResume"))
        val failure = helper.substringAfter("catch (failure: Exception)").substringBefore("/** One explicit")
        assertTrue(failure.contains("retire(ticket)"))
        assertFalse(failure.contains("repair(ticket)"))
        assertTrue(helper.contains("if (ticket.admission.get() == Admission.ACCEPTED) return true"))
    }

    @Test fun repairClickUsesControlQueueAndSameCommandGateWithoutMediaAdmissionOrCredentials() {
        val setup = runtime.substringAfter("private val resumeHandoff:").substringBefore("var pendingCommands")
        assertTrue(setup.contains("enqueue = { action -> controls.execute { action() } }"))
        assertTrue(setup.contains("pendingCommands = taskCommands.pendingTaskIds()"))
        assertTrue(setup.contains("publishRecords = { publishRecords(scheduleExpiry = false) }"))
        assertTrue(runtime.contains("if (scheduleExpiry && ::coordinator.isInitialized"))
        assertTrue(runtime.contains("if (scheduleExpiry) destinations?.refresh()"))
        val retry = helper.substringAfter("fun retry(ticket:").substringBefore("/** Memory-only")
        ordered(retry, "isPageCurrent()", "commands.tryRepair(ticket.taskId) ?: return false", "enqueue {", "commands.isCurrent(command)", "repair(ticket)")
        assertTrue(retry.contains("commands.release(command)"))
        for (forbidden in listOf("leaseFor(", "leases::", "reserveExecution", "worker.execute", "coordinator.resume", "coordinator.execute"))
            assertFalse(forbidden, retry.contains(forbidden))
        val repair = helper.substringAfter("private fun repair(ticket:").substringBefore("private fun owns")
        assertEquals(1, Regex("coordinator.recoverUnadmittedResume\\(").findAll(repair).count())
        assertFalse(repair.contains("while ("))
        assertFalse(repair.contains("tickets["))
    }

    @Test fun cancelSignalsImmediatelyAndRetiresBeforeQueueWhileLifecycleNeverRetriesCompensation() {
        val cancel = runtime.substringAfter("fun cancel(taskId:").substringBefore("fun resume(taskId:")
        ordered(cancel, "requireMain()", "coordinator.signalUserCancel(taskId)",
            "resumeHandoff.cancel(taskId)", "taskCommands.tryCancel(taskId)", "command(mediaWork = true")
        val retirement = helper.substringAfter("fun cancel(taskId:").substringBefore("private fun repair")
        ordered(retirement, "ticket.live.set(false)", "ticket.resumeAttempt.cancellation.cancel()", "tickets.remove(taskId, ticket)")
        for (section in listOf(
            runtime.substringAfter("fun setForeground(value:").substringBefore("internal fun authenticationBrowserAvailable"),
            runtime.substringAfter("private fun refresh()").substringBefore("private fun resourcesDraining()"),
        )) {
            assertFalse(section.contains("recoverUnadmittedResume"))
            assertFalse(section.contains("resumeHandoff.retry"))
            assertFalse(section.contains("resumeHandoff.launch"))
        }
    }

    @Test fun pendingRecoveryRemainsVisibleWithoutRecordsAndDisposedPageOrOldRuntimeCannotAdmit() {
        val screen = source("SaveTaskScreen")
        ordered(screen, "remember(runtime) { runtime.recoveryRetryAction { currentRecoveryAdmission(runtime) } }", "onDispose { recoveryAction.close() }")
        val fallback = screen.substringAfter("runtime.pendingSaveRecoveries.forEach").substringBefore("runtime.destinations")
        assertTrue(fallback.contains("recoveryAction.invoke(ticket)"))
        assertTrue(fallback.contains("recoveryAction.cancel(ticket.taskId, runtime::cancel)"))
        assertTrue(fallback.contains("ticket.taskId !in runtime.pendingCommands"))
        assertFalse(fallback.contains("runtime.records"))
        assertFalse(fallback.contains("activeTaskCount"))
        assertTrue(runtime.contains("isCurrent = { ready && foreground && instance === this && isPageCurrent() }"))
        assertTrue(helper.contains("@Volatile private var attached = true"))
        assertTrue(helper.contains("attached && isCurrent()"))
        assertTrue(helper.contains("isRuntimeCurrent() && isPageCurrent() && commands.isCurrent(command)"))
    }

    @Test fun actualHostRouteAndOverlayAreCheckedAtMainAdmissionWithoutControlThreadUiReads() {
        val activity = File("src/main/kotlin/com/easysavevideo/app/MainActivity.kt").readText()
        val admission = activity.substringAfter("recoveryIsCurrent = { expected ->").substringBefore("canOpenBrowserForAuthentication =")
        ordered(admission, "isForeground && hasFocusedWindow && activeDestination == EsvDestination.TASKS",
            "navController?.currentDestination?.route == EsvDestination.TASKS.route && saves === expected",
            "previewReceipt == null && libraryPreviewReference == null", "saves.segmentRecording?.previewActive != true",
            "saves.selection == null", "saves.destinations?.asking != true", "browser.jsDialog == null",
            "!isDestroyed && !isFinishing")
        val screen = source("SaveTaskScreen")
        assertTrue(screen.contains("recoveryIsCurrent: (AndroidSaveRuntime) -> Boolean = { false }"))
        assertTrue(screen.contains("val currentRecoveryAdmission by rememberUpdatedState(recoveryIsCurrent)"))
        val action = helper.substringAfter("internal class SaveRecoveryRetryAction")
        assertTrue(action.contains("if (current()) retry(ticket) { attached }"))
        assertTrue(action.contains("fun cancel(taskId: String, action: (String) -> Unit) { if (current()) action(taskId) }"))
        assertFalse(action.contains("retry(ticket, ::current)"))
        val fallback = screen.substringAfter("runtime.recoveryCancelTaskIds.forEach").substringBefore("runtime.destinations")
        assertTrue(fallback.contains("recoveryAction.cancel(taskId, runtime::cancel)"))
        assertFalse(fallback.contains("recoveryAction.invoke"))
        assertTrue(runtime.contains("if (coordinator.cancel(taskId)?.state?.isTerminal == true) resumeHandoff.cancellationConfirmed(taskId)"))
        assertTrue(runtime.contains("resumeHandoff.observeTerminal(next)"))
    }

    @Test fun pairedCurrentLocaleCopyExplainsSeparateResumeAndIsADeclaredBuildInput() {
        val required = setOf("save_recovery_failed", "save_recovery_retry", "save_recovery_cancel_pending")
        val strings = listOf("values", "values-zh-rCN").map { locale ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$locale/save_recovery.xml")).getElementsByTagName("string")
            (0 until nodes.length).associate { nodes.item(it).let { node ->
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            } }.also { assertEquals(required, it.keys) }
        }
        assertEquals("Retry recovery", strings[0].getValue("save_recovery_retry"))
        assertEquals("重试恢复", strings[1].getValue("save_recovery_retry"))
        assertTrue(strings[0].getValue("save_recovery_failed").contains("Resume or Reverify separately"))
        for (values in strings) assertTrue(values.getValue("save_recovery_failed").contains("%1\$s"))
        val screen = source("SaveTaskScreen")
        assertTrue(screen.contains("stringResource(R.string.save_recovery_retry)"))
        assertTrue(screen.contains("stringResource(R.string.save_recovery_failed, ticket.taskId.take(8))"))
        val inputs = File("build.gradle.kts").readText()
        assertTrue(inputs.contains("\"save_recovery.xml\""))
        assertTrue(inputs.contains("save/SaveResumeHandoff.kt"))
    }
}
