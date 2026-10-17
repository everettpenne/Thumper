package com.everettpenne.thumper.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    /** "Just now", "5 min ago", "2 hr ago", "Yesterday", "3 days ago", then "Oct 5" (and the year when it isn't this one). Nothing for an unknown date. */
    fun relativeDate(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0) return ""
        val diff = now - millis
        if (diff < 0) return "Soon"
        val minutes = diff / 60_000L
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} hr ago"
            minutes < 60 * 24 * 2 -> "Yesterday"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} days ago"
            else -> {
                val sameYear = SimpleDateFormat("yyyy", Locale.getDefault()).let { it.format(Date(millis)) == it.format(Date(now)) }
                SimpleDateFormat(if (sameYear) "MMM d" else "MMM d, yyyy", Locale.getDefault()).format(Date(millis))
            }
        }
    }

    /** The start of an article's text for a list preview: markup gone, whitespace collapsed to single spaces, cut at a word boundary. */
    fun snippet(html: String, maxChars: Int = 200): String {
        val flat = Html.plain(html).replace(Regex("\\s+"), " ").trim()
        if (flat.length <= maxChars) return flat
        val cut = flat.substring(0, maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        val end = if (lastSpace > maxChars / 2) lastSpace else maxChars
        return flat.substring(0, end).trimEnd() + "…"
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

    /** "812 KB", "42 MB", "1.3 GB". */
    fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> "%.1f GB".format(n / (1024.0 * 1024 * 1024)); n >= 1L shl 20 -> "%.0f MB".format(n / (1024.0 * 1024)); n >= 1L shl 10 -> "%d KB".format(n shr 10); else -> "$n B"
    }

    fun speed(v: Float): String = if (v == v.toInt().toFloat()) "${v.toInt()}×" else "${"%.2f".format(v).trimEnd('0')}×"
}
