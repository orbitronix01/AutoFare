package jo.autofare.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.data.DETECTED_APPS
import jo.autofare.data.History
import jo.autofare.rules.Reason
import jo.autofare.ui.components.*
import jo.autofare.ui.motion.Motion
import jo.autofare.ui.motion.enterProgress
import jo.autofare.ui.motion.stagger
import jo.autofare.ui.rememberHistory
import jo.autofare.ui.requests
import jo.autofare.ui.theme.FigureLarge
import jo.autofare.ui.theme.LocalAfColors
import java.util.Calendar
import java.util.Locale

private fun easeOut(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return 1f - (1f - c) * (1f - c) * (1f - c)
}

private fun jod(v: Double): String = String.format(Locale.US, "%.2f", v)

/**
 * Which hours get a bar.
 *
 * Every other hour from 06:00 to midnight. Drivers in Amman work evenings; the
 * small hours are shown folded into the last slot rather than given five empty
 * columns of their own.
 */
private val HOUR_SLOTS = listOf(6, 8, 10, 12, 14, 16, 18, 20, 22)

private data class StatsData(
    val week: History.Summary,
    val prevWeek: History.Summary,
    val byHour: Map<Int, Double>,
    val byApp: Map<String, Double>,
    val byReason: Map<String, Int>,
)

@Composable
fun StatsScreen() {
    val af = LocalAfColors.current

    val data = rememberHistory { h ->
        val now = System.currentTimeMillis()
        val weekStart = History.startOfWeek(now)
        val weekMs = 7 * 24 * 60 * 60 * 1000L
        StatsData(
            week = h.summary(weekStart),
            // the week before, so "↑ 18%" is a measurement instead of a decoration
            prevWeek = h.summary(weekStart - weekMs, weekStart - 1),
            byHour = h.byHourOfDay(weekStart),
            byApp = h.byApp(weekStart),
            byReason = h.byReason(weekStart),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader("الإحصائيات", weekLabel())

        Rule()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Kpi(
                value = data?.week?.earnings?.let { jod(it) } ?: "—",
                label = "د.أ هاد الأسبوع",
                delta = deltaText(data?.week?.earnings, data?.prevWeek?.earnings),
                modifier = Modifier.weight(1f),
                highlight = isUp(data?.week?.earnings, data?.prevWeek?.earnings),
            )
            VRule()
            Kpi(
                value = data?.week?.perHour?.let { jod(it) } ?: "—",
                label = "د.أ بالساعة",
                delta = deltaText(data?.week?.perHour, data?.prevWeek?.perHour),
                modifier = Modifier.weight(1f),
                highlight = isUp(data?.week?.perHour, data?.prevWeek?.perHour),
            )
        }
        Rule()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Kpi(
                value = data?.week?.accepted?.toString() ?: "—",
                label = "رحلة مقبولة",
                delta = data?.week?.let { "من ${requests(it.seen)}" } ?: "",
                modifier = Modifier.weight(1f),
                highlight = false,
            )
            VRule()
            Kpi(
                value = data?.week?.acceptRate?.let { "${(it * 100).toInt()}%" } ?: "—",
                label = "نسبة القبول",
                delta = "",
                modifier = Modifier.weight(1f),
                highlight = false,
            )
        }
        Rule()

        SectionHeader("01", "الربح حسب الساعة")
        HourChart(data?.byHour)

        BestHoursCard(data)

        SectionHeader("02", "الأرباح حسب التطبيق")
        Column(Modifier.padding(horizontal = 20.dp)) {
            val split = data?.byApp.orEmpty()
            val total = split.values.sum()
            if (total <= 0.0) {
                EmptyLine("لسا ما قبلنا ولا رحلة هاد الأسبوع.")
            } else {
                DETECTED_APPS.forEach { app ->
                    val amount = split[app.packageName] ?: 0.0
                    SplitRow(app.name, (amount / total).toFloat(), jod(amount), app.badge)
                }
            }
        }

        SectionHeader("03", "ليش رفضنا الطلبات")
        Column(Modifier.padding(horizontal = 20.dp)) {
            val reasons = data?.byReason.orEmpty()
            val total = reasons.values.sum()
            if (total == 0) {
                EmptyLine("ما رفضنا ولا طلب هاد الأسبوع.")
            } else {
                reasons.entries
                    .sortedByDescending { it.value }
                    .take(4)
                    .forEach { (name, count) ->
                        val label = runCatching { Reason.valueOf(name).ar }.getOrDefault(name)
                        ReasonRow(label, "${(count * 100 / total)}%")
                    }
            }
        }
    }
}

/** "هاد الأسبوع · 18 — 24 تشرين", built from the actual window. */
@Composable
private fun weekLabel(): String {
    val cal = Calendar.getInstance()
    val endDay = cal.get(Calendar.DAY_OF_MONTH)
    cal.timeInMillis = History.startOfWeek()
    val startDay = cal.get(Calendar.DAY_OF_MONTH)
    return "آخر ٧ أيام · $startDay — $endDay"
}

private fun isUp(now: Double?, before: Double?): Boolean =
    now != null && before != null && before > 0.0 && now > before

/** A change, or nothing at all when there is no earlier week to compare with. */
private fun deltaText(now: Double?, before: Double?): String {
    if (now == null || before == null || before <= 0.0) return ""
    val pct = (((now - before) / before) * 100).toInt()
    return when {
        pct > 0 -> "↑ $pct% عن الأسبوع اللي قبل"
        pct < 0 -> "↓ ${-pct}% عن الأسبوع اللي قبل"
        else -> "زي الأسبوع اللي قبل"
    }
}

@Composable
private fun EmptyLine(text: String) {
    val af = LocalAfColors.current
    Text(text, fontSize = 12.sp, color = af.muted, modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
private fun Kpi(
    value: String,
    label: String,
    delta: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = true,
) {
    val af = LocalAfColors.current
    Column(modifier.padding(horizontal = 20.dp, vertical = 17.dp)) {
        Text(value, style = FigureLarge, color = af.ink)
        Spacer(Modifier.height(7.dp))
        Text(label, fontSize = 11.5.sp, color = af.muted)
        Spacer(Modifier.height(5.dp))
        Text(
            delta,
            fontSize = 11.sp,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) af.accept else af.muted,
        )
    }
}

@Composable
private fun HourChart(byHour: Map<Int, Double>?) {
    val af = LocalAfColors.current

    // each slot owns itself and the hour after it, so two-hour columns cover the day
    val values = HOUR_SLOTS.map { h ->
        (byHour?.get(h) ?: 0.0) + (byHour?.get(h + 1) ?: 0.0)
    }
    val peak = values.maxOrNull() ?: 0.0

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        val t = enterProgress(durationMillis = Motion.SWEEP)
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val gap = 5.dp.toPx()
            val shear = 6.dp.toPx()
            val barW = (size.width - gap * (values.size - 1)) / values.size
            values.forEachIndexed { i, v ->
                val ratio = if (peak <= 0.0) 0.04f else (v / peak).toFloat().coerceAtLeast(0.04f)
                val h = size.height * ratio * easeOut(stagger(t, i, values.size))
                val x = i * (barW + gap)
                val top = size.height - h
                drawPath(
                    Path().apply {
                        moveTo(x + shear, top); lineTo(x + shear + barW, top)
                        lineTo(x + barW, size.height); lineTo(x, size.height); close()
                    },
                    color = if (peak > 0 && v >= peak * 0.9) af.ink else af.rule2,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // The bars are drawn on a Canvas, which is always left-to-right. The labels
        // must be forced LTR too, or RTL mirrors them and every bar gets the wrong hour.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                HOUR_SLOTS.forEach { hour ->
                    Text(
                        hour.toString(),
                        fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = af.muted,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * The read on the week.
 *
 * Names the driver's own best window instead of asserting one. Hidden until
 * there is enough in the table for "best" to mean anything — naming a peak hour
 * off two rides is noise with a confident voice.
 */
@Composable
private fun BestHoursCard(data: StatsData?) {
    val af = LocalAfColors.current
    val byHour = data?.byHour ?: return
    if (data.week.accepted < MIN_RIDES_FOR_ADVICE) return

    val best = byHour.maxByOrNull { it.value } ?: return
    if (best.value <= 0.0) return

    Spacer(Modifier.height(18.dp))
    AfCard(Modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            SpeedLines(af.ink, Modifier.padding(top = 4.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "أحسن ساعة إلك هاد الأسبوع ${best.key}:00 — جبت فيها ${jod(best.value)} د.أ.",
                fontSize = 12.5.sp, color = af.ink2, lineHeight = 23.sp,
            )
        }
    }
}

/** Below this, "your best hour" is one lucky ride rather than a pattern. */
private const val MIN_RIDES_FOR_ADVICE = 10

@Composable
private fun SplitRow(name: String, fraction: Float, amount: String, color: Color) {
    val af = LocalAfColors.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = af.ink,
                modifier = Modifier.width(76.dp),
            )
            val grow = easeOut(enterProgress(durationMillis = Motion.ENTER))
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(af.sunk, RoundedCornerShape(99.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((fraction * grow).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(color, RoundedCornerShape(99.dp))
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(amount, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = af.ink)
        }
        Rule()
    }
}

@Composable
private fun ReasonRow(label: String, pct: String) {
    val af = LocalAfColors.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 13.sp, color = af.ink, modifier = Modifier.weight(1f))
            Text(pct, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = af.ink)
        }
        Rule()
    }
}
