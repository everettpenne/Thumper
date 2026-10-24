package com.everettpenne.thumper.data.model

import kotlinx.serialization.Serializable

/** The two kinds of thing the one player can play. The kind changes the player's presentation and queue rules, never the tab it came from. */
@Serializable
sealed interface Playable {
    val id: String
    val title: String
    val subtitle: String
    val artworkUrl: String?
    val durationMs: Long
    val kind: String

    /** An episode from a feed, or an episode downloaded from one. Its progress belongs to the feed library. */
    @Serializable
    data class Podcast(val episode: Episode, val feed: Feed) : Playable {
        override val id: String get() = episode.id
        override val title: String get() = episode.title
        override val subtitle: String get() = feed.title
        override val artworkUrl: String? get() = episode.imageUrl ?: feed.imageUrl
        override val durationMs: Long get() = (episode.durationSec ?: 0) * 1000L
        override val kind: String get() = KIND
        companion object { const val KIND = "podcast" }
    }

    /**
     * A song from the phone or a Jellyfin server. It carries its own display fields and remote ids so the queue survives a rescan, and
     * [gain] so the player can level it without looking anything up.
     */
    @Serializable
    data class Music(
        val trackId: String,
        override val title: String,
        val artist: String,
        val album: String,
        val uri: String,
        override val artworkUrl: String? = null,
        override val durationMs: Long = 0L,
        val albumId: String? = null,
        val source: String = SOURCE_LOCAL,
        val gain: Float = 1f,
        val remoteId: String? = null,
        val artUrl: String? = null,
    ) : Playable {
        override val id: String get() = "music:$trackId"
        override val subtitle: String get() = artist
        override val kind: String get() = KIND
        companion object {
            const val KIND = "music"
            const val SOURCE_LOCAL = "local"
            const val SOURCE_JELLYFIN = "jellyfin"
        }
    }
}
