package com.easysavevideo.app.save

import com.easysavevideo.app.recording.recordingDurationLabel
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SaveDisplayFormattingTest {
    @Test fun originalByteThresholdsAndSubMiBTruncationArePreserved() {
        val cases = listOf(0L to SaveDisplaySize("0", SaveDisplaySizeUnit.KIB),
            1023L to SaveDisplaySize("0", SaveDisplaySizeUnit.KIB),
            1024L to SaveDisplaySize("1", SaveDisplaySizeUnit.KIB),
            1_048_575L to SaveDisplaySize("1023", SaveDisplaySizeUnit.KIB),
            1_048_576L to SaveDisplaySize("1.0", SaveDisplaySizeUnit.MIB),
            1_073_741_823L to SaveDisplaySize("1024.0", SaveDisplaySizeUnit.MIB),
            1_073_741_824L to SaveDisplaySize("1.00", SaveDisplaySizeUnit.GIB))
        cases.forEach { (bytes, expected) -> assertEquals(expected, saveDisplaySize(bytes, Locale.US)) }
    }
    @Test fun decimalFormattingUsesTheProvidedLocale() {
        assertEquals("1.5", saveDisplaySize(1_572_864, Locale.US).amount)
        assertEquals("1,5", saveDisplaySize(1_572_864, Locale.GERMANY).amount)
        assertEquals("1.5", saveDisplaySize(1_572_864, Locale.SIMPLIFIED_CHINESE).amount)
        assertEquals("1,50", saveDisplaySize(1_610_612_736, Locale.FRANCE).amount)
    }
    @Test fun byteFormattingDoesNotSwitchToDecimalSiUnits() {
        assertEquals(SaveDisplaySize("976", SaveDisplaySizeUnit.KIB), saveDisplaySize(1_000_000, Locale.US))
        assertEquals(SaveDisplaySize("953.7", SaveDisplaySizeUnit.MIB), saveDisplaySize(1_000_000_000, Locale.US))
    }
    @Test fun longByteCountsKeepTheOriginalFixedPointDisplayWithoutIntOverflow() {
        assertEquals(SaveDisplaySizeUnit.GIB, saveDisplaySize(Long.MAX_VALUE, Locale.US).unit)
        assertEquals("8589934592.00", saveDisplaySize(Long.MAX_VALUE, Locale.US).amount)
    }
    @Test fun elapsedTimeMatchesExistingRoundingClampingAndHourRange() {
        for (duration in listOf(Long.MIN_VALUE, -1L, 0L, 999_999L, 1_000_000L,
            59_999_999L, 3_600_000_000L, 90_061_000_000L, Long.MAX_VALUE)) {
            assertEquals(recordingDurationLabel(duration), saveDisplayDuration(duration, Locale.US))
        }
    }
    @Test fun elapsedTimeDoesNotWrapAfterTwentyFourHours() {
        assertEquals("25:01:01", saveDisplayDuration(90_061_999_999L, Locale.US))
    }
    @Test fun explicitLocaleDigitsAreUsedForCountsAndTime() {
        val arabic = Locale.forLanguageTag("ar-EG")
        assertEquals("١٢٣٤", saveDisplayInteger(1234, arabic))
        assertEquals("٠١:٠١:٠١", saveDisplayDuration(3_661_000_000L, arabic))
    }
    @Test fun displayCallsNeverChangeTheProcessLocale() {
        val before = Locale.getDefault()
        saveDisplaySize(1_572_864, Locale.GERMANY)
        saveDisplayDuration(3_661_000_000L, Locale.FRANCE)
        saveDisplayInteger(1234, Locale.SIMPLIFIED_CHINESE)
        assertEquals(before, Locale.getDefault())
    }
    @Test fun pluralQuantityConversionDoesNotOverflowWhileTextRetainsLongValue() {
        assertEquals(0, saveDisplayQuantity(-1))
        assertEquals(0, saveDisplayQuantity(0))
        assertEquals(1, saveDisplayQuantity(1))
        assertEquals(2, saveDisplayQuantity(2))
        assertEquals(Int.MAX_VALUE, saveDisplayQuantity(Long.MAX_VALUE))
        assertEquals("9223372036854775807", saveDisplayInteger(Long.MAX_VALUE, Locale.US))
    }
}
