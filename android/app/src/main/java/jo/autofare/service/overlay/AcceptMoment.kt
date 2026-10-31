package jo.autofare.service.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.R
import jo.autofare.data.AppPrefs
import jo.autofare.ui.theme.FigureHuge
import jo.autofare.ui.theme.LocalAfColors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * لحظة القبول — the card that appears over the ride app once AutoFare has
 * pressed Accept.
 *
 * Strictly after. It is only ever built from the outcome of a press that has
 * already landed, posted to the main loop from the decision callback, so the
 * earliest it can start drawing is after the accessibility event that carried
 * the offer has been handled completely. And it cannot get in the way of the
 * next one: the engine refuses to accept twice inside eight seconds, and the
 * press goes through accessibility, which no window on top can block.
 *
 * What it must not do is sit on the ride app. Nobody has yet captured what
 * Jeeny shows after a successful accept — there may be a confirmation step — so
 * the card is a window the size of the card, near the top where the map is, not
 * a full-screen scrim. Touches anywhere else reach the ride app untouched. A tap
 * on the card closes it, and it closes itself after [SHOW_MS]; once it is gone
 * it intercepts nothing at all.
 */
class AcceptMoment(
    private val context: Context,
    private val prefs: AppPrefs,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var view: View? = null
    private var owner: OverlayOwner? = null
    private val dismiss = Runnable { hide() }

    /** Main thread only. Replaces a card still on screen rather than stacking. */
    fun show(card: MomentCard) {
        hide()
        if (!canDrawOverlays(context)) return

        val o = OverlayOwner()
        val v = overlayView(context, o, overlayDark(context, prefs)) {
            Moment(card, SHOW_MS, onDismiss = { main.post(dismiss) })
        }
        val (sw, _) = screenSize(wm)
        val width = min(sw - (2 * SIDE_DP * density).roundToInt(), (MAX_WIDTH_DP * density).roundToInt())
        val p = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (TOP_DP * density).roundToInt()
        }

        val added = runCatching { wm.addView(v, p) }
            .onFailure { android.util.Log.w(TAG, "accept card not shown", it) }
            .isSuccess
        if (!added) {
            o.destroy()
            return
        }
        view = v
        owner = o
        main.postDelayed(dismiss, SHOW_MS.toLong())
    }

    fun hide() {
        main.removeCallbacks(dismiss)
        wm.removeQuietly(view)
        owner?.destroy()
        view = null
        owner = null
    }

    private companion object {
        const val TAG = "AutoFareMoment"

        /** Long enough to read the fare and the route, short enough to be gone before he is. */
        const val SHOW_MS = 2_600

        const val SIDE_DP = 16
        const val MAX_WIDTH_DP = 420
        const val TOP_DP = 64
    }
}

/** The design's `.icept`: header, card with the fare, the math and the stamp, then the countdown. */
@Composable
private fun Moment(card: MomentCard, showMs: Int, onDismiss: () -> Unit) {
    val af = LocalAfColors.current

    // in: a short rise; the countdown bar drains for exactly as long as the card stays
    val enter = remember { Animatable(0f) }
    val left = remember { Animatable(1f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(180)) }
    LaunchedEffect(Unit) { left.animateTo(0f, tween(showMs, easing = LinearEasing)) }

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * -14.dp.toPx()
            }
            // the whole card closes it — a small × is no target for a driver at speed
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
            .padding(bottom = 8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = af.raised,
            border = BorderStroke(1.dp, af.rule),
            shadowElevation = 14.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
        ) {
            Column {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.logo_word),
                            contentDescription = "AutoFare",
                            colorFilter = ColorFilter.tint(af.ink),
                            modifier = Modifier.height(12.dp),
                        )
                        Text(card.source, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = af.muted)
                    }
                    Spacer(Modifier.height(12.dp))

                    // fare reads right-to-left like the rest of the Arabic: the
                    // Arabic-Indic figure at the start (right), then the unit, as
                    // "٥٫٢٠ د.أ" reads in a sentence
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            card.fare ?: "—",
                            style = FigureHuge.copy(fontSize = 48.sp, lineHeight = 48.sp),
                            color = af.ink,
                            // a fare is one figure: "٣٫٢٥" once broke after the ٫
                            maxLines = 1, softWrap = false,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "د.أ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = af.muted,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }

                    if (card.route != null || card.reach != null) {
                        Spacer(Modifier.height(8.dp))
                        card.route?.let {
                            Text(it, fontSize = 12.5.sp, color = af.ink2, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
                        }
                        card.reach?.let {
                            Text(it, fontSize = 12.5.sp, color = af.muted, lineHeight = 20.sp)
                        }
                    }

                    if (card.rows.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = af.rule)
                        Spacer(Modifier.height(8.dp))
                        card.rows.forEach { row -> MathRow(row) }
                    }
                }

                // the stamp — the one green block on the card
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(af.accept)
                        .padding(vertical = 15.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val onAccept = if (af.isDark) Color(0xFF0B1A14) else Color.White
                    Text("قبلناه إلك", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = onAccept)
                    Spacer(Modifier.width(11.dp))
                    Text(card.took, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = onAccept.copy(alpha = 0.8f))
                }
            }
        }

        // the countdown: drains to nothing as the card's time runs out
        Box(
            Modifier
                .padding(horizontal = 34.dp)
                .padding(top = 12.dp)
                .fillMaxWidth()
                .height(3.dp)
                .background(af.rule2, RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(left.value)
                    .height(3.dp)
                    .background(af.ink, RoundedCornerShape(2.dp)),
            )
        }

        // on a paper chip: under it is the ride app's map, and muted grey on a map is unreadable
        Text(
            listOfNotNull(card.why, "بتختفي لحالها، أو اكبس عليها لتسكّرها").joinToString("\n"),
            fontSize = 11.5.sp,
            color = af.muted,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(af.paper.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MathRow(row: MomentCard.Row) {
    val af = LocalAfColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.label,
            fontSize = 12.5.sp,
            fontWeight = if (row.key) FontWeight.Bold else FontWeight.Normal,
            color = if (row.key) af.ink else af.ink2,
        )
        // Arabic-Indic figures, right-to-left like the label: "٥٫٢٠ ÷ ٤" reads from
        // the right. No tracking, since a value can be a word too ("٤ دقائق").
        Text(
            row.value,
            style = FigureHuge.copy(
                fontSize = if (row.key) 17.sp else 12.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
            ),
            color = if (row.key) af.ink else af.ink2,
        )
    }
}
