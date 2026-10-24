package com.everettpenne.thumper.music.data.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Jellyfin's JSON is PascalCase and grows new fields between versions: be lenient, and never trust a field to be present. */
val JellyfinJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }

const val TICKS_PER_SECOND = 10_000_000L

@Serializable data class PublicInfo(
    @SerialName("ServerName") val serverName: String = "Jellyfin",
    @SerialName("Version") val version: String = "",
    @SerialName("Id") val id: String = "",
    @SerialName("ProductName") val productName: String = "",
)

@Serializable data class UserDto(@SerialName("Id") val id: String, @SerialName("Name") val name: String = "")
@Serializable data class AuthResult(
    @SerialName("User") val user: UserDto,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String = "",
)
@Serializable data class AuthRequest(@SerialName("Username") val username: String, @SerialName("Pw") val pw: String)

@Serializable data class ImageTags(
    @SerialName("Primary") val primary: String? = null,
    @SerialName("Thumb") val thumb: String? = null,
    @SerialName("Logo") val logo: String? = null,
)

@Serializable data class UserData(
    @SerialName("PlaybackPositionTicks") val positionTicks: Long = 0,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("Played") val played: Boolean = false,
)

@Serializable data class MediaStreamDto(
    @SerialName("Type") val type: String = "",
    @SerialName("Index") val index: Int = 0,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
)

@Serializable data class MediaSourceDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Container") val container: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = true,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = true,
    @SerialName("MediaStreams") val streams: List<MediaStreamDto> = emptyList(),
)

/** A page about the item elsewhere (IMDb, TheMovieDb, TheTVDB, Trakt…), as the server lists it. */
@Serializable data class ExternalUrlDto(@SerialName("Name") val name: String = "", @SerialName("Url") val url: String = "")

@Serializable data class PersonDto(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String = "",
)

/** One thing in the library: a movie, a series, an episode, a folder, an album, an artist, a song. The fields are the ones the screens use. */
@Serializable data class JfItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("IsFolder") val isFolder: Boolean = false,
    @SerialName("ImageTags") val imageTags: ImageTags = ImageTags(),
    @SerialName("BackdropImageTags") val backdropTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropTags: List<String> = emptyList(),
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryTag: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("SeasonName") val seasonName: String? = null,
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("AlbumArtists") val albumArtists: List<String> = emptyList(),
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryTag: String? = null,
    @SerialName("IndexNumber") val index: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndex: Int? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("UserData") val userData: UserData = UserData(),
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
    @SerialName("People") val people: List<PersonDto> = emptyList(),
    @SerialName("ExternalUrls") val externalUrls: List<ExternalUrlDto> = emptyList(),
) {
    val isEpisode get() = type == "Episode"
    val isSeries get() = type == "Series"
    val isSeason get() = type == "Season"
    val isPlayable get() = type == "Movie" || type == "Episode" || type == "Video" || type == "MusicVideo"
    val isAudio get() = type == "Audio"
    val isMusicAlbum get() = type == "MusicAlbum"
    val isMusicArtist get() = type == "MusicArtist"
    val runtimeMinutes: Int? get() = runTimeTicks?.let { (it / TICKS_PER_SECOND / 60).toInt() }?.takeIf { it > 0 }
    val runtimeSeconds: Long? get() = runTimeTicks?.let { it / TICKS_PER_SECOND }?.takeIf { it > 0 }

    /** The artist name to show: the track's artist, falling back to the album artist. */
    val musicArtist: String? get() = artists.firstOrNull() ?: albumArtists.firstOrNull()

    /** "2" for a single-disc album track, "1-3" for disc 1 track 3 on a multi-disc album. */
    val trackLabel: String? get() = if (isAudio && index != null) (if ((parentIndex ?: 0) > 0) "${parentIndex}-$index" else "$index") else null

    /** How far through it the person is, 0..1, or null when they have not started (or have finished). */
    val progress: Float? get() {
        userData.playedPercentage?.let { if (it in 0.5..99.0) return (it / 100).toFloat() }
        val total = runTimeTicks ?: return null
        val at = userData.positionTicks
        return if (total > 0 && at > 0) (at.toDouble() / total).toFloat().coerceIn(0f, 1f).takeIf { it < .99f } else null
    }

    /** "S2 E5" for an episode, in the form Jellyfin's own clients use. */
    val episodeLabel: String? get() = if (isEpisode && index != null) (if (parentIndex != null) "S$parentIndex E$index" else "E$index") else null
}

@Serializable data class ItemsResult(
    @SerialName("Items") val items: List<JfItem> = emptyList(),
    @SerialName("TotalRecordCount") val total: Int = 0,
)

@Serializable data class PlaybackReport(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long = 0,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("PlayMethod") val playMethod: String = "DirectStream",
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("CanSeek") val canSeek: Boolean = true,
)
