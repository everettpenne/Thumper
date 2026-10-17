package com.everettpenne.thumper.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everettpenne.thumper.data.api.ApiResult
import com.everettpenne.thumper.data.api.Failure
import com.everettpenne.thumper.data.feed.FeedFetcher
import com.everettpenne.thumper.data.model.DirectoryShow
import com.everettpenne.thumper.data.model.stableId
import com.everettpenne.thumper.graph
import com.everettpenne.thumper.ui.components.*
import com.everettpenne.glass.GlassScreen
import com.everettpenne.glass.GlassTopBar
import com.everettpenne.glass.IosType
import com.everettpenne.thumper.ui.theme.Thumper
import kotlinx.coroutines.delay

/** Whether what was typed is an address rather than a search: something with no spaces and a dot, or a feed:/http scheme. */
internal fun looksLikeAddress(text: String): Boolean {
    val t = text.trim()
    return t.isNotEmpty() && !t.contains(' ') && (t.startsWith("http://", true) || t.startsWith("https://", true) || t.startsWith("feed:", true) || t.startsWith("pcast:", true) || Regex("""^[\w-]+(\.[\w-]+)+(:\d+)?(/.*)?$""").matches(t) || Regex("""^[\w-]+:\d{2,5}(/.*)?$""").matches(t))      // a host with a dot, or a bare host with a port (nas:8080/feed)
}

@Composable
private fun ShowRow(show: DirectoryShow, subscribed: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(show.imageUrl, 64.dp, radius = 14.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(show.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(show.author, show.genre).filter { it.isNotEmpty() }.joinToString("  ·  "), style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (subscribed) Icon(Icons.Rounded.CheckCircle, "Subscribed", tint = Thumper.accent)
    }
}

/** Search the directory for a show, or paste a feed address to add it. With nothing typed it shows what is popular. The field sits at the bottom, above the keyboard or the tab bar. */
@Composable
fun SearchScreen(nav: Navigator) {
    val graph = LocalContext.current.graph
    val choice by graph.settings.directory.collectAsState()
    val feeds by graph.library.feeds.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DirectoryShow>>(graph.popular[choice.name].orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Failure?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val subscribedIds = remember(feeds) { feeds.map { it.id }.toSet() }
    val address = looksLikeAddress(query)

    LaunchedEffect(query, choice, retry) {
        failure = null
        val q = query.trim()
        if (address) { results = emptyList(); loading = false; return@LaunchedEffect }
        loading = true
        if (q.isNotEmpty()) delay(400)
        val r = if (q.isEmpty()) graph.directory().popular() else graph.directory().search(q)
        when (r) { is ApiResult.Ok -> { results = r.value; if (q.isEmpty()) graph.popular[choice.name] = r.value }; is ApiResult.Failed -> { failure = r.reason; if (q.isNotEmpty()) results = emptyList() } }
        loading = false
    }

    GlassScreen(bars = {
        GlassTopBar("Search", Modifier.align(Alignment.TopCenter), subtitle = choice.label)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().padding(horizontal = 16.dp)
            .padding(bottom = if (imeVisible) 8.dp else 92.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + (if (com.everettpenne.thumper.playback.LocalPlayback.current.hasEpisode) 76.dp else 0.dp))) {
            GlassTextField(query, { query = it }, "Shows, or a feed address", leading = Icons.Rounded.Search, imeAction = ImeAction.Search, onSubmit = { if (address) nav.addFeed(query.trim()) })
        }
    }) { contentPadding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 12.dp, bottom = 240.dp)) {
            if (address) item {
                Row(Modifier.fillMaxWidth().clickable { nav.addFeed(query.trim()) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.RssFeed, null, tint = Thumper.accent, modifier = Modifier.size(32.dp))
                    Column { Text("Add this feed", style = MaterialTheme.typography.titleSmall); Text(query.trim(), style = IosType.caption1, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (!address && query.isBlank() && results.isNotEmpty()) item { SectionTitle("Popular on ${choice.label}") ; Spacer(Modifier.height(4.dp)) }
            if (loading && results.isEmpty()) item { LoadingRow() }
            failure?.let { f -> item { ErrorState(f) { retry++ } } }
            items(results, key = { it.feedUrl }) { show -> ShowRow(show, stableId(FeedFetcher.normalizeUrl(show.feedUrl)) in subscribedIds) { nav.openFeed(show.feedUrl) } }
            if (!loading && failure == null && !address && query.isNotBlank() && results.isEmpty()) item { EmptyState(Icons.Rounded.Search, "No shows found", "Try a different search, or paste the show's feed address.") }
        }
    }
}
