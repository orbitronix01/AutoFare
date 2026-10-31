package jo.autofare.ui.license

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import jo.autofare.ui.latinDigits
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.BuildConfig
import jo.autofare.R
import jo.autofare.data.License
import jo.autofare.data.LicenseStore
import jo.autofare.ui.components.PrimaryButton
import jo.autofare.ui.theme.LocalAfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Copy(val headline: String, val body: String)

private fun copyFor(state: License.State): Copy = when (state) {
    License.State.Expired -> Copy(
        "خلص\nالاشتراك",
        "اكتب رمز جديد، وبنرجع نقبل الطلبات عنك.",
    )
    License.State.Unverified -> Copy(
        "لازم\nإنترنت",
        "منتأكد من اشتراكك كل فترة، وهلّق ما في إنترنت. شغّله وجرّب كمان مرة.",
    )
    else -> Copy(
        "فعّل\nاشتراكك",
        "اكتب رمز الاشتراك اللي وصلك. كل رمز بيزيد شهر أو أكثر على اشتراكك.",
    )
}

/**
 * The whole app behind one door: shown instead of everything else until the
 * server has vouched for this phone.
 *
 * [checking] is true while the first check-in of this launch is in flight, so a
 * paying driver opening the app after a reboot sees "لحظة" rather than being
 * told for a second that he needs internet.
 */
@Composable
fun LockScreen(state: License.State, checking: Boolean, onRetry: () -> Unit) {
    val af = LocalAfColors.current
    val context = LocalContext.current
    val store = remember(context) { LicenseStore(context) }
    val copy = copyFor(state)

    Column(
        Modifier
            .fillMaxSize()
            .background(af.paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp),
    ) {
        Spacer(Modifier.height(40.dp))

        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(af.ink),
            modifier = Modifier.height(34.dp),
        )

        Spacer(Modifier.height(28.dp))

        if (checking) {
            Text("لحظة...", fontWeight = FontWeight.Bold, fontSize = 33.sp, lineHeight = 46.sp, color = af.ink)
            Spacer(Modifier.height(14.dp))
            Text("عم نتأكد من اشتراكك", fontSize = 13.5.sp, color = af.muted, lineHeight = 26.sp)
            return@Column
        }

        Text(copy.headline, fontWeight = FontWeight.Bold, fontSize = 33.sp, lineHeight = 46.sp, color = af.ink)
        Spacer(Modifier.height(14.dp))
        Text(copy.body, fontSize = 13.5.sp, color = af.muted, lineHeight = 26.sp)

        Spacer(Modifier.height(30.dp))

        if (state == License.State.Unverified) {
            PrimaryButton(text = "جرّب كمان مرة", onClick = onRetry, filled = true)
            Spacer(Modifier.height(22.dp))
        }

        CodeEntry(cta = "فعّل")

        if (BuildConfig.DEBUG) {
            Text(
                "تخطّى القفل (نسخة المطوّر بس)",
                fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = af.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { store.devBypass = true }
                    .padding(vertical = 18.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * The code box, shared by the lock screen and the subscription card.
 *
 * A redeemed code writes the new expiry through [LicenseStore], and everything
 * watching the store — the gate, the service — unlocks off that write.
 */
@Composable
fun CodeEntry(cta: String) {
    val af = LocalAfColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val code = typed.trim()
        if (busy || code.isBlank()) return
        busy = true
        message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { License.redeem(context, code) }
            message = when (r) {
                is License.Result.Ok -> {
                    typed = ""
                    "تمام، انضاف الاشتراك"
                }
                is License.Result.Refused -> License.refusalAr(r.reason)
                License.Result.Offline -> "ما في إنترنت. جرّب كمان شوي"
            }
            busy = false
        }
    }

    // the code is Latin letters and digits, read left to right even on an RTL screen
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        OutlinedTextField(
            value = typed,
            // codes are Latin; digits typed on an Arabic keyboard are folded back to 0–9
            onValueChange = { typed = latinDigits(it).uppercase().take(20) },
            placeholder = { Text("XXXX-XXXX-XXXX", color = af.muted) },
            singleLine = true,
            enabled = !busy,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                letterSpacing = 2.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            ),
            // an Arabic keyboard here is a driver typing عربي into a field that will never match
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(10.dp))
    PrimaryButton(text = if (busy) "لحظة..." else cta, onClick = { submit() })
    Text(
        message ?: "الدفع عن طريق كليك أو زين كاش، والتفعيل فوري",
        fontSize = 11.5.sp,
        color = if (message == null) af.muted else af.ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
}
