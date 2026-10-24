package com.everettpenne.thumper.music.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val createdAt: Long = 0L,
)

@Serializable
private data class Collections(val playlists: List<Playlist> = emptyList(), val favorites: List<String> = emptyList())

/**
 * The person's own lists: playlists they made, and the songs they loved. Stored as one small JSON file in the app's private storage,
 * so they survive restarts and are included in nothing else. Track ids are the library's stable ids; a playlist quietly skips tracks
 * that are no longer on the phone rather than losing the rest.
 */
class Playlists(context: Context) {
    private val file = File(context.filesDir, "collections.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var state = read()

    private val _playlists = MutableStateFlow(state.playlists)
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _favorites = MutableStateFlow(state.favorites.toSet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private fun read(): Collections =
        if (!file.isFile) Collections() else runCatching { json.decodeFromString<Collections>(file.readText()) }.getOrDefault(Collections())

    private fun write() {
        state = Collections(_playlists.value, _favorites.value.toList())
        runCatching { file.writeText(json.encodeToString(state)) }
    }

    fun create(name: String): Playlist {
        val playlist = Playlist(id = UUID.randomUUID().toString(), name = name.trim().ifBlank { "New Playlist" }, createdAt = System.currentTimeMillis())
        _playlists.value = _playlists.value + playlist
        write()
        return playlist
    }

    fun rename(id: String, name: String) {
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
        write()
    }

    fun delete(id: String) {
        _playlists.value = _playlists.value.filterNot { it.id == id }
        write()
    }

    /** Adds tracks that are not already there, keeping the order they were given in. */
    fun add(id: String, trackIds: List<String>) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != id) playlist else playlist.copy(trackIds = (playlist.trackIds + trackIds).distinct())
        }
        write()
    }

    fun remove(id: String, trackId: String) {
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(trackIds = it.trackIds - trackId) else it }
        write()
    }

    fun move(id: String, from: Int, to: Int) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != id) playlist else playlist.copy(trackIds = playlist.trackIds.toMutableList().also { list ->
                if (from in list.indices && to in list.indices) list.add(to, list.removeAt(from))
            })
        }
        write()
    }

    fun toggleFavorite(trackId: String) {
        val next = _favorites.value.toMutableSet()
        if (!next.add(trackId)) next.remove(trackId)
        _favorites.value = next
        write()
    }

    fun isFavorite(trackId: String): Boolean = trackId in _favorites.value

    /** Where a track already lives, so "Add to playlist" can tick the lists it is in. */
    fun containing(trackId: String): Set<String> = _playlists.value.filter { trackId in it.trackIds }.map { it.id }.toSet()
}
