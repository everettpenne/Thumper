package com.everettpenne.thumper.music.data.jellyfin

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import com.everettpenne.thumper.music.data.jellyfin.JellyfinJson
import java.util.UUID

/** Who the app is signed in as: the server, the person on it, and the access token Jellyfin issued for this device. */
@Serializable data class Session(val serverUrl: String, val userId: String, val userName: String, val token: String, val serverName: String = "")

/**
 * Keeps the session encrypted at rest ([SecureStore]) and the device id (a random id Jellyfin uses to tell this install's sessions apart) in
 * plain preferences. The token is a key to the person's media, so it never goes in plain storage and never in a URL.
 */
class SessionStore(context: Context, private val secure: SecureStore) {
    private val prefs = context.getSharedPreferences("thumper_jellyfin_device", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session.asStateFlow()

    val deviceId: String = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }

    private fun load(): Session? = secure.get(KEY)?.let { runCatching { JellyfinJson.decodeFromString<Session>(it) }.getOrNull() }

    fun signIn(session: Session) { secure.put(KEY, JellyfinJson.encodeToString(session)); _session.value = session }
    fun signOut() { secure.remove(KEY); _session.value = null }

    private companion object { const val KEY = "session" }
}
