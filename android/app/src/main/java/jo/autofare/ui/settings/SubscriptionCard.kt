package jo.autofare.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.data.License
import jo.autofare.data.LicenseStore
import jo.autofare.ui.components.PrimaryButton
import jo.autofare.ui.days
import jo.autofare.ui.theme.FigureLarge
import jo.autofare.ui.theme.LocalAfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private val ARABIC_MONTHS = listOf(
    "كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران",
    "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول",
)

private fun arabicDate(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "${c.get(Calendar.DAY_OF_MONTH)} ${ARABIC_MONTHS[c.get(Calendar.MONTH)]}"
}

/**
 * The subscription, as the driver sees it.
 *
 * Everything on it used to be a literal — a fake key, a fake 27 days and a renew
 * button wired to `{}`. It now reflects what is actually stored on the phone and
 * what the server last said.
 */
@Composable
fun SubscriptionCard() {
    val af = LocalAfColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val store = remember(context) { LicenseStore(context) }
    var state by remember { mutableStateOf(License.state(store)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    /*
     * Re-check once a day when there is a key. The result only ever extends or
     * revokes what is already on disk — if the phone is offline nothing changes
     * and the driver keeps working on the month he paid for.
     */
    LaunchedEffect(Unit) {
        val key = store.key ?: return@LaunchedEffect
        if (System.currentTimeMillis() - store.lastCheckAt < License.RECHECK_MS) return@LaunchedEffect

        when (val r = withContext(Dispatchers.IO) { License.check(context, key) }) {
            is License.Result.Ok -> store.save(key, r.expiresAt)
            is License.Result.Refused -> store.refusedReason = r.reason
            License.Result.Offline -> Unit // say nothing; this is not a failure
        }
        state = License.state(store)
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.outlinedCardColors(containerColor = af.raised),
        // the one card drawn in ink — the subscription is the only thing on this
        // screen that can stop the app working
        border = BorderStroke(1.5.dp, af.ink),
    ) {
        Column(Modifier.padding(19.dp)) {
            when (val s = state) {
                is License.State.Active -> ActiveBody(s)
                License.State.Expired -> StatusLine("خلص الاشتراك", af.flag, "الباقة الشهرية")
                is License.State.Refused -> StatusLine(s.ar, af.flag, "الباقة الشهرية")
                License.State.None -> StatusLine("ما في اشتراك", af.muted, "الباقة الشهرية")
            }

            val key = store.key
            if (key != null) {
                Spacer(Modifier.height(15.dp))
                Surface(
                    color = af.sunk,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(masked(key), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = af.ink)
                        Text(
                            "انسخ",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = af.ink,
                            modifier = Modifier.clickable {
                                clipboard.setText(AnnotatedString(key))
                                message = "انتسخ الرمز"
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            KeyEntry(
                busy = busy,
                cta = if (store.key == null) "فعّل الاشتراك" else "جدّد — 5 د.أ بالشهر",
                onSubmit = { typed ->
                    busy = true
                    message = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { License.activate(context, typed) }
                        when (r) {
                            is License.Result.Ok -> {
                                store.save(typed, r.expiresAt)
                                message = "تم التفعيل"
                            }
                            is License.Result.Refused -> {
                                store.refusedReason = r.reason
                                message = License.State.Refused(r.reason).ar
                            }
                            License.Result.Offline ->
                                message = "ما في إنترنت — جرّب كمان شوي"
                        }
                        state = License.state(store)
                        busy = false
                    }
                },
            )

            Text(
                message ?: "ادفع بـ CliQ أو زين كاش · التفعيل فوري",
                fontSize = 11.5.sp,
                color = if (message == null) af.muted else af.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ActiveBody(s: License.State.Active) {
    val af = LocalAfColors.current
    // under a week the figure turns amber — a driver who loses auto-accept
    // mid-shift because he forgot to renew blames the app, not the calendar
    val urgent = s.daysLeft <= 7

    StatusLine(if (urgent) "قرب يخلص" else "نشط", if (urgent) af.flag else af.accept, "الباقة الشهرية")
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            s.daysLeft.toString(),
            style = FigureLarge.copy(fontSize = 44.sp),
            color = if (urgent) af.flag else af.ink,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            // "يوم" / "يومين" / "أيام" — the numeral above is the figure
            if (s.daysLeft % 100 in 3..10) "أيام" else "يوم",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = af.muted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    Text("بينتهي ${arabicDate(s.expiresAt)}", fontSize = 12.5.sp, color = af.muted)
}

@Composable
private fun StatusLine(status: String, color: androidx.compose.ui.graphics.Color, plan: String) {
    val af = LocalAfColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(status, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color)
        Text(plan, fontSize = 11.5.sp, color = af.muted)
    }
}

@Composable
private fun KeyEntry(busy: Boolean, cta: String, onSubmit: (String) -> Unit) {
    val af = LocalAfColors.current
    var typed by remember { mutableStateOf("") }

    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it.uppercase() },
        label = { Text("رمز الاشتراك", fontSize = 12.sp) },
        singleLine = true,
        enabled = !busy,
        // the key is Latin letters and digits; an Arabic keyboard here is a
        // driver typing عربي into a field that will never match
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    PrimaryButton(
        text = if (busy) "لحظة..." else cta,
        onClick = { if (!busy && typed.isNotBlank()) onSubmit(typed.trim()) },
    )
}

/** `CN34RTNHMAYH` → `CN34-••••-MAYH`, so a shoulder-surfer cannot copy it. */
private fun masked(key: String): String =
    if (key.length <= 8) key else key.take(4) + "-••••-" + key.takeLast(4)
