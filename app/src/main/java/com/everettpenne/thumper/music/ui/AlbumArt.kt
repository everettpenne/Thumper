package com.everettpenne.thumper.music.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.everettpenne.thumper.music.data.Artwork

@Composable
fun rememberArtwork(albumKey: String?): ImageBitmap? {
    val context = LocalContext.current
    val revision = Artwork.revision
    val bitmap by produceState<ImageBitmap?>(initialValue = null, albumKey, revision) {
        value = albumKey?.let { key -> Artwork.load(context, key)?.asImageBitmap() }
    }
    return bitmap
}

fun placeholderBrush(key: String?): Brush {
    val palettes = listOf(
        Color(0xFF4A3426) to Color(0xFF201712),
        Color(0xFF2C3A45) to Color(0xFF141C22),
        Color(0xFF3E2D3A) to Color(0xFF1C1418),
        Color(0xFF2F3B2E) to Color(0xFF161D15),
        Color(0xFF43392A) to Color(0xFF1E1A12),
        Color(0xFF332E46) to Color(0xFF171521),
    )
    val index = ((key?.hashCode() ?: 0) and 0x7fffffff) % palettes.size
    val (start, end) = palettes[index]
    return Brush.linearGradient(listOf(start, end))
}

@Composable
fun AlbumArt(
    albumKey: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    iconSize: Dp = 22.dp,
    iconTint: Color = Color.White.copy(alpha = .45f),
) {
    val art = rememberArtwork(albumKey)
    Box(modifier.clip(RoundedCornerShape(corner)).background(placeholderBrush(albumKey)), contentAlignment = Alignment.Center) {
        if (art != null) Image(art, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}
