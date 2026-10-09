package com.easysavevideo.app.save.stream

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Synthetic allowlist/file-policy checks only. Android extraction/remux is not run by these tests. */
class LocalHlsAddressTest {
    @Test fun acceptsOnlyCanonicalGeneratedIds() {
        assertEquals(0, LocalHlsAddress.index("esv-local:///s0.ts", 2))
        assertEquals(1, LocalHlsAddress.index("esv-local:///s1.ts", 2))
        for (value in listOf("https://example.com/s0.ts", "file:///s0.ts", "content:///s0.ts",
            "esv-local://host/s0.ts", "esv-local:///s00.ts", "esv-local:///s2.ts", "esv-local:///s-1.ts",
            "esv-local:///s0.ts?x=y", "esv-local:///s0.ts#x", "esv-local:///%730.ts",
            "esv-local:///../s0.ts", "esv-local:////s0.ts", "ESV-LOCAL:///s0.ts", "esv-local:///s999999.ts")) {
            assertNull(value, LocalHlsAddress.index(value, 2))
        }
    }
    @Test fun sampleProofBindsInteriorTimingAndKeyframes() {
        val original = Media3LocalHlsEngine.sampleProofHeader(100, 100_000, 1)
        assertFalse(original.contentEquals(Media3LocalHlsEngine.sampleProofHeader(100, 150_000, 1)))
        assertFalse(original.contentEquals(Media3LocalHlsEngine.sampleProofHeader(100, 100_000, 0)))
        assertFalse(original.contentEquals(Media3LocalHlsEngine.sampleProofHeader(101, 100_000, 1)))
        assertArrayEquals(original, Media3LocalHlsEngine.sampleProofHeader(100, 100_000, 1))
    }
    @Test fun outputByteRangesRejectOverflowAndBudgetOverruns() {
        assertTrue(Media3LocalHlsEngine.outputRangeAllowed(0, 10, 10))
        assertTrue(Media3LocalHlsEngine.outputRangeAllowed(10, 0, 10))
        assertFalse(Media3LocalHlsEngine.outputRangeAllowed(10, 1, 10))
        assertFalse(Media3LocalHlsEngine.outputRangeAllowed(-1, 1, 10))
        assertFalse(Media3LocalHlsEngine.outputRangeAllowed(0, -1, 10))
        assertFalse(Media3LocalHlsEngine.outputRangeAllowed(Long.MAX_VALUE - 1, 2, Long.MAX_VALUE))
    }
    @Test fun recognizesMedia3ThreeByteAndFourByteSpsStartCodes() {
        assertEquals(3, Media3LocalHlsEngine.avcSpsOffset(byteArrayOf(0, 0, 1, 0x67, 66)))
        assertEquals(4, Media3LocalHlsEngine.avcSpsOffset(byteArrayOf(0, 0, 0, 1, 0x67, 66)))
        assertNull(Media3LocalHlsEngine.avcSpsOffset(byteArrayOf(0, 1, 0x67, 66)))
        assertNull(Media3LocalHlsEngine.avcSpsOffset(byteArrayOf(0, 0, 1, 0x68, 66)))
        assertNull(Media3LocalHlsEngine.avcSpsOffset(byteArrayOf(0, 0, 1, 0xe7.toByte(), 66)))
        assertNull(Media3LocalHlsEngine.avcSpsOffset(byteArrayOf(0, 0, 1, 0x67)))
    }
    @Test fun generatedPlaylistContainsOnlySyntheticIdsAndExactDecimalDurations() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val input = LocalHlsInput(listOf(
                LocalHlsSegment("https://example.com/private?token=secret", File("/not-an-allowed-file"), 188, "0".repeat(64), 1_234_567, 0),
                LocalHlsSegment("#EXT-X-KEY:bad", File("relative.ts"), 188, "0".repeat(64), 2_000_000, 1_234_567)), 3_234_567, false)
            val text = Media3LocalHlsEngine.localPlaylist(input).toString(Charsets.US_ASCII)
            assertTrue(text.contains("#EXTINF:1.234567,\n"))
            assertTrue(text.contains("#EXT-X-TARGETDURATION:2\n"))
            assertTrue(text.contains("esv-local:///s0.ts\n"))
            assertTrue(text.endsWith("#EXT-X-ENDLIST\n"))
            assertFalse(text.contains("example.com")); assertFalse(text.contains("KEY")); assertFalse(text.contains("relative"))
        } finally { java.util.Locale.setDefault(old) }
    }
    @Test fun fileGateRejectsOutsideAndSymlink() {
        val root = Files.createTempDirectory("esv-allowlist").toFile()
        val outside = Files.createTempFile("esv-outside", ".ts").toFile()
        try {
            outside.writeBytes(byteArrayOf(1))
            rejected { LocalHlsFile(outside, root, 1, sha(outside.readBytes())) }
            val link = File(root, "link.ts").toPath()
            Files.createSymbolicLink(link, outside.toPath())
            rejected { LocalHlsFile(link.toFile(), root, 1, sha(outside.readBytes())) }
        } finally { root.deleteRecursively(); outside.delete() }
    }
    @Test fun rejectsDotSegmentsAcrossDirectorySymlinks() {
        val root = Files.createTempDirectory("esv-dot-root").toFile()
        val outside = Files.createTempDirectory("esv-dot-outside").toFile()
        val link = File(root, "jump").toPath()
        try {
            val approved = File(root, "victim.ts").apply { writeBytes(byteArrayOf(1)) }
            File(outside, "subdir").mkdir()
            File(outside, "victim.ts").writeBytes(byteArrayOf(2))
            Files.createSymbolicLink(link, File(outside, "subdir").toPath())
            val ambiguous = File(root, "jump/../victim.ts")
            rejected { LocalHlsFile(ambiguous, root, 1, sha(approved.readBytes())) }
        } finally { Files.deleteIfExists(link); root.deleteRecursively(); outside.deleteRecursively() }
    }
    @Test fun digestAndIdentityRejectChanges() {
        val root = Files.createTempDirectory("esv-immutable").toFile()
        try {
            val input = File(root, "input.ts").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val approved = LocalHlsFile(input, root, 3, sha(input.readBytes()))
            approved.verifyHash {}
            input.writeBytes(byteArrayOf(1, 9, 3))
            rejected { approved.verifyHash {} }
            rejected { LocalHlsFile(input, root, 2, sha(input.readBytes())) }
        } finally { root.deleteRecursively() }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected local-only rejection") } catch (_: java.io.IOException) {}
    }
}
