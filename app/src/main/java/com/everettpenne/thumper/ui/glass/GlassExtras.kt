package com.everettpenne.thumper.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.everettpenne.thumper.ui.theme.IosType
import com.everettpenne.thumper.ui.theme.Thumper
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect

// ---- progressive blur --------------------------------------------------------------------------------------------------------

private const val PROGRESSIVE_BLUR = """
uniform shader content;
uniform float2 size;
layout(color) uniform half4 tint;
uniform float tintIntensity;
uniform float fadeFrom;
uniform float flip;

half4 main(float2 coord) {
    float y = mix(coord.y, size.y - coord.y, flip);
    float alpha = smoothstep(size.y, size.y * fadeFrom, y);
    return mix(content.eval(coord) * alpha, tint * alpha, tintIntensity);
}"""

/**
 * A strip that blurs what scrolls beneath it more and more strongly towards one edge, and washes it with [tint] (the Backdrop catalog's "progressive
 * blur"): the page runs under a bar and goes soft and quiet as it nears the edge, instead of being cut off by a flat scrim. [fromTop] puts the strong
 * end at the top edge (a top bar); otherwise at the bottom (the tab bar). On Android 12 and 12L, which cannot run the shader, it is a plain fade.
 */
@Composable
fun Modifier.progressiveBlur(fromTop: Boolean, tint: Color = MaterialTheme.colorScheme.background, tintIntensity: Float = .8f, fadeFrom: Float = .35f): Modifier {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null || Build.VERSION.SDK_INT < 33) {
        val colors = listOf(tint.copy(alpha = .94f), tint.copy(alpha = .8f), Color.Transparent)
        return this.background(Brush.verticalGradient(if (fromTop) colors else colors.reversed()))
    }
    return this.drawPlainBackdrop(
        backdrop = backdrop, shape = { RectangleShape },
        effects = {
            blur(4f.dp.toPx())
            val shader: RuntimeShader = obtainRuntimeShader("progressive-blur", PROGRESSIVE_BLUR).apply {
                setFloatUniform("size", size.width, size.height)
                setColorUniform("tint", tint.toArgb())
                setFloatUniform("tintIntensity", tintIntensity)
                setFloatUniform("fadeFrom", fadeFrom)
                setFloatUniform("flip", if (fromTop) 0f else 1f)
            }
            effect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        },
    )
}

// ---- circle buttons and flat-control press ----------------------------------------------------------------------------------

/**
 * A round glass button, for the small controls in rows and bars (a play button, an overflow "•••"). Clear by default; with [tint] it is a filled accent
 * disc. It swells and drifts under the finger with a light following it, as every glass control here does.
 */
@Composable
fun GlassCircleButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color? = null, description: String? = null, shadowed: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val ink = glassPalette().ink
    val press = rememberGlassPress()
    Box(modifier.size(size)
        .glassControl(CircleShape, press, tint = tint, surface = if (tint == null) ink.copy(alpha = .08f) else null, lensHeight = size.value / 4f, lensAmount = size.value / 2f, shadowed = shadowed)
        .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
        .semantics { if (description != null) contentDescription = description }, contentAlignment = Alignment.Center, content = content)
}

/** The press feel for a control that is not glass itself (an icon button inside a glass bar): it swells under the finger and a light follows it. */
@Composable
fun Modifier.pressSwell(): Modifier {
    val press = rememberGlassPress()
    return this.graphicsLayer { with(press) { swell() } }.then(press.light).then(press.watcher)
}

// ---- progress ----------------------------------------------------------------------------------------------------------------

/** A progress bar as a glass-era capsule: a quiet track, the accent filling it with a soft sheen along the top. With no [progress] it shows a slow sweep. */
@Composable
fun GlassProgressBar(progress: Float?, modifier: Modifier = Modifier, accent: Color = Thumper.accent) {
    val track = if (isSystemInDarkTheme()) Color(0xFF787880).copy(alpha = .36f) else Color(0xFF787878).copy(alpha = .2f)
    val sweep = if (progress == null) rememberInfiniteTransition(label = "sweep").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep").value else 0f
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(percent = 50)).background(track)) {
        Box(Modifier.fillMaxHeight().then(if (progress != null) Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)) else Modifier.fillMaxWidth(.3f))
            .graphicsLayer { if (progress == null) translationX = (size.width / .3f * 1.0f) * (sweep * 1.3f - .3f) }
            .clip(RoundedCornerShape(percent = 50)).background(accent)
            .drawBehind { drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .35f), Color.Transparent), endY = size.height * .6f)) })
    }
}

/** The pull-to-refresh indicator: a glass disc that follows the pull, with an arrow that turns over as it reaches its threshold, and a spinner while it loads. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.GlassRefreshIndicator(state: PullToRefreshState, isRefreshing: Boolean, topInset: Dp) {
    val fraction = state.distanceFraction
    if (fraction <= 0f && !isRefreshing) return
    val threshold = 80.dp
    Box(Modifier.align(Alignment.TopCenter).padding(top = topInset)
        .graphicsLayer {
            val pulled = fraction.coerceIn(0f, 1.4f)
            translationY = pulled * threshold.toPx() - size.height * 1.2f
            alpha = (pulled * 1.4f).coerceIn(0f, 1f); val s = .6f + .4f * pulled.coerceAtMost(1f); scaleX = s; scaleY = s
        }
        .size(44.dp).glassSurface(CircleShape, tint = glassPalette().ink.copy(alpha = .06f), blurRadius = 6f, lensHeight = 12f, lensAmount = 20f, shadowed = true), contentAlignment = Alignment.Center) {
        if (isRefreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Thumper.accent)
        else Icon(Icons.Rounded.ArrowDownward, "Pull to refresh", tint = Thumper.accent, modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = 180f * (fraction.coerceIn(0f, 1f) * 1.0f).let { if (it >= 1f) 1f else 0f } })
    }
}

// ---- alerts ------------------------------------------------------------------------------------------------------------------

/**
 * A confirmation as a floating glass card over a dimmed page (the Backdrop catalog's dialog, iOS's alert): a title, a few words, and two capsule buttons.
 * [destructive] makes the confirming button red. For forms and long lists use [GlassSheet]; this is for a quick yes or no.
 */
@Composable
fun GlassAlert(
    title: String, message: String?, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    dismissLabel: String = "Cancel", destructive: Boolean = false,
) {
    val backdrop = LocalOverlayBackdrop.current ?: LocalGlassBackdrop.current
    val dark = isSystemInDarkTheme()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, spring(.8f, 380f), label = "alert")
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (if (dark) .5f else .28f) * appear)).clickable(interactionSource = null, indication = null, onClick = onDismiss), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                Column(Modifier.padding(horizontal = 32.dp).widthIn(max = 420.dp).fillMaxWidth()
                    .graphicsLayer { alpha = appear; val s = .9f + .1f * appear; scaleX = s; scaleY = s }
                    .clickable(interactionSource = null, indication = null) {}
                    .glassSurface(RoundedCornerShape(40.dp), tint = if (dark) Color(0xFF121212).copy(alpha = .45f) else Color(0xFFFAFAFA).copy(alpha = .62f), blurRadius = 14f, lensHeight = 24f, lensAmount = 44f, shadowed = true)
                    .then(if (backdrop == null) Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(40.dp)) else Modifier)
                    .padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(title, style = IosType.title3.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassButton(dismissLabel, onDismiss, Modifier.weight(1f))
                        GlassButton({ onConfirm() }, Modifier.weight(1f), prominent = true, accent = if (destructive) Thumper.red else Thumper.accent) { Text(confirmLabel, maxLines = 1) }
                    }
                }
            }
        }
    }
}
