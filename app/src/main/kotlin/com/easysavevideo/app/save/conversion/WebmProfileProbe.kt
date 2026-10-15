package com.easysavevideo.app.save.conversion

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.IOException
import java.util.Collections

/** Transport observations only; no compressed packet is claimed to be a displayed/decoded frame. */
internal class WebmTrackTransport(
    val trackNumber: Int,
    val trackType: Int,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sampleRate: Int,
    val channelCount: Int,
    initializationData: List<ByteArray>,
    val sourceSampleCallbacks: Long,
    val firstSourceTimestampUs: Long,
    val lastSourceTimestampUs: Long,
    val supplementalBytes: Long,
    /** Validated video TrackEntry declaration, never inferred from Format or sample callbacks. */
    val declaredDefaultDurationNs: Long?,
) {
    private val configuration = initializationData.map { it.clone() }
    val initializationData: List<ByteArray> get() = configuration.map { it.clone() }
}

/** Not a storage capability, decoder/CFR proof, playable-output proof or publication authority. */
internal class WebmTransportFacts(
    val inputExtent: Long,
    val declaredContainerDurationUs: Long?,
    tracks: List<WebmTrackTransport>,
    val maximumPhysicalMasterDepth: Int,
    val outstandingMastersAtEof: Int,
    val actualUnknownEndCallbacks: Int,
    val checkedHeaders: Long,
    val delegatedPayloads: Long,
    /** Official SeekMap value; null means absent/unknown, independently of container rounding. */
    val demuxerDurationUs: Long?,
    /** Actual bounded parser TimecodeScale, including its specified default when absent. */
    val timecodeScaleNs: Long = 1_000_000L,
) {
    val tracks: List<WebmTrackTransport> = Collections.unmodifiableList(ArrayList(tracks))
}

/**
 * Local synchronous borrowed preflight, currently unconnected to Direct/storage/native admission.
 * checkpoint has no no-op default: its caller must enforce the operation's cancellation/deadline.
 * No source/output descriptor is opened or closed, no path/URI/DataSource is accepted, no thread is
 * scheduled, and no production Transformer input factory is issued here.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object WebmProfileProbe {
    fun probe(input: WebmOwnedInput, limits: WebmProbeLimits, expectedAudio: Boolean?, checkpoint: () -> Unit): WebmTransportFacts {
        var extractor: BoundedWebmExtractor? = null
        try {
            val binding = WebmInputBinding(input, limits, checkpoint)
            val current = WebmExtractorFactory(binding).create().also { extractor = it }
            val guarded = current.newInput()
            requireWebm(current.sniff(guarded), WebmProbeFailure.TRACK_PROFILE)
            guarded.resetPeekPosition()
            current.init(DiscardOutput)
            val seek = PositionHolder()
            while (true) {
                binding.check()
                when (current.read(guarded, seek)) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_END_OF_INPUT -> break
                    else -> throw WebmProbeException(WebmProbeFailure.SEEK_UNSUPPORTED)
                }
            }
            val facts = current.finish()
            requireWebm(expectedAudio != true || facts.tracks.any { it.trackType == C.TRACK_TYPE_AUDIO },
                WebmProbeFailure.TRACK_INVENTORY)
            binding.check()
            return facts
        } catch (e: WebmCallerFailure) {
            throw e.original
        } catch (e: WebmProbeException) {
            throw e
        } catch (_: IOException) {
            throw WebmProbeException(WebmProbeFailure.MALFORMED)
        } catch (_: RuntimeException) {
            throw WebmProbeException(WebmProbeFailure.MALFORMED)
        } finally {
            extractor?.release() // Matroska has no source descriptor; the borrowed caller FD stays open.
        }
    }

    private object DiscardOutput : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            private val scratch = ByteArray(4096)
            override fun format(format: Format) = Unit
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
                input.read(scratch, 0, minOf(scratch.size, length))
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }
}
