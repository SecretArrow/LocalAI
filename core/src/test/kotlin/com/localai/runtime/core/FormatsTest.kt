package com.localai.runtime.core

import com.localai.runtime.core.util.Formats
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * Pure JVM tests for [Formats]. Assertions match the ACTUAL implementation:
 * binary steps (1024) with decimal-style suffixes ("KB", "MB", …), one decimal
 * for everything above bytes, "—" for unknown ETAs, em dash, clamped percents.
 */
class FormatsTest {

    @Test
    fun bytesFormatting() {
        assertEquals("0 B", Formats.bytes(0))
        assertEquals("0 B", Formats.bytes(-512)) // negative clamped to 0
        assertEquals("1 B", Formats.bytes(1))
        assertEquals("512 B", Formats.bytes(512))
        assertEquals("1023 B", Formats.bytes(1023))
        assertEquals("1.0 KB", Formats.bytes(1024))
        assertEquals("1.5 KB", Formats.bytes(1536))
        assertEquals("1.0 MB", Formats.bytes(1L shl 20))
        // 5 GiB renders with a decimal-style GB suffix (1024-based steps, per KDoc).
        assertEquals("5.0 GB", Formats.bytes(5L * 1024 * 1024 * 1024))
        assertEquals("1.0 TB", Formats.bytes(1L shl 40))
        assertEquals("1.0 PB", Formats.bytes(1L shl 50))
    }

    @Test
    fun bytesFormattingBoundaryRoundsDownToOneDecimal() {
        // 1024*1024 - 1 = 1048575 -> 1023.9997558 KiB -> "1024.0 KB"
        assertEquals("1024.0 KB", Formats.bytes((1L shl 20) - 1))
        // Exactly 1 KiB boundary stays in KB.
        assertEquals("1.0 KB", Formats.bytes(1024))
    }

    @Test
    fun speedFormatting() {
        assertEquals("0 B/s", Formats.speed(0))
        assertEquals("0 B/s", Formats.speed(-10))
        assertEquals("512 B/s", Formats.speed(512))
        assertEquals("1.5 KB/s", Formats.speed(1536))
        assertEquals("12.4 MB/s", Formats.speed(13_000_000))
    }

    @Test
    fun etaFormatting() {
        assertEquals("—", Formats.eta(-1))
        assertEquals("—", Formats.eta(-100))
        assertEquals("0s", Formats.eta(0))
        assertEquals("45s", Formats.eta(45))
        assertEquals("59s", Formats.eta(59))
        assertEquals("1m", Formats.eta(60)) // whole minutes drop the seconds part
        assertEquals("2m 31s", Formats.eta(151))
        assertEquals("2m", Formats.eta(120))
        assertEquals("59m 59s", Formats.eta(3599))
        assertEquals("1h 00m", Formats.eta(3600))
        assertEquals("1h 03m", Formats.eta(3780))
        assertEquals("25h 00m", Formats.eta(90_000)) // hours are not capped at 24
    }

    @Test
    fun percentClamping() {
        assertEquals(0, Formats.percent(0, 100))
        assertEquals(0, Formats.percent(-5, 100))
        assertEquals(0, Formats.percent(50, 0))
        assertEquals(0, Formats.percent(50, -10))
        assertEquals(1, Formats.percent(1, 100))
        assertEquals(50, Formats.percent(50, 100))
        assertEquals(33, Formats.percent(1, 3))
        assertEquals(99, Formats.percent(999, 1000))
        assertEquals(100, Formats.percent(100, 100))
        assertEquals(100, Formats.percent(150, 100)) // overshoot clamped
    }

    @Test
    fun timeFormatting() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 9)
        calendar.set(Calendar.MINUTE, 5)
        calendar.set(Calendar.SECOND, 3)
        assertEquals("09:05:03", Formats.time(calendar.timeInMillis))

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        assertEquals("23:59:59", Formats.time(calendar.timeInMillis))

        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        assertEquals("00:00:00", Formats.time(calendar.timeInMillis))

        assertEquals("00:00:00", Formats.time(-1))
    }
}
