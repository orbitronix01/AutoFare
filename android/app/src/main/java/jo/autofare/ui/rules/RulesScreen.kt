package jo.autofare.ui.rules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.data.DETECTED_APPS
import jo.autofare.data.RideApp
import jo.autofare.data.RulesStore
import jo.autofare.detect.Offer
import jo.autofare.rules.Decision
import jo.autofare.rules.Mode
import jo.autofare.rules.RuleSet
import jo.autofare.rules.decide
import jo.autofare.ui.components.*
import jo.autofare.ui.dinars
import jo.autofare.ui.fig
import jo.autofare.ui.minutes
import jo.autofare.ui.minutesUnit
import jo.autofare.ui.settings.openAccessibilitySettings
import jo.autofare.ui.settings.rememberReadiness
import jo.autofare.ui.theme.LocalAfColors
import jo.autofare.ui.arFixed
import jo.autofare.ui.arNum

/** The one app that sends bonus rides with no price; see [RulesStore.NO_PRICE_APPS]. */
private const val PETRA = "petra"

/**
 * The rules screen, built around the three questions a driver actually asks
 * himself: which apps, which rides, and how far will I drive to get one.
 *
 * It used to open on a tab per app and a four-way segmented control whose labels
 * ("من سعر وفوق", "بين سعرين") meant nothing until you had read the paragraph
 * under them. The modes are now a list of plain choices, each with a sentence
 * using his own current numbers, and the screen ends by reading the whole rule
 * back to him in one line — if that line is not what he meant, he can see it
 * before an offer does.
 */
@Composable
fun RulesScreen() {
    val af = LocalAfColors.current
    val context = LocalContext.current
    val store = remember(context) { RulesStore(context) }

    fun armedNow() = DETECTED_APPS.associate { it.id to store.isEnabled(it.id) }

    var rules by remember { mutableStateOf(store.shared()) }
    var armed by remember { mutableStateOf(armedNow()) }
    var petraNoPrice by remember { mutableStateOf(store.acceptsNoPrice(PETRA)) }

    // Home and the floating button flip the same switches while this screen sits
    // in the background; without this, the next edit here would show — or write —
    // a state the driver has already changed somewhere else
    DisposableEffect(store) {
        val listener = store.observe {
            rules = store.shared()
            armed = armedNow()
            petraNoPrice = store.acceptsNoPrice(PETRA)
        }
        onDispose { store.stopObserving(listener) }
    }

    /*
     * Every change is written the moment it is made, and there is no save button.
     *
     * A driver edits these at a traffic light and then locks his phone. A screen
     * that holds changes until a button is pressed means a shift spent accepting
     * rides by rules he believes he already changed — and he would have no way to
     * tell, because the screen would be showing him the new ones. The service is
     * watching the store and picks each change up as it lands.
     */
    fun update(change: (RuleSet) -> RuleSet) {
        rules = change(rules).also { store.putShared(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader("قواعد القبول", "قلّنا شو الطلب اللي بدك إياه، وإحنا منقبله عنك.")

        /* ── 01 which apps ── */
        SectionHeader("01", "من وين نقبل؟")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DETECTED_APPS.forEach { app ->
                AppSwitchCard(
                    app = app,
                    on = armed[app.id] == true,
                    onToggle = {
                        val next = armed[app.id] != true
                        armed = armed + (app.id to next)
                        store.setEnabled(app.id, next)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val ready = rememberReadiness()
        if (!ready.accessibility) {
            Text(
                // "إمكانية الوصول" is what the phone's own settings call it; "الوصولية"
                // sent the driver looking for a menu that does not exist
                "الهاتف طافي خدمة AutoFare، فما رح نقبل ولا طلب. اكبس هون وشغّلها من «إمكانية الوصول».",
                fontSize = 12.sp, color = af.flag, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .background(af.flagBg, RoundedCornerShape(12.dp))
                    .clickable { openAccessibilitySettings(context) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        /* ── 02 which rides ── */
        SectionHeader("02", "أي طلبات نقبل؟")
        Text(
            "اختار وحدة. نفس القاعدة لجيني وبترا.",
            fontSize = 12.sp, color = af.muted,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp),
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Mode.entries.forEach { mode ->
                ModeOption(
                    title = modeTitle(mode),
                    example = modeExample(mode, rules),
                    selected = rules.mode == mode,
                    onSelect = { update { it.copy(mode = mode) } },
                ) { ModeControls(mode, rules, ::update) }
            }
        }

        /* ── 03 how far ── */
        SectionHeader("03", "أقصى وقت لتوصل للزبون")
        Panel {
            Text(
                "إذا الزبون أبعد من هيك ما منقبل الطلب، مهما كانت أجرته. هاد الشرط شغّال مع كل الخيارات فوق.",
                fontSize = 12.5.sp, color = af.muted, lineHeight = 21.sp,
            )
            Stepper("", arNum(rules.maxEtaMin), minutesUnit(rules.maxEtaMin),
                onMinus = { update { it.copy(maxEtaMin = (it.maxEtaMin - 1).coerceAtLeast(1)) } },
                onPlus = { update { it.copy(maxEtaMin = it.maxEtaMin + 1) } })
        }

        /* ── 04 Petra's bonus rides ── */
        SectionHeader("04", "طلبات المكافأة — بترا بس")
        Panel {
            ToggleLine(
                "اقبل طلبات المكافأة بدون أجرة",
                if (petraNoPrice) {
                    "شغّال: إذا بعتت بترا طلب مكافأة (مشوار بعيد) بدون أجرة مكتوبة، منقبله إذا الزبون قريب بس."
                } else {
                    "مطفي: بترا أحياناً بتبعت طلب مكافأة بدون أجرة مكتوبة. هيك منتركه إلك تقرّر فيه."
                },
                petraNoPrice,
                {
                    petraNoPrice = !petraNoPrice
                    store.setAcceptNoPrice(PETRA, petraNoPrice)
                },
                showRule = false,
            )
        }

        /* ── 05 the rehearsal ── */
        SectionHeader("05", "جرّبها")
        Panel {
            Text(
                "هدول ٣ طلبات حقيقية إجت لسوّاق بعمّان. هيك كانت قواعدك رح تتصرّف معهم:",
                fontSize = 12.5.sp, color = af.muted, lineHeight = 21.sp,
            )
            Spacer(Modifier.height(6.dp))
            /*
             * Real offers off the driver's own shift on 2026-10-27, so the preview
             * is a rehearsal and not an illustration — these three are a Jeeny
             * 1.50 four minutes out, a Petra 1.58 one minute out, and a Jeeny 3.43
             * two minutes out.
             */
            SimRow(1.50, 4, rules)
            SimRow(1.58, 1, rules)
            SimRow(3.43, 2, rules, showRule = false)
        }

        /* ── the whole rule, read back ── */
        Spacer(Modifier.height(22.dp))
        SummaryCard(summary(rules, armed, petraNoPrice), anyOn = armed.values.any { it })

        Spacer(Modifier.height(14.dp))
        Text(
            "كل تغيير بينحفظ لحاله، ما في زر حفظ.",
            fontSize = 11.5.sp, color = af.muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

/**
 * One app's on/off, as a card with its badge. The same switch as the row on
 * Home — both write [RulesStore.setEnabled] — so they can never disagree.
 */
@Composable
private fun AppSwitchCard(app: RideApp, on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val af = LocalAfColors.current
    OutlinedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = af.raised),
        border = BorderStroke(if (on) 1.5.dp else 1.dp, if (on) af.accept else af.rule),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).background(app.badge, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(app.letter, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(app.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = af.ink, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (on) "شغّال" else "مطفي",
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    color = if (on) af.accept else af.muted,
                    modifier = Modifier.weight(1f),
                )
                AfSwitch(on, onToggle)
            }
        }
    }
}

/** One fare mode as a radio choice: a name, a sentence with his numbers, and its controls when picked. */
@Composable
private fun ModeOption(
    title: String,
    example: String,
    selected: Boolean,
    onSelect: () -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val af = LocalAfColors.current
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) af.raised else af.paper),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) af.ink else af.rule),
    ) {
        Column(
            Modifier
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(start = 6.dp, end = 16.dp, top = 10.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = af.ink, unselectedColor = af.muted),
                    modifier = Modifier.padding(10.dp),
                )
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(
                        title, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        color = if (selected) af.ink else af.ink2,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(example, fontSize = 12.5.sp, color = af.muted, lineHeight = 20.sp)
                }
            }
        }
        if (selected) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = controls)
        }
    }
}

/** The numbers each mode needs, and only those. */
@Composable
private fun ModeControls(mode: Mode, rules: RuleSet, update: ((RuleSet) -> RuleSet) -> Unit) {
    val floorStep: (Double) -> Unit = { d -> update { it.copy(floor = (it.floor + d).coerceAtLeast(0.0)) } }
    when (mode) {
        Mode.EVERYTHING -> Unit

        Mode.FLOOR ->
            Stepper("أقل أجرة بتقبلها", money2(rules.floor), "د.أ",
                onMinus = { floorStep(-0.25) }, onPlus = { floorStep(0.25) })

        Mode.BAND -> {
            Stepper("من", money2(rules.floor), "د.أ",
                onMinus = { floorStep(-0.25) },
                // the floor may not pass the ceiling, or the band is empty and nothing is ever accepted
                onPlus = { update { it.copy(floor = (it.floor + 0.25).coerceAtMost(it.ceiling)) } })
            Stepper("لحد", money2(rules.ceiling), "د.أ",
                onMinus = { update { it.copy(ceiling = (it.ceiling - 0.25).coerceAtLeast(it.floor)) } },
                onPlus = { update { it.copy(ceiling = it.ceiling + 0.25) } })
        }

        Mode.PER_MINUTE -> {
            Stepper("أقل ربح بالدقيقة", money2(rules.rate), "د.أ للدقيقة",
                onMinus = { update { it.copy(rate = (it.rate - 0.05).coerceAtLeast(0.0)) } },
                onPlus = { update { it.copy(rate = it.rate + 0.05) } })
            // the engine keeps the floor in this mode: a great rate on a tiny fare is still a tiny fare
            Stepper("وبرضو أقل أجرة بتقبلها", money2(rules.floor), "د.أ",
                onMinus = { floorStep(-0.25) }, onPlus = { floorStep(0.25) })
        }
    }
}

/**
 * One remembered offer, run through the rules on screen.
 *
 * Judged with the apps switched on and with no bonus-ride exception, because the
 * question here is "what do my numbers do", not "is anything armed right now" —
 * the summary underneath answers that one.
 */
@Composable
private fun SimRow(price: Double, etaMin: Int, rules: RuleSet, showRule: Boolean = true) {
    val af = LocalAfColors.current
    val decision = decide(Offer(pkg = "", fare = price, etaMin = etaMin), rules.copy(enabled = true, acceptNoPrice = false))
    val accepted = decision is Decision.Accept
    val verdict = when (decision) {
        is Decision.Accept -> "منقبله"
        is Decision.Skip -> decision.reason.ar
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(dinars(price), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = af.ink)
            Text(
                buildString {
                    append("الزبون على بعد ${minutes(etaMin)}")
                    if (rules.mode == Mode.PER_MINUTE) append(" · ${fig(price / etaMin)} للدقيقة")
                },
                fontSize = 12.sp, color = af.muted,
            )
        }
        Spacer(Modifier.width(10.dp))
        Surface(
            color = if (accepted) af.acceptBg else af.rejectBg,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                (if (accepted) "✓ " else "✗ ") + verdict,
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (accepted) af.accept else af.reject,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
    if (showRule) Rule()
}

/** The whole rule in one sentence, on the one card drawn in ink. */
@Composable
private fun SummaryCard(text: String, anyOn: Boolean) {
    val af = LocalAfColors.current
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.outlinedCardColors(containerColor = af.raised),
        border = BorderStroke(1.5.dp, if (anyOn) af.ink else af.flag),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("بالمختصر", style = jo.autofare.ui.theme.SectionLabel, color = af.muted)
            Spacer(Modifier.height(8.dp))
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = af.ink, lineHeight = 26.sp)
        }
    }
}

/* ───────────────────────── wording ───────────────────────── */

private fun modeTitle(mode: Mode) = when (mode) {
    Mode.EVERYTHING -> "أي طلب قريب"
    Mode.FLOOR -> "أجرة أقل شي"
    Mode.BAND -> "أجرة بين حدّين"
    Mode.PER_MINUTE -> "حسب الربح بالدقيقة"
}

/** One line per mode, in his numbers, so the choice explains itself before he picks it. */
private fun modeExample(mode: Mode, r: RuleSet) = when (mode) {
    Mode.EVERYTHING ->
        "منقبل كل طلب مهما كانت أجرته، بس لازم الزبون يكون قريب."
    Mode.FLOOR ->
        "منقبل إذا الأجرة ${dinars(r.floor)} أو أكثر."
    Mode.BAND ->
        "منقبل إذا الأجرة بين ${fig(r.floor)} و${dinars(r.ceiling)}. بتفيد إذا بدك تترك المشاوير الطويلة كمان."
    Mode.PER_MINUTE -> {
        // a worked example in his own numbers: the smallest quarter-dinar fare that
        // clears both his rate and his floor, so the example is always a "yes"
        val eta = r.maxEtaMin.coerceIn(1, 4)
        val fare = (Math.ceil(r.rate.coerceAtLeast(0.05) * eta * 4 - 1e-9) / 4.0).coerceAtLeast(r.floor)
        "منقسم الأجرة على الدقائق اللي بتاخدها لتوصل للزبون. مثلاً: طلب أجرته ${dinars(fare)} والزبون " +
            "على بعد ${minutes(eta)}، يعني ${fig(fare / eta)} للدقيقة. حدّك ${fig(r.rate)}، فمنقبله."
    }
}

/**
 * The whole rule as one sentence: which apps, how far, which fares.
 *
 * Built from the same fields the engine reads, so if this sentence is not what
 * he meant, the engine is not doing what he meant either.
 */
private fun summary(r: RuleSet, armed: Map<String, Boolean>, petraNoPrice: Boolean): String {
    val on = DETECTED_APPS.filter { armed[it.id] == true }
    if (on.isEmpty()) {
        return "${DETECTED_APPS.joinToString(" و") { it.name }} مطفيين، فما رح نقبل ولا طلب. شغّل واحد منهم من فوق."
    }

    val apps = when (on.size) {
        DETECTED_APPS.size -> "طلبات ${on.joinToString(" و") { it.name }}"
        else -> "طلبات ${on.joinToString(" و") { it.name }} بس"
    }
    val reach = "إذا الزبون على بعد ${minutes(r.maxEtaMin)} أو أقل"
    val fare = when (r.mode) {
        Mode.EVERYTHING -> "، مهما كانت الأجرة"
        Mode.FLOOR -> " والأجرة ${dinars(r.floor)} أو أكثر"
        Mode.BAND -> " والأجرة بين ${fig(r.floor)} و${dinars(r.ceiling)}"
        Mode.PER_MINUTE ->
            " والربح ${fig(r.rate)} د.أ للدقيقة أو أكثر، بشرط الأجرة ما تقل عن ${dinars(r.floor)}"
    }
    val bonus = if (petraNoPrice && armed[PETRA] == true) {
        " وطلبات المكافأة ببترا منقبلها حتى بدون أجرة، إذا الزبون قريب."
    } else "."
    return "رح نقبل $apps $reach$fare$bonus"
}

/** Two places, Arabic-Indic digits, for the steppers: `٢٫٥٠`. */
private fun money2(v: Double) = arFixed(v)
