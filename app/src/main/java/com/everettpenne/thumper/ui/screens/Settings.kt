package com.everettpenne.thumper.ui.screens

import com.everettpenne.updater.collectStatus
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import coil3.SingletonImageLoader
import com.everettpenne.thumper.BuildConfig
import com.everettpenne.thumper.data.DirectoryChoice
import com.everettpenne.thumper.data.api.ApiResult
import com.everettpenne.thumper.data.feed.FeedFetch
import com.everettpenne.thumper.data.feed.Opml
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.ui.components.*
import com.everettpenne.glass.*
import com.everettpenne.thumper.ui.theme.Thumper
import com.everettpenne.thumper.util.Format
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val SKIPS = listOf(10, 15, 30, 45, 60)
private val SPEEDS = listOf(0.8f, 1f, 1.2f, 1.5f, 2f)

@Composable
fun SettingsScreen(nav: Navigator, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val settings = graph.settings
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val choice by settings.directory.collectAsState(); val back by settings.skipBackSeconds.collectAsState(); val forward by settings.skipForwardSeconds.collectAsState(); val speed by settings.speed.collectAsState()
    val autoplay by settings.autoplay.collectAsState()
    val leveling by graph.musicSettings.leveling.collectAsState()
    val crossfade by graph.musicSettings.crossfadeMs.collectAsState()
    val levelState by graph.levels.state.collectAsState()
    val musicSnapshot by graph.music.snapshot.collectAsState()
    val linksInApp by settings.linksInApp.collectAsState(); val fullArticles by settings.fullArticles.collectAsState(); val readerScale by settings.readerScale.collectAsState()
    val wifiOnly by settings.wifiOnlyDownloads.collectAsState(); val deletePlayed by settings.deleteWhenPlayed.collectAsState(); val downloadBytes by graph.downloads.totalBytes.collectAsState(0L)
    val hasKeys by settings.hasPodcastIndexKeys.collectAsState(); val feeds by graph.library.feeds.collectAsState(); val refreshing by graph.refreshing.collectAsState()
    var key by remember { mutableStateOf(settings.keys()?.first.orEmpty()) }; var secret by remember { mutableStateOf(settings.keys()?.second.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableStateOf(SingletonImageLoader.get(context).diskCache?.size ?: 0L) }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val entries = try { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(picked)!!.use { Opml.parse(it) } } } catch (e: Exception) { message = "That file isn't an OPML list of feeds."; busy = false; return@launch }
            var done = 0; var missed = 0; val gate = Semaphore(4)
            coroutineScope {
                entries.map { e -> async { gate.withPermit {
                    val r = graph.fetcher.fetch(e.feedUrl)
                    val content = ((r as? ApiResult.Ok)?.value as? FeedFetch.Updated)?.content
                    if (content != null) graph.library.subscribe(content) else { graph.library.subscribePlaceholder(com.everettpenne.thumper.data.feed.FeedFetcher.normalizeUrl(e.feedUrl), e.title); missed++ }
                    done++; message = "Importing… $done of ${entries.size}"
                } } }.awaitAll()
            }
            message = "Added ${entries.size} feeds" + if (missed > 0) ". $missed couldn't be reached just now; they will fill in when they can." else "."
            busy = false
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(target)!!.use { it.write(Opml.write(feeds.map { f -> Opml.Entry(f.title, f.url, f.siteUrl) }).toByteArray()) } }
                .onSuccess { message = "Exported ${feeds.size} feeds." }.onFailure { message = "Couldn't write that file." }
        }
    }

    GlassScreen(bars = { GlassCenteredTopBar("Settings", onBack, Modifier.align(Alignment.TopCenter)) }) { contentPadding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding(), start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Group("Find Podcasts With", footer = "Apple's directory needs nothing. Podcast Index is an open, independent directory; its free API needs a key and secret from api.podcastindex.org. Either way only your search words are sent, and your shows' episodes always come straight from the shows' own feeds.") {
                DirectoryChoice.entries.forEach { c -> ChoiceRow(c.label, c == choice) { settings.setDirectory(c) } }
                if (choice == DirectoryChoice.PODCAST_INDEX) Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassTextField(key, { key = it }, "API key", leading = Icons.Rounded.Key)
                    GlassTextField(secret, { secret = it }, "API secret", leading = Icons.Rounded.Lock, password = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(if (hasKeys) "Update keys" else "Save keys", { settings.setKeys(key, secret); message = "Saved." }, enabled = key.isNotBlank() && secret.isNotBlank(), prominent = true)
                        GlassButton("Get a key", { uri.openUri("https://api.podcastindex.org/") })
                    }
                }
            }
            Group("Playback") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Labeled("Skip back") { SKIPS.forEach { s -> GlassChip(s == back, { settings.setSkipBack(s) }) { Text("${s}s") } } }
                    Labeled("Skip forward") { SKIPS.forEach { s -> GlassChip(s == forward, { settings.setSkipForward(s) }) { Text("${s}s") } } }
                    Labeled("Starting speed") { SPEEDS.forEach { s -> GlassChip(s == speed, { settings.setSpeed(s) }) { Text(Format.speed(s)) } } }
                }
                SwitchRow(Icons.Rounded.Autorenew, "Autoplay Next Episode", autoplay, settings::setAutoplay)
            }
            Group("Music", footer = "Leveling and crossfade apply to music only. Podcasts keep their own speed, sleep timer and speech audio.") {
                SwitchRow(Icons.Rounded.VolumeUp, "Volume Leveling", leveling, graph.musicSettings::setLeveling)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            levelState.running -> "Measuring ${levelState.done} of ${levelState.total}…"
                            levelState.error != null -> levelState.error!!
                            else -> "${levelState.measured} of ${musicSnapshot.tracks.size} songs measured"
                        },
                        style = MaterialTheme.typography.bodySmall, color = if (levelState.error != null) Thumper.red else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(if (levelState.running) "Measuring…" else "Measure Volume", {
                            graph.levels.analyze(musicSnapshot.tracks)
                        }, enabled = !levelState.running, prominent = true)
                        if (levelState.running) GlassButton("Cancel", { graph.levels.cancel() })
                    }
                }
                Column(Modifier.padding(16.dp)) {
                    Labeled("Crossfade") {
                        listOf(0 to "Off", 3_000 to "3s", 5_000 to "5s", 8_000 to "8s", 12_000 to "12s").forEach { (ms, label) ->
                            GlassChip(crossfade == ms, { graph.musicSettings.setCrossfadeMs(ms) }) { Text(label) }
                        }
                    }
                }
            }
            Group("Reading", footer = if (linksInApp) "Links open in Thumper's own browser, over the app. On GrapheneOS it is Vanadium's WebView that draws them." else
                "Links open in " + (if (com.everettpenne.browser.Browsers.hasVanadium(context)) "Vanadium." else "your browser.")) {
                SwitchRow(Icons.Rounded.Language, "Open Links in Thumper", linksInApp, settings::setLinksInApp)
                SwitchRow(Icons.Rounded.Article, "Load Full Articles", fullArticles, settings::setFullArticles)
                Column(Modifier.padding(16.dp)) {
                    Labeled("Text size") { listOf("S" to .9f, "M" to 1f, "L" to 1.15f, "XL" to 1.3f).forEach { (l, v) -> GlassChip(kotlin.math.abs(readerScale - v) < .01f, { settings.setReaderScale(v) }) { Text(l) } } }
                }
            }
            Group("Downloads", footer = "Downloaded episodes are saved in the app's own storage and play with no connection.") {
                SwitchRow(Icons.Rounded.Wifi, "Download Only on Wi-Fi", wifiOnly, settings::setWifiOnlyDownloads)
                SwitchRow(Icons.Rounded.AutoDelete, "Delete After Playing", deletePlayed, settings::setDeleteWhenPlayed)
                NavRow(Icons.Rounded.DownloadForOffline, "Manage Downloads", Format.bytes(downloadBytes) + " on this phone") { nav.openDownloads() }
            }
            Group("Library", footer = "OPML is the list of subscriptions every podcast app and feed reader can import and export, so you can bring yours here or take it anywhere.") {
                NavRow(Icons.Rounded.Refresh, if (refreshing) "Refreshing…" else "Refresh Everything Now", "${feeds.size} subscriptions") { scope.launch { val r = graph.refreshAll(force = true); if (r != null) message = "${r.updated} updated, ${r.failed.size} couldn't be reached." } }
                NavRow(Icons.Rounded.FileDownload, "Import Subscriptions", "From an OPML file") { import.launch("*/*") }
                NavRow(Icons.Rounded.FileUpload, "Export Subscriptions", "To an OPML file") { export.launch("thumper-subscriptions.opml") }
            }
            if (busy || message != null) Text(message ?: "", style = MaterialTheme.typography.bodyMedium, color = Thumper.accent, modifier = Modifier.padding(horizontal = 10.dp))
            Group("Storage") {
                NavRow(Icons.Rounded.DeleteSweep, "Clear Image Cache", "%.1f MB".format(cacheBytes / 1_048_576.0)) {
                    val loader = SingletonImageLoader.get(context); loader.memoryCache?.clear(); loader.diskCache?.clear(); cacheBytes = loader.diskCache?.size ?: 0L
                }
            }
            Group("About") {
                com.everettpenne.updater.LocalUpdater.current?.let { u -> NavRow(androidx.compose.material.icons.Icons.Rounded.SystemUpdate, "Check for Updates", u.collectStatus()) { u.onSettingsRowClick() } }
                Info(Icons.Rounded.Info, "Thumper ${BuildConfig.VERSION_NAME}", "A podcast player and feed reader with iOS-style liquid glass")
                Info(Icons.Rounded.Lock, "Private by design", "No account, no analytics, no tracking. Your subscriptions and listening history stay on this phone.")
            }
        }
    }
}

@Composable
private fun Labeled(title: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun Group(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(26.dp)), content = content)
        if (footer != null) Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@Composable
private fun RowShell(icon: ImageVector?, title: String, subtitle: String?, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (icon != null) Icon(icon, null, tint = Thumper.accent, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}
@Composable private fun Info(icon: ImageVector, title: String, subtitle: String) = RowShell(icon, title, subtitle, null) {}
@Composable private fun NavRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) = RowShell(icon, title, subtitle, onClick) { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) = RowShell(null, title, null, onClick) { if (selected) Icon(Icons.Rounded.Check, "Selected", tint = Thumper.accent) }

@Composable private fun SwitchRow(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) = RowShell(icon, title, null, { onChange(!checked) }) { LiquidSwitch(checked, onChange) }
