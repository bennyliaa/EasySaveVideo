package com.easysavevideo.app.editor.engine

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.container.NalUnitUtil
import androidx.media3.container.ParsableNalUnitBitArray
import androidx.media3.extractor.AacUtil
import com.easysavevideo.library.EditInputRef
import com.easysavevideo.library.edit.*
import java.security.MessageDigest
import kotlin.math.abs

/** Every byte and video timestamp is inspected through a currently leased catalog descriptor. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object EditorMetadataProbe {
    fun inspect(ref: EditInputRef, session: EditorSession): EditSourceMetadata {
        session.checkpoint()
        session.retain(ref)
        val input = session.openInput(ref)
        val extractor = MediaExtractor()
        session.probeExtractor = extractor
        try {
            if (input.sizeBytes !in 1..EditLimits.MAX_SOURCE_BYTES || input.stream.channel.size() != input.sizeBytes) reject()
            val digest = MessageDigest.getInstance("SHA-256")
            val scratch = ByteArray(64 * 1024)
            var bytes = 0L
            while (bytes < input.sizeBytes) {
                session.checkpoint()
                val read = input.stream.read(scratch, 0, minOf(scratch.size.toLong(), input.sizeBytes - bytes).toInt())
                if (read <= 0) reject()
                bytes += read; digest.update(scratch, 0, read)
            }
            if (input.stream.read() != -1 || digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) } != input.sha256) reject()
            input.stream.channel.position(0)
            extractor.setDataSource(input.stream.fd, 0, input.sizeBytes)
            if (extractor.trackCount !in 1..2 || extractor.drmInitData != null || extractor.psshInfo?.isNotEmpty() == true) reject()
            var video: MediaFormat? = null
            var audio: EditAudioProfile = EditAudioProfile.Silent
            var videoIndex = -1
            for (index in 0 until extractor.trackCount) {
                session.checkpoint()
                val format = extractor.getTrackFormat(index)
                when (format.getString(MediaFormat.KEY_MIME)) {
                    "video/avc" -> { if (video != null) reject(); video = format; videoIndex = index }
                    "audio/mp4a-latm" -> {
                        if (audio != EditAudioProfile.Silent) reject()
                        val config = AacUtil.parseAudioSpecificConfig(csd(format, "csd-0"))
                        val rate = integer(format, MediaFormat.KEY_SAMPLE_RATE)
                        val channels = integer(format, MediaFormat.KEY_CHANNEL_COUNT)
                        if (config.codecs != "mp4a.40.2" || config.sampleRateHz != rate || config.channelCount != channels ||
                            rate !in setOf(32_000, 44_100, 48_000) || channels !in 1..2) reject()
                        audio = EditAudioProfile.Aac(rate, channels)
                    }
                    else -> reject()
                }
                extractor.selectTrack(index)
            }
            val format = video ?: reject()
            val width = integer(format, MediaFormat.KEY_WIDTH)
            val height = integer(format, MediaFormat.KEY_HEIGHT)
            val rotation = integer(format, MediaFormat.KEY_ROTATION, 0)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (width !in 2..EditLimits.MAX_DIMENSION || height !in 2..EditLimits.MAX_DIMENSION ||
                width.toLong() * height > EditLimits.MAX_PIXELS || rotation !in setOf(0, 90, 180, 270) ||
                duration !in 1..EditLimits.MAX_SOURCE_DURATION_US) reject()
            val spsBytes = csd(format, "csd-0")
            val offset = spsOffset(spsBytes) ?: reject()
            val sps = NalUnitUtil.parseSpsNalUnit(spsBytes, offset, spsBytes.size)
            val profile = when (sps.profileIdc) { 66 -> EditAvcProfile.BASELINE; 77 -> EditAvcProfile.MAIN; 100 -> EditAvcProfile.HIGH; else -> reject() }
            if (sps.width != width || sps.height != height || !sps.frameMbsOnlyFlag || sps.bitDepthLumaMinus8 != 0 ||
                sps.bitDepthChromaMinus8 != 0 || sps.separateColorPlaneFlag || sps.pixelWidthHeightRatio != 1f) reject()
            if (profile == EditAvcProfile.HIGH) {
                val bits = ParsableNalUnitBitArray(spsBytes, offset + 1, spsBytes.size)
                bits.skipBits(24); bits.readUnsignedExpGolombCodedInt()
                if (bits.readUnsignedExpGolombCodedInt() != 1) reject() // 4:2:0 only.
            }
            val color = integer(format, MediaFormat.KEY_COLOR_TRANSFER, sps.colorTransfer)
            if (color !in setOf(C.COLOR_TRANSFER_SDR, MediaFormat.COLOR_TRANSFER_SDR_VIDEO) ||
                sps.colorTransfer !in setOf(C.COLOR_TRANSFER_SDR, -1)) reject()
            if (format.containsKey("sar-width") && integer(format, "sar-width") != integer(format, "sar-height")) reject()
            for (key in listOf("crop-left", "crop-top")) if (integer(format, key, 0) != 0) reject()
            if (integer(format, "crop-right", width - 1) != width - 1 || integer(format, "crop-bottom", height - 1) != height - 1) reject()
            val pts = LongArray((duration * EditLimits.MAX_FRAME_RATE / 1_000_000L + 2).toInt())
            var videoCount = 0
            var samples = 0
            var lastAudio: Long? = null
            var firstAudio: Long? = null
            while (extractor.sampleTrackIndex != -1) {
                session.checkpoint()
                if (++samples > EditorEnginePolicy.MAX_SOURCE_SAMPLES || extractor.sampleSize !in 1..EditorEnginePolicy.MAX_SAMPLE_BYTES.toLong() ||
                    extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) reject()
                val time = extractor.sampleTime
                if (time > duration + 1_000_000) reject()
                if (extractor.sampleTrackIndex == videoIndex) {
                    if (time < 0 || videoCount == pts.size) reject()
                    pts[videoCount++] = time
                } else {
                    val aac = audio as? EditAudioProfile.Aac ?: reject()
                    val primingUs = (2_048_000_000L + aac.sampleRate - 1) / aac.sampleRate
                    if (time < -primingUs || lastAudio?.let { time <= it } == true) reject()
                    if (firstAudio == null) firstAudio = time
                    lastAudio = time
                }
                extractor.advance()
            }
            val frameRate = EditorEnginePolicy.observedFrameRate(pts, videoCount, duration)
            if (pts[0] > EditLimits.MAX_FRAME_TIMESTAMP_DRIFT_US) reject()
            if (audio is EditAudioProfile.Aac && (firstAudio == null || lastAudio == null ||
                    abs(firstAudio - pts[0]) > 100_000 ||
                    abs(lastAudio + 1_024_000_000L / audio.sampleRate - duration) > 100_000)) reject()
            return EditSourceMetadata(ref, duration, width, height, rotation, frameRate, audio, input.sizeBytes,
                avcProfile = profile, color = EditColorProfile.SDR, squarePixels = true, constantFrameRate = true, bitDepth = 8,
                frameTimeline = EditFrameTimeline(pts.copyOf(videoCount), duration))
        } finally {
            // If release hangs or throws, these capabilities stay attached to the retained session.
            extractor.release(); session.probeExtractor = null
            session.closeInput(input)
        }
    }
    private fun csd(format: MediaFormat, key: String): ByteArray {
        val source = format.getByteBuffer(key)?.duplicate() ?: reject()
        if (source.remaining() !in 1..65_536) reject()
        return ByteArray(source.remaining()).also(source::get)
    }
    private fun integer(format: MediaFormat, key: String, fallback: Int = -1): Int =
        if (format.containsKey(key)) format.getInteger(key) else fallback
    internal fun spsOffset(bytes: ByteArray): Int? {
        for (index in 0 until bytes.size - 4) {
            val offset = when {
                bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte() -> index + 3
                index + 4 < bytes.size && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> index + 4
                else -> continue
            }
            if (bytes[offset].toInt() and 0x9f == 7) return offset
        }
        return null
    }
    private fun reject(): Nothing = throw EditFailure(EditError.UNSUPPORTED_PROFILE)
}
