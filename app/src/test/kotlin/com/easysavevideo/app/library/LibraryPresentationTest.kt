package com.easysavevideo.app.library

import com.easysavevideo.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LibraryPresentationTest {
    @Test fun boundedTextPreservesWholeSupplementaryCharactersAndUtf8Budget() {
        assertEquals("a", boundedLibraryText("a😀b", 4))
        assertEquals("a😀", boundedLibraryText("a😀b", 5))
        assertEquals("中文", boundedLibraryText("中文abc", 6))
        assertEquals("", boundedLibraryText("😀", 3))
        assertEquals("", boundedLibraryText("anything", 0))
        assertTrue(boundedLibraryText("😀".repeat(1_000), 512).toByteArray().size <= 512)
    }

    @Test fun invalidNamesAreRejectedWithoutSilentPathSanitizing() {
        listOf("", " ", ".", "..", "a/b", "a\\b", "a\u0000b", "a\nb", "\uD800", "\uDC00", " name", "name ", "中".repeat(86)).forEach {
            assertNotNull(libraryNameError(it))
        }
        assertNull(libraryNameError("电影 2026.mp4"))
        assertNull(libraryNameError("Cafe\u0301"))
        assertNull(libraryNameError("x".repeat(255)))
    }

    @Test fun metadataLabelsDoNotInventMissingDurationOrResolution() {
        assertEquals("时长未知", libraryDuration(null,"时长未知"))
        assertEquals("Duration unknown",libraryDuration(null,"Duration unknown"))
        assertEquals("00:00:00", libraryDuration(0,"Duration unknown"))
        assertEquals("01:02:03", libraryDuration(3_723_000,"Duration unknown"))
        assertEquals("0 B", libraryBytes(0))
        assertEquals("1.0 KiB", libraryBytes(1_024))
        assertEquals("2.00 GiB", libraryBytes(2L * 1_024 * 1_024 * 1_024))
        // Keep the locale-format assertion away from month boundaries in every time zone.
        val createdAt = java.time.Instant.parse("1970-01-15T12:00:00Z").toEpochMilli()
        val imported = MediaItem("one", 1, 1, null, "One", createdAtMillis = createdAt,
            dateKind = MediaDateKind.IMPORTED, sizeBytes = 0, durationMillis = null, width = null, height = null)
        assertEquals("分辨率未知", libraryResolution(imported,"分辨率未知"))
        assertEquals("Resolution unknown",libraryResolution(imported,"Resolution unknown"))
        assertTrue(libraryDate(imported,Locale.ENGLISH).contains("Jan"))
        assertFalse(libraryDate(imported,Locale.SIMPLIFIED_CHINESE).contains("Jan"))
        assertEquals(libraryDate(imported,Locale.ENGLISH),libraryDate(imported.copy(dateKind=MediaDateKind.SAVED),Locale.ENGLISH))
    }

    @Test fun allDomainErrorsHaveUserFacingCopyWithoutExceptionDetails() {
        LibraryError.entries.forEach { error ->
            val text = libraryErrorText(error)
            assertTrue(text.isNotBlank())
            assertFalse(text.contains("Exception"))
            assertFalse(text.contains("/data/"))
        }
    }
}
