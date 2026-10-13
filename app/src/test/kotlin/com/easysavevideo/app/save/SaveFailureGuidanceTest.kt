package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.SaveError
import org.junit.Assert.*
import org.junit.Test

class SaveFailureGuidanceTest {
    private val expected = mapOf(
        SaveFailureAdvice.CANCELLED to setOf(SaveError.CANCELLED),
        SaveFailureAdvice.PROTECTED to setOf(SaveError.PROTECTED_MEDIA, SaveError.POLICY_BLOCKED),
        SaveFailureAdvice.CONNECTION_BLOCKED to setOf(SaveError.TLS_REJECTED),
        SaveFailureAdvice.STORAGE to setOf(SaveError.STORAGE_FULL, SaveError.STORAGE_FAILURE, SaveError.JOURNAL_UNAVAILABLE),
        SaveFailureAdvice.RECOVERY to setOf(SaveError.COMMIT_MISMATCH, SaveError.RECOVERY_REQUIRED, SaveError.VERIFICATION_UNAVAILABLE),
        SaveFailureAdvice.RELEASE_PENDING to setOf(SaveError.RESOURCE_RELEASE_UNCONFIRMED),
        SaveFailureAdvice.BUSY to setOf(SaveError.BUSY),
        SaveFailureAdvice.AUTHENTICATION to setOf(SaveError.AUTHENTICATION_REQUIRED, SaveError.HTTP_REJECTED),
        SaveFailureAdvice.SOURCE_CHANGED to setOf(SaveError.EXPIRED_RESOURCE, SaveError.HTTP_NOT_FOUND,
            SaveError.INVALID_RANGE, SaveError.RESOURCE_CHANGED, SaveError.SOURCE_RESELECTION_REQUIRED),
        SaveFailureAdvice.NETWORK to setOf(SaveError.WIFI_REQUIRED, SaveError.NETWORK_UNAVAILABLE, SaveError.NETWORK_TIMEOUT, SaveError.HTTP_FAILURE,
            SaveError.RETRY_EXHAUSTED, SaveError.DEADLINE_EXCEEDED, SaveError.INCOMPLETE_TRANSFER),
        SaveFailureAdvice.RATE_LIMITED to setOf(SaveError.RATE_LIMITED),
        SaveFailureAdvice.REASSESS_METHOD to setOf(SaveError.METHOD_UNSUPPORTED, SaveError.UNSUPPORTED_MEDIA),
        SaveFailureAdvice.INVALID_OUTPUT to setOf(SaveError.INVALID_MEDIA),
        SaveFailureAdvice.BUDGET to setOf(SaveError.BYTE_LIMIT_EXCEEDED),
        SaveFailureAdvice.UNKNOWN to setOf(SaveError.INVALID_REQUEST, SaveError.UNKNOWN_TECHNICAL_ERROR),
        SaveFailureAdvice.RECORD_CONSENT to setOf(SaveError.CONSENT_DENIED),
        SaveFailureAdvice.RECORD_AUDIO to setOf(SaveError.AUDIO_PERMISSION_DENIED, SaveError.AUDIO_FAILED),
        SaveFailureAdvice.RECORD_SETUP to setOf(SaveError.CAPTURE_UNAVAILABLE, SaveError.FGS_START_REJECTED, SaveError.PROFILE_UNSUPPORTED),
        SaveFailureAdvice.RECORD_OUTPUT to setOf(SaveError.CAPTURE_EMPTY, SaveError.CAPTURE_TOO_SHORT, SaveError.KEYFRAME_TIMEOUT,
            SaveError.TIMESTAMP_INVALID, SaveError.ENCODER_FAILED, SaveError.FINALIZATION_FAILED),
    )
    private fun identity(error: SaveError? = SaveError.EXPIRED_RESOURCE, method: TaskMethod = TaskMethod.DIRECT,
        state: TaskState = TaskState.FAILED) = FailedTaskIdentity("failed-task", 4, state, method, error)

    @Test fun everyErrorIsClassifiedExactlyOnceForBothNetworkMethods() {
        assertEquals(SaveError.entries.toSet(), expected.values.flatten().toSet())
        assertEquals(SaveError.entries.size, expected.values.sumOf { it.size })
        for (method in listOf(TaskMethod.DIRECT, TaskMethod.STREAM)) expected.forEach { (advice, errors) ->
            errors.forEach { assertEquals("$method $it", advice, saveFailureAdvice(TaskState.FAILED, method, it)) }
        }
    }
    @Test fun everyStatusMethodErrorCombinationHasConservativeTerminalBehavior() {
        for (state in TaskState.entries) for (method in TaskMethod.entries) for (error in SaveError.entries + listOf(null)) {
            val advice = saveFailureAdvice(state, method, error)
            when (state) {
                TaskState.CANCELLED -> assertEquals(SaveFailureAdvice.CANCELLED, advice)
                TaskState.FAILED -> { assertNotNull(advice); if (method !in setOf(TaskMethod.DIRECT, TaskMethod.STREAM)) assertFalse(advice!!.canReturnToBrowser) }
                else -> assertNull(advice)
            }
        }
    }
    @Test fun cancelledProtectedStorageAndUnreleasedResourcesNeverChangeMethods() {
        val blocked = setOf(SaveError.CANCELLED, SaveError.PROTECTED_MEDIA, SaveError.POLICY_BLOCKED, SaveError.TLS_REJECTED,
            SaveError.STORAGE_FULL, SaveError.STORAGE_FAILURE, SaveError.JOURNAL_UNAVAILABLE, SaveError.RESOURCE_RELEASE_UNCONFIRMED,
            SaveError.COMMIT_MISMATCH, SaveError.RECOVERY_REQUIRED, SaveError.VERIFICATION_UNAVAILABLE)
        for (method in TaskMethod.entries) for (error in blocked) {
            assertFalse(saveFailureAdvice(TaskState.FAILED, method, error)!!.canReturnToBrowser)
            val task = identity(error, method)
            assertEquals(FailureNavigationResult.NOT_ALLOWED, FailureBrowserCommand(task).accept(task, true))
        }
    }
    @Test fun authenticationAndRefusalRequireNormalAccessBeforeFreshDetection() {
        for (error in setOf(SaveError.AUTHENTICATION_REQUIRED, SaveError.HTTP_REJECTED)) {
            assertEquals(SaveFailureAdvice.AUTHENTICATION, saveFailureAdvice(TaskState.FAILED, TaskMethod.STREAM, error))
        }
    }
    @Test fun recordingDenialNeverOffersNetworkOrAutomaticRecordingAction() {
        for (error in SaveError.entries) assertFalse(saveFailureAdvice(TaskState.FAILED, TaskMethod.RECORD, error)!!.canReturnToBrowser)
        assertEquals(SaveFailureAdvice.RECORD_CONSENT, saveFailureAdvice(TaskState.FAILED, TaskMethod.RECORD, SaveError.CONSENT_DENIED))
        assertEquals(SaveFailureAdvice.RECORD_AUDIO, saveFailureAdvice(TaskState.FAILED, TaskMethod.RECORD, SaveError.AUDIO_PERMISSION_DENIED))
    }
    @Test fun actionWhitelistIsSmallAndExhaustive() {
        assertEquals(setOf(SaveFailureAdvice.AUTHENTICATION, SaveFailureAdvice.SOURCE_CHANGED, SaveFailureAdvice.NETWORK,
            SaveFailureAdvice.RATE_LIMITED, SaveFailureAdvice.REASSESS_METHOD, SaveFailureAdvice.INVALID_OUTPUT),
            SaveFailureAdvice.entries.filter { it.canReturnToBrowser }.toSet())
    }
    @Test fun commandAcceptsOnlyOnceWithoutChangingOriginalIdentity() {
        val task = identity(); val original = task.copy(); val command = FailureBrowserCommand(task)
        assertEquals(FailureNavigationResult.ACCEPTED, command.accept(task.copy(), true))
        repeat(10) { assertEquals(FailureNavigationResult.ALREADY_USED, command.accept(task, true)) }
        assertEquals(original, task)
    }
    @Test fun changedRevisionStateMethodErrorOrMissingTaskRejectsStaleCommand() {
        val task = identity()
        for (current in listOf(null, task.copy(taskId = "other"), task.copy(revision = 5), task.copy(state = TaskState.CANCELLED),
            task.copy(method = TaskMethod.STREAM), task.copy(error = SaveError.PROTECTED_MEDIA))) {
            assertEquals(FailureNavigationResult.STALE_TASK, FailureBrowserCommand(task).accept(current, true))
        }
    }
    @Test fun everyDisallowedActionRefusesEvenWhenRuntimeIsAvailable() {
        for (state in TaskState.entries) for (method in TaskMethod.entries) for (error in SaveError.entries + listOf(null)) {
            val task = identity(error, method, state)
            val permitted = saveFailureAdvice(state, method, error)?.canReturnToBrowser == true
            assertEquals(if (permitted) FailureNavigationResult.ACCEPTED else FailureNavigationResult.NOT_ALLOWED,
                FailureBrowserCommand(task).accept(task, true))
        }
    }
    @Test fun temporaryUnavailabilityDoesNotConsumeCommandOrStartAnything() {
        val task = identity(); val command = FailureBrowserCommand(task)
        repeat(3) { assertEquals(FailureNavigationResult.UNAVAILABLE, command.accept(task, false)) }
        assertEquals(FailureNavigationResult.ACCEPTED, command.accept(task, true))
    }
    @Test fun absentErrorDoesNotGuessAnAlternative() {
        assertEquals(SaveFailureAdvice.UNKNOWN, saveFailureAdvice(TaskState.FAILED, TaskMethod.DIRECT, null))
        val task = identity(null); assertEquals(FailureNavigationResult.NOT_ALLOWED, FailureBrowserCommand(task).accept(task, true))
    }
}
