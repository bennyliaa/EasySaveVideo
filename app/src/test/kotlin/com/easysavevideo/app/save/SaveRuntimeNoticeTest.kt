package com.easysavevideo.app.save

import com.easysavevideo.save.SaveError
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

/** Pure payload/equality tests; no Android resource resolution or actual Compose execution. */
class SaveRuntimeNoticeTest {
    private val fixtures = SaveRuntimeNoticeFixtures

    @Test fun everyDomainErrorMapsToExactlyItsOriginalDetailedTextGroup() {
        assertEquals(45, SaveError.entries.size)
        assertEquals(35, SaveRuntimeFailureCode.entries.size)
        assertEquals(SaveRuntimeFailureCode.entries.toSet(), fixtures.errorGroups.keys)
        val errors = fixtures.errorGroups.values.flatten()
        assertEquals(45, errors.size)
        assertEquals(SaveError.entries.toSet(), errors.toSet())
        assertEquals(SaveRuntimeFailureCode.entries.toSet(), fixtures.failures.keys)
        for ((code, group) in fixtures.errorGroups) {
            for (error in group) {
                assertEquals(error.name, code, saveRuntimeFailureCode(error))
                assertEquals(error.name, SaveRuntimeNotice.Failure(code), saveRuntimeFailureNotice(error))
            }
        }
        assertEquals(35, SaveError.entries.map(::saveRuntimeFailureNotice).toSet().size)
    }

    @Test fun allOldSameTextErrorsRemainEqualAndDifferentTextsRemainDifferent() {
        val oldTextByError = fixtures.errorGroups.flatMap { (code, group) ->
            group.map { it to fixtures.failures.getValue(code).chinese }
        }.toMap()
        for (first in SaveError.entries) {
            for (second in SaveError.entries) {
                assertEquals("$first / $second", oldTextByError.getValue(first) == oldTextByError.getValue(second),
                    saveRuntimeFailureNotice(first) == saveRuntimeFailureNotice(second))
            }
        }
        assertEquals(saveRuntimeFailureNotice(SaveError.INVALID_REQUEST),
            saveRuntimeFailureNotice(SaveError.UNKNOWN_TECHNICAL_ERROR))
        assertEquals(SaveRuntimeFailureCode.INVALID_REQUEST, saveRuntimeFailureCode(SaveError.UNKNOWN_TECHNICAL_ERROR))
    }

    @Test fun fixedAndDetectionInventoriesAreExhaustiveAndStructurallyStable() {
        assertEquals(36, SaveRuntimeFixedNotice.entries.size)
        assertEquals(SaveRuntimeFixedNotice.entries.toSet(), fixtures.fixed.keys)
        assertEquals(36, fixtures.fixed.values.map { it.chinese }.toSet().size)
        assertEquals(6, PageSaveDetection.Reason.entries.size)
        assertEquals(PageSaveDetection.Reason.entries.toSet(), fixtures.detection.keys)
        for (kind in SaveRuntimeFixedNotice.entries) {
            assertEquals(SaveRuntimeNotice.Fixed(kind), SaveRuntimeNotice.Fixed(kind))
        }
        for (reason in PageSaveDetection.Reason.entries) {
            assertEquals(SaveRuntimeNotice.DetectionUnavailable(reason), SaveRuntimeNotice.DetectionUnavailable(reason))
        }
        // Each of these existing messages has two production emission sites using the same token.
        for (kind in listOf(SaveRuntimeFixedNotice.MEDIA_RESOURCES_EXITING,
            SaveRuntimeFixedNotice.RECORDING_PAUSE_RESUME_ENTITLEMENT_REQUIRED)) {
            assertEquals(SaveRuntimeNotice.Fixed(kind), SaveRuntimeNotice.Fixed(kind).copy())
        }
    }

    @Test fun onlyTheCapturedCapacityIntegerEntersTheVariableNotice() {
        for (maximum in listOf(1, 100, 1000)) {
            val notice = SaveRuntimeNotice.TaskCapacityReached(maximum)
            assertEquals(maximum, notice.maxUnfinishedTasks)
            assertEquals(notice, notice.copy())
            assertNotEquals(notice, notice.copy(maxUnfinishedTasks = maximum + 1))
            assertEquals("SaveRuntimeNotice.TaskCapacityReached([redacted])", notice.toString())
            assertFalse(notice.toString().contains(maximum.toString()))
        }
    }

    @Test fun payloadAndConstructorTypesCannotRetainUserTextOrRuntimeAuthority() {
        val expected = mapOf(
            SaveRuntimeNotice.Fixed::class.java to mapOf("kind" to SaveRuntimeFixedNotice::class.java),
            SaveRuntimeNotice.TaskCapacityReached::class.java to mapOf("maxUnfinishedTasks" to Int::class.javaPrimitiveType),
            SaveRuntimeNotice.DetectionUnavailable::class.java to mapOf("reason" to PageSaveDetection.Reason::class.java),
            SaveRuntimeNotice.Failure::class.java to mapOf("code" to SaveRuntimeFailureCode::class.java),
        )
        assertEquals(expected.keys, SaveRuntimeNotice::class.java.declaredClasses.toSet())
        for ((type, fields) in expected) {
            val actual = type.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            assertEquals(type.name, fields, actual.associate { it.name to it.type })
            assertTrue(type.name, actual.all { Modifier.isFinal(it.modifiers) })
            assertTrue(type.name, type.declaredConstructors.all { constructor ->
                constructor.parameterTypes.toList() == fields.values.toList()
            })
        }
    }

    @Test fun diagnosticStringsContainOnlySafeKindsAndRedactedCapacity() {
        for (kind in SaveRuntimeFixedNotice.entries) {
            assertEquals("SaveRuntimeNotice.Fixed($kind)", SaveRuntimeNotice.Fixed(kind).toString())
        }
        for (reason in PageSaveDetection.Reason.entries) {
            assertEquals("SaveRuntimeNotice.DetectionUnavailable($reason)",
                SaveRuntimeNotice.DetectionUnavailable(reason).toString())
        }
        for (error in SaveError.entries) {
            val notice = saveRuntimeFailureNotice(error)
            assertEquals("SaveRuntimeNotice.Failure(${notice.code})", notice.toString())
        }
        val strings = SaveRuntimeFixedNotice.entries.map { SaveRuntimeNotice.Fixed(it).toString() } +
            PageSaveDetection.Reason.entries.map { SaveRuntimeNotice.DetectionUnavailable(it).toString() } +
            SaveError.entries.map { saveRuntimeFailureNotice(it).toString() } +
            SaveRuntimeNotice.TaskCapacityReached(1000).toString()
        for (forbidden in listOf("https://", "content://", "cookie", "token=", "filename", "Throwable", "Exception")) {
            assertTrue(forbidden, strings.none { it.contains(forbidden, ignoreCase = true) })
        }
    }
}
