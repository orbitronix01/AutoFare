package jo.autofare.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.ui.components.*
import jo.autofare.ui.motion.Motion
import jo.autofare.ui.motion.enterProgress
import jo.autofare.ui.motion.stagger
import jo.autofare.ui.theme.*

/** Decelerating curve — things arrive quickly then settle. */
private fun easeOut(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return 1f - (1f - c) * (1f - c) * (1f - c)
}

private data class Decision(
    val accepted: Boolean,
    val title: String,
    val detail: String,
    val price: String,
)

private val sampleDecisions = listOf(
    Decision(true, "قبلنا — جيني", "تل الرمان ← الصويفية · 4 دقايق · 1.30 للدقيقة", "5.20"),
    Decision(false, "رفضنا — جيني", "السعر تحت الحد · طلبت 3.00 وإجا 2.10", "2.10"),
    Decision(true, "قبلنا — بترا رايد", "الجاردنز ← خلدا · دقيقة وحدة للزبون", "4.00"),
    Decision(false, "رفضنا — جيني", "الزبون بعيد · 11 دقيقة وإنت حاطط 5", "6.80"),
)

private val sampleHours = listOf(.18f, .32f, .24f, .48f, .40f, .64f, .96f, .72f, .38f, .54f, .28f, .44f)

@Composable
fun HomeScreen(onOpenSettings: () -> Unit = {}) {
    val af = LocalAfColors.current
    var running by rememberSaveable { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        TopLine(onOpenSettings)
        ShiftBlock(running = running, onToggle = { running = !running })

        SectionHeader("01", "تقييم اليوم")
        ScoreBlock()

        SectionHeader("02", "التطبيقات")
        Rule()
        AppRow("Jeeny", "من 3.00 د.أ وفوق · لحد 5 دقايق", Color(0xFF5B3FE8), "J")
        AppRow("Petra Ride", "ربح بالدقيقة 0.60 د.أ", Color(0xFF0E76CC), "P")

        SectionHeader("03", "آخر القرارات")
        Rule()
        sampleDecisions.forEach { DecisionRow(it) }

        Spacer(Modifier.height(20.dp))
        AdviceCard()
    }
}

@Composable
private fun TopLine(onOpenSettings: () -> Unit) {
    val af = LocalAfColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(jo.autofare.R.drawable.logo_mark),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(af.ink),
                modifier = Modifier.height(24.dp),
            )
            Spacer(Modifier.width(9.dp))
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(jo.autofare.R.drawable.logo_word),
                contentDescription = "AutoFare",
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(af.ink),
                modifier = Modifier.height(13.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("الأحد · 25 تشرين", fontSize = 11.5.sp, color = af.muted)
            Spacer(Modifier.width(12.dp))
            Text(
                "الإعدادات",
                fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = af.ink,
                modifier = Modifier.clickable { onOpenSettings() }.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ShiftBlock(running: Boolean, onToggle: () -> Unit) {
    val af = LocalAfColors.current
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBand(if (running) "الدوام شغّال" else "الدوام واقف", running)
            Text("04:12", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = af.muted)
        }

        // the day's take counts up — the one number a driver opens the app for
        val countUp = enterProgress(durationMillis = Motion.SWEEP)
        val shown = String.format(java.util.Locale.US, "%.2f", 47.50 * easeOut(countUp))

        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(shown, style = FigureHuge, color = af.ink)
            Spacer(Modifier.width(10.dp))
            Text("د.أ", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = af.muted,
                modifier = Modifier.padding(bottom = 8.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("أرباح اليوم", fontSize = 12.sp, color = af.muted)
            Text("12 رحلة · 9 ساعات", fontSize = 12.sp, color = af.muted)
        }

        Sparkline(Modifier.padding(top = 20.dp))

        Spacer(Modifier.height(20.dp))
        Rule()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Stat("11.4", "د.أ بالساعة", Modifier.weight(1f))
            VRule()
            Stat("0.9", "ثانية للقبول", Modifier.weight(1f))
            VRule()
            Stat("61%", "نسبة القبول", Modifier.weight(1f))
        }
        Rule()

        PrimaryButton(
            text = if (running) "وقّف الدوام" else "ابدأ الدوام",
            filled = running,
            onClick = onToggle,
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    val af = LocalAfColors.current
    Column(
        modifier.padding(vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = FigureMedium, color = af.ink)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.5.sp, color = af.muted)
    }
}

/** Slanted bars — same lean as the logo. */
@Composable
private fun Sparkline(modifier: Modifier = Modifier) {
    val af = LocalAfColors.current
    val peak = sampleHours.max()
    // bars grow out of the baseline, left to right
    val t = enterProgress(durationMillis = Motion.ENTER)
    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        val gap = 3.dp.toPx()
        val shear = 5.dp.toPx()
        val barW = (size.width - gap * (sampleHours.size - 1)) / sampleHours.size
        sampleHours.forEachIndexed { i, v ->
            val grow = easeOut(stagger(t, i, sampleHours.size))
            val h = size.height * v * grow
            val x = i * (barW + gap)
            val top = size.height - h
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x + shear, top)
                lineTo(x + shear + barW, top)
                lineTo(x + barW, size.height)
                lineTo(x, size.height)
                close()
            }
            drawPath(path, color = if (v >= peak * 0.7f) af.ink else af.rule2)
        }
    }
}

@Composable
private fun ScoreBlock() {
    val af = LocalAfColors.current
    AfCard(Modifier.padding(horizontal = 20.dp)) {
    Row(
        Modifier.fillMaxWidth().padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // dial sweeps to the score, and the number counts with it
        val dial = easeOut(enterProgress(durationMillis = Motion.SWEEP))
        Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = af.rule, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = af.accept, startAngle = -90f, sweepAngle = 360f * 0.78f * dial,
                    useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Text("${(78 * dial).toInt()}", style = FigureMedium.copy(fontSize = 25.sp), color = af.ink)
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text("يوم منيح", fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = af.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "ربحك بالساعة فوق متوسطك. بس ضيّعت 3 طلبات حلوة وإنت بوضع الراحة — جرّب تقصّر الاستراحة.",
                fontSize = 11.5.sp, color = af.muted, lineHeight = 19.sp,
            )
        }
    }
    }
}

@Composable
private fun AppRow(name: String, rule: String, badge: Color, letter: String) {
    val af = LocalAfColors.current
    var on by remember { mutableStateOf(true) }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(37.dp).background(badge, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(letter, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = af.ink)
                Text(rule, fontSize = 11.5.sp, color = af.muted)
            }
            AfSwitch(on) { on = !on }
        }
        Rule()
    }
}

@Composable
private fun DecisionRow(d: Decision) {
    val af = LocalAfColors.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(34.dp)
                    .background(if (d.accepted) af.accept else af.rule2, RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = af.ink)
                Spacer(Modifier.height(3.dp))
                Text(d.detail, fontSize = 11.5.sp, color = af.muted, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                d.price,
                fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                color = if (d.accepted) af.ink else af.rule2,
            )
        }
        Rule()
    }
}

@Composable
private fun AdviceCard() {
    val af = LocalAfColors.current
    AfCard(Modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            SpeedLines(af.ink, Modifier.padding(top = 4.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "من الصبح رفضنا 14 طلب كانوا بيضيّعوا عليك وقت. ربحك بالساعة أعلى 18% من أمس.",
                fontSize = 12.5.sp, color = af.ink2, lineHeight = 23.sp,
            )
        }
    }
}

