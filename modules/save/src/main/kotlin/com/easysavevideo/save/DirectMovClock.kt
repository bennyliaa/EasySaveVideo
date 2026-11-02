package com.easysavevideo.save

/**
 * Exact, finite v1 MOV clock. This is arithmetic evidence, not container/codec admission. In
 * particular [audio] models a future AAC proof target; the production MOV profile remains silent.
 * No float frame rate, origin adjustment, accumulated rounded delta or duration callback is used.
 */
class DirectMovClock private constructor(
    val kind: DirectSampleKind,
    val timescale: Int,
    val sampleDeltaTicks: Long,
    val sampleCount: Long,
    val frameRate: Int?,
) {
    val endpointTicks: Long = checkedMultiply(sampleCount, sampleDeltaTicks)
    val endpoint: DirectTrackEndpoint = DirectTrackEndpoint(
        floorUs(endpointTicks, timescale), ceilUs(endpointTicks, timescale),
    )
    val roundedDurationUs: Long = halfUpTicksToUs(endpointTicks, timescale)
    val headerDurationTicks: Long = halfUpUsToTicks(roundedDurationUs, MOVIE_TIMESCALE)

    /** Includes the endpoint position N, useful for exact EOS; it is never an additional sample. */
    fun presentationTimeUs(index: Long): Long = floorUs(ticks(index), timescale)

    fun ticks(index: Long): Long {
        require(index in 0..sampleCount)
        return checkedMultiply(index, sampleDeltaTicks)
    }

    fun requirePresentationTime(index: Long, timeUs: Long) {
        require(index in 0 until sampleCount && timeUs == presentationTimeUs(index))
    }

    companion object {
        const val VIDEO_TIMESCALE = 90_000
        const val MOVIE_TIMESCALE = 10_000
        const val MAX_DURATION_SECONDS = 1_800L
        // Explicit contract values, rather than every integral divisor of 90,000.
        val VIDEO_FRAME_RATES: Set<Int> get() = setOf(
            1, 2, 3, 4, 5, 6, 8, 9, 10, 12, 15, 16, 18, 20, 24, 25, 30, 36, 40, 45, 48, 50, 60,
        )
        val AUDIO_SAMPLE_RATES: Set<Int> get() = setOf(32_000, 44_100, 48_000)

        fun video(frameRate: Int, sampleCount: Long): DirectMovClock {
            require(frameRate in VIDEO_FRAME_RATES)
            require(sampleCount in 2..checkedMultiply(MAX_DURATION_SECONDS, frameRate.toLong()))
            return DirectMovClock(DirectSampleKind.AVC_BASELINE, VIDEO_TIMESCALE,
                VIDEO_TIMESCALE.toLong() / frameRate, sampleCount, frameRate)
        }

        fun videoFromTable(timescale: Int, sampleDelta: Long, sampleCount: Long,
            mediaDurationTicks: Long): DirectMovClock {
            require(timescale == VIDEO_TIMESCALE)
            require(sampleDelta in 1..VIDEO_TIMESCALE.toLong() && VIDEO_TIMESCALE % sampleDelta == 0L)
            val clock = video((VIDEO_TIMESCALE / sampleDelta).toInt(), sampleCount)
            require(mediaDurationTicks == clock.endpointTicks)
            return clock
        }

        /** Synthetic timing model only. It does not validate AAC AUs or admit an audio track. */
        fun audio(sampleRate: Int, sampleCount: Long): DirectMovClock {
            require(sampleRate in AUDIO_SAMPLE_RATES)
            val cap = checkedMultiply(MAX_DURATION_SECONDS, sampleRate.toLong()) / 1_024
            require(sampleCount in 2..cap)
            return DirectMovClock(DirectSampleKind.AAC_LC, sampleRate, 1_024, sampleCount, null)
        }

        fun audioFromTable(timescale: Int, sampleDelta: Long, sampleCount: Long,
            mediaDurationTicks: Long): DirectMovClock {
            require(sampleDelta == 1_024L)
            val clock = audio(timescale, sampleCount)
            require(mediaDurationTicks == clock.endpointTicks)
            return clock
        }

        fun movieHeaderDurationTicks(video: DirectMovClock, audio: DirectMovClock? = null): Long {
            require(video.kind == DirectSampleKind.AVC_BASELINE)
            require(audio == null || audio.kind == DirectSampleKind.AAC_LC)
            return halfUpUsToTicks(maxOf(video.roundedDurationUs, audio?.roundedDurationUs ?: 0),
                MOVIE_TIMESCALE)
        }

        fun floorUs(ticks: Long, timescale: Int): Long = scaled(ticks, 1_000_000, timescale, 0)
        fun ceilUs(ticks: Long, timescale: Int): Long = scaled(ticks, 1_000_000, timescale, 1)
        fun halfUpTicksToUs(ticks: Long, timescale: Int): Long = scaled(ticks, 1_000_000, timescale, 2)
        fun halfUpUsToTicks(timeUs: Long, timescale: Int): Long {
            require(timescale > 0)
            return scaled(timeUs, timescale.toLong(), 1_000_000, 2)
        }

        private fun scaled(value: Long, multiplier: Long, divisor: Int, rounding: Int): Long {
            require(value >= 0 && divisor > 0)
            val product = checkedMultiply(value, multiplier)
            val quotient = product / divisor
            val remainder = product % divisor
            val increment = when (rounding) {
                1 -> remainder != 0L
                2 -> remainder >= (divisor.toLong() + 1) / 2
                else -> false
            }
            return checkedAdd(quotient, if (increment) 1 else 0)
        }

        internal fun checkedMultiply(left: Long, right: Long): Long {
            require(left >= 0 && right >= 0 && (right == 0L || left <= Long.MAX_VALUE / right))
            return left * right
        }

        internal fun checkedAdd(left: Long, right: Long): Long {
            require(left >= 0 && right >= 0 && left <= Long.MAX_VALUE - right)
            return left + right
        }
    }
}

/** Pure validation of the pinned extractor's callback flags. Raw sync plus AVC syntax stay required. */
object DirectMovCallbackFlags {
    const val KEY_FRAME = 1
    const val END_OF_STREAM = 4
    const val LAST_SAMPLE = 0x20000000

    fun sync(flags: Int, index: Long, clock: DirectMovClock, rawSync: Int): Int {
        require(index in 0 until clock.sampleCount && rawSync in 0..1)
        require(flags and (KEY_FRAME or LAST_SAMPLE).inv() == 0)
        require(flags and LAST_SAMPLE == 0 || index == clock.sampleCount - 1)
        val sync = flags and KEY_FRAME
        require(sync == rawSync)
        require(clock.kind != DirectSampleKind.AAC_LC || sync == 1)
        require(clock.kind != DirectSampleKind.AVC_BASELINE || index != 0L || sync == 1)
        return sync
    }
}

/**
 * Track-local mux write discipline. Call these guards before the corresponding mux call, and
 * [finish] before accepting close. This is not evidence that a failed native/mux write succeeded.
 */
class DirectMovWriteSequence(private val clock: DirectMovClock) {
    private var count = 0L
    private var eos = false
    private var finished = false

    fun media(timeUs: Long, size: Int, flags: Int) {
        require(!eos && !finished)
        require(size in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES)
        require(flags in 0..1)
        clock.requirePresentationTime(count, timeUs)
        require(clock.kind != DirectSampleKind.AAC_LC || flags == 1)
        require(clock.kind != DirectSampleKind.AVC_BASELINE || count != 0L || flags == 1)
        count++
    }

    fun endOfStream(timeUs: Long, size: Int = 0, flags: Int = DirectMovCallbackFlags.END_OF_STREAM) {
        require(!eos && !finished && count == clock.sampleCount)
        require(size == 0 && flags == DirectMovCallbackFlags.END_OF_STREAM)
        require(timeUs == clock.endpoint.minimumUs)
        eos = true
    }

    fun finish() {
        require(!finished && eos && count == clock.sampleCount)
        finished = true
    }
}
