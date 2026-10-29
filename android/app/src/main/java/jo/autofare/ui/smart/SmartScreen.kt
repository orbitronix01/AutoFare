package jo.autofare.ui.smart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import jo.autofare.ui.components.*
import jo.autofare.ui.motion.Motion
import jo.autofare.ui.motion.enterProgress
import jo.autofare.ui.theme.LocalAfColors

private fun easeOut(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return 1f - (1f - c) * (1f - c) * (1f - c)
}

@Composable
fun SmartScreen() {
    val af = LocalAfColors.current
    var goalOn by rememberSaveable { mutableStateOf(true) }
    var goal by rememberSaveable { mutableStateOf(60) }
    var eveningOn by rememberSaveable { mutableStateOf(true) }
    var morningOn by rememberSaveable { mutableStateOf(false) }
    var homeMode by rememberSaveable { mutableStateOf(false) }

    val earned = 47.50
    val progress = (earned / goal).coerceIn(0.0, 1.0).toFloat()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader("الأوضاع الذكية", "القواعد بتتغيّر لحالها حسب الوقت وشو ناقصك.")

        SectionHeader("01", "هدف اليوم")
        Panel {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("بدي أجمّع اليوم", fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = af.ink)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "كل ما تقرّب ع الهدف منصير أصعب بالقبول. وإذا متأخر منخفّف شوي.",
                        fontSize = 12.5.sp, color = af.muted, lineHeight = 21.sp,
                    )
                }
                Spacer(Modifier.width(14.dp))
                AfSwitch(goalOn) { goalOn = !goalOn }
            }

            Stepper("", goal.toString(), "د.أ",
                onMinus = { goal = (goal - 5).coerceAtLeast(5) },
                onPlus = { goal += 5 })

            Spacer(Modifier.height(14.dp))
            // fills on entry, and re-animates smoothly whenever the goal changes
            val target = progress * easeOut(enterProgress(durationMillis = Motion.SWEEP))
            val width by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(Motion.QUICK),
                label = "goal",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .background(af.sunk, RoundedCornerShape(99.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(width.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(af.ink, RoundedCornerShape(99.dp))
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("وصلت 47.50 من $goal", fontSize = 12.5.sp, color = af.muted)
                Text(
                    "باقي " + String.format(java.util.Locale.US, "%.2f", (goal - earned).coerceAtLeast(0.0)),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = af.ink,
                )
            }
        }

        SectionHeader("02", "أوقات الشغل")
        Rule()
        ProfileRow("16:00", "20:00", "ذروة العصر", "ربح بالدقيقة 0.75 · لحد 4 دقايق", live = true)
        ProfileRow("20:00", "01:00", "سهرة", "ربح بالدقيقة 0.60 · لحد 7 دقايق",
            checked = eveningOn, onToggle = { eveningOn = !eveningOn })
        ProfileRow("06:00", "10:00", "دوام الصبح", "من سعر وفوق 3.50 د.أ",
            checked = morningOn, onToggle = { morningOn = !morningOn })

        Spacer(Modifier.height(14.dp))
        PrimaryButton("+ ضيف وقت جديد", onClick = {}, filled = false,
            modifier = Modifier.padding(horizontal = 20.dp))

        SectionHeader("03", "الرجوع ع البيت")
        Panel {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("خلص دوامي — ردّني ع البيت", fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = af.ink)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "منقبل بس الرحلات الرايحة ع جهة بيتك. بترجع وإنت كاسب بدل ما ترجع فاضي.",
                        fontSize = 12.5.sp, color = af.muted, lineHeight = 21.sp,
                    )
                }
                Spacer(Modifier.width(14.dp))
                AfSwitch(homeMode) { homeMode = !homeMode }
            }
            Spacer(Modifier.height(14.dp))
            Rule()
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("بيتي", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = af.ink)
                    Spacer(Modifier.height(2.dp))
                    Text("صويلح · شارع الأردن", fontSize = 12.5.sp, color = af.muted)
                }
                Text("غيّر", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = af.ink)
            }
        }

        SectionHeader("04", "مناطق ما بدي إياها")
        Panel {
            Text("ما منقبل أي طلب زبونه بهالمناطق:", fontSize = 12.5.sp, color = af.muted)
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Chip("وسط البلد ✕", dashed = false)
                Chip("الهاشمي ✕", dashed = false)
            }
            Spacer(Modifier.height(7.dp))
            Chip("+ ضيف منطقة", dashed = true)
        }
    }
}

@Composable
private fun Chip(text: String, dashed: Boolean) {
    val af = LocalAfColors.current
    Box(
        Modifier
            .border(1.dp, af.rule2, RoundedCornerShape(99.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (dashed) af.muted else af.ink)
    }
}

@Composable
private fun ProfileRow(
    from: String,
    to: String,
    title: String,
    detail: String,
    live: Boolean = false,
    checked: Boolean = false,
    onToggle: () -> Unit = {},
) {
    val af = LocalAfColors.current
    Column(Modifier.background(if (live) af.raised else af.paper)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(from, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = af.muted)
                Text(to, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = af.muted)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = af.ink)
                Text(detail, fontSize = 11.5.sp, color = af.muted)
            }
            Spacer(Modifier.width(10.dp))
            if (live) {
                Box(
                    Modifier.background(af.ink, RoundedCornerShape(5.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text("شغّال", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = af.paper)
                }
            } else {
                AfSwitch(checked, onToggle)
            }
        }
        Rule()
    }
}
