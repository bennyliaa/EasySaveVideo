package com.easysavevideo.app.save.conversion

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import com.easysavevideo.research.webm.OriginalWebmFixtures as F
import java.io.File
import java.security.MessageDigest

/** Permanent tests in the existing pinned host. No FFmpeg/runtime generation dependency. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object ProgressiveSdrWebmTest {
    private const val COLOUR = 0x55B0
    private var passed = 0
    private var failed = 0
    private val records = mutableListOf<String>()
    @JvmStatic fun main(args: Array<String>) {
        val output = File(System.getProperty("esv.webm.output"))
        check(File(MatroskaExtractor::class.java.protectionDomain.codeSource.location.toURI()).canonicalFile ==
            File(output, "jars/media3-extractor-1.11.1.jar").canonicalFile)
        check(File(WebmProfileProbe::class.java.protectionDomain.codeSource.location.toURI()).canonicalFile ==
            File(output, "classes").canonicalFile)
        test("absent_and_explicit_undetermined_keep_same_format") {
            val absent = extract(video(byteArrayOf()))
            val explicit = extract(video(F.uint(0x9A, 0)))
            check(absent == explicit)
        }
        for (value in listOf(0L, 2L)) test("interlace_allowed_$value") { probe(video(F.uint(0x9A, value))) }
        for (value in listOf(1L, 3L, 255L, 0x1_0000_0002L, Long.MAX_VALUE))
            negative("interlace_reject_$value", video(F.uint(0x9A, value)), WebmProbeFailure.TRACK_PROFILE)
        negative("interlace_duplicate", video(F.concat(F.uint(0x9A, 2), F.uint(0x9A, 2))), WebmProbeFailure.DUPLICATE_FIELD)
        negative("interlace_track_entry_context", video(byteArrayOf(), F.uint(0x9A, 2)), WebmProbeFailure.ELEMENT_CONTEXT)
        negative("interlace_info_context", withInfo(F.uint(0x9A, 2)), WebmProbeFailure.ELEMENT_CONTEXT)
        test("video_fields_before_track_type") { probe(video(F.concat(F.uint(0x9A, 2), colour(F.uint(0x55B9, 1))), videoFirst = true)) }

        for (range in listOf(1L, 2L)) test("range_only_preserves_unknown_$range") {
            val format = extract(video(colour(F.uint(0x55B9, range))))
            val info = checkNotNull(format.colorInfo)
            eq(if (range == 1L) C.COLOR_RANGE_LIMITED else C.COLOR_RANGE_FULL, info.colorRange)
            eq(Format.NO_VALUE, info.colorTransfer); eq(Format.NO_VALUE, info.colorSpace)
        }
        for (range in listOf(1L, 2L)) for (transfer in listOf(1L, 6L)) for (primaries in listOf(1L, 5L, 6L))
            test("pinned_format_r${range}_t${transfer}_p$primaries") {
                val format = extract(video(colour(F.concat(F.uint(0x55B9, range), F.uint(0x55BA, transfer), F.uint(0x55BB, primaries)))))
                val info = checkNotNull(format.colorInfo)
                eq(if (range == 1L) C.COLOR_RANGE_LIMITED else C.COLOR_RANGE_FULL, info.colorRange)
                eq(C.COLOR_TRANSFER_SDR, info.colorTransfer)
                eq(ColorInfo.isoColorPrimariesToColorSpace(primaries.toInt()), info.colorSpace)
                eq(null, info.hdrStaticInfo)
            }
        val invalid = mapOf(0x55B9 to listOf(0L, 3L, 255L, 0x1_0000_0001L),
            0x55BA to listOf(0L, 2L, 3L, 4L, 7L, 13L, 16L, 18L, 0x1_0000_0001L),
            0x55BB to listOf(0L, 2L, 3L, 4L, 7L, 9L, 0x1_0000_0001L))
        for ((id, values) in invalid) for (value in values)
            negative("colour_value_${id}_$value", video(colour(F.uint(id, value))), WebmProbeFailure.TRACK_PROFILE)
        for (id in listOf(0x55B9, 0x55BA, 0x55BB)) {
            negative("colour_duplicate_$id", video(colour(F.concat(F.uint(id, 1), F.uint(id, 1)))), WebmProbeFailure.DUPLICATE_FIELD)
            negative("colour_scalar_video_context_$id", video(F.uint(id, 1)), WebmProbeFailure.ELEMENT_CONTEXT)
            negative("colour_scalar_track_context_$id", video(byteArrayOf(), F.uint(id, 1)), WebmProbeFailure.ELEMENT_CONTEXT)
            negative("colour_scalar_info_context_$id", withInfo(F.uint(id, 1)), WebmProbeFailure.ELEMENT_CONTEXT)
        }
        val range = colour(F.uint(0x55B9, 1))
        negative("colour_master_duplicate", video(F.concat(range, range)), WebmProbeFailure.DUPLICATE_FIELD)
        negative("colour_master_track_context", video(byteArrayOf(), range), WebmProbeFailure.ELEMENT_CONTEXT)
        negative("colour_master_info_context", withInfo(range), WebmProbeFailure.ELEMENT_CONTEXT)
        negative("colour_nested", video(colour(range)), WebmProbeFailure.ELEMENT_CONTEXT)
        negative("colour_empty", video(colour(byteArrayOf())), WebmProbeFailure.FIELD_VALUE)
        negative("colour_unknown_size", video(F.unknownMaster(COLOUR, 1, F.uint(0x55B9, 1))), WebmProbeFailure.UNKNOWN_SIZE)
        negative("colour_extent_overrun", video(F.declared(COLOUR, 20, 1, F.uint(0x55B9, 1))), WebmProbeFailure.MASTER_EXTENT)
        for (id in listOf(0x55B1, 0x55B2, 0x55BC, 0x55BD, 0xB0, 0x83, 0x9A))
            negative("colour_reject_nonprofile_child_$id", video(colour(F.uint(id, 1))), WebmProbeFailure.UNSUPPORTED_ELEMENT)
        negative("colour_reject_codec_private_child", video(colour(F.element(F.CODEC_PRIVATE, F.opusHead(1)))), WebmProbeFailure.UNSUPPORTED_ELEMENT)
        negative("colour_mastering_rejected", video(colour(F.element(0x55D0, byteArrayOf()))), WebmProbeFailure.UNSUPPORTED_ELEMENT)
        test("colour_global_void_crc_bounded") {
            probe(video(colour(F.concat(F.uint(0x55B9, 1), F.element(0xEC, byteArrayOf(0)), F.element(0xBF, ByteArray(4))))))
        }
        for ((name, fields) in listOf("flag" to F.uint(0x9A, 2), "colour" to range))
            negative("video_${name}_in_actual_opus_track", opusWithVideo(fields), WebmProbeFailure.TRACK_PROFILE)
        for (depth in listOf(1L, 8L, 16L, 24L, 32L)) test("audio_bit_depth_$depth") {
            val facts = probe(audio(F.uint(0x6264, depth)))
            eq("audio/opus", facts.tracks.single { it.trackType == C.TRACK_TYPE_AUDIO }.mimeType)
        }
        test("audio_bit_depth_before_track_type") { probe(audio(F.uint(0x6264, 16), audioFirst = true)) }
        test("audio_bit_depth_does_not_set_decoded_pcm_precision") {
            val absent = extract(audio(byteArrayOf()))
            val declared = extract(audio(F.uint(0x6264, 16)))
            eq(absent, declared); eq(Format.NO_VALUE, declared.pcmEncoding)
        }
        for (depth in listOf(0L, 33L, 64L, 0x1_0000_0010L, Long.MAX_VALUE))
            negative("audio_bit_depth_reject_$depth", audio(F.uint(0x6264, depth)), WebmProbeFailure.TRACK_PROFILE)
        negative("audio_bit_depth_duplicate", audio(F.concat(F.uint(0x6264, 16), F.uint(0x6264, 16))), WebmProbeFailure.DUPLICATE_FIELD)
        negative("audio_bit_depth_video_context", video(F.uint(0x6264, 16)), WebmProbeFailure.ELEMENT_CONTEXT)
        negative("audio_bit_depth_track_context", video(byteArrayOf(), F.uint(0x6264, 16)), WebmProbeFailure.ELEMENT_CONTEXT)
        negative("audio_bit_depth_info_context", withInfo(F.uint(0x6264, 16)), WebmProbeFailure.ELEMENT_CONTEXT)
        negative("audio_bit_depth_in_actual_video", video(byteArrayOf(), F.element(0xE1, F.uint(0x6264, 16))), WebmProbeFailure.TRACK_PROFILE)
        for (transfer in listOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG, C.COLOR_TRANSFER_GAMMA_2_2))
            test("final_format_gate_rejects_$transfer") {
                extract(video(range)) { extractor, format ->
                    val observed = extractor.javaClass.getDeclaredField("output").apply { isAccessible = true }.get(extractor)
                    val sinks = observed.javaClass.getDeclaredField("sinks").apply { isAccessible = true }.get(observed) as Map<*, *>
                    val sink = sinks.values.single() as TrackOutput
                    reject(WebmProbeFailure.TRACK_PROFILE) {
                        sink.format(format.buildUpon().setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build()).build())
                    }
                }
            }

        val fixtures = File(System.getProperty("esv.webm.fixtures"))
        val originals = linkedMapOf(
            "aligned_1s" to "3b06438e3a1037bb449dd4ad6ce01fb996f72eccb32276af220bf0c6b756e5d0",
            "nonaligned_1007ms" to "90a290a441eb3fc0050e246983c76ae497d7d7ace5d9075b3b5cbea67f3b41e8",
            "short_audio_600ms" to "1772ddf33feec0cb198e9736cb2ced7838e0e76fc8746243fe15cdcfafe97919")
        for ((name, sha) in originals) test("unchanged_original_transport_$name") {
            val bytes = File(fixtures, "$name.webm").readBytes(); eq(sha, digest(bytes))
            val facts = probe(bytes)
            eq(2, facts.tracks.size)
            eq("audio/opus", facts.tracks.single { it.trackType == C.TRACK_TYPE_AUDIO }.mimeType)
            eq(if (name == "nonaligned_1007ms") 27L else 25L,
                facts.tracks.single { it.trackType == C.TRACK_TYPE_VIDEO }.sourceSampleCallbacks)
            eq(if (name == "short_audio_600ms") 31L else 51L,
                facts.tracks.single { it.trackType == C.TRACK_TYPE_AUDIO }.sourceSampleCallbacks)
            val expectedDuration = when (name) { "aligned_1s" -> 1_008_000L; "nonaligned_1007ms" -> 1_080_000L; else -> 1_000_000L }
            eq(expectedDuration, facts.declaredContainerDurationUs); eq(expectedDuration, facts.demuxerDurationUs)
        }
        val base = File(fixtures, "aligned_1s.webm").readBytes()
        eq(originals.getValue("aligned_1s"), digest(base))
        check(base.copyOfRange(306, 324).contentEquals(hex("e090b08140ba81409a810255b08455b98101")))
        check(base.copyOfRange(399, 403).contentEquals(hex("62648110")))
        // Every derived fixture is an in-memory, same-extent mutation of this exact original.
        val control = base.copyOf()
        test("real_derived_control_is_parseable") { probe(control) }
        test("real_derived_full_range") { probe(mutate(control, 323, 2)) }
        test("real_derived_explicit_undetermined") { probe(mutate(control, 316, 0)) }
        negative("real_derived_interlaced", mutate(control, 316, 1), WebmProbeFailure.TRACK_PROFILE)
        negative("real_derived_interlaced_invalid", mutate(control, 316, 3), WebmProbeFailure.TRACK_PROFILE)
        negative("real_derived_range_unspecified", mutate(control, 323, 0), WebmProbeFailure.TRACK_PROFILE)
        negative("real_derived_range_invalid", mutate(control, 323, 3), WebmProbeFailure.TRACK_PROFILE)
        negative("real_derived_hdr_transfer", mutate(mutate(control, 321, 0xBA), 323, 16), WebmProbeFailure.TRACK_PROFILE)
        negative("real_derived_colour_unknown_size", mutate(control, 319, 0xFF), WebmProbeFailure.UNKNOWN_SIZE)
        negative("real_derived_colour_overrun", mutate(control, 319, 0x85), WebmProbeFailure.MASTER_EXTENT)
        negative("real_derived_matrix_unsupported", mutate(control, 321, 0xB1), WebmProbeFailure.UNSUPPORTED_ELEMENT)
        negative("real_derived_duplicate_flag", control.copyOf().apply { hex("9a8102ec820000").copyInto(this, 317) }, WebmProbeFailure.DUPLICATE_FIELD)
        negative("real_derived_flag_audio_context", mutate(control, 306, 0xE1), WebmProbeFailure.ELEMENT_CONTEXT)
        negative("real_derived_colour_audio_context", mutate(control, 306, 0xE1).apply { hex("ec8100").copyInto(this, 314) }, WebmProbeFailure.ELEMENT_CONTEXT)
        negative("real_derived_range_video_context", control.copyOf().apply { hex("ec8100").copyInto(this, 317) }, WebmProbeFailure.ELEMENT_CONTEXT)
        negative("real_derived_audio_bitdepth_zero", mutate(control, 402, 0), WebmProbeFailure.TRACK_PROFILE)
        negative("real_derived_audio_bitdepth_33", mutate(control, 402, 33), WebmProbeFailure.TRACK_PROFILE)
        eq(originals.getValue("aligned_1s"), digest(File(fixtures, "aligned_1s.webm").readBytes()))
        output.mkdirs()
        File(output, "progressive-sdr-results.json").writeText("{\"passed\":$passed,\"failed\":$failed,\"nativeDecodeTested\":false,\"results\":[${records.joinToString(",")}]}\n")
        println("PROGRESSIVE_SDR_PINNED_HOST passed=$passed failed=$failed nativeDecodeTested=false")
        check(failed == 0)
    }

    private fun colour(fields: ByteArray) = F.element(COLOUR, fields)
    private fun video(fields: ByteArray, tail: ByteArray = byteArrayOf(), videoFirst: Boolean = false): ByteArray {
        val video = F.element(0xE0, F.concat(F.uint(0xB0, 16), F.uint(0xBA, 16), fields))
        val trackFields = F.concat(F.uint(0xD7, 1), F.uint(0x83, 1), F.ascii(F.CODEC_ID, "V_VP8"))
        val entry = F.element(F.TRACK_ENTRY, if (videoFirst) F.concat(video, trackFields, tail) else F.concat(trackFields, video, tail))
        return F.fileWithBody(F.concat(F.info(), F.tracks(entry), F.cluster(0, false, 0)), 8)
    }
    private fun withInfo(fields: ByteArray) = F.fileWithBody(F.concat(
        F.element(F.INFO, F.concat(F.uint(0x2AD7B1, 1_000_000), fields)), F.tracks(F.videoTrack(1)), F.cluster(0, false, 0)), 8)
    private fun opusWithVideo(fields: ByteArray): ByteArray {
        val entry = F.element(F.TRACK_ENTRY, F.concat(F.element(0xE0, fields),
            F.uint(0xD7, 1), F.uint(0x83, 2), F.ascii(F.CODEC_ID, "A_OPUS"), F.element(F.CODEC_PRIVATE, F.opusHead(1)),
            F.element(0xE1, F.concat(F.uint(0x9F, 1), F.floating(0xB5, 48_000.0)))))
        return F.fileWithBody(F.concat(F.info(), F.tracks(entry), F.cluster(0, false, 0)), 8)
    }
    private fun audio(fields: ByteArray, audioFirst: Boolean = false): ByteArray {
        val audio = F.element(0xE1, F.concat(F.uint(0x9F, 1), F.floating(0xB5, 48_000.0), fields))
        val track = F.concat(F.uint(0xD7, 2), F.uint(0x83, 2), F.ascii(F.CODEC_ID, "A_OPUS"), F.element(F.CODEC_PRIVATE, F.opusHead(1)))
        val entry = F.element(F.TRACK_ENTRY, if (audioFirst) F.concat(audio, track) else F.concat(track, audio))
        return F.fileWithBody(F.concat(F.info(), F.tracks(F.videoTrack(1), entry), F.cluster(0, true, 0)), 8)
    }
    private class Memory(private val bytes: ByteArray) : WebmOwnedInput {
        override val length = bytes.size.toLong()
        override fun validateIdentity() = Unit
        override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int): Int {
            bytes.copyInto(target, targetOffset, offset.toInt(), offset.toInt() + length); return length
        }
    }
    private fun checkpoint(): () -> Unit {
        val deadline = System.nanoTime() + 10_000_000_000L
        return { if (Thread.currentThread().isInterrupted) throw WebmProbeException(WebmProbeFailure.CANCELLED)
            if (System.nanoTime() > deadline) throw WebmProbeException(WebmProbeFailure.DEADLINE) }
    }
    private fun limits(bytes: ByteArray) = WebmProbeLimits(maxIoBytes = bytes.size * 64L + 262_144)
    private fun probe(bytes: ByteArray) = WebmProfileProbe.probe(Memory(bytes), limits(bytes), null, checkpoint())
    private fun extract(bytes: ByteArray, after: (BoundedWebmExtractor, Format) -> Unit = { _, _ -> }): Format {
        val extractor = WebmExtractorFactory(WebmInputBinding(Memory(bytes), limits(bytes), checkpoint())).create()
        val input = extractor.newInput(); var format: Format? = null
        val sink = object : TrackOutput by Discard {
            override fun format(value: Format) { format = value }
        }
        try {
            check(extractor.sniff(input)); input.resetPeekPosition()
            extractor.init(output(sink))
            while (extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) Unit
            val result = checkNotNull(format); after(extractor, result); extractor.finish(); return result
        } finally { extractor.release() }
    }
    private fun output(sink: TrackOutput) = object : ExtractorOutput {
        override fun track(id: Int, type: Int) = sink
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }
    private object Discard : TrackOutput {
        override fun format(format: Format) = Unit
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = input.read(ByteArray(minOf(length, 4096)), 0, minOf(length, 4096))
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
    }
    private fun mutate(bytes: ByteArray, offset: Int, value: Int) = bytes.copyOf().apply { this[offset] = value.toByte() }
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun eq(expected: Any?, actual: Any?) { check(expected == actual) }
    private fun reject(code: WebmProbeFailure, action: () -> Unit) {
        try { action() } catch (e: WebmProbeException) { eq(code, e.failure); return }; error("Expected technical rejection")
    }
    private fun negative(name: String, bytes: ByteArray, code: WebmProbeFailure) = test(name) { reject(code) { probe(bytes) } }
    private fun test(name: String, action: () -> Unit) {
        try { action(); passed++; records += "{\"name\":\"$name\",\"passed\":true}"; println("PASS $name") }
        catch (e: Exception) { failed++; records += "{\"name\":\"$name\",\"passed\":false,\"type\":\"${e.javaClass.simpleName}\"}"; println("FAIL $name ${e.javaClass.simpleName}") }
    }
}
