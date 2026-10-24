package com.everettpenne.thumper

import com.everettpenne.thumper.music.data.jellyfin.ApiResult
import com.everettpenne.thumper.music.data.jellyfin.Failure
import com.everettpenne.thumper.music.data.jellyfin.JellyfinApi
import com.everettpenne.thumper.music.data.jellyfin.MediaUrls
import com.everettpenne.thumper.music.data.jellyfin.Session
import com.everettpenne.thumper.music.data.jellyfin.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class JellyfinApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: JellyfinApi
    private var session: Session? = null

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        session = Session(server.url("/").toString().trimEnd('/'), "u1", "demo", "secret-token", "Home")
        api = JellyfinApi(OkHttpClient(), { session }, { "device-1" })
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `auth header names the client and carries the token only when there is one`() {
        val signedOut = api.authHeader(token = null)
        assertTrue(signedOut.startsWith("MediaBrowser Client=\"Thumper\""))
        assertTrue(signedOut.contains("DeviceId=\"device-1\""))
        assertFalse(signedOut.contains("Token"))
        assertTrue(api.authHeader().contains("Token=\"secret-token\""))
    }

    @Test fun `login posts the credentials without a token and returns the session`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"User":{"Id":"abc","Name":"demo"},"AccessToken":"tok","ServerId":"s"}"""))
        val r = api.login(session!!.serverUrl, "Home", "demo", "pw") as ApiResult.Ok
        assertEquals("tok", r.value.token); assertEquals("abc", r.value.userId)
        val sent = server.takeRequest()
        assertEquals("/Users/AuthenticateByName", sent.path)
        assertFalse(sent.getHeader("Authorization")!!.contains("Token"))
        assertEquals("""{"Username":"demo","Pw":"pw"}""", sent.body.readUtf8())
    }

    @Test fun `a refused login is a bad login, not a signed-out session`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(Failure.BAD_LOGIN, (api.login(session!!.serverUrl, "Home", "demo", "wrong") as ApiResult.Failed).reason)
    }

    @Test fun `a 401 on a normal request means the session ended`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(Failure.SIGNED_OUT, (api.libraries() as ApiResult.Failed).reason)
    }

    @Test fun `requests carry the token in the header and never in the address`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}"""))
        api.resume()
        val sent = server.takeRequest()
        assertTrue(sent.getHeader("Authorization")!!.contains("Token=\"secret-token\""))
        assertFalse(sent.path!!.contains("secret-token"))
        assertTrue(sent.path!!.startsWith("/Users/u1/Items/Resume"))
    }

    @Test fun `typed addresses get a scheme, https first`() {
        assertEquals(listOf("https://jf.example.com", "http://jf.example.com"), api.candidates("jf.example.com/"))
        assertEquals(listOf("http://192.168.1.2:8096"), api.candidates(" http://192.168.1.2:8096/ "))
        assertTrue(api.candidates("   ").isEmpty())
    }

    @Test fun `connect accepts a Jellyfin server and rejects something else that answers`() = runBlocking {
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse().setBody("""{"ServerName":"Home","Version":"10.10.0","Id":"x"}"""))
        assertEquals("Home", (api.connect(base) as ApiResult.Ok).value.second.serverName)
        server.enqueue(MockResponse().setBody("""{"hello":"world"}"""))
        assertEquals(Failure.NOT_JELLYFIN, (api.connect(base) as ApiResult.Failed).reason)
        server.enqueue(MockResponse().setBody("<html>router login</html>"))
        assertEquals(Failure.NOT_JELLYFIN, (api.connect(base) as ApiResult.Failed).reason)
    }

    @Test fun `an unreachable server is offline`() = runBlocking {
        val base = server.url("/").toString().trimEnd('/'); server.shutdown()
        assertEquals(Failure.OFFLINE, (api.connect(base) as ApiResult.Failed).reason)
    }

    @Test fun `a real-shaped item list decodes, including fields the app does not know`() {
        val json = """{"Items":[{"Id":"1","Name":"Dune","Type":"Movie","ProductionYear":2021,"RunTimeTicks":93000000000,"CommunityRating":8.1,
            "ImageTags":{"Primary":"abc","Logo":"l"},"BackdropImageTags":["b1"],"Genres":["Sci-Fi"],"SomethingNew":{"x":1},
            "UserData":{"PlaybackPositionTicks":18600000000,"PlayedPercentage":20.0,"Played":false,"IsFavorite":true,"Key":"k"},
            "MediaSources":[{"Id":"ms","Container":"mkv","MediaStreams":[{"Type":"Video","Index":0,"Codec":"hevc"}]}]},
            {"Id":"2","Name":"Pilot","Type":"Episode","SeriesId":"9","SeriesName":"Show","ParentIndexNumber":1,"IndexNumber":1}],"TotalRecordCount":2}"""
        val r = JellyfinJson.decodeFromString<ItemsResult>(json)
        assertEquals(2, r.total)
        val dune = r.items[0]
        assertEquals(155, dune.runtimeMinutes); assertEquals("abc", dune.imageTags.primary); assertTrue(dune.userData.isFavorite)
        assertEquals(0.2f, dune.progress!!, 0.001f)
        assertEquals("mkv", dune.mediaSources[0].container)
        assertEquals("S1 E1", r.items[1].episodeLabel)
    }

    @Test fun `progress is absent when unstarted or finished`() {
        fun item(pos: Long, pct: Double?, played: Boolean = false) = JfItem("i", runTimeTicks = 1000, userData = UserData(pos, pct, played = played))
        assertNull(item(0, null).progress)
        assertNull(item(995, null).progress)
        assertNull(item(0, 100.0).progress)
        assertEquals(.5f, item(500, null).progress!!, 0.001f)
    }

    @Test fun `image and stream addresses are built without the token`() {
        val urls = MediaUrls({ "http://jf.local:8096" }, { "dev" })
        val movie = JfItem("m1", "Dune", "Movie", imageTags = ImageTags(primary = "tag"), backdropTags = listOf("bt"))
        assertEquals("http://jf.local:8096/Items/m1/Images/Primary?fillWidth=400&quality=90&tag=tag", urls.poster(movie))
        assertTrue(urls.backdrop(movie)!!.contains("/Images/Backdrop/0"))
        val direct = urls.directStream("m1", "ms")!!
        assertTrue(direct.contains("/Videos/m1/stream") && direct.contains("static=true") && !direct.contains("api_key") && !direct.contains("Token"))
        assertTrue(urls.hlsStream("m1", "ms", "ps")!!.contains("/Videos/m1/master.m3u8"))
    }

    @Test fun `an episode without its own picture falls back to its series poster`() {
        val urls = MediaUrls({ "http://jf.local" }, { "d" })
        val ep = JfItem("e1", "Pilot", "Episode", seriesId = "s1", seriesPrimaryTag = "st")
        assertTrue(urls.poster(ep)!!.contains("/Items/s1/Images/Primary"))
    }

    @Test fun `nothing is built when signed out`() {
        val urls = MediaUrls({ null }, { "d" })
        assertNull(urls.poster(JfItem("m", imageTags = ImageTags(primary = "t"))))
        assertNull(urls.directStream("m", "ms"))
    }

    @Test fun `music endpoints hit their paths and parse their items`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"ar0","Name":"Tame Impala","Type":"MusicArtist"}],"TotalRecordCount":1}"""))
        val artists = api.artists("lib-mu") as ApiResult.Ok
        assertEquals("Tame Impala", artists.value[0].name)
        assertTrue(server.takeRequest().path!!.startsWith("/Artists"))

        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"al1","Name":"Currents","Type":"MusicAlbum","AlbumArtists":["Tame Impala"],"Artists":["Tame Impala"]}],"TotalRecordCount":1}"""))
        val albums = api.albumsByArtist("ar0") as ApiResult.Ok
        assertEquals("Tame Impala", albums.value[0].musicArtist)
        val byArtist = server.takeRequest().path!!
        assertTrue(byArtist.startsWith("/Users/u1/Items") && byArtist.contains("ArtistIds=ar0") && byArtist.contains("IncludeItemTypes=MusicAlbum"))

        server.enqueue(MockResponse().setBody("""[{"Id":"al1","Name":"Currents","Type":"MusicAlbum"}]"""))
        val latest = api.latestAlbums("lib-mu") as ApiResult.Ok
        assertEquals("Currents", latest.value[0].name)
        assertTrue(server.takeRequest().path!!.contains("IncludeItemTypes=MusicAlbum"))

        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"s1","Name":"Track 1","Type":"Audio","IndexNumber":1}],"TotalRecordCount":1}"""))
        val tracks = api.albumTracks("al1") as ApiResult.Ok
        assertEquals(1, tracks.value[0].index)
        val albumTracks = server.takeRequest().path!!
        assertTrue(albumTracks.startsWith("/Users/u1/Items") && albumTracks.contains("ParentId=al1") && albumTracks.contains("IncludeItemTypes=Audio"))
    }

    @Test fun `music libraries are only the music collection type`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"lib-m","Name":"Movies","Type":"CollectionFolder","CollectionType":"movies"},
            {"Id":"lib-mu","Name":"Music","Type":"CollectionFolder","CollectionType":"music"}],"TotalRecordCount":2}"""))
        val libs = api.musicLibraries() as ApiResult.Ok
        assertEquals(listOf("lib-mu"), libs.value.map { it.id })
    }

    @Test fun `a song decodes its artists album and track number`() {
        val json = """{"Id":"s1","Name":"Let It Happen","Type":"Audio","Artists":["Tame Impala"],"AlbumArtists":["Tame Impala"],
            "Album":"Currents","AlbumId":"al1","AlbumPrimaryImageTag":"p","IndexNumber":1,"ParentIndexNumber":0,"RunTimeTicks":4500000000}"""
        val song = JellyfinJson.decodeFromString<JfItem>(json)
        assertTrue(song.isAudio)
        assertEquals("Tame Impala", song.musicArtist)
        assertEquals("Currents", song.album)
        assertEquals("1", song.trackLabel)
        assertEquals(450L, song.runtimeSeconds)
        assertEquals("2-3", JellyfinJson.decodeFromString<JfItem>("""{"Id":"s2","Type":"Audio","IndexNumber":3,"ParentIndexNumber":2}""").trackLabel)
        assertTrue(JellyfinJson.decodeFromString<JfItem>("""{"Id":"a","Type":"MusicAlbum"}""").isMusicAlbum)
        assertTrue(JellyfinJson.decodeFromString<JfItem>("""{"Id":"b","Type":"MusicArtist"}""").isMusicArtist)
    }

    @Test fun `audio stream addresses are built without the token`() {
        val urls = MediaUrls({ "http://jf.local:8096" }, { "dev" })
        val direct = urls.audioStream("s1", "ms-s1")!!
        assertTrue(direct.contains("/Audio/s1/stream") && direct.contains("static=true") && direct.contains("mediaSourceId=ms-s1") && !direct.contains("Token"))
    }

    @Test fun `a song without its own picture falls back to its album art`() {
        val urls = MediaUrls({ "http://jf.local" }, { "d" })
        val song = JfItem("s1", "Track", "Audio", albumId = "al1", albumPrimaryTag = "at")
        assertTrue(urls.poster(song)!!.contains("/Items/al1/Images/Primary"))
    }
}
