package com.everettpenne.thumper.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    /** "Today", "Yesterday", "3 days ago", then "Oct 5" (and the year when it isn't this one). Nothing for an unknown date. */
    fun relativeDate(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0) return ""
        val days = ((now - millis) / 86_400_000L).toInt()
        return when {
            days < 0 -> "Soon"; days == 0 -> "Today"; days == 1 -> "Yesterday"; days < 7 -> "$days days ago"
            else -> {
                val sameYear = SimpleDateFormat("yyyy", Locale.getDefault()).let { it.format(Date(millis)) == it.format(Date(now)) }
                SimpleDateFormat(if (sameYear) "MMM d" else "MMM d, yyyy", Locale.getDefault()).format(Date(millis))
            }
        }
    }

    /** "1 hr 02 min", "45 min", "12 sec" from seconds. */
    fun duration(seconds: Int?): String {
        val s = seconds ?: return ""
        if (s <= 0) return ""
        val h = s / 3600; val m = s / 60 % 60
        return when { h > 0 -> "%d hr %02d min".format(h, m); m > 0 -> "$m min"; else -> "$s sec" }
    }

    /** What is left: "32 min left". Empty when it is not known. */
    fun remaining(durationMs: Long, positionMs: Long): String {
        if (durationMs <= 0) return ""
        val left = ((durationMs - positionMs) / 1000).toInt()
        return if (left <= 0) "" else duration(left) + " left"
    }

    /** A playback clock: "4:07" or "1:02:03". */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun speed(v: Float): String = if (v == v.toInt().toFloat()) "${v.toInt()}×" else "${"%.2f".format(v).trimEnd('0')}×"
}
