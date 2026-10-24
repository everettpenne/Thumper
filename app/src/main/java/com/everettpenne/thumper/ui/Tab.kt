package com.everettpenne.thumper.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.everettpenne.glass.GlassTabSpec

/** Thumper's top-level destinations. Listen and Read sit in the glass tab capsule; Search is the round button beside it. */
enum class Tab(override val route: String, override val label: String, override val icon: ImageVector) : GlassTabSpec {
    Listen("listen", "Listen", Icons.Rounded.Headphones),
    Read("read", "Read", Icons.Rounded.Article),
    Music("music", "Music", Icons.Rounded.LibraryMusic),
    Search("search", "Search", Icons.Rounded.Search);

    companion object { val bar = listOf(Listen, Read, Music) }
}
