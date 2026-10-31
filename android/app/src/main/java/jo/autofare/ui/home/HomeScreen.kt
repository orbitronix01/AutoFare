package jo.autofare.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import jo.autofare.service.AutoFareService
import jo.autofare.data.DETECTED_APPS
import jo.autofare.data.History
import jo.autofare.data.RideApp
import jo.autofare.data.RulesStore
import jo.autofare.rules.Mode
import jo.autofare.rules.RuleSet
import jo.autofare.ui.components.*
import jo.autofare.ui.motion.Motion
import jo.autofare.ui.motion.enterProgress
import jo.autofare.ui.minutes
import jo.autofare.ui.motion.stagger
import jo.autofare.ui.rememberHistory
import jo.autofare.ui.requests
import jo.autofare.ui.theme.*
import java.util.Locale

/** Decelerating curve — things arrive quickly then settle. */
private fun easeOut(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return 1f - (1f - c) * (1f - c) * (1f - c)
}

/** How many slots the sparkline shows, and how wide each one is. */
private const val SPARK_BUCKETS = 12
private const val SPARK_BUCKET_MS = 60 * 60 * 1000L

/**
 * Everything the screen shows, read in one pass.
 *
 * One query set per change rather than six, because every figure here comes out
 * of the same table and recomposition would otherwise hit SQLite repeatedly for
 * numbers that were all decided at the same instant.
 */
private data class HomeData(
    val today: History.Summary,
    val week: History.Summary,
    val spark: List<Double>,
    val recent: List<History.Row>,
)

/** Dinars, the way the rest of the app prints them. */
private fun jod(v: Double): String = String.format(Locale.US, "%.2f", v)

@Composable
fun HomeScreen() {
    val data = rememberHistory { h ->
        val now = System.currentTimeMillis()
        HomeData(
            today = h.summary(History.startOfToday(now)),
            week = h.summary(History.startOfWeek(now)),
            spark = h.buckets(
                fromAt = now - SPARK_BUCKETS * SPARK_BUCKET_MS,
                bucketMs = SPARK_BUCKET_MS,
                buckets = SPARK_BUCKETS,
            ),
            recent = h.recent(4),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        TopLine()
        ShiftBlock(data)

        Spacer(Modifier.height(22.dp))
        ScoreBlock(data)

        SectionHeader("01", "التطبيقات")
        Rule()
        AppRows()

        SectionHeader("02", "آخر القرارات")
        Rule()
        DecisionList(data?.recent)

        AdviceCard(data)
    }
}

/** Just the mark. Settings lives in the navigation bar, not up here. */
@Composable
private fun TopLine() {
    val af = LocalAfColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(jo.autofare.R.drawable.logo_mark),
            contentDescription = "AutoFare",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(af.ink),
            modifier = Modifier.height(24.dp),
        )
    }
}

@Composable
private fun ShiftBlock(data: HomeData?) {
    val af = LocalAfColors.current
    val armed = anyAppArmed()

    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // the app's own term, not "الدوام" — this band is AutoFare's state,
            // not the driver's shift, and it matches Reason.OFF word for word
            StatusBand(if (armed) "القبول التلقائي شغّال" else "القبول التلقائي مطفي", armed)
        }

        // the day's take counts up — the one number a driver opens the app for
        val target = data?.today?.earnings ?: 0.0
        val countUp = enterProgress(durationMillis = Motion.SWEEP)
        val shown = if (data == null) "—" else jod(target * easeOut(countUp))

        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(shown, style = FigureHuge, color = af.ink)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("أرباح اليوم", fontSize = 12.sp, color = af.muted)
        }

        Sparkline(data?.spark, Modifier.padding(top = 20.dp))

        Spacer(Modifier.height(20.dp))
        Rule()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Stat(
                value = data?.today?.perHour?.let { jod(it) } ?: "—",
                label = "د.أ بالساعة",
                modifier = Modifier.weight(1f),
            )
            VRule()
            Stat(
                value = data?.today?.acceptRate?.let { "${(it * 100).toInt()}%" } ?: "—",
                label = "نسبة القبول",
                modifier = Modifier.weight(1f),
            )
        }
        Rule()
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

/**
 * Slanted bars — same lean as the logo.
 *
 * Scaled against the tallest bar in the window rather than a fixed ceiling, so a
 * quiet morning still reads as a shape instead of a flat line.
 */
@Composable
private fun Sparkline(values: List<Double>?, modifier: Modifier = Modifier) {
    val af = LocalAfColors.current
    val bars = values ?: List(SPARK_BUCKETS) { 0.0 }
    val peak = bars.maxOrNull() ?: 0.0
    val t = enterProgress(durationMillis = Motion.ENTER)

    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        val gap = 3.dp.toPx()
        val shear = 5.dp.toPx()
        val barW = (size.width - gap * (bars.size - 1)) / bars.size

        bars.forEachIndexed { i, v ->
            val grow = easeOut(stagger(t, i, bars.size))
            // an empty slot still draws a stub, so the grid of the chart survives
            val ratio = if (peak <= 0.0) 0.06f else (v / peak).toFloat().coerceAtLeast(0.06f)
            val h = size.height * ratio * grow
            val x = i * (barW + gap)
            val top = size.height - h
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x + shear, top)
                lineTo(x + shear + barW, top)
                lineTo(x + barW, size.height)
                lineTo(x, size.height)
                close()
            }
            drawPath(path, color = if (peak > 0 && v >= peak * 0.7) af.ink else af.rule2)
        }
    }
}

/**
 * The day's score.
 *
 * Deliberately relative to the driver's own week rather than to any absolute
 * figure: a good hour in Amman is not a good hour everywhere, and the app has no
 * business inventing a benchmark. 50 means exactly his own average, 100 means
 * double it. Until there is a week to compare against there is no score, and the
 * card says so rather than showing a number it cannot justify.
 */
@Composable
private fun ScoreBlock(data: HomeData?) {
    val af = LocalAfColors.current
    val today = data?.today?.perHour
    val week = data?.week?.perHour

    val score: Int? =
        if (today != null && week != null && week > 0.0) {
            ((today / week) * 50).toInt().coerceIn(0, 99)
        } else null

    AfCard(Modifier.padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                    if (score != null) {
                        drawArc(
                            color = af.accept, startAngle = -90f,
                            sweepAngle = 360f * (score / 100f) * dial,
                            useCenter = false,
                            topLeft = Offset(inset, inset), size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Text(
                    if (score == null) "—" else "${(score * dial).toInt()}",
                    style = FigureMedium.copy(fontSize = 25.sp),
                    color = if (score == null) af.muted else af.ink,
                )
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                val head = when {
                    score == null -> "لسا بدري"
                    score >= 70 -> "يوم منيح"
                    score >= 45 -> "يوم عادي"
                    else -> "يوم بطيء"
                }
                val body = when {
                    score == null ->
                        "منحسبلك تقييم اليوم لما يصير عندك شغل أسبوع كامل منقارن عليه."
                    today != null && week != null && today >= week ->
                        "ربحك بالساعة اليوم ${jod(today)} د.أ — فوق متوسطك (${jod(week)})."
                    today != null && week != null ->
                        "ربحك بالساعة اليوم ${jod(today)} د.أ — تحت متوسطك (${jod(week)})."
                    else -> ""
                }
                Text(head, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = af.ink)
                Spacer(Modifier.height(4.dp))
                Text(body, fontSize = 11.5.sp, color = af.muted, lineHeight = 19.sp)
            }
        }
    }
}

/* ───────────────────────── the apps ───────────────────────── */

/**
 * True only when AutoFare can actually take a ride.
 *
 * Both halves are required. A rule being on means nothing if Android has the
 * accessibility service switched off — and EMUI switches it off after every
 * install — so a band reading الدوام شغّال off the rules alone tells a driver he
 * is covered during the exact shift where he is not.
 */
@Composable
private fun anyAppArmed(): Boolean {
    val context = LocalContext.current
    val store = remember(context) { RulesStore(context) }
    var rulesOn by remember { mutableStateOf(false) }
    var serviceOn by remember { mutableStateOf(AutoFareService.isEnabled(context)) }

    DisposableEffect(store) {
        rulesOn = DETECTED_APPS.any { store.get(it.id).enabled }
        val listener = store.observe { rulesOn = DETECTED_APPS.any { store.get(it.id).enabled } }
        onDispose { store.stopObserving(listener) }
    }

    // the only way to switch the service on is to leave and come back, so the
    // value read at first composition is stale precisely when it matters
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) serviceOn = AutoFareService.isEnabled(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    return rulesOn && serviceOn
}

@Composable
private fun AppRows() {
    val context = LocalContext.current
    val store = remember(context) { RulesStore(context) }

    DETECTED_APPS.forEach { app ->
        key(app.id) {
            var rules by remember { mutableStateOf(store.get(app.id)) }
            AppRow(
                app = app,
                rules = rules,
                onToggle = {
                    // writing straight through: a driver must not be able to leave
                    // the home toggle in a state the engine has not been told about
                    rules = rules.copy(enabled = !rules.enabled).also { store.put(app.id, it) }
                },
            )
        }
    }
}

@Composable
private fun AppRow(app: RideApp, rules: RuleSet, onToggle: () -> Unit) {
    val af = LocalAfColors.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(37.dp).background(app.badge, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(app.letter, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = af.ink)
                Text(ruleSummary(rules, app), fontSize = 11.5.sp, color = af.muted)
            }
            AfSwitch(rules.enabled) { onToggle() }
        }
        Rule()
    }
}

/** The driver's own rule, said back to him in one line. */
private fun ruleSummary(r: RuleSet, app: RideApp): String {
    val eta = "لحد ${minutes(r.maxEtaMin)}"
    return when (r.mode) {
        Mode.EVERYTHING -> "كل الطلبات · $eta"
        Mode.FLOOR -> "من ${jod(r.floor)} ${app.currency} وفوق · $eta"
        Mode.BAND -> "بين ${jod(r.floor)} و ${jod(r.ceiling)} ${app.currency} · $eta"
        Mode.PER_MINUTE -> "ربح بالدقيقة ${jod(r.rate)} ${app.currency} · $eta"
    }
}

/* ───────────────────────── decisions ───────────────────────── */

@Composable
private fun DecisionList(rows: List<History.Row>?) {
    val af = LocalAfColors.current
    if (rows == null) return
    if (rows.isEmpty()) {
        Text(
            "لساته ما إجا ولا طلب. شغّل تطبيق الرحلات وخلّي AutoFare شغّال.",
            fontSize = 12.sp, color = af.muted, lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        )
        Rule()
        return
    }
    rows.forEach { DecisionRow(it) }
}

@Composable
private fun DecisionRow(row: History.Row) {
    val af = LocalAfColors.current
    val app = DETECTED_APPS.firstOrNull { it.packageName == row.pkg }
    val appName = app?.name ?: row.pkg

    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(34.dp)
                    .background(if (row.accepted) af.accept else af.rule2, RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (row.accepted) "قبلنا — " else "رفضنا — ") + appName,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, color = af.ink,
                )
                Spacer(Modifier.height(3.dp))
                Text(detail(row), fontSize = 11.5.sp, color = af.muted, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                row.fare?.let { jod(it) } ?: "—",
                fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                color = if (row.accepted) af.ink else af.rule2,
            )
        }
        Rule()
    }
}

/** The one line under a decision: the route when known, the reason when not. */
private fun detail(row: History.Row): String {
    val bits = ArrayList<String>(3)

    val route = listOfNotNull(row.pickup, row.dropoff)
    if (route.size == 2) bits += "${route[0]} ← ${route[1]}"

    row.etaMin?.let { bits += minutes(it) }
    row.perMinute?.let { bits += "${jod(it)} للدقيقة" }

    // a refusal explains itself; the Reason enum already carries its own Arabic
    if (!row.accepted) {
        row.reason
            ?.let { name -> runCatching { jo.autofare.rules.Reason.valueOf(name).ar }.getOrNull() }
            ?.let { bits.add(0, it) }
    }

    return if (bits.isEmpty()) "—" else bits.joinToString(" · ")
}

/**
 * The one-line read on the day.
 *
 * Hidden entirely when there is nothing to say. An advice card that invents a
 * comparison is worse than no card.
 */
@Composable
private fun AdviceCard(data: HomeData?) {
    val af = LocalAfColors.current
    val today = data?.today ?: return
    if (today.seen == 0) return

    val refused = today.seen - today.accepted
    val line = buildString {
        append("من الصبح شفنا ${requests(today.seen)}")
        if (refused > 0) append(" ورفضنا $refused")
        append(".")
        val t = today.perHour
        val w = data.week.perHour
        if (t != null && w != null && w > 0) {
            val pct = (((t - w) / w) * 100).toInt()
            if (pct != 0) {
                append(if (pct > 0) " ربحك بالساعة أعلى $pct% من متوسطك."
                       else " ربحك بالساعة أقل ${-pct}% من متوسطك.")
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    AfCard(Modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            SpeedLines(af.ink, Modifier.padding(top = 4.dp))
            Spacer(Modifier.width(12.dp))
            Text(line, fontSize = 12.5.sp, color = af.ink2, lineHeight = 23.sp)
        }
    }
}
