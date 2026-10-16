package com.everettpenne.thumper.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.everettpenne.thumper.ui.theme.Thumper
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * A slider in the Backdrop catalog's style (and iOS 26's): a thin track with the accent filling it, and a white pill for a thumb that, when you
 * press it, grows into a clear lens that bends the track beneath it with a faint colour fringe, and stretches as you drag. [onValueChange] is called
 * while dragging (and on a tap on the track); [onValueChangeFinished] when the finger lifts. It also works over things it cannot see through, such
 * as a video: the lens refracts its own track.
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    accent: Color = Thumper.accent,
    trackColor: Color? = null,
    description: String = "Slider",
) {
    val backdrop = LocalGlassBackdrop.current ?: emptyBackdrop()
    val track = trackColor ?: if (isSystemInDarkTheme()) Color(0xFF787880).copy(alpha = .36f) else Color(0xFF787878).copy(alpha = .2f)
    val trackBackdrop = rememberLayerBackdrop()
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)

    BoxWithConstraints(modifier.fillMaxWidth().height(40.dp).semantics {
        contentDescription = description
        progressBarRangeInfo = ProgressBarRangeInfo(latestValue.coerceIn(valueRange), valueRange)
        setProgress { target -> latestChange(target.coerceIn(valueRange)); true }
    }, contentAlignment = Alignment.CenterStart) {
        val trackWidth = constraints.maxWidth
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val scope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        val drag = remember(scope) {
            DampedDragAnimation(
                animationScope = scope, initialValue = value, valueRange = valueRange, visibilityThreshold = (valueRange.endInclusive - valueRange.start) * .001f,
                initialScale = 1f, pressedScale = 1.5f, onDragStarted = { didDrag = false },
                onDragStopped = { if (didDrag) latestChange(targetValue); latestFinished?.invoke() },
                onDrag = { _, amount ->
                    if (!didDrag) didDrag = amount.x != 0f
                    val delta = (valueRange.endInclusive - valueRange.start) * (amount.x / trackWidth)
                    latestChange(if (isLtr) (targetValue + delta).coerceIn(valueRange) else (targetValue - delta).coerceIn(valueRange))
                },
            )
        }
        // the thumb follows the value, whoever moves it (the finger, or playback)
        LaunchedEffect(drag) { snapshotFlow { latestValue }.collectLatest { v -> if (drag.targetValue != v) drag.updateValue(v) } }

        Box(Modifier.layerBackdrop(trackBackdrop).align(Alignment.CenterStart)) {
            Box(Modifier.clip(RoundedCornerShape(percent = 50)).background(track)
                .pointerInput(scope) {
                    detectTapGestures { p ->
                        val delta = (valueRange.endInclusive - valueRange.start) * (p.x / trackWidth)
                        val target = (if (isLtr) valueRange.start + delta else valueRange.endInclusive - delta).coerceIn(valueRange)
                        drag.animateToValue(target); latestChange(target); latestFinished?.invoke()
                    }
                }.height(6.dp).fillMaxWidth())
            Box(Modifier.clip(RoundedCornerShape(percent = 50)).background(accent).height(6.dp)
                .layout { measurable, c ->
                    val placeable = measurable.measure(c)
                    val w = (c.maxWidth * drag.progress).roundToInt().coerceAtLeast(0)
                    layout(w, placeable.height) { placeable.place(0, 0) }
                })
        }

        Box(Modifier
            .graphicsLayer {
                translationX = (-size.width / 2f + trackWidth * drag.progress).coerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
            }
            .then(drag.modifier)
            .drawBackdrop(
                backdrop = rememberCombinedBackdrop(backdrop, rememberBackdrop(trackBackdrop) { drawTrack ->
                    val p = drag.pressProgress
                    scale(lerp(2f / 3f, 1f, p), lerp(0f, 1f, p)) { drawTrack() }
                }),
                shape = { RoundedCornerShape(percent = 50) },
                effects = { val p = drag.pressProgress; blur(8f.dp.toPx() * (1f - p)); lens(10f.dp.toPx() * p, 14f.dp.toPx() * p, chromaticAberration = true) },
                highlight = { val p = drag.pressProgress; Highlight.Ambient.copy(width = Highlight.Ambient.width / 1.5f, blurRadius = Highlight.Ambient.blurRadius / 1.5f, alpha = p) },
                shadow = { Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = .05f)) },
                innerShadow = { val p = drag.pressProgress; InnerShadow(radius = 4f.dp * p, alpha = p) },
                layerBlock = {
                    scaleX = drag.scaleX; scaleY = drag.scaleY
                    val v = drag.velocity / 10f
                    scaleX /= 1f - (v * .75f).coerceIn(-.2f, .2f); scaleY *= 1f - (v * .25f).coerceIn(-.2f, .2f)
                },
                onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - drag.pressProgress)) },
            )
            .size(40.dp, 24.dp))
    }
}
