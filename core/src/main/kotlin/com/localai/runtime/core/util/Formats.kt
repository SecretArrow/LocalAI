package com.localai.runtime.core.util

import java.util.Calendar
import java.util.Locale

/**
 * Human-readable formatting helpers (pure JVM, no Android dependencies — unit-testable).
 *
 * Binary units (1024-based) displayed with decimal-style suffixes: "4.8 GB".
 * All functions tolerate negative/zero inputs gracefully.
 */
object Formats {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    /** e.g. 0 -> "0 B", 512 -> "512 B", 5 GiB -> "5.0 GB". Negative values are clamped to 0. */
    fun bytes(v: Long): String {
        if (v <= 0L) return "0 B"
        var value = v.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < UNITS.size - 1) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) {
            "$v B"
        } else {
            String.format(Locale.US, "%.1f %s", value, UNITS[unit])
        }
    }

    /** e.g. 13 000 000 -> "12.4 MB/s". Non-positive speeds render as "0 B/s". */
    fun speed(bytesPerSec: Long): String =
        if (bytesPerSec <= 0L) "0 B/s" else bytes(bytesPerSec) + "/s"

    /**
     * e.g. 45 -> "45s", 151 -> "2m 31s", 3780 -> "1h 03m".
     * Negative (unknown) values render as an em dash.
     */
    fun eta(seconds: Long): String = when {
        seconds < 0L -> "—"
        seconds < 60L -> "${seconds}s"
        seconds < 3600L -> {
            val m = seconds / 60
            val s = seconds % 60
            if (s == 0L) "${m}m" else "${m}m ${s}s"
        }
        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            String.format(Locale.US, "%dh %02dm", h, m)
        }
    }

    /** 0..100, defensive against zero/negative totals and overshoot. */
    fun percent(done: Long, total: Long): Int {
        if (total <= 0L || done <= 0L) return 0
        return ((done * 100L) / total).toInt().coerceIn(0, 100)
    }

    /** Local-time HH:mm:ss for an epoch-millis timestamp. Negative values render as 00:00:00. */
    fun time(ts: Long): String {
        if (ts < 0L) return "00:00:00"
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = ts
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
        )
    }
}
