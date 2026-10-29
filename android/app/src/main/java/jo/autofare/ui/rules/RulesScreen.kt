package jo.autofare.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.data.RulesStore
import jo.autofare.rules.Mode
import jo.autofare.rules.RuleSet
import jo.autofare.ui.components.*
import jo.autofare.ui.theme.LocalAfColors
import java.util.Locale

private val MODES = listOf("كل الطلبات", "من سعر وفوق", "بين سعرين", "الربح بالدقيقة")

/** Index in [MODES] is the [Mode] ordinal — the screen and the engine share an order. */
private val APPS = listOf("jeeny" to "Jeeny", "petra" to "Petra Ride")

@Composable
fun RulesScreen() {
    val af = LocalAfColors.current
    val context = LocalContext.current
    val store = remember(context) { RulesStore(context) }

    var app by rememberSaveable { mutableStateOf(0) }
    val (appId, appName) = APPS[app]

    var rules by remember { mutableStateOf(store.get(appId)) }
    LaunchedEffect(appId) { rules = store.get(appId) }

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
        rules = change(rules).also { store.put(appId, it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader("قواعد القبول", "إحنا منقرّر بدالك — إنت بس حدّد شو بدك.")

        Segmented(APPS.map { it.second }, app, { app = it })

        SectionHeader("01", "القبول التلقائي")
        Panel {
            ToggleLine(
                "اقبل الطلبات بدالي بـ $appName",
                if (rules.enabled) {
                    "شغّال. أول ما يجي طلب بمواصفاتك منضغط قبول فوراً."
                } else {
                    "مطفي. منراقب الطلبات وما منضغط ولا شي."
                },
                rules.enabled,
                { update { it.copy(enabled = !it.enabled) } },
                showRule = false,
            )
            if (rules.enabled) {
                Hint(
                    "لازم خدمة AutoFare تكون مفعّلة من إعدادات الوصولية، وإذا الهاتف " +
                        "طفّاها لحالها بعد تحديث التطبيق لازم ترجع تفعّلها.",
                )
            }
        }

        SectionHeader("02", "طريقة الحساب")
        Segmented(MODES, rules.mode.ordinal, { picked ->
            update { it.copy(mode = Mode.entries[picked]) }
        })

        Spacer(Modifier.height(11.dp))
        Panel {
            Text(MODES[rules.mode.ordinal], fontWeight = FontWeight.Bold, fontSize = 15.sp, color = af.ink)
            Spacer(Modifier.height(7.dp))
            Text(
                modeExplainer(rules.mode),
                fontSize = 12.5.sp, color = af.muted, lineHeight = 21.sp,
            )

            when (rules.mode) {
                Mode.PER_MINUTE -> {
                    Stepper("اقبل إذا الربح بالدقيقة", fmt(rules.rate), "د.أ / دقيقة",
                        onMinus = { update { it.copy(rate = (it.rate - 0.05).coerceAtLeast(0.0)) } },
                        onPlus = { update { it.copy(rate = it.rate + 0.05) } })
                    Stepper("ولا تقبل أقل من", fmt(rules.floor), "د.أ",
                        onMinus = { update { it.copy(floor = (it.floor - 0.25).coerceAtLeast(0.0)) } },
                        onPlus = { update { it.copy(floor = it.floor + 0.25) } })
                }

                Mode.FLOOR ->
                    Stepper("يبدأ القبول من", fmt(rules.floor), "د.أ",
                        onMinus = { update { it.copy(floor = (it.floor - 0.25).coerceAtLeast(0.0)) } },
                        onPlus = { update { it.copy(floor = it.floor + 0.25) } })

                Mode.BAND -> {
                    Stepper("يبدأ القبول من", fmt(rules.floor), "د.أ",
                        onMinus = { update { it.copy(floor = (it.floor - 0.25).coerceAtLeast(0.0)) } },
                        onPlus = { update { it.copy(floor = it.floor + 0.25) } })
                    // Kept above the floor, or the band is empty and nothing is ever accepted.
                    Stepper("ولحد", fmt(rules.ceiling), "د.أ",
                        onMinus = {
                            update { it.copy(ceiling = (it.ceiling - 0.25).coerceAtLeast(it.floor)) }
                        },
                        onPlus = { update { it.copy(ceiling = it.ceiling + 0.25) } })
                }

                Mode.EVERYTHING -> Unit
            }

            Stepper("أقصى وقت للوصول للزبون", rules.maxEtaMin.toString(), "دقايق",
                onMinus = { update { it.copy(maxEtaMin = (it.maxEtaMin - 1).coerceAtLeast(1)) } },
                onPlus = { update { it.copy(maxEtaMin = it.maxEtaMin + 1) } })

            Spacer(Modifier.height(16.dp))
            Rule()
            Spacer(Modifier.height(14.dp))
            Text("جرّبها ع طلبات حقيقية من أمس", style = jo.autofare.ui.theme.SectionLabel, color = af.muted)
            Spacer(Modifier.height(10.dp))

            /*
             * Real offers off the driver's own shift on 2026-10-27, so the preview
             * is a rehearsal and not an illustration — these three are a Jeeny
             * 1.50 four minutes out, a Petra 1.58 one minute out, and a Jeeny 3.43
             * two minutes out.
             */
            SimRow("1.50 د.أ · الزبون 4 دقايق", 1.50, 4, rules)
            SimRow("1.58 د.أ · الزبون دقيقة", 1.58, 1, rules)
            SimRow("3.43 د.أ · الزبون دقيقتين", 3.43, 2, rules)
        }

        SectionHeader("03", "خيارات")
        Panel {
            ToggleLine(
                "اقبل الطلب اللي ما إله سعر",
                "بترا رايد بتبعت طلبات مكافأة بدون سعر. إذا شغّلتها منقبلها حسب الوقت بس.",
                rules.acceptNoPrice,
                { update { it.copy(acceptNoPrice = !it.acceptNoPrice) } },
                showRule = false,
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "القواعد تُحفظ لحالها — ما في زر حفظ.",
            fontSize = 11.5.sp, color = af.muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

/** One remembered offer, run through the rules the driver is looking at. */
@Composable
private fun SimRow(label: String, price: Double, etaMin: Int, rules: RuleSet) {
    val af = LocalAfColors.current
    val decision = jo.autofare.rules.decide(
        jo.autofare.detect.Offer(pkg = "", fare = price, etaMin = etaMin),
        // judged on the numbers, not on whether the switch happens to be on
        rules.copy(enabled = true),
    )
    val accepted = decision is jo.autofare.rules.Decision.Accept
    val verdict = when (decision) {
        is jo.autofare.rules.Decision.Accept -> "بنقبل"
        is jo.autofare.rules.Decision.Skip -> decision.reason.ar
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.5.sp, color = af.ink2, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(fmt(price / etaMin), fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = af.ink)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .background(
                    if (accepted) af.acceptBg else af.rejectBg,
                    RoundedCornerShape(5.dp),
                )
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text(
                verdict,
                fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                color = if (accepted) af.accept else af.reject,
            )
        }
    }
    Rule()
}

private fun modeExplainer(mode: Mode) = when (mode) {
    Mode.EVERYTHING -> "منقبل أي طلب يجيك، بس لازم الزبون يكون ضمن وقت الوصول اللي حاططه تحت."
    Mode.FLOOR -> "منقبل الطلبات اللي سعرها يساوي الرقم اللي حاططه أو أكثر."
    Mode.BAND -> "منقبل بس الطلبات اللي سعرها بين رقمين — مفيد لو بدك ترفض الرحلات الطويلة كمان."
    Mode.PER_MINUTE -> "بدل ما تحسب السعر لحاله — منقسم السعر ع الوقت. رحلة بـ 4 دنانير والزبون بعيد " +
        "10 دقايق أسوأ من رحلة بـ 3 دنانير والزبون جنبك."
}

private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
