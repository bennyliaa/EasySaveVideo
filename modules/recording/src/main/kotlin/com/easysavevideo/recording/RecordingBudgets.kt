package com.easysavevideo.recording

/** A monotonic source, normally supplied by the platform's capture clock. Never an epoch clock. */
fun interface RecordingClock { fun nowUs(): Long }

/** Opaque app-owned output identity. The core cannot resolve it to a storage location. */
data class RecordingOutputHandle(val id: String) { init { requireRecordingId(id) } }

/**
 * Ownership fence for one native lane. Abandoning an uninterruptible call revokes its result but
 * retains occupancy. Only the actual owner, in its release/finally path, may confirm release.
 */
class RecordingResourceFence {
    private var owner: RecordingSessionId? = null
    private var abandoned = false
    private var lastGeneration = 0L
    @Synchronized fun tryAcquire(session: RecordingSessionId): Boolean {
        if (owner != null || session.generation <= lastGeneration) return false
        owner = session
        lastGeneration = session.generation
        abandoned = false
        return true
    }
    @Synchronized fun abandon(session: RecordingSessionId): Boolean {
        if (owner != session) return false
        abandoned = true
        return true
    }
    @Synchronized fun acceptsResult(session: RecordingSessionId): Boolean = owner == session && !abandoned
    @Synchronized fun confirmReleased(session: RecordingSessionId): Boolean {
        if (owner != session) return false
        owner = null
        abandoned = false
        return true
    }
    @Synchronized fun isOccupied(): Boolean = owner != null
}

/** Single-use reservation returned before copying a pending compressed sample. */
class RecordingBufferReservation internal constructor(internal val owner: RecordingBufferBudget,
    internal val sequence: Long, val byteCount: Int)

/** No unbounded queue or arbitrary allocation can be justified by a missing second track format. */
class RecordingBufferBudget(private val limits: RecordingLimits = RecordingLimits()) {
    private val pending = mutableMapOf<Long, Int>()
    private var sequence = 0L
    private var bytes = 0L
    @Synchronized fun reserve(byteCount: Int): RecordingBufferReservation {
        if (byteCount <= 0 || byteCount > limits.maxSampleBytes) throw RecordingFailure(RecordingError.SAMPLE_LIMIT)
        if (pending.size >= limits.maxPendingSamples || byteCount.toLong() > limits.maxPendingBytes - bytes)
            throw RecordingFailure(RecordingError.BUFFER_LIMIT)
        if (sequence == Long.MAX_VALUE) throw RecordingFailure(RecordingError.BUFFER_LIMIT)
        val id = ++sequence
        pending[id] = byteCount
        bytes += byteCount
        return RecordingBufferReservation(this, id, byteCount)
    }
    @Synchronized fun release(reservation: RecordingBufferReservation): Boolean {
        if (reservation.owner !== this) return false
        val count = pending.remove(reservation.sequence) ?: return false
        bytes -= count
        return true
    }
    @Synchronized fun pendingBytes(): Long = bytes
    @Synchronized fun pendingSamples(): Int = pending.size
}

/**
 * Format barrier and deadline only; the adapter retains the actual formats and reserved samples.
 * A duplicate/revised format is not a request to start another muxer.
 */
class RecordingFormatGate(
    private val audioChoice: RecordingAudioChoice,
    private val startedAtMonotonicUs: Long,
    private val limits: RecordingLimits = RecordingLimits(),
) {
    init { require(startedAtMonotonicUs >= 0) }
    private var videoReady = false
    private var audioReady = false
    private var lastNowUs = startedAtMonotonicUs
    var started: Boolean = false
        private set
    var failed: Boolean = false
        private set

    /** True only for the one transition at which all required formats became ready. */
    fun formatReady(track: RecordingTrack, nowUs: Long): Boolean {
        checkDeadline(nowUs)
        if (failed) throw RecordingFailure(RecordingError.FORMAT_CHANGED)
        if ((track == RecordingTrack.VIDEO && videoReady) ||
            (track == RecordingTrack.AUDIO && (audioReady || audioChoice == RecordingAudioChoice.NONE)))
            fail(RecordingError.FORMAT_CHANGED)
        if (track == RecordingTrack.VIDEO) videoReady = true else audioReady = true
        if (videoReady && (audioChoice == RecordingAudioChoice.NONE || audioReady)) {
            started = true
            return true
        }
        return false
    }

    fun checkDeadline(nowUs: Long) {
        if (nowUs < lastNowUs) fail(RecordingError.CLOCK_INVALID)
        lastNowUs = nowUs
        if (failed) throw RecordingFailure(RecordingError.FORMAT_CHANGED)
        if (!started && nowUs - startedAtMonotonicUs >= limits.formatTimeoutUs) fail(RecordingError.FORMAT_TIMEOUT)
    }
    private fun fail(error: RecordingError): Nothing {
        failed = true
        throw RecordingFailure(error)
    }
}

/** Counts actual encoded input separately from observed total output (which includes container data). */
class RecordingByteBudget(private val limits: RecordingLimits = RecordingLimits()) {
    var encodedBytes: Long = 0
        private set
    var observedArtifactBytes: Long = 0
        private set
    fun admitSample(byteCount: Int) {
        if (byteCount <= 0 || byteCount > limits.maxSampleBytes) throw RecordingFailure(RecordingError.SAMPLE_LIMIT)
        if (byteCount.toLong() > limits.maxFileBytes - encodedBytes) throw RecordingFailure(RecordingError.BYTE_LIMIT)
        encodedBytes += byteCount
    }
    fun observeArtifactBytes(byteCount: Long) {
        if (byteCount < observedArtifactBytes) throw RecordingFailure(RecordingError.SAMPLE_INVALID)
        if (byteCount > limits.maxFileBytes) throw RecordingFailure(RecordingError.BYTE_LIMIT)
        observedArtifactBytes = byteCount
    }
    fun checkStorage(freeBytes: Long) {
        if (freeBytes < limits.minFreeBytes) throw RecordingFailure(RecordingError.STORAGE_LOW)
    }
}
