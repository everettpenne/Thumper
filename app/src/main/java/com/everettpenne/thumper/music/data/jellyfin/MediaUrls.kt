package com.everettpenne.thumper.music.data.jellyfin

import com.everettpenne.thumper.music.data.jellyfin.JfItem
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Builds image and stream addresses. None of them carries the token: the shared HTTP client adds the Authorization header to requests for the server. */
class MediaUrls(private val server: () -> String?, private val deviceId: () -> String) {
    private fun builder(path: String) = server()?.toHttpUrl()?.newBuilder()?.also { b -> path.trim('/').split('/').forEach(b::addPathSegment) }

    private fun image(itemId: String, kind: String, tag: String?, width: Int, index: Int? = null): String? {
        val b = builder("Items/$itemId/Images/$kind") ?: return null
        if (index != null) b.addPathSegment("$index")
        b.addQueryParameter("fillWidth", "$width").addQueryParameter("quality", "90")
        if (tag != null) b.addQueryParameter("tag", tag)
        return b.build().toString()
    }

    /** The poster: the item's own, or (for an episode with none) its series', or (for a song with none) its album's. */
    fun poster(item: JfItem, width: Int = 400): String? = when {
        item.imageTags.primary != null -> image(item.id, "Primary", item.imageTags.primary, width)
        item.seriesId != null && item.seriesPrimaryTag != null -> image(item.seriesId, "Primary", item.seriesPrimaryTag, width)
        item.albumId != null && item.albumPrimaryTag != null -> image(item.albumId, "Primary", item.albumPrimaryTag, width)
        else -> null
    }

    /** The wide picture: a backdrop, else a thumb, else the poster. For an episode, its own still comes first (that is its Primary image). */
    fun wide(item: JfItem, width: Int = 800): String? = when {
        item.isEpisode && item.imageTags.primary != null -> image(item.id, "Primary", item.imageTags.primary, width)
        item.imageTags.thumb != null -> image(item.id, "Thumb", item.imageTags.thumb, width)
        item.backdropTags.isNotEmpty() -> image(item.id, "Backdrop", item.backdropTags[0], width, index = 0)
        item.parentBackdropItemId != null && item.parentBackdropTags.isNotEmpty() -> image(item.parentBackdropItemId, "Backdrop", item.parentBackdropTags[0], width, index = 0)
        else -> poster(item, width)
    }

    fun backdrop(item: JfItem, width: Int = 1200): String? = when {
        item.backdropTags.isNotEmpty() -> image(item.id, "Backdrop", item.backdropTags[0], width, index = 0)
        item.parentBackdropItemId != null && item.parentBackdropTags.isNotEmpty() -> image(item.parentBackdropItemId, "Backdrop", item.parentBackdropTags[0], width, index = 0)
        else -> wide(item, width)
    }

    fun person(id: String, width: Int = 200) = image(id, "Primary", null, width)

    /** The original file, served as it is: ExoPlayer plays it directly when it understands the container and codecs. */
    fun directStream(itemId: String, mediaSourceId: String): String? = builder("Videos/$itemId/stream")
        ?.addQueryParameter("static", "true")?.addQueryParameter("mediaSourceId", mediaSourceId)?.addQueryParameter("deviceId", deviceId())?.build()?.toString()

    /** The original file for saving to the phone (Jellyfin's download endpoint: the account must be allowed to download). */
    fun download(itemId: String): String? = builder("Items/$itemId/Download")?.build()?.toString()

    /** The original audio file, served as it is. */
    fun audioStream(itemId: String, mediaSourceId: String?): String? = builder("Audio/$itemId/stream")?.apply {
        addQueryParameter("static", "true"); addQueryParameter("deviceId", deviceId())
        if (!mediaSourceId.isNullOrEmpty()) addQueryParameter("mediaSourceId", mediaSourceId)
    }?.build()?.toString()

    /** A transcoded HLS stream (H.264 + AAC, stereo), the fallback when the phone cannot play the original. */
    fun hlsStream(itemId: String, mediaSourceId: String, playSessionId: String, maxBitrate: Int = 8_000_000, startTicks: Long = 0): String? =
        builder("Videos/$itemId/master.m3u8")?.apply {
            addQueryParameter("mediaSourceId", mediaSourceId); addQueryParameter("deviceId", deviceId()); addQueryParameter("playSessionId", playSessionId)
            addQueryParameter("videoCodec", "h264"); addQueryParameter("audioCodec", "aac"); addQueryParameter("maxAudioChannels", "2")
            addQueryParameter("videoBitRate", "$maxBitrate"); addQueryParameter("segmentContainer", "ts"); addQueryParameter("minSegments", "1")
            addQueryParameter("breakOnNonKeyFrames", "true")
            if (startTicks > 0) addQueryParameter("startTimeTicks", "$startTicks")
        }?.build()?.toString()
}
