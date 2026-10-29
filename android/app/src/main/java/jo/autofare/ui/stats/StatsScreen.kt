package jo.autofare.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import jo.autofare.ui.components.*
import jo.autofare.ui.motion.Motion
import jo.autofare.ui.motion.enterProgress
import jo.autofare.ui.motion.stagger
import jo.autofare.ui.theme.FigureLarge
import jo.autofare.ui.theme.LocalAfColors

private fun easeOut(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return 1f - (1f - c) * (1f - c) * (1f - c)
}

private val HOURS = listOf(
    "6" to .28f, "8" to .50f, "10" to .38f, "12" to .26f, "14" to .44f,
    "16" to .90f, "18" to 1.0f, "20" to .66f, "22" to .54f, "24" to .32f,
)

@Composable
fun StatsScreen() {
    val af = LocalAfColors.current

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader("الإحصائيات", "هاد الأسبوع · 18 — 24 تشرين")

        Rule()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Kpi("312.80", "د.أ هاد الأسبوع", "↑ 18%", Modifier.weight(1f))
            VRule()
            Kpi("11.4", "د.أ بالساعة", "↑ 2.1 د.أ", Modifier.weight(1f))
        }
        Rule()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Kpi("84", "رحلة مقبولة", "من 247 طلب", Modifier.weight(1f), highlight = false)
            VRule()
            Kpi("0.9", "ثانية للقبول", "أسرع من 96%", Modifier.weight(1f))
        }
        Rule()

        SectionHeader("01", "الربح حسب الساعة")
        HourChart()

        Spacer(Modifier.height(18.dp))
        AfCard(Modifier.padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                SpeedLines(af.ink, Modifier.padding(top = 4.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "أحسن ساعاتك 4 — 7 العصر. لو شغّلت ساعتين زيادة بهالوقت بدل الصبح، " +
                        "تقديرنا بتكسب +46 د.أ بالأسبوع.",
                    fontSize = 12.5.sp, color = af.ink2, lineHeight = 23.sp,
                )
            }
        }

        SectionHeader("02", "من وين إجت المصاري")
        Column(Modifier.padding(horizontal = 20.dp)) {
            SplitRow("Jeeny", .68f, "212.40", Color(0xFF5B3FE8))
            SplitRow("Petra Ride", .32f, "100.40", Color(0xFF0E76CC))
        }

        SectionHeader("03", "ليش رفضنا الطلبات")
        Column(Modifier.padding(horizontal = 20.dp)) {
            ReasonRow("السعر تحت الحد", "59%")
            ReasonRow("الزبون بعيد", "31%")
            ReasonRow("منطقة ممنوعة", "10%")
            Hint("لو نزّلت الحد من 3.00 لـ 2.60 كنت قبلت 23 طلب زيادة — بس ربحك بالساعة كان نزل 0.8 د.أ. إعدادك هلق أحسن.")
        }
    }
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
private fun HourChart() {
    val af = LocalAfColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        val t = enterProgress(durationMillis = Motion.SWEEP)
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val gap = 5.dp.toPx()
            val shear = 6.dp.toPx()
            val barW = (size.width - gap * (HOURS.size - 1)) / HOURS.size
            HOURS.forEachIndexed { i, (_, v) ->
                val h = size.height * v * easeOut(stagger(t, i, HOURS.size))
                val x = i * (barW + gap)
                val top = size.height - h
                drawPath(
                    Path().apply {
                        moveTo(x + shear, top); lineTo(x + shear + barW, top)
                        lineTo(x + barW, size.height); lineTo(x, size.height); close()
                    },
                    color = if (v >= .9f) af.ink else af.rule2,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // The bars are drawn on a Canvas, which is always left-to-right. The labels
        // must be forced LTR too, or RTL mirrors them and every bar gets the wrong hour.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                HOURS.forEach { (label, _) ->
                    Text(
                        label,
                        fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = af.muted,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

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
                        .fillMaxWidth(fraction * grow)
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
