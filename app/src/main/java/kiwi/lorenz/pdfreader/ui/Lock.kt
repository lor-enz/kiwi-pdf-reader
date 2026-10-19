package kiwi.lorenz.pdfreader.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

/**
 * A large padlock, drawn like Material's lock icon so its shackle can move. Decorative: the
 * text around it says what's locked.
 */
@Composable
fun Lock(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val body = remember {
        Path().apply {
            addRoundRect(RoundRect(160f, 320f, 800f, 880f, CornerRadius(80f)))
            addOval(Rect(center = Offset(480f, 600f), radius = 80f))
            fillType = PathFillType.EvenOdd
        }
    }
    val shackle = remember {
        Path().apply {
            moveTo(320f, 360f)
            lineTo(320f, 240f)
            arcTo(Rect(center = Offset(480f, 240f), radius = 160f), 180f, 180f, forceMoveTo = false)
            lineTo(640f, 360f)
        }
    }
    val shackleStroke = remember { Stroke(width = 80f) }
    Spacer(
        modifier
            .size(LockSize)
            .drawWithCache {
                val unit = size.width / GRID
                onDrawBehind {
                    scale(unit, pivot = Offset.Zero) {
                        drawPath(shackle, color, style = shackleStroke)
                        drawPath(body, color)
                    }
                }
            },
    )
}

private val LockSize = 72.dp
private const val GRID = 960f
