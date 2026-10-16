package com.everettpenne.thumper.ui.glass

import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import com.everettpenne.thumper.ui.theme.Thumper

/** The bar across the top of a root screen: a large title on the left, glass buttons on the right, fading into the page beneath. */
@Composable
fun GlassTopBar(title: String, modifier: Modifier = Modifier, subtitle: String? = null, onBack: (() -> Unit)? = null,
                actions: @Composable RowScope.() -> Unit = {}) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(bg.copy(alpha = .94f), bg.copy(alpha = .8f), Color.Transparent)))) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onBack != null) GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Column(Modifier.weight(1f)) {
                Text(title, style = if (onBack != null) MaterialTheme.typography.headlineMedium else com.everettpenne.thumper.ui.theme.IosType.largeTitle.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            actions()
        }
    }
}

/** The bar of a pushed screen (a post, settings): a back button, the title centred with an optional line under it, and a button on the right. */
@Composable
fun GlassCenteredTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(bg.copy(alpha = .94f), bg.copy(alpha = .8f), Color.Transparent)))) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp).heightIn(min = 44.dp)) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, Modifier.align(Alignment.CenterStart))
            Column(Modifier.align(Alignment.Center).padding(horizontal = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

/** A glass capsule holding several icon buttons side by side, as the original's toolbar does. */
@Composable
fun GlassActionPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val ink = glassPalette().ink
    Row(modifier.height(48.dp)
        .glassSurface(RoundedCornerShape(percent = 50), tint = ink.copy(alpha = .06f).compositeOver(glassPalette().tint), blurRadius = 6f, lensHeight = 12f, lensAmount = 24f, shadowed = false)
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** One icon inside a [GlassActionPill]. */
@Composable
fun PillIcon(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = Thumper.accent) {
    val press = rememberGlassPress()
    Box(Modifier.size(width = 52.dp, height = 44.dp).graphicsLayer { with(press) { swell() } }.then(press.watcher)
        .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp)) }
}

/** How far the top bar reaches down, for the content's top padding (status bar plus the bar itself). */
val TopBarHeight = 72.dp

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Listen("listen", "Listen", Icons.Rounded.Headphones),
    Read("read", "Read", Icons.Rounded.Article),
    Search("search", "Search", Icons.Rounded.Search),
}

/**
 * The floating tab bar, laid out as in the original: a glass capsule with Listen and Read at the bottom left (the chosen one in a darker
 * pill with the accent), and Search as its own round glass button at the bottom right.
 */
@Composable
fun GlassTabBar(selected: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val ink = glassPalette().ink
    val tint = ink.copy(alpha = .05f).compositeOver(glassPalette().tint)
    Row(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.widthIn(max = 260.dp).weight(1f, fill = false).height(64.dp)
            .glassSurface(RoundedCornerShape(percent = 50), tint = tint, blurRadius = 8f, lensHeight = 24f, lensAmount = 32f).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(Tab.Listen, Tab.Read).forEach { tab ->
                val press = rememberGlassPress()
                val on = tab == selected
                Column(Modifier.weight(1f).fillMaxHeight().widthIn(min = 96.dp)
                    .then(if (on) Modifier.clip(RoundedCornerShape(percent = 50)).background(ink.copy(alpha = .10f)) else Modifier)
                    .graphicsLayer { with(press) { swell() } }.then(press.light).then(press.watcher)
                    .clickable(interactionSource = null, indication = null, role = Role.Tab) { onSelect(tab) }.semantics { contentDescription = tab.label },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(tab.icon, null, tint = if (on) Thumper.accent else ink, modifier = Modifier.size(26.dp))
                    Text(tab.label, style = com.everettpenne.thumper.ui.theme.IosType.caption1.copy(fontWeight = FontWeight.SemiBold), color = if (on) Thumper.accent else ink)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val press = rememberGlassPress()
        Box(Modifier.size(64.dp).glassSurface(CircleShape, tint = tint, blurRadius = 8f, lensHeight = 22f, lensAmount = 32f)
            .graphicsLayer { with(press) { swell() } }.then(press.light).then(press.watcher)
            .clickable(interactionSource = null, indication = null, role = Role.Tab) { onSelect(Tab.Search) }.semantics { contentDescription = Tab.Search.label },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Search, null, tint = if (selected == Tab.Search) Thumper.accent else ink, modifier = Modifier.size(30.dp))
        }
    }
}

/** Supplied by the root: the floating tab bar, drawn by each top-level screen so it refracts that screen's content. Null on pushed screens. */
val LocalTabBar = androidx.compose.runtime.compositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * A screen's glass layout. [content] (the scrolling page) is recorded into a layer; [bars] (top bar, search field, the tab bar) are drawn over
 * it and sample that layer, so the page really bends and blurs under them as it scrolls. Menus and sheets opened from anywhere in the screen
 * sample the same layer through [LocalOverlayBackdrop].
 */
@Composable
fun GlassScreen(modifier: Modifier = Modifier, bars: @Composable BoxScope.() -> Unit, content: @Composable BoxScope.() -> Unit) {
    val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val tabBar = LocalTabBar.current
    CompositionLocalProvider(LocalOverlayBackdrop provides backdrop) {
        Box(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background), content = content)
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                bars()
                if (tabBar != null) Box(Modifier.align(Alignment.BottomCenter)) { tabBar() }
            }
        }
    }
}


/** A bottom sheet that floats as an inset liquid-glass card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = .62f)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent, tonalElevation = 0.dp, scrimColor = Color.Black.copy(alpha = .22f), dragHandle = null) {
        CompositionLocalProvider(LocalGlassBackdrop provides (LocalOverlayBackdrop.current ?: LocalGlassBackdrop.current)) {
        Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp).fillMaxWidth()
            .glassSurface(RoundedCornerShape(32.dp), tint = surface, blurRadius = 8f, lensHeight = 24f, lensAmount = 48f, shadowed = false)
            .background(if (LocalGlassBackdrop.current == null) MaterialTheme.colorScheme.surface else Color.Transparent, RoundedCornerShape(32.dp))
            .navigationBarsPadding().padding(bottom = 8.dp)) {
            androidx.compose.material3.BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
            content()
        }
        }
    }
}
