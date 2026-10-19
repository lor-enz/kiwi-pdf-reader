package kiwi.lorenz.pdfreader.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** How unlocking feels: all its timings and distances, to tune them in one place. */
object LockMotion {
    /** The shackle lifts until its short leg is out of the body... */
    const val LIFT_MS = 150
    /** ...then swings half a turn around its long leg, to the other side. */
    const val SWING_MS = 250
    /** After opening, the document fades in as the prompt fades out. */
    const val REVEAL_MS = 150
    /** A wrong password: the lock shakes from side to side, dying down. */
    const val SHAKE_MS = 400
    const val SHAKE_CYCLES = 3
    val ShakeDistance = 10.dp
    /** In units of the lock's 960-unit grid (Material's lock icon). */
    const val SHACKLE_LIFT = 100f
}

/** Opens and shakes a [Lock]; the animations run in the caller's scope. */
@Stable
class LockState(isOpen: Boolean = false) {
    internal val lifting = Animatable(if (isOpen) 1f else 0f)
    internal val swinging = Animatable(if (isOpen) 1f else 0f)
    internal val shaking = Animatable(0f)

    /** Returns once the lock is open. */
    suspend fun unlock() {
        lifting.animateTo(1f, tween(LockMotion.LIFT_MS, easing = FastOutSlowInEasing))
        swinging.animateTo(1f, tween(LockMotion.SWING_MS, easing = FastOutSlowInEasing))
    }

    suspend fun shake() {
        shaking.snapTo(0f)
        shaking.animateTo(1f, tween(LockMotion.SHAKE_MS, easing = LinearEasing))
    }
}

@Composable
fun rememberLockState(isOpen: Boolean = false) = remember { LockState(isOpen) }

/**
 * A large padlock, drawn like Material's lock icon so its shackle can move. Decorative: the
 * text around it says what's locked. It leaves room around it for the opened shackle; the
 * closed lock is centered.
 */
@Composable
fun Lock(state: LockState, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val body = remember {
        Path().apply {
            addRoundRect(RoundRect(160f, 320f, 800f, 880f, CornerRadius(80f)))
            addOval(Rect(center = Offset(480f, 600f), radius = 80f))
            fillType = PathFillType.EvenOdd
        }
    }
    // Round joins give the shackle a round top when it's seen edge-on, halfway through its swing.
    val shackleStroke = remember { Stroke(width = 80f, join = StrokeJoin.Round) }
    Spacer(
        modifier
            .size(width = LockSize * (GRID + 2 * SIDE_ROOM) / GRID, height = LockSize * (GRID + HEADROOM) / GRID)
            .graphicsLayer {
                val t = state.shaking.value
                translationX = LockMotion.ShakeDistance.toPx() * (1 - t) *
                    sin(t * LockMotion.SHAKE_CYCLES * 2 * PI).toFloat()
            }
            .drawWithCache {
                val unit = size.height / (GRID + HEADROOM)
                val shackle = Path()
                onDrawBehind {
                    shackle.rewind()
                    shackle.shackle(
                        lift = LockMotion.SHACKLE_LIFT * state.lifting.value,
                        swing = cos(state.swinging.value * PI).toFloat(),
                    )
                    scale(unit, pivot = Offset.Zero) {
                        translate(left = SIDE_ROOM, top = HEADROOM) {
                            drawPath(shackle, color, style = shackleStroke)
                            drawPath(body, color)
                        }
                    }
                }
            },
    )
}

/**
 * The shackle, lifted by [lift] and turned around its long left leg: seen from the front, a
 * point's distance from that leg shrinks with the turn, so [swing] runs from 1 (closed) through
 * 0 (edge-on) to -1 (swung to the other side). Its legs reach into the body: the long one so
 * deep that it never comes out, the short one just enough to come out when lifted.
 */
private fun Path.shackle(lift: Float, swing: Float) {
    fun x(x: Float) = LONG_LEG_X + (x - LONG_LEG_X) * swing
    moveTo(LONG_LEG_X, 520f - lift)
    lineTo(LONG_LEG_X, 240f - lift)
    // The arc as segments, as a squeezed arc can't be drawn with arcTo once it's edge-on.
    for (i in 1..ARC_SEGMENTS) {
        val angle = PI + PI * i / ARC_SEGMENTS
        lineTo(x(480f + 160f * cos(angle).toFloat()), 240f - lift + 160f * sin(angle).toFloat())
    }
    lineTo(x(640f), 360f - lift)
}

private val LockSize = 72.dp
private const val GRID = 960f
/** Room above the icon's grid for the lifted shackle. */
private const val HEADROOM = 200f
/** Room on both sides for the swung shackle, which ends up left of the body. */
private const val SIDE_ROOM = 80f
private const val LONG_LEG_X = 320f
private const val ARC_SEGMENTS = 32
