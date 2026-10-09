package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.RecordingError
import com.easysavevideo.recording.RecordingFailure
import java.util.ArrayDeque

/** Whole AAC packets wait for a measured video endpoint; no trimming, padding or invented PTS. */
internal class MeasuredAudioTail<T : Any>(private val maxBytes: Long, private val maxSamples: Int) {
    private data class Packet<T>(val sample: T, val ptsUs: Long, val endUs: Long, val bytes: Int)
    private val packets = ArrayDeque<Packet<T>>()
    var retainedBytes: Long = 0; private set
    val retainedSamples: Int get() = packets.size

    fun offer(sample: T, ptsUs: Long, durationUs: Long, bytes: Int, otherBytes: Long, otherSamples: Int) {
        if (ptsUs < 0 || durationUs !in 1..1_000_000 || ptsUs > Long.MAX_VALUE - durationUs ||
            (packets.peekLast()?.let { ptsUs <= it.ptsUs } == true)) throw RecordingFailure(RecordingError.TIMESTAMP_INVALID)
        if (bytes <= 0 || otherBytes < 0 || otherSamples < 0 || retainedBytes + otherBytes + bytes > maxBytes ||
            packets.size + otherSamples >= maxSamples) throw RecordingFailure(RecordingError.BUFFER_LIMIT)
        packets.addLast(Packet(sample, ptsUs, ptsUs + durationUs, bytes)); retainedBytes += bytes
    }

    fun pollReady(observedVideoEndUs: Long): T? {
        val head = packets.peekFirst() ?: return null
        if (head.endUs > observedVideoEndUs) return null
        packets.removeFirst(); retainedBytes -= head.bytes
        return head.sample
    }

    /** At EOS, all remaining packets extend past observed video and are discarded whole. */
    fun discard(): List<T> {
        val result = packets.map { it.sample }
        packets.clear(); retainedBytes = 0
        return result
    }
}
