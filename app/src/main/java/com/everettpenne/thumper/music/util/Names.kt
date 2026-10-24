package com.everettpenne.thumper.music.util

/**
 * What to call a file that carries no tags. Music copies arrive named in a few common ways, and a friendly player should show
 * "Artist · Title" rather than "03_-_artist_-_title.mp3". Pure, so it is unit-tested.
 */
data class NameGuess(val artist: String, val title: String)

/** Strips the extension, a leading track number ("01 ", "01.", "01 - "), and turns underscores and runs of spaces into spaces. */
fun cleanFileName(fileName: String): String {
    val withoutExtension = fileName.substringBeforeLast('.')
    val withoutNumber = Regex("^\\s*\\d{1,3}\\s*[-.)]?\\s+").replace(withoutExtension, "")
    return withoutNumber.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
}

/** Guesses artist and title from a file name. "Artist - Title" splits; a lone name is the title with an unknown artist. */
fun guessName(fileName: String, unknownArtist: String = "Unknown artist"): NameGuess {
    val clean = cleanFileName(fileName)
    val parts = clean.split(" - ", limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
    return when {
        parts.size == 2 -> NameGuess(parts[0], parts[1])
        else -> NameGuess(unknownArtist, clean.ifBlank { fileName })
    }
}

/**
 * True for the names music folders conventionally use for their cover. Case-insensitive, and only image extensions, so a
 * stray "cover.txt" beside the album is not treated as artwork.
 */
fun isCoverFileName(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension !in setOf("jpg", "jpeg", "png", "webp", "bmp")) return false
    val base = name.substringBeforeLast('.').lowercase()
    return base in setOf("cover", "folder", "front", "album", "albumart", "artwork", "thumb")
}

/** True for the AIFF family, which needs its own reader because Android has no AIFF support. */
fun isAiffFileName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("aif", "aiff", "aifc")

/** The file name in a content or file URI, decoded; empty when there is none. */
fun fileNameOf(uri: String): String = runCatching {
    android.net.Uri.parse(uri).lastPathSegment.orEmpty()
}.getOrDefault(uri.substringAfterLast('/'))

/**
 * The main artist of a track, without the featured guests: "Kali Uchis Feat. Tyler, the Creator" becomes "Kali Uchis".
 *
 * Used to gather an album and an artist page even when the tag writers spell the guests differently on every track. It is only a
 * fallback: a real album-artist tag (TPE2) always wins, and the displayed track still keeps the full credit.
 */
fun primaryArtist(artist: String): String {
    val trimmed = artist.trim()
    if (trimmed.isEmpty()) return trimmed
    val match = FEATURE_ARTIST.find(trimmed) ?: return trimmed
    return trimmed.substring(0, match.range.first).trim().ifEmpty { trimmed }
}

private val FEATURE_ARTIST = Regex("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$")
