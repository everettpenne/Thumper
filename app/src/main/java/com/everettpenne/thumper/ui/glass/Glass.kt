package com.everettpenne.thumper.ui.glass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow

/**
 * Liquid glass in the style of iOS 26, drawn with the Backdrop library (the same one Uno Launcher uses): a surface that blurs and
 * bends whatever sits behind it, with a lit rim. Every glass piece samples [LocalGlassBackdrop], the layer the screen's scrolling content is
 * recorded into, so bars, buttons and menus really refract the page as it moves under them. When it is null (no glass in this subtree) the
 * pieces fall back to plain translucent fills and stay readable.
 */
val LocalGlassBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * What pop-ups (menus, sheets) sample: the current screen's content layer. They live in windows of their own, so they can safely read a
 * layer that the main window is still recording; surfaces inside the content cannot (they would draw the layer into itself), and use
 * [LocalGlassBackdrop], which inside the content is only the plain page background.
 */
val LocalOverlayBackdrop = compositionLocalOf<Backdrop?> { null }

/** The wallpaper-less "ink" glass uses: dark text in the light theme, light text in the dark theme. */
data class GlassPalette(val ink: Color, val surface: Color, val tint: Color)

@Composable
fun glassPalette(): GlassPalette = if (isSystemInDarkTheme())
    GlassPalette(ink = Color(0xFFF2F2F7), surface = Color(0xFF1C1C1E), tint = Color.White.copy(alpha = .10f))
else GlassPalette(ink = Color(0xFF1C1C1E), surface = Color.White, tint = Color.White.copy(alpha = .38f))

/** The rim light shared by every glass surface, brightest at the upper left, so they all agree on where the light is. */
internal val GlassRim: Highlight = Highlight.Default.copy(
    width = 0.5f.dp, style = HighlightStyle.Default(intensity = 0.5f, angle = 45f, falloff = 1f))

/** Saturation like the library's `vibrancy()`. */
internal fun BackdropEffectScope.glassColor() = colorControls(brightness = 0f, contrast = 1f, saturation = 1.5f)

/**
 * A glass panel. [lensHeight] and [lensAmount] (dp) are how far the edge bends the picture behind it; they must fit inside the shape's
 * corner radius, so small controls pass smaller values. [shadowed] adds the library's soft shadow under larger surfaces.
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    tint: Color = glassPalette().tint,
    blurRadius: Float = 4f,
    lensHeight: Float = 16f,
    lensAmount: Float = 32f,
    shadowed: Boolean = true,
): Modifier {
    val backdrop = LocalGlassBackdrop.current ?: return this
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            glassColor()
            blur(blurRadius.dp.toPx())
            lens(lensHeight.dp.toPx(), lensAmount.dp.toPx(), depthEffect = true, chromaticAberration = true)
        },
        highlight = { GlassRim },
        shadow = { if (shadowed) Shadow.Default else null },
        onDrawSurface = { drawRect(tint) },
    )
}

internal fun lerpDp(a: Float, b: Float, t: Float) = lerp(a, b, t)

/** The light frost of the Backdrop catalog's slider track and tab panel: a lens-bent, vibrant, moderately blurred pane under a pale wash. */
@Composable
internal fun frostContainer(): Color = if (isSystemInDarkTheme()) Color(0xFF121212).copy(alpha = .4f) else Color(0xFFFAFAFA).copy(alpha = .4f)

/**
 * Lightly frosted glass, for the controls that sit in the bars (the tab bar, search button, back button, action pill and search field):
 * the same recipe as the catalog's slider track and tab panel (vibrancy, an 8dp blur, a lens at the edge, a pale wash). Falls back to the wash
 * alone when there is no glass in scope.
 */
@Composable
fun Modifier.frostedGlass(shape: Shape, lensHeight: Float = 12f, lensAmount: Float = 24f, shadowed: Boolean = false): Modifier {
    val container = frostContainer()
    val backdrop = LocalGlassBackdrop.current ?: return this.background(container, shape)
    return drawBackdrop(
        backdrop = backdrop, shape = { shape },
        effects = { vibrancy(); blur(8f.dp.toPx()); lens(lensHeight.dp.toPx(), lensAmount.dp.toPx(), chromaticAberration = true) },
        highlight = { GlassRim },
        shadow = { if (shadowed) Shadow.Default else null },
        onDrawSurface = { drawRect(container) },
    )
}
