package com.easysavevideo.library

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LibraryNamesTest {
    @Test fun acceptsOpaqueIdsAndRejectsLocators() {
        listOf("id", "9_a-B", "a".repeat(128), "6040da79-55d2-4987-8d2e-9aeea003507b").forEach(LibraryIds::requireValid)
        listOf("", ".", "..", "a.b", "a/b", "a\\b", "a\u0000b", "a b", "é", "a".repeat(129), "file://abc")
            .forEach { fails(LibraryError.INVALID_ID) { LibraryIds.requireValid(it) } }
    }

    @Test fun canonicalNamesNormalizeToNfc() {
        assertEquals("Café", LibraryNames.validate("Cafe\u0301"))
        assertEquals(LibraryNames.key("Cafe\u0301"), LibraryNames.key("CAFÉ"))
        assertEquals("東京 🎬", LibraryNames.validate("東京 🎬"))
    }

    @Test fun keyDoesNotDependOnDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("title i", LibraryNames.key("TITLE I"))
        } finally { Locale.setDefault(original) }
    }

    @Test fun doesNotClaimFullUnicodeCaseFolding() {
        assertNotEquals(LibraryNames.key("Straße"), LibraryNames.key("STRASSE"))
        assertNotEquals(LibraryNames.key("ς"), LibraryNames.key("σ"))
    }

    @Test fun rejectsPathsDotsWhitespaceAndInvisibleCharacters() {
        listOf("", " ", ".", "..", "a/b", "a\\b", "a\u0000b", "a\nb", "a\tb", "a\u007fb", "a\u0085b",
            "a\u200bb", "a\u202eb", "a\u2028b", "a\u2029b", " name", "name ", "a\uFDD0b", "a\uFFFFb")
            .forEach { fails(LibraryError.INVALID_NAME) { LibraryNames.validate(it) } }
    }

    @Test fun rejectsMalformedUtf16WithoutReplacementEncoding() {
        listOf("\uD800", "\uDC00", "a\uD800b", "\uD800\uD800", "\uDC00\uD800")
            .forEach { fails(LibraryError.INVALID_NAME) { LibraryNames.validate(it) } }
        assertEquals("🎬", LibraryNames.validate("\uD83C\uDFAC"))
    }

    @Test fun nameLimitUsesUtf8Bytes() {
        assertEquals(255, LibraryNames.validate("a".repeat(255)).toByteArray().size)
        assertEquals(252, LibraryNames.validate("🎬".repeat(63)).toByteArray().size)
        fails(LibraryError.INVALID_NAME) { LibraryNames.validate("a".repeat(256)) }
        fails(LibraryError.INVALID_NAME) { LibraryNames.validate("é".repeat(128)) }
        fails(LibraryError.INVALID_NAME) { LibraryNames.validate("🎬".repeat(64)) }
        fails(LibraryError.INVALID_NAME) { LibraryNames.validate("a".repeat(1_000_000)) }
    }

    @Test fun normalizationHappensBeforeEncodedLengthCheck() {
        assertEquals("é".repeat(100), LibraryNames.validate("e\u0301".repeat(100)))
    }

    @Test fun searchIsBoundedLiteralTextWithEmptyAllowed() {
        assertEquals("", LibraryNames.searchKey(""))
        assertEquals("%_\\test", LibraryNames.searchKey("%_\\TEST"))
        assertEquals(512, LibraryNames.searchKey("a".repeat(512)).length)
        fails(LibraryError.INVALID_QUERY) { LibraryNames.searchKey("a".repeat(513)) }
        fails(LibraryError.INVALID_QUERY) { LibraryNames.searchKey("\uD800") }
        fails(LibraryError.INVALID_QUERY) { LibraryNames.searchKey("a\nb") }
    }

    @Test fun failuresDoNotEchoInput() {
        val exception = assertThrows(LibraryFailure::class.java) { LibraryNames.validate("secret/name") }
        assertEquals("INVALID_NAME", exception.message)
        assertFalse(exception.toString().contains("secret"))
    }

    private fun fails(error: LibraryError, action: () -> Unit) {
        assertEquals(error, assertThrows(LibraryFailure::class.java, action).code)
    }
}
