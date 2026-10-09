package com.easysavevideo.app.save

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSeekPolicyTest {
    @Test fun seeksWithinKnownDuration() {
        assertEquals(30_000L, PreviewSeekPolicy.target(15_000, 60_000, 15_000))
        assertEquals(0L, PreviewSeekPolicy.target(15_000, 60_000, -15_000))
    }
    @Test fun clampsToBounds() {
        assertEquals(0L, PreviewSeekPolicy.target(10_000, 60_000, -15_000))
        assertEquals(60_000L, PreviewSeekPolicy.target(59_000, 60_000, 15_000))
    }
    @Test fun unknownDurationIsNotSeekable() {
        assertEquals(0L, PreviewSeekPolicy.target(1_000, -1, 15_000))
    }
    @Test fun extremeInputsDoNotWrap() {
        assertEquals(Int.MAX_VALUE.toLong(), PreviewSeekPolicy.target(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(0L, PreviewSeekPolicy.target(Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE))
    }
}
