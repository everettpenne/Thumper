package com.everettpenne.thumper.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drag handling that reports every move of a finger that went down on the element, without taking the touch from anything beneath it (the Backdrop
 * catalog's `inspectDragGestures`). A control can then follow a drag and still let a tap through.
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)
        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        val up = dragUntilUp(initialDown.id) { onDrag(it, it.positionChange()) }
        if (up == null) onDragCancel() else onDragEnd(up)
    }
}

private suspend inline fun AwaitPointerEventScope.dragUntilUp(pointerId: PointerId, onDrag: (PointerInputChange) -> Unit): PointerInputChange? {
    if (currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true) return null
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(pointerId: PointerId): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (change.changedToUpIgnoreConsumed()) {
            val other = event.changes.fastFirstOrNull { it.pressed } ?: return change
            pointer = other.id
        } else if (change.previousPosition != change.position) return change
    }
}

/**
 * A value that follows a dragging finger with damped springs, with the press and squash that make the Backdrop catalog's slider thumb and tab indicator
 * feel like liquid: [pressProgress] (0..1) while held, [scaleX]/[scaleY] that swell and settle at different speeds, and a smoothed [velocity] to stretch
 * the shape along its motion.
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val valueSpec = spring<Float>(1f, 1000f, visibilityThreshold)
    private val velocitySpec = spring<Float>(.5f, 300f, visibilityThreshold * 10f)
    private val pressSpec = spring<Float>(1f, 1000f, .001f)
    private val scaleXSpec = spring<Float>(.6f, 250f, .001f)
    private val scaleYSpec = spring<Float>(.7f, 250f, .001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressAnimation = Animatable(0f, .001f)
    private val scaleXAnimation = Animatable(initialScale, .001f)
    private val scaleYAnimation = Animatable(initialScale, .001f)
    private val mutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down -> onDragStarted(down.position); press() },
            onDragEnd = { onDragStopped(); release() },
            onDragCancel = { onDragStopped(); release() },
        ) { _, dragAmount -> onDrag(size, dragAmount) }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressAnimation.animateTo(1f, pressSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            withFrameNanos { }
            if (value != targetValue) {                                    // let the value arrive (nearly) before the press lets go
                val threshold = (valueRange.endInclusive - valueRange.start) * .025f
                snapshotFlow { valueAnimation.value }.filter { abs(it - valueAnimation.targetValue) < threshold }.first()
            }
            launch { pressAnimation.animateTo(0f, pressSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYSpec) }
        }
    }

    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        animationScope.launch { launch { valueAnimation.animateTo(target, valueSpec) { updateVelocity() } } }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutex.mutate {
                press()
                val target = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(target, valueSpec) }
                if (velocity != 0f) launch { velocityAnimation.animateTo(0f, velocitySpec) }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(System.currentTimeMillis(), Offset(value, 0f))
        val target = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(target, velocitySpec) }
    }
}

/**
 * The light that blooms under a finger and follows it across a glass panel (the Backdrop catalog's `InteractiveHighlight`). [position] says where the
 * light sits while it is held, given the panel's size and the touch: a tab bar can keep it centred on the selected tab.
 */
class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressSpec = spring<Float>(.5f, 300f, .001f)
    private val positionSpec = spring<Offset>(.5f, 300f, Offset.VisibilityThreshold)
    private val press = Animatable(0f, .001f)
    private val pos = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var start = Offset.Zero
    val pressProgress: Float get() = press.value
    val offset: Offset get() = pos.value - start

    val modifier: Modifier = Modifier.drawWithContent {
        val p = press.value
        if (p > 0f) {
            drawRect(Color.White.copy(alpha = .08f * p), blendMode = BlendMode.Plus)
            val at = position(size, pos.value).let { Offset(it.x.coerceIn(0f, size.width), it.y.coerceIn(0f, size.height)) }
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .15f * p), Color.Transparent), at, size.minDimension * 1.5f), blendMode = BlendMode.Plus)
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                start = down.position
                animationScope.launch { launch { press.animateTo(1f, pressSpec) }; launch { pos.snapTo(start) } }
            },
            onDragEnd = { animationScope.launch { launch { press.animateTo(0f, pressSpec) }; launch { pos.animateTo(start, positionSpec) } } },
            onDragCancel = { animationScope.launch { launch { press.animateTo(0f, pressSpec) }; launch { pos.animateTo(start, positionSpec) } } },
        ) { change, _ -> animationScope.launch { pos.snapTo(change.position) } }
    }
}
