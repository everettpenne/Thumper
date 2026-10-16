package com.everettpenne.thumper.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The press feel of the Backdrop library's own button: while a finger is down a control swells a few dp, drifts a little towards where
 * the finger drags and stretches along the drag, and a soft light blooms under the finger and follows it; on release it springs back.
 * It only watches touches (never consumes them), so the control's own click and semantics are untouched.
 */
class GlassPressState(private val scope: CoroutineScope) {
    private val spec = spring<Float>(.5f, 300f, .001f)
    private val press = Animatable(0f, .001f)
    private val position = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var start = Offset.Zero

    val progress: Float get() = press.value
    private val offset: Offset get() = position.value - start

    /** The light under the finger, drawn over the surface and under the content. */
    val light: Modifier = Modifier.drawWithContent {
        val p = press.value
        if (p > 0f) {
            drawRect(Color.White.copy(alpha = .08f * p), blendMode = BlendMode.Plus)
            val at = Offset(position.value.x.coerceIn(0f, size.width), position.value.y.coerceIn(0f, size.height))
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .15f * p), Color.Transparent), at, size.minDimension * 1.5f),
                blendMode = BlendMode.Plus)
        }
        drawContent()
    }

    val watcher: Modifier = Modifier.pointerInput(scope) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            start = down.position
            scope.launch { press.animateTo(1f, spec) }
            scope.launch { position.snapTo(start) }
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                scope.launch { position.snapTo(change.position) }
            }
            scope.launch { press.animateTo(0f, spec) }
            scope.launch { position.animateTo(start, spring(.5f, 300f, Offset.VisibilityThreshold)) }
        }
    }

    /** Swell, drift and stretch, for a surface's `layerBlock` (so the glass behind it is not scaled with it). */
    fun GraphicsLayerScope.deform() {
        val grow = 4f.dp.toPx() / size.height
        val scale = lerp(1f, 1f + grow, press.value)
        val maxOffset = size.minDimension
        val drag = offset
        translationX = maxOffset * tanh(.05f * drag.x / maxOffset)
        translationY = maxOffset * tanh(.05f * drag.y / maxOffset)
        val angle = atan2(drag.y, drag.x)
        scaleX = scale + grow * abs(cos(angle) * drag.x / size.maxDimension) * (size.width / size.height).fastCoerceAtMost(1f)
        scaleY = scale + grow * abs(sin(angle) * drag.y / size.maxDimension) * (size.height / size.width).fastCoerceAtMost(1f)
    }

    /** Just the swell, for flat controls. */
    fun GraphicsLayerScope.swell() {
        val scale = lerp(1f, 1f + 3f.dp.toPx() / size.height, press.value)
        scaleX = scale; scaleY = scale
    }
}

@Composable
fun rememberGlassPress(): GlassPressState {
    val scope = rememberCoroutineScope()
    return remember(scope) { GlassPressState(scope) }
}
