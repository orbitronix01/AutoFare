package jo.autofare.ui.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.R
import jo.autofare.ui.theme.LocalAfColors
import kotlin.math.max
import kotlin.math.min

private const val TOTAL_MS = 1750

/**
 * Opening animation.
 *
 * The idea comes straight out of the logo: three speed lines streak in from the
 * right (the direction Arabic reads from), the "A" mark chases them down and
 * overtakes, then the wordmark settles underneath. It is the product's promise
 * in one and a half seconds — something fast went past, and AutoFare caught it.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val af = LocalAfColors.current
    val context = LocalContext.current

    // Respect the phone's "remove animations" accessibility setting.
    val animationsOff = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }

    // When animations are off we still show the brand, just held still: 0.88 is the
    // point where everything has arrived but the exit fade has not started. Starting
    // at 1f would render the whole screen at alpha 0 — a blank flash.
    val p = remember { Animatable(if (animationsOff) 0.88f else 0f) }

    LaunchedEffect(Unit) {
        if (!animationsOff) {
            p.animateTo(1f, tween(durationMillis = TOTAL_MS, easing = LinearEasing))
        } else {
            kotlinx.coroutines.delay(600)
        }
        onFinished()
    }

    val t = p.value
    val lines = seg(t, 0.00f, 0.34f)
    val mark = seg(t, 0.14f, 0.52f)
    val word = seg(t, 0.40f, 0.68f)
    val tag = seg(t, 0.58f, 0.86f)
    val exit = seg(t, 0.90f, 1.00f)

    Box(
        Modifier
            .fillMaxSize()
            .background(af.paper)
            .alpha(1f - exit),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            Box(
                Modifier.height(132.dp).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // the streak the mark is chasing
                SpeedStreak(
                    progress = lines,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .padding(horizontal = 24.dp),
                )

                // the mark arrives, slightly overshooting then settling
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.logo_mark),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(af.ink),
                    modifier = Modifier
                        .height(84.dp)
                        .offset(x = ((1f - ease(mark)) * -54).dp)
                        .scale(0.93f + 0.07f * ease(mark))
                        .alpha(min(1f, mark * 2.2f)),
                )
            }

            Spacer(Modifier.height(22.dp))

            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.logo_word),
                contentDescription = "AutoFare",
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(af.ink),
                modifier = Modifier
                    .height(30.dp)
                    .offset(y = ((1f - ease(word)) * 12).dp)
                    .alpha(word),
            )

            Spacer(Modifier.height(26.dp))

            Text(
                "ما تلحق ع الطلبات.\nخلّي AutoFare يلحق إلك.",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = af.muted,
                lineHeight = 25.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset(y = ((1f - ease(tag)) * 8).dp)
                    .alpha(tag),
            )
        }
    }
}

/**
 * Three tapered bars sweeping in from the right, staggered, each one
 * stretching as it travels and then snapping back to its resting width —
 * the same shapes used as a section motif throughout the app.
 */
@Composable
private fun SpeedStreak(progress: Float, modifier: Modifier = Modifier) {
    val af = LocalAfColors.current
    Canvas(modifier) {
        val barH = 5.dp.toPx()
        val gap = 11.dp.toPx()
        val shear = 7.dp.toPx()
        val restWidths = listOf(0.46f, 0.30f, 0.17f)
        val alphas = listOf(0.9f, 0.5f, 0.26f)

        restWidths.forEachIndexed { i, restFrac ->
            val local = seg(progress, i * 0.13f, 0.62f + i * 0.13f)
            if (local <= 0f) return@forEachIndexed
            val e = ease(local)

            // stretch on the way in, settle at rest width
            val stretch = 1f + 1.35f * (1f - e) * e * 4f
            val w = size.width * restFrac * stretch
            // travels from off the right edge to its resting spot
            val right = size.width * (1f - 0.04f * i) + (1f - e) * size.width * 0.9f
            val left = right - w

            val y = size.height / 2f - (barH * 3 + gap * 2) / 2f + i * (barH + gap)

            drawPath(
                Path().apply {
                    moveTo(left + shear, y)
                    lineTo(right + shear, y)
                    lineTo(right, y + barH)
                    lineTo(left, y + barH)
                    close()
                },
                color = af.ink,
                alpha = alphas[i] * min(1f, e * 3f) * (1f - seg(progress, 0.72f, 1f)),
            )
        }
    }
}

/** Normalise a sub-range of the master timeline to 0..1. */
private fun seg(t: Float, start: Float, end: Float): Float =
    ((t - start) / (end - start)).coerceIn(0f, 1f)

/** Smooth ease-out so things arrive fast and settle gently. */
private fun ease(x: Float): Float {
    val c = max(0f, min(1f, x))
    return 1f - (1f - c) * (1f - c) * (1f - c)
}
