package com.easysavevideo.app.editor

import com.easysavevideo.app.ui.RuntimeMessageCopy
import com.easysavevideo.app.ui.RuntimeMessageText
import com.easysavevideo.app.ui.runtimeMessageFixture
import com.easysavevideo.library.edit.EditError
import com.easysavevideo.library.edit.EditFailure
import org.junit.Assert.*
import org.junit.Test

class EditorErrorCopyTest {
    private val expected = mapOf(
        EditError.UNSUPPORTED_PROFILE to RuntimeMessageCopy.EDITOR_ERROR_UNSUPPORTED_PROFILE,
        EditError.INCOMPATIBLE_SEGMENTS to RuntimeMessageCopy.EDITOR_ERROR_INCOMPATIBLE_SEGMENTS,
        EditError.STALE_SOURCE to RuntimeMessageCopy.EDITOR_ERROR_SOURCE_UNAVAILABLE,
        EditError.SOURCE_MISSING to RuntimeMessageCopy.EDITOR_ERROR_SOURCE_UNAVAILABLE,
        EditError.INSUFFICIENT_SPACE to RuntimeMessageCopy.EDITOR_ERROR_INSUFFICIENT_SPACE,
        EditError.CANCELLED to RuntimeMessageCopy.EDITOR_ERROR_CANCELLED,
        EditError.RELEASE_UNCONFIRMED to RuntimeMessageCopy.EDITOR_ERROR_RELEASE_UNCONFIRMED,
        EditError.TIMEOUT to RuntimeMessageCopy.EDITOR_ERROR_TIMEOUT,
        EditError.VERIFICATION_FAILED to RuntimeMessageCopy.EDITOR_ERROR_VERIFICATION_FAILED,
        EditError.SOURCE_LIMIT to RuntimeMessageCopy.EDITOR_ERROR_LIMIT,
        EditError.DURATION_LIMIT to RuntimeMessageCopy.EDITOR_ERROR_LIMIT,
        EditError.PIXEL_LIMIT to RuntimeMessageCopy.EDITOR_ERROR_LIMIT,
        EditError.INVALID_REQUEST to RuntimeMessageCopy.EDITOR_ERROR_INCOMPLETE,
        EditError.INVALID_CROP to RuntimeMessageCopy.EDITOR_ERROR_INCOMPLETE,
        EditError.CAPACITY to RuntimeMessageCopy.EDITOR_ERROR_INCOMPLETE,
        EditError.INVALID_STATE to RuntimeMessageCopy.EDITOR_ERROR_INCOMPLETE,
        EditError.EXECUTION_FAILED to RuntimeMessageCopy.EDITOR_ERROR_INCOMPLETE,
        EditError.PUBLICATION_UNCERTAIN to RuntimeMessageCopy.EDITOR_ERROR_INCOMPLETE,
    )

    @Test fun allErrorCodesKeepTheirOriginalDisplayClassificationWithoutMutation() {
        assertEquals(EditError.entries.toSet(), expected.keys)
        for ((code, key) in expected) {
            val failure = EditFailure(code)
            assertEquals(key, editorErrorCopy(failure))
            for (folder in listOf("values", "values-zh-rCN")) {
                val text = runtimeMessageFixture(folder)
                assertEquals(text.get(key), errorText(failure, text))
                assertEquals(code, failure.code)
            }
        }
    }

    @Test fun unknownFailureUsesGenericCopyAndNeverReadsExceptionText() {
        val failure = object : RuntimeException() {
            override val message: String get() = error("Exception messages are not display data")
            override fun toString(): String = error("Exception details are not display data")
        }
        assertEquals(RuntimeMessageCopy.EDITOR_ERROR_INCOMPLETE, editorErrorCopy(failure))
        for (folder in listOf("values", "values-zh-rCN")) {
            val text = runtimeMessageFixture(folder)
            assertEquals(text.get(RuntimeMessageCopy.EDITOR_ERROR_INCOMPLETE), errorText(failure, text))
        }
    }

    @Test fun errorTextCallsCurrentRendererExactlyOnceWithNoDiagnosticArguments() {
        var selected = "en"
        val seen = mutableListOf<RuntimeMessageCopy>()
        val text = RuntimeMessageText { key, arguments ->
            assertTrue(arguments.isEmpty())
            seen += key
            "$selected:${key.name}"
        }
        val failure = EditFailure(EditError.CANCELLED)
        assertEquals("en:EDITOR_ERROR_CANCELLED", errorText(failure, text))
        selected = "zh"
        assertEquals("zh:EDITOR_ERROR_CANCELLED", errorText(failure, text))
        assertEquals(listOf(RuntimeMessageCopy.EDITOR_ERROR_CANCELLED, RuntimeMessageCopy.EDITOR_ERROR_CANCELLED), seen)
        assertEquals(EditError.CANCELLED, failure.code)
    }
}
