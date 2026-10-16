package com.everettpenne.thumper.ui.glass

import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

/**
 * A pop-up menu that is liquid glass, like the menus on iOS: a rounded panel that bends and blurs what is behind it, with the rim light,
 * that springs open from the corner nearest what you tapped. A pop-up lives in a window of its own, so the glass is told where that
 * window sits: the backdrop is drawn shifted back by the pop-up's position, so the panel bends the part of the page really behind it.
 * Without glass in scope it is Material's own menu.
 */
@Composable
fun GlassDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val backdrop = LocalOverlayBackdrop.current ?: LocalGlassBackdrop.current
    if (backdrop == null) { DropdownMenu(expanded, onDismissRequest, modifier, content = content); return }
    if (!expanded) return
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    var popupAt by remember { mutableStateOf(IntOffset.Zero) }
    var origin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    val provider = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                // Below the anchor with the menu's right edge on the anchor's when it would run off the screen; above when there is no room below.
                val rightAligned = anchorBounds.left + popupContentSize.width > windowSize.width - gap
                val x = (if (rightAligned) anchorBounds.right - popupContentSize.width else anchorBounds.left).coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap))
                val below = anchorBounds.bottom + gap
                val flip = below + popupContentSize.height > windowSize.height - gap && anchorBounds.top - gap - popupContentSize.height >= gap
                val y = if (flip) anchorBounds.top - gap - popupContentSize.height else below.coerceAtMost((windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap))
                popupAt = IntOffset(x, y)
                origin = TransformOrigin(((anchorBounds.left + anchorBounds.width / 2f - x) / popupContentSize.width.coerceAtLeast(1)).coerceIn(0f, 1f), if (flip) 1f else 0f)
                return popupAt
            }
        }
    }
    val open = remember { Animatable(0f, .001f) }
    LaunchedEffect(Unit) { open.animateTo(1f, spring(.72f, 520f, .001f)) }
    val shape = RoundedCornerShape(24.dp)
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = .45f)
    val shifted = rememberBackdrop(backdrop) { drawBackdrop -> translate(-popupAt.x.toFloat(), -popupAt.y.toFloat()) { drawBackdrop() } }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        Column(modifier
            .graphicsLayer { val g = .82f + .18f * open.value; scaleX = g; scaleY = g; alpha = open.value.coerceIn(0f, 1f); transformOrigin = origin }
            .widthIn(min = 200.dp, max = 300.dp).width(IntrinsicSize.Max)
            .drawBackdrop(backdrop = shifted, shape = { shape },
                effects = { glassColor(); blur(10f.dp.toPx()); lens(14f.dp.toPx(), 28f.dp.toPx(), chromaticAberration = true) },
                highlight = { GlassRim }, shadow = null, onDrawSurface = { drawRect(tint) })
            .padding(vertical = 8.dp)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
        }
    }
}

/** A row in a [GlassDropdownMenu]; it fills with a soft light while pressed, as iOS menu rows do. */
@Composable
fun GlassMenuItem(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, selected: Boolean = false, destructive: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val color = if (destructive) MaterialTheme.colorScheme.error else LocalContentColor.current
    Row(modifier.fillMaxWidth().heightIn(min = 46.dp)
        .background(if (pressed) LocalContentColor.current.copy(alpha = .12f) else Color.Transparent)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, color = color, style = com.everettpenne.thumper.ui.theme.IosType.callout, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, null, tint = color, modifier = Modifier.size(18.dp))
        else if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
    }
}
