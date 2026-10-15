package com.easysavevideo.app.save.conversion

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.easysavevideo.research.webm.OriginalWebmFixtures as F
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Actual production classes + admitted extractor bytecode. No duplicate safe-policy test adapter. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object PinnedProductionWebmTest {
    private var passed = 0
    private var failed = 0
    private val records = mutableListOf<String>()
    @JvmStatic fun main(args: Array<String>) {
        test("known_segment_cluster_eof_transport") {
            val f = probe(F.file(false, 0, 0, 3)); eq(3L, f.tracks.single().sourceSampleCallbacks)
            eq(0, f.outstandingMastersAtEof); eq(0L, f.tracks.single().firstSourceTimestampUs)
            eq(67_000L, f.tracks.single().lastSourceTimestampUs); eq(100_000L, f.declaredContainerDurationUs)
        }
        for (rate in listOf(24, 25, 30, 60)) {
            for (nanos in setOf(1_000_000_000L / rate, (1_000_000_000L + rate - 1) / rate)) {
                test("declared_default_duration_${rate}_$nanos") {
                    var frameRate: Float? = null
                    val sink = object : TrackOutput by DummyTrack {
                        override fun format(format: Format) { frameRate = format.frameRate }
                    }
                    val facts = extract(F.declaredTimeline(nanos, 100.0, false), sink)
                    eq(nanos, facts.tracks.single().declaredDefaultDurationNs)
                    eq(Format.NO_VALUE.toFloat(), frameRate)
                    eq(100_000L, facts.demuxerDurationUs)
                }
            }
        }
        test("absent_default_duration_is_null") {
            eq(null, probe(F.file(false, 8, 0, 3)).tracks.single().declaredDefaultDurationNs)
        }
        for (value in listOf(1L, Int.MAX_VALUE.toLong())) test("default_duration_guard_boundary_$value") {
            eq(value, probe(F.declaredTimeline(value, 100.0, false)).tracks.single().declaredDefaultDurationNs)
        }
        test("default_duration_follows_video_track_number_not_track_order") {
            val video = F.videoTrack(7, "V_VP8", F.uint(0x23E383, 40_000_000))
            val cluster = F.element(F.CLUSTER, F.concat(F.uint(0xE7, 0),
                F.simpleBlock(7, 0, F.VIDEO_PACKET), F.simpleBlock(2, 0, F.AUDIO_PACKET)))
            val facts = probe(F.fileWithBody(F.concat(F.info(), F.tracks(F.audioTrack(2), video), cluster), 8))
            eq(40_000_000L, facts.tracks.single { it.trackNumber == 7 }.declaredDefaultDurationNs)
            eq(null, facts.tracks.single { it.trackNumber == 2 }.declaredDefaultDurationNs)
        }
        for (value in listOf(0L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE))
            test("default_duration_invalid_$value") {
                reject(WebmProbeFailure.FIELD_VALUE) { probe(F.declaredTimeline(value, 100.0, false)) }
            }
        test("default_duration_duplicate_rejected") {
            val fields = F.concat(F.uint(0x23E383, 33_333_333), F.uint(0x23E383, 33_333_333))
            reject(WebmProbeFailure.DUPLICATE_FIELD) { probe(withVideoFields(fields)) }
        }
        for (context in listOf("info", "video", "cluster")) test("default_duration_wrong_context_$context") {
            val field = F.uint(0x23E383, 33_333_333)
            val info = if (context == "info") F.element(F.INFO, F.concat(F.uint(0x2AD7B1, 1_000_000), field)) else F.info()
            val video = if (context == "video") F.element(F.TRACK_ENTRY, F.concat(F.uint(0xD7, 1),
                F.uint(0x83, 1), F.ascii(F.CODEC_ID, "V_VP8"),
                F.element(0xE0, F.concat(F.uint(0xB0, 16), F.uint(0xBA, 16), field)))) else F.videoTrack(1)
            val cluster = if (context == "cluster") F.element(F.CLUSTER, F.concat(F.uint(0xE7, 0), field,
                F.simpleBlock(1, 0, F.VIDEO_PACKET))) else F.cluster(0, false, 0)
            reject(WebmProbeFailure.ELEMENT_CONTEXT) { probe(F.fileWithBody(F.concat(info, F.tracks(video), cluster), 8)) }
        }
        test("fractional_ticks_preserve_declared_and_official_duration") {
            val facts = probe(F.declaredTimeline(33_333_333, 100.75, false))
            eq(100_750L, facts.declaredContainerDurationUs)
            eq(100_000L, facts.demuxerDurationUs)
        }
        test("fractional_ticks_nondefault_scale_preserve_parser_truncation") {
            val info = F.element(F.INFO, F.concat(F.uint(0x2AD7B1, 10_000_000), F.floating(0x4489, 10.75)))
            val facts = probe(F.fileWithBody(F.concat(info, F.tracks(F.videoTrack(1)), F.cluster(0, false, 0)), 8))
            eq(107_500L, facts.declaredContainerDurationUs); eq(100_000L, facts.demuxerDurationUs)
        }
        test("fractional_ticks_that_official_parser_truncates_to_zero_rejected") {
            reject(WebmProbeFailure.FIELD_VALUE) { probe(F.declaredTimeline(33_333_333, 0.75, false)) }
        }
        test("unknown_official_duration_is_null_without_tail_estimate") {
            val info = F.element(F.INFO, F.uint(0x2AD7B1, 1_000_000))
            val facts = probe(F.fileWithBody(F.concat(info, F.tracks(F.videoTrack(1)),
                F.cluster(0, false, 0), F.cluster(100, false, 0)), 8))
            eq(null, facts.declaredContainerDurationUs); eq(null, facts.demuxerDurationUs)
            eq(100_000L, facts.tracks.single().lastSourceTimestampUs)
        }
        test("callback_same_official_duration_can_repeat") {
            val facts = extract(F.declaredTimeline(33_333_333, 100.75, false), afterRead = {
                it.seekMap(SeekMap.Unseekable(100_000)); it.seekMap(SeekMap.Unseekable(100_000))
            })
            eq(100_000L, facts.demuxerDurationUs)
        }
        for (duration in listOf(C.TIME_UNSET, 99_999L, 100_001L, 0L, -1L, Long.MAX_VALUE))
            test("callback_conflicting_or_invalid_official_duration_$duration") {
                reject(WebmProbeFailure.FIELD_VALUE) { extract(F.declaredTimeline(33_333_333, 100.75, false), afterRead = {
                    it.seekMap(SeekMap.Unseekable(duration))
                }) }
            }
        test("callback_unknown_duration_cannot_later_become_known") {
            val info = F.element(F.INFO, F.uint(0x2AD7B1, 1_000_000))
            val bytes = F.fileWithBody(F.concat(info, F.tracks(F.videoTrack(1)), F.cluster(0, false, 0)), 8)
            reject(WebmProbeFailure.FIELD_VALUE) { extract(bytes, afterRead = { it.seekMap(SeekMap.Unseekable(100_000)) }) }
        }
        test("callback_unknown_duration_can_repeat") {
            val info = F.element(F.INFO, F.uint(0x2AD7B1, 1_000_000))
            val bytes = F.fileWithBody(F.concat(info, F.tracks(F.videoTrack(1)), F.cluster(0, false, 0)), 8)
            eq(null, extract(bytes, afterRead = { it.seekMap(SeekMap.Unseekable(C.TIME_UNSET)) }).demuxerDurationUs)
        }
        for (hint in listOf(null, false, true)) test("timeline_audio_inventory_preserved_hint_$hint") {
            val facts = probe(F.declaredTimeline(33_333_333, 100.75, true), expectedAudio = hint)
            eq(2, facts.tracks.size); eq(100_000L, facts.demuxerDurationUs)
            eq(33_333_333L, facts.tracks.single { it.trackType == C.TRACK_TYPE_VIDEO }.declaredDefaultDurationNs)
            val audio = facts.tracks.single { it.trackType == C.TRACK_TYPE_AUDIO }
            eq(null, audio.declaredDefaultDurationNs); eq(3, audio.initializationData.size)
            check(audio.initializationData[0].contentEquals(F.opusHead(2)))
        }
        test("official_vp8_opus_csd") {
            val f = probe(F.file(true, 0, 0, 3)); eq(2, f.tracks.size)
            val a = f.tracks.single { it.trackType == C.TRACK_TYPE_AUDIO }
            eq("audio/opus", a.mimeType); eq(48_000, a.sampleRate); eq(2, a.channelCount)
            eq(3L, a.sourceSampleCallbacks); eq(3, a.initializationData.size)
            check(a.initializationData[0].contentEquals(F.opusHead(2)))
            eq(6_500_000L, ByteBuffer.wrap(a.initializationData[1]).order(ByteOrder.LITTLE_ENDIAN).long)
            eq(80_000_000L, ByteBuffer.wrap(a.initializationData[2]).order(ByteOrder.LITTLE_ENDIAN).long)
            val mutable = a.initializationData[0]; mutable[0] = 0
            check(a.initializationData[0].contentEquals(F.opusHead(2)))
        }
        test("unknown_segment_known_clusters_actual_eof") {
            val f = probe(F.file(false, 8, 0, 3)); eq(1, f.outstandingMastersAtEof); eq(0, f.actualUnknownEndCallbacks)
        }
        test("known_segment_unknown_cluster_no_fake_ends") {
            val f = probe(F.file(false, 0, 8, 1)); eq(2, f.outstandingMastersAtEof); eq(0, f.actualUnknownEndCallbacks)
        }
        for (w in 1..8) {
            test("small_body_unknown_segment_width_$w") { eq(1, probe(F.file(false, w, 0, 3)).outstandingMastersAtEof) }
            test("small_body_unknown_cluster_width_$w") { eq(2, probe(F.file(false, 8, w, 1)).outstandingMastersAtEof) }
        }
        test("consecutive_unknown_clusters_below_cap") { eq(4, probe(F.file(false, 8, 8, 3)).outstandingMastersAtEof) }
        test("consecutive_unknown_clusters_at_16") {
            val f = probe(F.file(false, 8, 8, 15)); eq(16, f.maximumPhysicalMasterDepth); eq(16, f.outstandingMastersAtEof)
        }
        test("consecutive_unknown_clusters_17_rejected") { reject(WebmProbeFailure.MASTER_DEPTH) { probe(F.file(false, 8, 8, 16)) } }
        test("known_127_wider_vint_extent") { reject(WebmProbeFailure.MASTER_EXTENT) { probe(F.knownClusterSentinelOverrun()) } }
        test("known_child_outside_known_parent") { reject(WebmProbeFailure.MASTER_EXTENT) { probe(F.knownClusterPastKnownParent()) } }
        test("truncated_known_block") { reject(WebmProbeFailure.TRUNCATED) { probe(F.truncatedKnownChild()) } }
        test("truncated_element_header") { reject(WebmProbeFailure.TRUNCATED) { probe(F.truncatedElementHeader()) } }
        test("missing_tracks") { reject(WebmProbeFailure.TRACK_INVENTORY) { probe(F.missingTrackInventory()) } }
        test("incomplete_tracks") { reject(WebmProbeFailure.MASTER_EXTENT) { probe(F.unterminatedKnownTrackInventory()) } }
        test("third_track") { reject(WebmProbeFailure.TRACK_LIMIT) { probe(F.thirdTrack()) } }
        test("unsupported_second_track") { reject(WebmProbeFailure.TRACK_PROFILE) { probe(F.unsupportedSecondTrack()) } }
        test("duplicate_track_id") { reject(WebmProbeFailure.TRACK_ID) { probe(F.duplicateTrackNumber()) } }
        test("required_audio_missing") { reject(WebmProbeFailure.TRACK_INVENTORY) { probe(F.file(false, 8, 0, 3), expectedAudio = true) } }
        test("false_audio_hint_does_not_drop_audio") { eq(2, probe(F.file(true, 8, 0, 3), expectedAudio = false).tracks.size) }
        test("identifier_129") { reject(WebmProbeFailure.IDENTIFIER) { probe(F.oversizedCodecIdentifier()) } }
        test("configuration_65537") { reject(WebmProbeFailure.CONFIG_LIMIT) { probe(F.oversizedCodecPrivate()) } }
        test("content_encoding_before_parser") { reject(WebmProbeFailure.PROTECTED_MEDIA) { probe(F.contentEncoding()) } }
        test("official_opus_supplemental") {
            val a = probe(F.opusDiscardPadding()).tracks.single { it.trackType == C.TRACK_TYPE_AUDIO }
            check(a.supplementalBytes >= 8); eq(1L, a.sourceSampleCallbacks)
        }
        test("premature_numeric_unknown_end") { reject(WebmProbeFailure.UNKNOWN_NUMERIC_END) { probe(F.shortUnknownClusterCrossing()) } }
        test("wrapped_binary_2pow32_plus4") { rejectBeforePayload(F.wrappedBinarySize(), WebmProbeFailure.TRUNCATED) }
        test("wrapped_ignored_2pow32_plus4") { rejectBeforePayload(F.wrappedIgnoredSize(), WebmProbeFailure.TRUNCATED) }
        test("master_embedded_fake_unknown_candidate_first") { rejectBeforePayload(masterFake(), WebmProbeFailure.HEADER_CANDIDATES) }
        for (path in listOf("binary", "string", "ignored")) {
            test("ambiguous_${path}_candidate_first") { rejectBeforePayload(ambiguous(path), WebmProbeFailure.HEADER_CANDIDATES) }
            for (size in listOf(Int.MAX_VALUE.toLong(), 1L shl 31, (1L shl 32) - 1, (1L shl 32) + 5))
                test("full_long_${path}_$size") { rejectBeforePayload(wrapped(path, size), WebmProbeFailure.TRUNCATED) }
        }
        test("track_number_long_narrowing_before_super") {
            val entry = F.element(F.TRACK_ENTRY, F.concat(F.uint(0xD7, (1L shl 32) + 1), F.uint(0x83, 1),
                F.ascii(F.CODEC_ID, "V_VP8"), F.element(0xE0, F.concat(F.uint(0xB0, 16), F.uint(0xBA, 16)))))
            reject(WebmProbeFailure.TRACK_ID) { probe(F.fileWithBody(F.concat(F.info(), F.tracks(entry), F.cluster(0, false, 0)), 8)) }
        }
        for (size in listOf(127, 128, 129)) test("identifier_limit_$size") {
            val entry = F.videoTrack(1, "A".repeat(size), byteArrayOf())
            reject(if (size > 128) WebmProbeFailure.IDENTIFIER else WebmProbeFailure.TRACK_PROFILE) {
                probe(F.fileWithBody(F.concat(F.info(), F.tracks(entry), F.cluster(0, false, 0)), 8))
            }
        }
        for (size in listOf(65_535, 65_536, 65_537)) test("config_limit_$size") {
            val entry = F.videoTrack(1, "V_VP8", F.element(F.CODEC_PRIVATE, ByteArray(size)))
            val bytes = F.fileWithBody(F.concat(F.info(), F.tracks(entry), F.cluster(0, false, 0)), 8)
            if (size > 65_536) reject(WebmProbeFailure.CONFIG_LIMIT) { probe(bytes) } else probe(bytes)
        }
        test("fixed_extent_input_limit_before_read") {
            val source = Memory(F.file(false, 8, 0, 3))
            reject(WebmProbeFailure.INPUT_EXTENT) { WebmInputBinding(source, limits(source.length).copy(maxInputBytes = source.length - 1)) {} }
            eq(0, source.readCalls)
        }
        test("read_zero_progress") {
            val source = Memory(F.file(false, 8, 0, 3)).apply { zeroProgress = true }
            reject(WebmProbeFailure.ZERO_PROGRESS) { probe(source) }
        }
        test("partial_positive_reads_complete") {
            val source = Memory(F.file(false, 8, 0, 3)).apply { fragment = 1 }
            eq(3L, probe(source).tracks.single().sourceSampleCallbacks)
        }
        test("cancellation_mid_read") {
            var checkpoints = 0
            reject(WebmProbeFailure.CANCELLED) {
                WebmProfileProbe.probe(Memory(F.file(false, 8, 0, 3)), limits(200), null) {
                    if (++checkpoints == 12) throw WebmProbeException(WebmProbeFailure.CANCELLED)
                }
            }
        }
        test("deadline_before_read") {
            val source = Memory(F.file(false, 8, 0, 3))
            reject(WebmProbeFailure.DEADLINE) { WebmProfileProbe.probe(source, limits(source.length), null) {
                throw WebmProbeException(WebmProbeFailure.DEADLINE)
            } }
            eq(0, source.readCalls)
        }
        test("shared_io_budget") {
            val source = Memory(F.file(false, 8, 0, 3))
            reject(WebmProbeFailure.IO_BUDGET) { WebmProfileProbe.probe(source, limits(source.length).copy(maxIoBytes = 1), null) {} }
        }
        for (checked in listOf(false, true)) {
            val label = if (checked) "io" else "runtime"
            test("caller_checkpoint_${label}_reason_preserved") {
                val sentinel: Exception = if (checked) IOException() else IllegalStateException()
                sameFailure(sentinel) { WebmProfileProbe.probe(Memory(F.file(false, 8, 0, 3)), limits(200), null) {
                    throw sentinel
                } }
            }
            test("caller_read_${label}_reason_preserved") {
                val sentinel: Exception = if (checked) IOException() else IllegalStateException()
                val source = Memory(F.file(false, 8, 0, 3)).apply { readFailure = sentinel }
                sameFailure(sentinel) { probe(source) }
            }
            test("caller_identity_${label}_reason_preserved") {
                val sentinel: Exception = if (checked) IOException() else IllegalStateException()
                val source = Memory(F.file(false, 8, 0, 3)).apply { identityFailure = sentinel }
                sameFailure(sentinel) { probe(source) }
            }
        }
        test("actual_pinned_parser_exception_still_malformed") {
            val invalidInfo = F.element(F.INFO, F.element(0x4489, byteArrayOf(1, 2, 3)))
            reject(WebmProbeFailure.MALFORMED) { probe(F.fileWithBody(F.concat(invalidInfo,
                F.tracks(F.videoTrack(1)), F.cluster(0, false, 0)), 8)) }
        }
        test("cached_read_rechecks_revocation") {
            val source = Memory(ByteArray(32)); val binding = WebmInputBinding(source, limits(32)) {}
            val input = binding.newInput { 32L }; input.peekFully(ByteArray(8), 0, 8)
            source.revoked = true; val reads = source.readCalls
            reject(WebmProbeFailure.IDENTITY) { input.readFully(ByteArray(8), 0, 8) }; eq(reads, source.readCalls)
        }
        test("cached_peek_rechecks_extent") {
            val source = Memory(ByteArray(32)); val binding = WebmInputBinding(source, limits(32)) {}
            val input = binding.newInput { 32L }; input.peekFully(ByteArray(8), 0, 8)
            source.exposedLength = 31
            reject(WebmProbeFailure.IDENTITY) { input.resetPeekPosition() }
        }
        test("foreign_default_extractor_input_refused") {
            val source = Memory(F.file(false, 8, 0, 3)); val binding = WebmInputBinding(source, limits(source.length)) {}
            val extractor = WebmExtractorFactory(binding).create(); extractor.newInput()
            val foreign = DefaultExtractorInput(DataReader { _, _, _ -> -1 }, 0, source.length)
            reject(WebmProbeFailure.FOREIGN_INPUT) { extractor.sniff(foreign) }; eq(0, source.readCalls)
        }
        test("guarded_input_peek_never_allocates_huge") {
            val source = Memory(ByteArray(64)); val binding = WebmInputBinding(source, limits(64)) {}
            val input = binding.newInput { 64L }
            reject(WebmProbeFailure.READ_REQUEST) { input.peekFully(ByteArray(1), 0, Int.MAX_VALUE, true) }
            reject(WebmProbeFailure.READ_REQUEST) { input.advancePeekPosition(Int.MAX_VALUE, true) }
            reject(WebmProbeFailure.READ_REQUEST) { input.peek(ByteArray(1), 0, Int.MAX_VALUE) }
            eq(0, source.readCalls); eq(0L, input.peekPosition)
        }
        test("cumulative_peek_window_prevents_incremental_growth") {
            val source = Memory(ByteArray(64))
            val binding = WebmInputBinding(source, limits(64).copy(maxPeekWindowBytes = 16)) {}
            val input = binding.newInput { 64L }
            input.advancePeekPosition(8)
            val calls = source.readCalls
            reject(WebmProbeFailure.READ_REQUEST) { input.advancePeekPosition(9) }
            reject(WebmProbeFailure.READ_REQUEST) { input.peekFully(ByteArray(9), 0, 9, false) }
            reject(WebmProbeFailure.READ_REQUEST) { input.peek(ByteArray(9), 0, 9) }
            eq(calls, source.readCalls); eq(8L, input.peekPosition)
            input.resetPeekPosition(); input.advancePeekPosition(16)
            val fullCalls = source.readCalls
            reject(WebmProbeFailure.READ_REQUEST) { input.advancePeekPosition(1, true) }
            eq(fullCalls, source.readCalls); eq(16L, input.peekPosition)
        }
        for (payload in listOf(18, 19)) test("actual_pinned_sniff_window_boundary_${payload}") {
            val header = F.element(F.EBML, F.concat(F.ascii(F.DOC_TYPE, "webm"), F.element(F.VOID, ByteArray(payload))))
            val bytes = F.concat(header, F.file(false, 8, 0, 3))
            val source = Memory(bytes)
            val binding = WebmInputBinding(source, limits(source.length).copy(maxPeekWindowBytes = 32)) {}
            val extractor = WebmExtractorFactory(binding).create(); val input = extractor.newInput()
            try {
                if (payload == 18) { check(extractor.sniff(input)); eq(32L, input.peekPosition) }
                else reject(WebmProbeFailure.READ_REQUEST) { extractor.sniff(input) }
                check(source.highWater <= 32); check(input.peekPosition <= 32)
            } finally { extractor.release() }
        }
        test("actual_pinned_sniff_repeated_small_header_elements") {
            val header = F.element(F.EBML, F.concat(F.ascii(F.DOC_TYPE, "webm"),
                F.element(F.VOID, ByteArray(8)), F.element(F.VOID, ByteArray(8)),
                F.element(F.VOID, ByteArray(8)), F.element(F.VOID, ByteArray(8))))
            val source = Memory(F.concat(header, F.file(false, 8, 0, 3)))
            val binding = WebmInputBinding(source, limits(source.length).copy(maxPeekWindowBytes = 32)) {}
            val extractor = WebmExtractorFactory(binding).create(); val input = extractor.newInput()
            try {
                reject(WebmProbeFailure.READ_REQUEST) { extractor.sniff(input) }
                check(source.highWater <= 32); check(input.peekPosition <= 32)
            } finally { extractor.release() }
        }
        test("guarded_input_read_skip_overloads_predelegate") {
            val source = Memory(ByteArray(64)); val binding = WebmInputBinding(source, limits(64)) {}
            val input = binding.newInput { 64L }
            reject(WebmProbeFailure.READ_REQUEST) { input.read(ByteArray(1), 0, Int.MAX_VALUE) }
            reject(WebmProbeFailure.READ_REQUEST) { input.readFully(ByteArray(1), 0, Int.MAX_VALUE, false) }
            reject(WebmProbeFailure.READ_REQUEST) { input.readFully(ByteArray(1), 0, Int.MAX_VALUE) }
            reject(WebmProbeFailure.READ_REQUEST) { input.skip(Int.MAX_VALUE) }
            reject(WebmProbeFailure.READ_REQUEST) { input.skipFully(Int.MAX_VALUE, false) }
            reject(WebmProbeFailure.READ_REQUEST) { input.skipFully(Int.MAX_VALUE) }
            eq(0, source.readCalls); eq(0L, input.position)
        }
        test("guarded_input_exact_eof_and_one_past") {
            val source = Memory(ByteArray(8)); val binding = WebmInputBinding(source, limits(8)) {}
            val input = binding.newInput { 8L }
            reject(WebmProbeFailure.TRUNCATED) { input.peekFully(ByteArray(9), 0, 9, true) }
            eq(0, source.readCalls)
            input.readFully(ByteArray(8), 0, 8)
            check(!input.readFully(ByteArray(1), 0, 1, true)); eq(-1, input.read(ByteArray(1), 0, 1))
            reject(WebmProbeFailure.TRUNCATED) { input.readFully(ByteArray(1), 0, 1, false) }
        }
        test("known_parent_bound_before_buffered_read") {
            val source = Memory(ByteArray(8)); val binding = WebmInputBinding(source, limits(8)) {}
            val input = binding.newInput { 2L }
            reject(WebmProbeFailure.TRUNCATED) { input.readFully(ByteArray(3), 0, 3) }; eq(0, source.readCalls)
        }
        test("reset_peek_does_not_reset_budget") {
            val source = Memory(ByteArray(16)); val binding = WebmInputBinding(source, limits(16).copy(maxIoBytes = 20)) {}
            val input = binding.newInput { 16L }; input.peekFully(ByteArray(8), 0, 8)
            val charged = binding.chargedBytes; input.resetPeekPosition(); eq(charged, binding.chargedBytes)
            reject(WebmProbeFailure.IO_BUDGET) { input.peekFully(ByteArray(8), 0, 8) }
        }
        test("all_elements_and_samples_share_one_cap") {
            val bytes = F.file(true, 8, 0, 3)
            reject(WebmProbeFailure.ELEMENT_LIMIT) { WebmProfileProbe.probe(Memory(bytes), limits(bytes.size.toLong()).copy(maxElementsAndSamples = 3), null) {} }
        }
        val out = File(System.getProperty("esv.webm.output")); out.mkdirs()
        File(out, "results.json").writeText("{\"passed\":$passed,\"failed\":$failed,\"productionAdmission\":false,\"nativeDecodeTested\":false,\"results\":[${records.joinToString(",")}]}")
        println("PRODUCTION_PINNED_HOST passed=$passed failed=$failed productionAdmission=false nativeDecodeTested=false")
        check(failed == 0) { "Host tests failed" }
    }

    private class Memory(val bytes: ByteArray) : WebmOwnedInput {
        var exposedLength = bytes.size.toLong(); override val length get() = exposedLength
        var readFailure: Exception? = null
        var identityFailure: Exception? = null
        var highWater = 0L
        var readCalls = 0; var zeroProgress = false; var revoked = false; var fragment = Int.MAX_VALUE
        override fun validateIdentity() {
            identityFailure?.let { throw it }
            requireWebm(!revoked, WebmProbeFailure.IDENTITY)
        }
        override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int): Int {
            readCalls++; readFailure?.let { throw it }; if (zeroProgress) return 0
            val count = minOf(fragment, length, bytes.size - offset.toInt())
            if (count <= 0) return -1
            bytes.copyInto(target, targetOffset, offset.toInt(), offset.toInt() + count)
            highWater = maxOf(highWater, offset + count); return count
        }
    }
    private fun withVideoFields(fields: ByteArray) = F.fileWithBody(F.concat(F.info(),
        F.tracks(F.videoTrack(1, "V_VP8", fields)), F.cluster(0, false, 0)), 8)
    /** Actual parser; optional callback injection is explicitly only an observation-policy test. */
    private fun extract(bytes: ByteArray, sink: TrackOutput = DummyTrack,
        afterRead: (ExtractorOutput) -> Unit = {}): WebmTransportFacts {
        val source = Memory(bytes); val binding = WebmInputBinding(source, limits(source.length)) {}
        val extractor = WebmExtractorFactory(binding).create(); val input = extractor.newInput()
        try {
            check(extractor.sniff(input)); input.resetPeekPosition()
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = sink
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            })
            while (extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) Unit
            val observed = extractor.javaClass.getDeclaredField("output").apply { isAccessible = true }.get(extractor) as ExtractorOutput
            afterRead(observed)
            return extractor.finish()
        } finally { extractor.release() }
    }
    private fun limits(size: Long) = WebmProbeLimits(maxIoBytes = size * 32 + 262_144)
    private fun probe(bytes: ByteArray, expectedAudio: Boolean? = null) =
        WebmProfileProbe.probe(Memory(bytes), limits(bytes.size.toLong()), expectedAudio) {}
    private fun probe(source: Memory) = WebmProfileProbe.probe(source, limits(source.length), null) {}
    private fun rejectBeforePayload(bytes: ByteArray, code: WebmProbeFailure) {
        val source = Memory(bytes); val binding = WebmInputBinding(source, limits(source.length)) {}
        val extractor = WebmExtractorFactory(binding).create(); val input = extractor.newInput()
        try {
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = DummyTrack
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            })
            reject(code) { while (extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) Unit; extractor.finish() }
            check(binding.headerChecks > 0); eq(0L, binding.payloadDelegations)
        } finally { extractor.release() }
    }
    private object DummyTrack : TrackOutput {
        override fun format(format: Format) = Unit
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = input.read(ByteArray(length), 0, length)
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
    }
    private fun wrapped(path: String, size: Long): ByteArray {
        val payload = if (path == "string") "V_VP8".toByteArray() else byteArrayOf(1, 2, 3, 4, 5)
        val id = if (path == "binary") F.CODEC_PRIVATE else if (path == "string") F.CODEC_ID else F.VOID
        return pathFile(path, F.declared(id, size, 5, payload))
    }
    private fun ambiguous(path: String): ByteArray {
        val id = if (path == "binary") F.CODEC_PRIVATE else if (path == "string") F.CODEC_ID else F.VOID
        val size = if (path == "binary") byteArrayOf(1, 0, 0x63, 0xA2.toByte(), 0x10, 0, 0, 4)
            else byteArrayOf(1, 0, 0, 0, id.toByte(), 0x20, 0, 5)
        val payload = if (path == "string") "V_VP8".toByteArray() else if (path == "binary") byteArrayOf(1, 2, 3, 4)
            else byteArrayOf(1, 2, 3, 4, 5)
        return pathFile(path, F.concat(F.id(id), size, payload))
    }
    private fun pathFile(path: String, field: ByteArray): ByteArray {
        val body = when (path) {
            "ignored" -> F.concat(F.info(), F.tracks(F.videoTrack(1)), field, F.cluster(0, false, 0))
            "binary" -> F.concat(F.info(), F.tracks(F.videoTrack(1, "V_VP8", field)), F.cluster(0, false, 0))
            else -> F.concat(F.info(), F.tracks(F.element(F.TRACK_ENTRY, F.concat(F.uint(0xD7, 1), F.uint(0x83, 1),
                field, F.element(0xE0, F.concat(F.uint(0xB0, 16), F.uint(0xBA, 16)))))), F.cluster(0, false, 0))
        }
        return F.fileWithBody(body, 8)
    }
    private fun masterFake() = F.concat(F.header(), F.id(F.SEGMENT),
        byteArrayOf(1, 0x18, 0x53, 0x80.toByte(), 0x67, 0x3F, 0xFF.toByte(), 0xFF.toByte()),
        F.info(), F.tracks(F.videoTrack(1)), F.cluster(0, false, 0))
    private fun sameFailure(expected: Exception, action: () -> Unit) {
        try { action() } catch (actual: Exception) { check(actual === expected); return }
        error("Expected original caller failure")
    }
    private fun reject(expected: WebmProbeFailure, action: () -> Unit) {
        try { action() } catch (e: WebmProbeException) { eq(expected, e.failure); return }
        error("Expected technical rejection $expected")
    }
    private fun eq(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected, actual $actual" } }
    private fun test(name: String, action: () -> Unit) {
        try {
            action(); passed++; println("PASS $name"); records += "{\"name\":\"$name\",\"passed\":true}"
        } catch (e: Exception) {
            failed++; println("FAIL $name ${e.javaClass.simpleName} " + if (e is WebmProbeException) e.failure.name else "assertion")
            records += "{\"name\":\"$name\",\"passed\":false,\"failureType\":\"${e.javaClass.simpleName}\"}"
        }
    }
}
