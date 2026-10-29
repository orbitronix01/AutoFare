package jo.autofare.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Motion rules for this app:
 *  - entrances only, no loops — a driver glancing at the screen must never wait
 *  - everything is alpha / transform / Canvas, nothing that triggers re-layout
 *  - nothing exceeds ~700ms including stagger
 *  - Compose honours the phone's "remove animations" setting for free, so a
 *    driver who has motion turned off simply gets the final state instantly
 */
object Motion {
    const val QUICK = 190
    const val ENTER = 520
    const val SWEEP = 780
}

/**
 * A 0→1 ramp that runs once when the composable appears.
 * Pass a [key] to replay it (e.g. the onboarding step index).
 */
@Composable
fun enterProgress(
    key: Any? = Unit,
    durationMillis: Int = Motion.ENTER,
    delayMillis: Int = 0,
): Float {
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        p.animateTo(
            1f,
            tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = FastOutSlowInEasing),
        )
    }
    return p.value
}

/** Sub-range of a master ramp, normalised to 0..1 — used for staggering. */
fun stagger(t: Float, index: Int, count: Int, overlap: Float = 0.55f): Float {
    if (count <= 1) return t
    val step = (1f - overlap) / (count - 1)
    val start = index * step
    val end = start + overlap
    return ((t - start) / (end - start)).coerceIn(0f, 1f)
}

/**
 * The onboarding wheel: a dashed track that fills clockwise like a gauge,
 * with a pip riding the head of the arc. Reads as "AutoFare is getting ready".
 */
@Composable
fun FillRing(
    progress: Float,
    diameter: Dp,
    track: Color,
    fill: Color,
    pip: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            val r = size.minDimension / 2 - stroke * 2
            val center = Offset(size.width / 2, size.height / 2)

            drawCircle(
                color = track,
                radius = r,
                center = center,
                style = Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(7.dp.toPx(), 7.dp.toPx())
                    ),
                ),
            )

            if (progress > 0.001f) {
                drawArc(
                    color = fill,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )

                val angle = (-90f + 360f * progress) * PI.toFloat() / 180f
                drawCircle(
                    color = pip,
                    radius = 5.5.dp.toPx(),
                    center = Offset(
                        center.x + r * cos(angle),
                        center.y + r * sin(angle),
                    ),
                )
            }
        }
        content()
    }
}
