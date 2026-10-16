package com.everettpenne.thumper.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.everettpenne.thumper.ui.theme.Thumper
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch

/**
 * The glass surface of a button or chip, as in the Backdrop library's own button: a capsule of the page behind it, bent by a small
 * lens and tinted, that swells and drifts under the finger with a light following it. [tint] colours it (the accent for a prominent
 * button); [surface] is a plain wash for the resting look. Without glass in scope it is a flat translucent fill.
 */
@Composable
internal fun Modifier.glassControl(shape: androidx.compose.ui.graphics.Shape, press: GlassPressState, tint: Color?, surface: Color?,
                                  lensHeight: Float, lensAmount: Float, shadowed: Boolean): Modifier {
    val frost = frostContainer()
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null) {
        return this.clip(shape).background(tint ?: surface ?: Color.Transparent).graphicsLayer { with(press) { swell() } }.then(press.light).then(press.watcher)
    }
    return drawBackdrop(
        backdrop = backdrop, shape = { shape },
        effects = { if (tint == null) { vibrancy(); blur(8f.dp.toPx()) } else { glassColor(); blur(2f.dp.toPx()) }; lens(lensHeight.dp.toPx(), lensAmount.dp.toPx(), chromaticAberration = true) },
        highlight = { GlassRim },
        shadow = { if (shadowed) Shadow(radius = 8f.dp, color = Color.Black.copy(alpha = .10f)) else null },
        layerBlock = { with(press) { deform() } },
        onDrawSurface = {
            if (tint != null) { drawRect(tint, blendMode = BlendMode.Hue); drawRect(tint.copy(alpha = .75f)) }
            if (surface != null) drawRect(if (tint == null) frost else surface)
        },
    ).then(press.light).then(press.watcher)
}

/** A pill action. [prominent] fills it with the accent (use for the one main action). */
@Composable
fun GlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, prominent: Boolean = false, accent: Color = Thumper.accent,
                content: @Composable RowScope.() -> Unit) {
    val ink = glassPalette().ink
    val press = rememberGlassPress()
    val shape = RoundedCornerShape(percent = 50)
    val content_ = (if (prominent) Color.White else ink).copy(alpha = if (enabled) 1f else .45f)
    Row(modifier.heightIn(min = 46.dp)
        .glassControl(shape, press, tint = if (prominent) accent.copy(alpha = if (enabled) 1f else .45f) else null,
            surface = if (prominent) null else ink.copy(alpha = if (enabled) .10f else .05f), lensHeight = 10f, lensAmount = 20f, shadowed = true)
        .clickable(interactionSource = null, indication = null, enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides content_) {
            androidx.compose.material3.ProvideTextStyle(com.everettpenne.thumper.ui.theme.IosType.subheadline.copy(fontWeight = FontWeight.SemiBold)) { content() }
        }
    }
}

@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, prominent: Boolean = false) =
    GlassButton(onClick, modifier, enabled, prominent) { Text(text, maxLines = 1) }

/** A round glass button with one icon, for toolbars. */
@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color? = null,
                    size: Int = 44) {
    val ink = glassPalette().ink
    val press = rememberGlassPress()
    Box(modifier.size(size.dp)
        .glassControl(CircleShape, press, tint = null, surface = ink.copy(alpha = .08f), lensHeight = (size / 4).toFloat(), lensAmount = (size / 2).toFloat(), shadowed = true)
        .clickable(interactionSource = null, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick)
        .semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint ?: ink, modifier = Modifier.size((size * 0.5f).dp))
    }
}

/** One choice among several. The selected one is a lit accent glass capsule; the others are flat and quiet but swell under the finger. */
@Composable
fun GlassChip(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    val ink = glassPalette().ink
    val press = rememberGlassPress()
    val shape = RoundedCornerShape(percent = 50)
    val look = if (selected) Modifier.glassControl(shape, press, tint = Thumper.accent, surface = null, lensHeight = 9f, lensAmount = 18f, shadowed = false)
        else Modifier.clip(shape).background(ink.copy(alpha = .09f)).border(1.dp, ink.copy(alpha = .12f), shape)
            .graphicsLayer { with(press) { swell() } }.then(press.light).then(press.watcher)
    Box(modifier.heightIn(min = 38.dp).then(look)
        .selectable(selected = selected, role = Role.RadioButton, interactionSource = null, indication = null, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides (if (selected) Color.White else ink)) {
            androidx.compose.material3.ProvideTextStyle(com.everettpenne.thumper.ui.theme.IosType.footnote.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)) { label() }
        }
    }
}

/**
 * The glass thumb of the switch: a white pill at rest that, while held, grows, turns clear and bends the track beneath it with a faint
 * colour fringe, and stretches as it moves. It sees only the [track] (not the page: the switch sits inside the page's own recording).
 */
@Composable
private fun Modifier.liquidThumb(track: LayerBackdrop, press: () -> Float, velocity: () -> Float): Modifier {
    val seen = rememberBackdrop(track) { drawTrack ->
        val p = press()
        scale(lerp(2f / 3f, .75f, p), lerp(0f, .75f, p)) { drawTrack() }
    }
    return drawBackdrop(
        backdrop = seen, shape = { RoundedCornerShape(percent = 50) },
        effects = { val p = press(); blur(8f.dp.toPx() * (1f - p)); lens(5f.dp.toPx() * p, 10f.dp.toPx() * p, chromaticAberration = true) },
        highlight = { val p = press(); Highlight.Ambient.copy(width = Highlight.Ambient.width / 1.5f, blurRadius = Highlight.Ambient.blurRadius / 1.5f, alpha = p) },
        shadow = { Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = .08f)) },
        innerShadow = { val p = press(); InnerShadow(radius = 4f.dp * p, alpha = p) },
        layerBlock = {
            val p = press(); val grown = lerp(1f, 1.5f, p); val v = velocity() / 50f
            scaleX = grown / (1f - (v * .75f).coerceIn(-.2f, .2f)); scaleY = grown * (1f - (v * .25f).coerceIn(-.2f, .2f))
        },
        onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - press())) },
    )
}

/** An iOS 26 toggle: tap it, or drag its thumb across and let go. */
@Composable
fun LiquidSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val density = androidx.compose.ui.platform.LocalDensity.current
    val trackWidth = 64.dp; val trackHeight = 28.dp; val thumbWidth = 40.dp; val thumbHeight = 24.dp
    val inset = (trackHeight - thumbHeight) / 2
    val travel = with(density) { (trackWidth - thumbWidth - inset * 2).toPx() }
    val scope = rememberCoroutineScope()
    val position = remember { Animatable(if (checked) 1f else 0f, .001f) }
    val press = remember { Animatable(0f, .001f) }
    var dragging by remember { mutableStateOf(false) }
    val latestChecked by rememberUpdatedState(checked)
    val latestChange by rememberUpdatedState(onCheckedChange)
    LaunchedEffect(checked) { if (!dragging) position.animateTo(if (checked) 1f else 0f, spring(.7f, 380f, .001f)) }
    val trackOff = Color(0xFF787880).copy(alpha = .38f)
    val trackBackdrop = rememberLayerBackdrop()
    Box(modifier.minimumInteractiveComponentSize().size(trackWidth, trackHeight)
        .pointerInput(isLtr, travel) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                scope.launch { press.animateTo(1f, spring(.5f, 300f, .001f)) }
                var moved = false; dragging = true
                drag(down.id) { change ->
                    val dx = (change.position - change.previousPosition).x
                    if (dx != 0f) { moved = true; val next = (position.value + (if (isLtr) dx else -dx) / travel).coerceIn(0f, 1f); scope.launch { position.snapTo(next) } }
                    change.consume()
                }
                dragging = false
                val target = if (moved) (if (position.value >= .5f) 1f else 0f) else (if (latestChecked) 0f else 1f)
                scope.launch { press.animateTo(0f, spring(.5f, 300f, .001f)) }
                scope.launch { position.animateTo(target, spring(.7f, 380f, .001f)) }
                if ((target == 1f) != latestChecked) latestChange(target == 1f)
            }
        }
        .semantics {
            role = Role.Switch; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            contentDescription = if (checked) "On" else "Off"; onClick { onCheckedChange(!checked); true }
        }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxSize().layerBackdrop(trackBackdrop).clip(RoundedCornerShape(percent = 50))
            .drawBehind { drawRect(lerpColor(trackOff, Thumper.green, position.value)) })
        Box(Modifier.graphicsLayer { val x = inset.toPx() + position.value * travel; translationX = if (isLtr) x else -x }
            .liquidThumb(trackBackdrop, { press.value }, { position.velocity * travel / 1000f * 20f })
            .size(thumbWidth, thumbHeight))
    }
}
