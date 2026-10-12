package com.easysavevideo.app.diagnostics

import com.easysavevideo.core.model.ErrorCode
import com.easysavevideo.core.model.ErrorModule
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.FileTime

/** T-P01..05 / FI-24: synthetic bait only, including fault and captured-extent cases. */
class DiagnosticSnapshotReaderTest {
    @Test fun allClosedEnumsAndNumericBoundariesRoundTrip() = temporary { directory ->
        val expected = buildString {
            for (module in ErrorModule.entries) for (code in ErrorCode.entries) {
                append(record(module = module.name, code = code.name))
            }
            append(record(api = "2147483647", http = "100"))
            append(record(api = "1", http = "599"))
        }
        log(directory).writeText(expected)
        assertEquals(expected, ready(reader(directory)).text)
    }

    @Test fun readsOnlyNewestFiveDaysWithinSevenDayWindow() = temporary { directory ->
        for (age in -1L..8L) log(directory, TODAY - age).writeText(record(timestamp = (TODAY - age) * DAY))
        val expected = (0L..4L).joinToString("") { record(timestamp = (TODAY - it) * DAY) }
        assertEquals(expected, ready(reader(directory)).text)
    }

    @Test fun seventhDayIsIncludedAndEighthDayIsExpired() = temporary { directory ->
        val expected = record(timestamp = (TODAY - 6) * DAY)
        log(directory, TODAY - 6).writeText(expected)
        log(directory, TODAY - 7).writeText(record(timestamp = (TODAY - 7) * DAY))
        assertEquals(expected, ready(reader(directory)).text)
    }

    @Test fun exactNamesOnlyAndNoFunctionalStorageTraversal() = temporary { directory ->
        val valid = record()
        val bait = "Cookie=SYNTHETIC_COOKIE token=SYNTHETIC_TOKEN https://example.invalid/PRIVATE_TITLE"
        log(directory).writeText(valid)
        for (name in listOf("0$TODAY.log", "$TODAY.LOG", "$TODAY.log.bak", "$TODAY.1.log", "２０000.log", "browser_session_v1.bin")) {
            File(directory, name).writeText(bait)
        }
        val private = File(directory, "vault").apply { mkdirs() }
        File(private, "$TODAY.log").writeText(bait)
        val sibling = File(directory.parentFile, "browser_session_v1.bin").apply { writeText(bait) }
        assertEquals(valid, ready(reader(directory)).text)
        assertEquals(bait, sibling.readText())
        assertEquals(bait, File(private, "$TODAY.log").readText())
    }

    @Test fun missingDirectoryIsEmptyAndIsNeverCreated() = temporary { directory ->
        assertTrue(directory.delete())
        assertSame(DiagnosticSnapshotResult.Empty, reader(directory).read())
        assertFalse(directory.exists())
    }

    @Test fun emptyDirectoryAndMalformedOnlyFilesAreEmpty() = temporary { directory ->
        assertSame(DiagnosticSnapshotResult.Empty, reader(directory).read())
        log(directory).writeText("https://example.invalid/secret\nCookie=BAIT\n")
        assertSame(DiagnosticSnapshotResult.Empty, reader(directory).read())
    }

    @Test fun arbitraryRootAndNonDirectoryRootFailClosed() = temporary { directory ->
        assertSame(DiagnosticSnapshotResult.Failed, reader(directory.parentFile).read())
        assertTrue(directory.delete())
        directory.writeText("synthetic private value")
        assertSame(DiagnosticSnapshotResult.Failed, reader(directory).read())
        assertEquals("synthetic private value", directory.readText())
    }

    @Test fun sensitiveBaitInAnyExtraOrUnknownFieldIsDiscarded() = temporary { directory ->
        val valid = record()
        val baits = listOf(
            "https://example.invalid/SYNTHETIC_KEYWORD", "/private/SYNTHETIC_FILENAME.mp4",
            "Cookie=SYNTHETIC_COOKIE", "Token=SYNTHETIC_TOKEN", "PIN=123456", "password=SYNTHETIC_PASSWORD",
            "key=SYNTHETIC_KEY", "Exception(SYNTHETIC_MESSAGE)", "title=SYNTHETIC_TITLE", "providerBody=SYNTHETIC_BODY",
        )
        log(directory).writeText(buildString {
            append(valid)
            for (bait in baits) {
                append(valid.trimEnd()).append(' ').append(bait).append('\n')
                append(bait).append(' ').append(valid)
                append(record(module = bait))
                append(record(code = bait))
            }
        })
        val text = ready(reader(directory)).text
        assertEquals(valid, text)
        for (bait in baits) assertFalse(text.contains(bait))
    }

    @Test fun exactAsciiGrammarRejectsWhitespaceUnicodeAndControlBait() = temporary { directory ->
        val valid = record()
        val malformed = listOf(
            valid.replace(" ERROR ", " INFO "), valid.replace(" ERROR ", " error "),
            valid.replace(" ERROR ", "  ERROR "), valid.replace(" ERROR ", "\tERROR "),
            " $valid", valid.trimEnd() + " \n", valid.replace("\n", "\r\n"),
            valid.replace("BROWSER", "UNKNOWN"), valid.replace("BROWSER", "browser"),
            valid.replace("NETWORK_TIMEOUT", "UNKNOWN_ERROR"), valid.replace("api=31", "api=٣١"),
            valid.replace("api=31", "api=３１"), valid.replace("http=0", "http=0\u0000"),
            valid.replace("ERROR", "ERRОR"), // Cyrillic O must not match ASCII.
            valid.replace(" api=31 http=0", " http=0 api=31"), valid.replace("http=0", "http=0 attempt=1"),
        )
        log(directory).writeText(malformed.joinToString("") + valid)
        assertEquals(valid, ready(reader(directory)).text)
    }

    @Test fun numericOverflowAndNonCanonicalRepresentationsAreRejected() = temporary { directory ->
        val valid = record()
        val badApis = listOf("0", "-1", "+1", "01", "2147483648", "99999999999999999999", "1.0", "1e2")
        val badHttp = listOf("-1", "+200", "00", "099", "99", "600", "999", "0200", "2000", "2147483648")
        log(directory).writeText(buildString {
            for (api in badApis) append(record(api = api))
            for (http in badHttp) append(record(http = http))
            append(valid.replace(NOW.toString(), "9223372036854775808"))
            append(valid.replace(NOW.toString(), "0$NOW"))
            append(valid.replace(NOW.toString(), "+$NOW"))
            append(valid.replace(NOW.toString(), "-$NOW"))
            append(valid)
        })
        assertEquals(valid, ready(reader(directory)).text)
    }

    @Test fun timestampsMustBelongToBucketAndNotExceedCapturedNow() = temporary { directory ->
        val earliest = record(timestamp = TODAY * DAY)
        val latest = record(timestamp = NOW)
        log(directory).writeText(
            record(timestamp = TODAY * DAY - 1) + record(timestamp = (TODAY + 1) * DAY) +
                record(timestamp = NOW + 1) + earliest + latest,
        )
        assertEquals(earliest + latest, ready(reader(directory)).text)
    }

    @Test fun epochZeroAndMaximumLongClockDoNotOverflow() = temporary { directory ->
        val zero = record(timestamp = 0)
        log(directory, 0).writeText(zero)
        assertEquals(zero, ready(DiagnosticSnapshotReader(directory) { 0 }).text)
        val day = Long.MAX_VALUE / DAY
        val last = record(timestamp = Long.MAX_VALUE)
        log(directory, day).writeText(last)
        assertEquals(last, ready(DiagnosticSnapshotReader(directory) { Long.MAX_VALUE }).text)
        assertSame(DiagnosticSnapshotResult.Failed, DiagnosticSnapshotReader(directory) { -1 }.read())
    }

    @Test fun clockFailureReturnsClosedFailureWithoutExceptionText() = temporary { directory ->
        val failing = DiagnosticSnapshotReader(directory) { throw IOException("SYNTHETIC_PRIVATE_ERROR") }
        assertSame(DiagnosticSnapshotResult.Failed, failing.read())
    }

    @Test fun incompleteTailIsNeverParsedEvenWhenItHasValidGrammar() = temporary { directory ->
        val complete = record()
        log(directory).writeText(complete + record(http = "500").trimEnd())
        assertEquals(complete, ready(reader(directory)).text)
    }

    @Test fun longOrNonAsciiLineDropsThroughNewlineAndResumesSafely() = temporary { directory ->
        val valid = record()
        log(directory).writeText("SYNTHETIC_SECRET".repeat(1300) + valid + "\u0000" + valid + valid)
        assertEquals(valid, ready(reader(directory)).text)
    }

    @Test fun validRecordsAcrossReadBufferBoundaryRemainComplete() = temporary { directory ->
        val expected = record().repeat(180)
        log(directory).writeText(expected)
        assertTrue(expected.length > 4096)
        assertEquals(expected, ready(reader(directory)).text)
    }

    @Test fun totalInputBudgetIncludesMalformedBytesAndNeverReadsPastCap() = temporary { directory ->
        val valid = record()
        log(directory).writeText(valid + "x".repeat(DiagnosticSnapshotReader.MAX_BYTES) + "\n" + valid)
        log(directory, TODAY - 1).writeText(record(timestamp = (TODAY - 1) * DAY))
        var readBytes = 0
        val bounded = injected(directory) { delegate -> object : DelegatingChannel(delegate) {
            override fun read(dst: ByteBuffer): Int = super.read(dst).also { if (it > 0) readBytes += it }
        } }
        assertEquals(valid, ready(bounded).text)
        assertEquals(DiagnosticSnapshotReader.MAX_BYTES, readBytes)
    }

    @Test fun inputBudgetIsSharedAcrossFilesAndDropsItsPartialLastLine() = temporary { directory ->
        val newest = record().repeat(1500)
        val older = record(timestamp = (TODAY - 1) * DAY, http = "500")
        log(directory).writeText(newest)
        log(directory, TODAY - 1).writeText(older.repeat(4000))
        val completeOlder = (DiagnosticSnapshotReader.MAX_BYTES - newest.length) / older.length
        val text = ready(reader(directory)).text
        assertEquals(newest + older.repeat(completeOlder), text)
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= DiagnosticSnapshotReader.MAX_BYTES)
        assertTrue(text.endsWith('\n'))
    }

    @Test fun snapshotIsDetachedAndReaderDoesNotAlterOriginals() = temporary { directory ->
        val original = record() + "SYNTHETIC_DROPPED_BAIT\n"
        val file = log(directory).apply { writeText(original) }
        val before = file.readBytes()
        val modified = file.lastModified()
        val snapshot = ready(reader(directory))
        assertArrayEquals(before, file.readBytes())
        assertEquals(modified, file.lastModified())
        file.appendText(record(http = "500"))
        assertEquals(record(), snapshot.text)
        assertEquals(record() + record(http = "500"), ready(reader(directory)).text)
    }

    @Test fun snapshotConstructionEnforcesAsciiAndByteCeiling() {
        for (invalid in listOf("", "私密", "x".repeat(DiagnosticSnapshotReader.MAX_BYTES + 1))) {
            try { DiagnosticSnapshot(invalid); fail("Invalid snapshot must fail") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun fileSymlinkNeverReadsOutsideDirectory() = temporary { directory ->
        val outside = File(directory.parentFile, "private.txt").apply { writeText(record()) }
        Files.createSymbolicLink(log(directory).toPath(), outside.toPath())
        assertSame(DiagnosticSnapshotResult.Failed, reader(directory).read())
        assertEquals(record(), outside.readText())
    }

    @Test fun directorySymlinkIsRejectedButTrustedParentAliasWorks() = temporary { directory ->
        val outside = File(directory.parentFile, "other").apply { mkdirs() }
        File(outside, "$TODAY.log").writeText(record())
        assertTrue(directory.delete())
        Files.createSymbolicLink(directory.toPath(), outside.toPath())
        assertSame(DiagnosticSnapshotResult.Failed, reader(directory).read())
        Files.delete(directory.toPath())
        assertTrue(directory.mkdir())
        log(directory).writeText(record())
        val alias = File(directory.parentFile, "alias")
        Files.createSymbolicLink(alias.toPath(), directory.parentFile.toPath())
        try {
            assertEquals(record(), ready(reader(File(alias, "technical_errors"))).text)
        } finally { Files.delete(alias.toPath()) }
    }

    @Test fun directoryAtExpectedDailyFilenameFailsClosed() = temporary { directory ->
        assertTrue(log(directory).mkdir())
        File(log(directory), "private.txt").writeText("SYNTHETIC_BAIT")
        assertSame(DiagnosticSnapshotResult.Failed, reader(directory).read())
    }

    @Test fun readAndOpenFailureNeverPublishPartialSuccessfulBuckets() = temporary { directory ->
        log(directory).writeText(record())
        log(directory, TODAY - 1).writeText(record(timestamp = (TODAY - 1) * DAY))
        val failedOpen = DiagnosticSnapshotReader(directory, { NOW }) { path ->
            if (path.fileName.toString() != "$TODAY.log") throw IOException("SYNTHETIC_SECRET")
            open(path)
        }
        assertSame(DiagnosticSnapshotResult.Failed, failedOpen.read())
        val failedRead = injected(directory) { delegate -> object : DelegatingChannel(delegate) {
            override fun read(dst: ByteBuffer): Int = throw IOException("SYNTHETIC_SECRET")
        } }
        assertSame(DiagnosticSnapshotResult.Failed, failedRead.read())
    }

    @Test fun zeroProgressAndUnexpectedEofFailWithoutSpinning() = temporary { directory ->
        log(directory).writeText(record())
        for (result in listOf(0, -1)) {
            var calls = 0
            val failed = injected(directory) { delegate -> object : DelegatingChannel(delegate) {
                override fun read(dst: ByteBuffer): Int { calls++; return result }
            } }
            assertSame(DiagnosticSnapshotResult.Failed, failed.read())
            assertEquals(1, calls)
        }
    }

    @Test fun closeFailureDiscardsAnOtherwiseValidSnapshot() = temporary { directory ->
        log(directory).writeText(record())
        val failed = injected(directory) { delegate -> object : DelegatingChannel(delegate) {
            override fun close() { super.close(); throw IOException("SYNTHETIC_PRIVATE_CLOSE") }
        } }
        assertSame(DiagnosticSnapshotResult.Failed, failed.read())
    }

    @Test fun concurrentAppendCannotAddRecordsOrCompleteCapturedTail() = temporary { directory ->
        val original = record()
        val file = log(directory).apply { writeText(original + record(http = "500").trimEnd()) }
        var appended = false
        val captured = injected(directory) { delegate -> object : DelegatingChannel(delegate) {
            override fun read(dst: ByteBuffer): Int {
                if (!appended) { appended = true; file.appendText("\n" + record(http = "503")) }
                return super.read(dst)
            }
        } }
        assertEquals(original, ready(captured).text)
        assertTrue(appended)
        assertEquals(original + record(http = "500") + record(http = "503"), ready(reader(directory)).text)
    }

    @Test fun allBucketExtentsAreCapturedBeforeFirstRead() = temporary { directory ->
        val first = record()
        val second = record(timestamp = (TODAY - 1) * DAY)
        log(directory).writeText(first)
        val older = log(directory, TODAY - 1).apply { writeText(second) }
        var appended = false
        val captured = injected(directory) { delegate -> object : DelegatingChannel(delegate) {
            override fun read(dst: ByteBuffer): Int {
                if (!appended) { appended = true; older.appendText(record(timestamp = (TODAY - 1) * DAY, http = "503")) }
                return super.read(dst)
            }
        } }
        assertEquals(first + second, ready(captured).text)
    }

    @Test fun truncationDuringReadFailsEvenAfterBytesWereBuffered() = temporary { directory ->
        val file = log(directory).apply { writeText(record().repeat(200)) }
        var truncated = false
        val failed = injected(directory) { delegate -> object : DelegatingChannel(delegate) {
            override fun read(dst: ByteBuffer): Int {
                val count = super.read(dst)
                if (!truncated) { truncated = true; file.writeText("") }
                return count
            }
        } }
        assertSame(DiagnosticSnapshotResult.Failed, failed.read())
    }

    @Test fun removalAndReplacementDuringReadFailClosed() = temporary { directory ->
        val file = log(directory)
        for (replace in listOf(false, true)) {
            file.writeText(record())
            var removed = false
            val failed = injected(directory) { delegate -> object : DelegatingChannel(delegate) {
                override fun read(dst: ByteBuffer): Int {
                    val count = super.read(dst)
                    if (!removed) {
                        removed = true
                        assertTrue(file.delete())
                        if (replace) file.writeText(record(http = "500"))
                    }
                    return count
                }
            } }
            assertSame(DiagnosticSnapshotResult.Failed, failed.read())
        }
    }

    @Test fun symlinkSwapImmediatelyBeforeOpenIsRejected() = temporary { directory ->
        val file = log(directory).apply { writeText(record()) }
        val outside = File(directory.parentFile, "outside.txt").apply { writeText(record(http = "503")) }
        val failed = DiagnosticSnapshotReader(directory, { NOW }) { path ->
            assertTrue(file.delete())
            Files.createSymbolicLink(path, outside.toPath())
            open(path)
        }
        assertSame(DiagnosticSnapshotResult.Failed, failed.read())
        assertEquals(record(http = "503"), outside.readText())
    }

    @Test fun sameSizeRewriteIsNotMistakenForAppend() = temporary { directory ->
        val file = log(directory).apply { writeText(record()) }
        val originalTime = file.lastModified()
        var rewritten = false
        val failed = injected(directory) { delegate -> object : DelegatingChannel(delegate) {
            override fun read(dst: ByteBuffer): Int {
                if (!rewritten) {
                    rewritten = true
                    file.writeText(record(api = "32"))
                    Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(originalTime + 2000))
                }
                return super.read(dst)
            }
        } }
        assertSame(DiagnosticSnapshotResult.Failed, failed.read())
    }

    private fun reader(directory: File) = DiagnosticSnapshotReader(directory) { NOW }
    private fun injected(directory: File, wrap: (SeekableByteChannel) -> SeekableByteChannel) =
        DiagnosticSnapshotReader(directory, { NOW }) { wrap(open(it)) }
    private fun open(path: Path): SeekableByteChannel = Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS))
    private fun ready(reader: DiagnosticSnapshotReader): DiagnosticSnapshot {
        val result = reader.read()
        assertTrue("Expected ready snapshot, got $result", result is DiagnosticSnapshotResult.Ready)
        return (result as DiagnosticSnapshotResult.Ready).snapshot
    }
    private fun log(directory: File, day: Long = TODAY) = File(directory, "$day.log")
    private fun record(
        timestamp: Long = NOW, module: String = "BROWSER", code: String = "NETWORK_TIMEOUT",
        api: String = "31", http: String = "0",
    ) = "$timestamp ERROR $module $code api=$api http=$http\n"

    private fun temporary(action: (File) -> Unit) {
        val root = Files.createTempDirectory("esv-diagnostic-test-").toFile()
        val directory = File(root, "technical_errors").apply { mkdir() }
        try { action(directory) } finally {
            // Do not follow symlinks left by a fault-injection case during test cleanup either.
            Files.walk(root.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private open class DelegatingChannel(private val delegate: SeekableByteChannel) : SeekableByteChannel by delegate

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val TODAY = 20_000L
        private const val NOW = TODAY * DAY + DAY / 2
    }
}
