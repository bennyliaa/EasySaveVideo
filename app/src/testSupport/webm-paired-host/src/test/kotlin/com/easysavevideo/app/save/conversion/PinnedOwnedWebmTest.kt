package com.easysavevideo.app.save.conversion

import android.net.Uri
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.StatsDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.easysavevideo.app.media.transform.TransformerOutcome
import com.easysavevideo.app.media.transform.TransformerSession
import com.easysavevideo.app.media.transform.TransformerReadHandle
import com.easysavevideo.app.media.transform.TransformerFailure
import com.easysavevideo.app.media.transform.TransformerError
import com.easysavevideo.app.media.transform.TransformerRelease
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import com.easysavevideo.save.SaveFailure
import com.easysavevideo.save.SaveError
import androidx.media3.extractor.DefaultExtractorInput
import com.easysavevideo.app.save.storage.OwnedTranscodeTestFixture
import com.easysavevideo.research.webm.OriginalWebmFixtures as F
import java.io.File
import java.lang.reflect.InvocationTargetException

/** Actual registered production owner/pair/guard + pinned public StatsDataSource/adapter. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object PinnedOwnedWebmTest {
    private var passed = 0
    private var failed = 0
    private val records = ArrayList<String>()
    @JvmStatic fun main(args: Array<String>) {
        val backgroundFailures = CopyOnWriteArrayList<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> backgroundFailures.add(failure) }
        test("pinned_binary_class_code_sources") {
            pinnedLocation(StatsDataSource::class.java, "datasource")
            pinnedLocation(androidx.media3.extractor.mkv.MatroskaExtractor::class.java, "extractor")
            pinnedLocation(ProgressiveMediaExtractor::class.java, "exoplayer")
            pinnedLocation(androidx.media3.common.MediaItem::class.java, "common")
        }
        test("registered_stats_full_loading_eof") { owned(F.file(true, 8, 0, 3)) { _, pair, session ->
            val h = Harness(pair, session)
            try {
                h.start(); val facts = h.finish()
                check(facts.tracks.size == 2); check(facts.tracks.all { it.sourceSampleCallbacks == 3L })
                check(h.stats.bytesRead == facts.inputExtent)
            } finally { h.close() }
        } }
        test("registered_declared_timeline_matches_actual_loading") {
            owned(F.declaredTimeline(33_333_333, 100.75, true)) { _, pair, session ->
                val loader = Harness(pair, session)
                try {
                    loader.start(); val facts = loader.finish()
                    check(facts.declaredContainerDurationUs == 100_750L && facts.demuxerDurationUs == 100_000L)
                    check(facts.tracks.first().declaredDefaultDurationNs == 33_333_333L)
                    check(facts.tracks.size == 2 && facts.tracks.last().initializationData.size == 3)
                } finally { loader.close() }
            }
        }
        for (changed in listOf("default_duration", "declared_duration_same_demuxer", "demuxer_duration"))
            test("same_extent_loading_timeline_mismatch_$changed") {
                val before = F.declaredTimeline(33_333_333, 100.75, false)
                val after = when (changed) {
                    "default_duration" -> F.declaredTimeline(33_333_334, 100.75, false)
                    "declared_duration_same_demuxer" -> F.declaredTimeline(33_333_333, 100.5, false)
                    else -> F.declaredTimeline(33_333_333, 101.75, false)
                }
                check(before.size == after.size)
                owned(before) { h, pair, session ->
                    h.f.io.mutate(h.f.primary, after, preserveTimes = true)
                    val loader = Harness(pair, session)
                    try { loader.start(); reject(WebmProbeFailure.IDENTITY) { loader.finish() } }
                    finally { loader.close() }
                }
            }
        test("same_extent_and_timing_different_actual_timecode_scale_rejected") {
            // These files have equal duration, equal source PTS, equal payload/extent and all
            // existing scalar observations; only the original scale differs (ticks compensate).
            fun scaled(scale: Long, multiplier: Int): ByteArray = F.fileWithBody(F.concat(
                F.element(F.INFO, F.concat(F.uint(0x2AD7B1, scale), F.floating(0x4489, 100.0 * multiplier))),
                F.tracks(F.videoTrack(1, "V_VP8", F.uint(0x23E383, 33_333_333))),
                F.cluster(0, false, 0), F.cluster(33 * multiplier, false, 0), F.cluster(67 * multiplier, false, 0)), 8)
            val before = scaled(1_000_000L, 1)
            val after = scaled(500_000L, 2)
            check(before.size == after.size)
            owned(after) { _, pair, session ->
                val loader = Harness(pair, session)
                try {
                    loader.start(); val actual = loader.finish()
                    check(actual.timecodeScaleNs == 500_000L)
                    check(actual.declaredContainerDurationUs == 100_000L && actual.demuxerDurationUs == 100_000L)
                    check(actual.tracks.single().firstSourceTimestampUs == 0L && actual.tracks.single().lastSourceTimestampUs == 67_000L)
                } finally { loader.close() }
            }
            owned(before) { h, pair, session ->
                check(field<WebmTransportFacts>(pair, "expected").timecodeScaleNs == 1_000_000L)
                h.f.io.mutate(h.f.primary, after, preserveTimes = true)
                val loader = Harness(pair, session)
                try { loader.start(); reject(WebmProbeFailure.IDENTITY) { loader.finish() } }
                finally { loader.close() }
            }
        }
        test("same_declaration_loading_official_duration_mismatch") {
            owned(F.declaredTimeline(33_333_333, 100.75, false)) { _, pair, session ->
                // Isolate the new equality field; same bytes/declaration/geometry still parse normally.
                val expected = field<WebmTransportFacts>(pair, "expected")
                setField(pair, "expected", WebmTransportFacts(expected.inputExtent, expected.declaredContainerDurationUs,
                    expected.tracks, expected.maximumPhysicalMasterDepth, expected.outstandingMastersAtEof,
                    expected.actualUnknownEndCallbacks, expected.checkedHeaders, expected.delegatedPayloads, 99_999L))
                val loader = Harness(pair, session)
                try { loader.start(); reject(WebmProbeFailure.IDENTITY) { loader.finish() } }
                finally { loader.close() }
            }
        }
        test("sniff_reset_peek_consumed_position_and_suffix_cursor") { owned(F.file(false, 8, 0, 3)) { _, pair, session ->
            val h = Harness(pair, session)
            try {
                h.start(); check(h.adapter.currentInputPosition == 0L); check(h.stats.bytesRead > 0)
                val before = field<Long>(h.source, "position")
                val shared = field<WebmInputBinding>(pair, "shared")
                val charged = shared.chargedBytes
                shared.suffix(before)
                check(field<Long>(h.source, "position") == before); check(shared.chargedBytes > charged)
                h.adapter.read(PositionHolder()) // A pause is simply no next read, preserving this input.
                val input = field<WebmGuardedInput>(h.adapter, "input")
                check(h.adapter.currentInputPosition == input.position)
                h.finish()
            } finally { h.close() }
        } }
        for (kind in listOf("missing", "duplicate", "tuple", "return_count")) test("receipt_$kind") {
            owned(F.file(false, 8, 0, 3)) { _, pair, session ->
                val h = Harness(pair, session)
                val reader = DataReader { bytes, offset, length -> when (kind) {
                    "missing" -> -1
                    "duplicate" -> { h.stats.read(bytes, offset, length); h.stats.read(bytes, offset, length) }
                    "tuple" -> h.stats.read(bytes.clone(), offset, length)
                    else -> h.stats.read(bytes, offset, length) + 1
                } }
                try { reject(WebmProbeFailure.FOREIGN_INPUT) { h.start(reader) } }
                finally { h.close() }
            }
        }
        test("bare_source_read_has_no_receipt") { owned(F.file(false, 8, 0, 3)) { _, pair, session ->
            val h = Harness(pair, session)
            try { h.open(); reject(WebmProbeFailure.FOREIGN_INPUT) { h.source.read(ByteArray(1), 0, 1) } }
            finally { h.close() }
        } }
        for (kind in listOf("nonzero_seek", "second_zero_seek", "reinit", "reopen", "headers")) test("reject_$kind") {
            owned(F.file(false, 8, 0, 3)) { _, pair, session ->
                val h = Harness(pair, session)
                try {
                    if (kind == "headers") { h.open(); reject(WebmProbeFailure.FOREIGN_INPUT) {
                        h.adapter.init(h.stats, h.uri, mapOf("icy-metaint" to listOf("16")), 0, h.extent, Discard)
                    } } else {
                        h.start()
                        when (kind) {
                            "nonzero_seek" -> reject(WebmProbeFailure.SEEK_UNSUPPORTED) { h.adapter.seek(1, 0) }
                            "second_zero_seek" -> reject(WebmProbeFailure.SEEK_UNSUPPORTED) { h.adapter.seek(0, 0) }
                            "reinit" -> reject(WebmProbeFailure.INCOMPLETE) { h.adapter.init(h.stats, h.uri, emptyMap(), 0, h.extent, Discard) }
                            "reopen" -> { h.stats.close(); reject(WebmProbeFailure.INCOMPLETE) { h.stats.open(h.spec) } }
                        }
                    }
                } finally { h.close() }
            }
        }
        for (kind in listOf("uri", "flags", "headers", "position", "key", "finite_length")) test("dataspec_$kind") {
            owned(F.file(false, 8, 0, 3)) { _, pair, session ->
                val h = Harness(pair, session)
                try {
                    val spec = h.spec.buildUpon().apply { when (kind) {
                        "uri" -> setUri("esv-webm-owned:foreign")
                        "flags" -> setFlags(0)
                        "headers" -> setHttpRequestHeaders(emptyMap())
                        "position" -> setPosition(1)
                        "key" -> setKey("foreign")
                        "finite_length" -> setLength(h.extent)
                    } }.build()
                    reject(WebmProbeFailure.FOREIGN_INPUT) { h.stats.open(spec) }
                } finally { h.close() }
            }
        }
        test("guard_loading_huge_peek_before_allocation") { owned(F.file(false, 8, 0, 3)) { h, pair, session ->
            val loader = Harness(pair, session)
            try {
                loader.start(); val guard = field<WebmGuardedInput>(loader.adapter, "input")
                val reads = h.f.io.calls.count { it.operation == "pread" }
                reject(WebmProbeFailure.READ_REQUEST) { guard.advancePeekPosition(Int.MAX_VALUE, true) }
                check(reads == h.f.io.calls.count { it.operation == "pread" })
            } finally { loader.close() }
        } }
        test("same_extent_loading_track_change_rejected") {
            val before = F.file(false, 8, 0, 3)
            val after = before.clone(); val width = byteArrayOf(0xB0.toByte(), 0x81.toByte(), 16)
            val at = (0..after.size - width.size).first { n -> width.indices.all { after[n + it] == width[it] } }
            after[at + 2] = 18
            owned(before) { h, pair, session ->
                h.f.io.mutate(h.f.primary, after, preserveTimes = true)
                val loader = Harness(pair, session)
                try { loader.start(); reject(WebmProbeFailure.IDENTITY) { loader.finish() } }
                finally { loader.close() }
            }
        }
        val malformed = listOf(
            Triple("known_parent_overrun", F.knownClusterPastKnownParent(), WebmProbeFailure.MASTER_EXTENT),
            Triple("long_wrapped_binary", F.wrappedBinarySize(), WebmProbeFailure.TRUNCATED),
            Triple("long_wrapped_ignored", F.wrappedIgnoredSize(), WebmProbeFailure.TRUNCATED),
            Triple("content_encryption", F.contentEncoding(), WebmProbeFailure.PROTECTED_MEDIA),
            Triple("third_track", F.thirdTrack(), WebmProbeFailure.TRACK_LIMIT),
            Triple("duplicate_track", F.duplicateTrackNumber(), WebmProbeFailure.TRACK_ID),
            Triple("unknown_depth_17", F.file(false, 8, 8, 16), WebmProbeFailure.MASTER_DEPTH),
        )
        for ((name, bytes, expectedFailure) in malformed) test("loading_guard_$name") {
            val baseline = F.concat(F.file(false, 8, 0, 3), F.element(F.VOID, ByteArray(bytes.size + 1024)))
            owned(baseline) { h, pair, session ->
                h.f.io.mutate(h.f.primary, bytes.copyOf(baseline.size), preserveTimes = true)
                val loader = Harness(pair, session)
                try {
                    reject(expectedFailure) { loader.start(); loader.finish() }
                } finally { loader.close() }
            }
        }
        test("cross_pair_real_stats_receipt_rejected") {
            owned(F.file(false, 8, 0, 3)) { _, first, firstSession ->
                val a = Harness(first, firstSession)
                try {
                    a.open()
                    owned(F.file(false, 8, 0, 3)) { _, second, secondSession ->
                        val b = Harness(second, secondSession)
                        try {
                            b.open()
                            reject(WebmProbeFailure.FOREIGN_INPUT) {
                                a.adapter.init(b.stats, a.uri, emptyMap(), 0, a.extent, Discard)
                            }
                        } finally { b.close() }
                    }
                } finally { a.close() }
            }
        }
        test("original_caller_stop_survives_parser_read_and_forbids_late_facts") {
            val stop = AtomicReference<Exception?>()
            val sentinel = IOException("HOST_ORIGINAL_CALLER_STOP")
            owned(F.file(false, 8, 0, 3), expectFailed = true, expectedPrimary = sentinel,
                sessionFactory = { h -> TransformerSession(h.session().limits,
                    { stop.get()?.let { throw it }; h.f.checkOperation(h.f.context) }, { null }) }) { h, pair, session ->
                val loader = Harness(pair, session)
                try {
                    loader.start()
                    h.f.io.after = { if (it.operation == "pread") stop.set(sentinel) }
                    var observed: Throwable? = null
                    try { while (loader.adapter.read(PositionHolder()) != Extractor.RESULT_END_OF_INPUT) Unit }
                    catch (failure: Throwable) { observed = failure }
                    check(observed === sentinel)
                    var denied = false
                    try { pair.completedTransportFacts() } catch (_: Exception) { denied = true }
                    check(denied)
                } finally { h.f.io.after = {}; loader.close() }
            }
        }
        test("cancel_after_eof_still_forbids_completed_facts") {
            owned(F.file(false, 8, 0, 3), expectFailed = true,
                expectedFailure = { (it.primary as? SaveFailure)?.code == SaveError.CANCELLED }) { h, pair, session ->
                val loader = Harness(pair, session)
                try {
                    loader.start()
                    while (loader.adapter.read(PositionHolder()) != Extractor.RESULT_END_OF_INPUT) Unit
                    h.f.cancellation.cancel()
                    loader.stats.close()
                    var denied = false
                    try { pair.completedTransportFacts() }
                    catch (failure: SaveFailure) { check(failure.code == SaveError.CANCELLED); denied = true }
                    check(denied)
                } finally { loader.close() }
            }
        }
        test("loading_parser_rejects_foreign_default_input") { owned(F.file(false, 8, 0, 3)) { _, pair, session ->
            val loader = Harness(pair, session)
            try {
                loader.start()
                val parser = field<BoundedWebmExtractor>(loader.adapter, "parser")
                val foreign = DefaultExtractorInput(DataReader { _, _, _ -> -1 }, 0, loader.extent)
                reject(WebmProbeFailure.FOREIGN_INPUT) { parser.sniff(foreign) }
            } finally { loader.close() }
        } }
        test("closeQuietly_failure_is_sticky") { owned(F.file(false, 8, 0, 3), expectFailed = true,
            expectedFailure = { it.release == TransformerRelease.UNCONFIRMED &&
                (it.primary as? TransformerFailure)?.code == TransformerError.EXECUTION_FAILED }) { _, pair, session ->
            val loader = Harness(pair, session)
            loader.start()
            while (loader.adapter.read(PositionHolder()) != Extractor.RESULT_END_OF_INPUT) Unit
            val original = field<TransformerReadHandle>(loader.source, "input")
            val failing = object : TransformerReadHandle by original {
                override fun close() { original.close(); throw IOException("HOST_CLOSE_UNCERTAIN") }
            }
            setField(loader.source, "input", failing)
            DataSourceUtil.closeQuietly(loader.stats)
            reject(WebmProbeFailure.INCOMPLETE) { pair.completedTransportFacts() }
            setField(loader.source, "input", original)
            loader.close()
        } }
        test("repeat_source_issuance_rejected") { owned(F.file(false, 8, 0, 3)) { _, pair, session ->
            pair.createMediaSource(session)
            reject(WebmProbeFailure.INCOMPLETE) { pair.createMediaSource(session) }
        } }
        test("foreign_empty_session_does_not_release_loading_retention") { owned(F.file(false, 8, 0, 3)) { h, pair, session ->
            val loader = Harness(pair, session)
            try {
                loader.start(); check(h.session().finish())
                check(field<Map<*, *>>(session, "readers").size == 1)
                loader.finish()
            } finally { loader.close() }
        } }
        test("source_assembly_and_factory_creation_never_retain_open") { owned(F.file(false, 8, 0, 3)) { h, pair, session ->
            val opens = h.f.io.calls.count { it.operation == "open" }
            val loader = Harness(pair, session)
            check(opens == h.f.io.calls.count { it.operation == "open" })
            loader.close()
        } }
        test("background_callback_dispatch_drained_without_uncaught_failure") {
            check(android.os.HandlerThread.drainAll(5_000))
            backgroundFailures.forEach { it.printStackTrace() }
            check(backgroundFailures.isEmpty())
        }
        val out = File(requireNotNull(System.getProperty("esv.webm.output"))); out.mkdirs()
        File(out, "results.json").writeText("{\"passed\":$passed,\"failed\":$failed,\"nativeDecodeTested\":false,\"directIssued\":false,\"results\":[${records.joinToString(",")}]}")
        println("PAIRED_PINNED_HOST passed=$passed failed=$failed nativeDecodeTested=false directIssued=false")
        check(failed == 0)
    }
    private fun owned(bytes: ByteArray, expectFailed: Boolean = false, expectedPrimary: Throwable? = null,
        expectedFailure: ((TransformerOutcome.Failed) -> Boolean)? = null,
        sessionFactory: (OwnedTranscodeTestFixture) -> TransformerSession = { it.session() }, body: (OwnedTranscodeTestFixture, OwnedWebmInputBinding, TransformerSession) -> Unit) {
        val owner = OwnedTranscodeTestFixture(bytes.size)
        owner.f.io.mutate(owner.f.primary, bytes, preserveTimes = true)
        owner.run { h, _ ->
            val attempt = h.prepare(); val pair = attempt.bindWebmInputOnce()
            try {
                pair.probe()
                val session = sessionFactory(h)
                var bodyCompleted = false
                val result = h.entered(session) { body(h, pair, session); bodyCompleted = true }
                if (expectFailed) {
                    check(bodyCompleted) { "Expected failure requires all body assertions to finish" }
                    check(result is TransformerOutcome.Failed)
                    check(expectedPrimary != null || expectedFailure != null)
                    if (expectedPrimary != null) check(result.primary === expectedPrimary)
                    if (expectedFailure != null) check(expectedFailure(result))
                }
                else if (result is TransformerOutcome.Failed) throw result.primary
            } finally { attempt.close() }
        }
    }
    private class Harness(val pair: OwnedWebmInputBinding, session: TransformerSession) {
        init { pair.createMediaSource(session) }
        val uri = Uri.parse(field<String>(pair, "route"))
        val extent = field<WebmInputBinding>(pair, "shared").extent
        val source = call<DataSource>(pair, "newDataSource")
        val stats = StatsDataSource(source)
        val adapter = call<ProgressiveMediaExtractor>(pair, "newExtractor")
        val spec: DataSpec = DataSpec.Builder().setUri(uri).setFlags(6).setHttpRequestHeaders(mapOf("Icy-MetaData" to "1")).build()
        fun open() { check(stats.open(spec) == extent) }
        fun start(reader: DataReader = stats) {
            open(); adapter.init(reader, uri, emptyMap(), 0, extent, Discard); adapter.seek(0, 0)
        }
        fun finish(): WebmTransportFacts {
            while (adapter.read(PositionHolder()) != Extractor.RESULT_END_OF_INPUT) Unit
            stats.close()
            return pair.completedTransportFacts()
        }
        fun close() { try { stats.close() } finally { adapter.release() } }
    }
    private object Discard : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            val buffer = ByteArray(4096)
            override fun format(format: Format) = Unit
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = input.read(buffer, 0, minOf(buffer.size, length))
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }
    private fun pinnedLocation(type: Class<*>, artifact: String) {
        val expected = File(requireNotNull(System.getProperty("esv.webm.output")), "jars/media3-$artifact-1.11.1.jar").canonicalFile
        val actual = File(requireNotNull(type.protectionDomain.codeSource).location.toURI()).canonicalFile
        check(actual == expected) { "Expected exact pinned jar for ${type.simpleName}" }
    }
    private fun setField(value: Any, name: String, replacement: Any) {
        value.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(value, replacement)
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(value: Any, name: String): T = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value) as T
    @Suppress("UNCHECKED_CAST") private fun <T> call(value: Any, name: String): T = try {
        value.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(value) as T
    } catch (failure: InvocationTargetException) { throw requireNotNull(failure.cause) }
    private fun reject(expected: WebmProbeFailure, action: () -> Unit) {
        try { action() } catch (failure: WebmProbeException) { check(failure.failure == expected) { "Expected $expected, got ${failure.failure}" }; return }
        error("Expected rejection $expected")
    }
    private fun test(name: String, action: () -> Unit) {
        try { action(); passed++; println("PASS $name"); records += "{\"name\":\"$name\",\"passed\":true}" }
        catch (failure: Throwable) {
            failed++; println("FAIL $name ${failure.javaClass.simpleName} " + if (failure is WebmProbeException) failure.failure.name else "assertion")
            failure.printStackTrace()
            records += "{\"name\":\"$name\",\"passed\":false,\"failureType\":\"${failure.javaClass.simpleName}\"}"
        }
    }
}
