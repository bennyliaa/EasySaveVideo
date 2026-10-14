package com.easysavevideo.save

import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import org.junit.Assert.*
import org.junit.Test

class SaveMethodAlternativesTest {
    @Test fun everyStateMethodAndErrorUsesAnExplicitTwoErrorAllowlist() {
        for (state in TaskState.entries) for (method in TaskMethod.entries) for (error in listOf(null) + SaveError.entries) {
            val identity = SaveFailedIdentity("failed", 7, state, method, error)
            val expected = if (state == TaskState.FAILED && method == TaskMethod.DIRECT &&
                error in setOf(SaveError.UNSUPPORTED_MEDIA, SaveError.METHOD_UNSUPPORTED))
                SaveAlternativeReview.NEEDS_FRESH_STREAM_SOURCE else SaveAlternativeReview.NOT_ELIGIBLE
            assertEquals("$state/$method/$error", expected, SaveMethodAlternatives.review(identity))
        }
    }

    @Test fun observedFailureCannotClaimStreamAvailableOrRecordingCapability() {
        assertEquals(setOf("NOT_ELIGIBLE", "NEEDS_FRESH_STREAM_SOURCE"), SaveAlternativeReview.entries.map { it.name }.toSet())
        assertEquals(SaveAlternativeReview.NOT_ELIGIBLE, SaveMethodAlternatives.review(
            SaveFailedIdentity("failed", 7, TaskState.FAILED, TaskMethod.STREAM, SaveError.UNSUPPORTED_MEDIA)))
        assertEquals(SaveAlternativeReview.NOT_ELIGIBLE, SaveMethodAlternatives.review(
            SaveFailedIdentity("failed", 7, TaskState.FAILED, TaskMethod.RECORD, SaveError.UNSUPPORTED_MEDIA)))
    }
}
