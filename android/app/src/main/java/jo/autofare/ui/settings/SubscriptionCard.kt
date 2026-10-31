package jo.autofare.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.BuildConfig
import jo.autofare.data.License
import jo.autofare.ui.arNum
import jo.autofare.data.LicenseStore
import jo.autofare.ui.license.CodeEntry
import jo.autofare.ui.theme.FigureLarge
import jo.autofare.ui.theme.LocalAfColors
import java.util.Calendar

private val ARABIC_MONTHS = listOf(
    "كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران",
    "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول",
)

/** `١٤ تشرين الثاني` — day in Arabic-Indic digits, Levantine month name. */
private fun arabicDate(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "${arNum(c.get(Calendar.DAY_OF_MONTH))} ${ARABIC_MONTHS[c.get(Calendar.MONTH)]}"
}

/**
 * The subscription, as the driver sees it once he is past the lock screen:
 * how long is left, and the box for the next month's code.
 *
 * A renewal is just another code. It adds its months on top of whatever is left,
 * so renewing early never costs him days.
 */
@Composable
fun SubscriptionCard() {
    val af = LocalAfColors.current
    val context = LocalContext.current
    val store = remember(context) { LicenseStore(context) }
    var state by remember { mutableStateOf(License.state(store)) }

    DisposableEffect(store) {
        val listener = store.observe { state = License.state(store) }
        onDispose { store.stopObserving(listener) }
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
                License.State.Expired -> StatusLine("خلص الاشتراك", af.flag)
                License.State.Unverified -> StatusLine("ما قدرنا نتأكد من اشتراكك. شغّل الإنترنت", af.flag)
                License.State.None -> StatusLine("ما في اشتراك", af.muted)
            }

            Spacer(Modifier.height(18.dp))
            Text("عندك رمز جديد؟", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = af.ink)
            Spacer(Modifier.height(4.dp))
            /*
             * The price on its own line, in its own nodes. Inside the button as
             * "جدّد — ٥ د.أ بالشهر" the dash, the figure and the currency's dot
             * were all neutral or weak characters between Arabic words, and the
             * line rendered in an order nobody wrote.
             */
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الاشتراك ", fontSize = 12.sp, color = af.muted)
                Text(arNum(5), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = af.ink)
                Text(" د.أ بالشهر. كل رمز بيزيد على المدّة اللي ضايلة.", fontSize = 12.sp, color = af.muted)
            }
            Spacer(Modifier.height(10.dp))
            CodeEntry(cta = "جدّد الاشتراك")

            // License.bypassed is already false outside debug; checked here as well
            // so this block cannot ship even if that ever changes
            if (BuildConfig.DEBUG && License.bypassed(store)) DevBypassNotice { store.devBypass = false }
        }
    }
}

@Composable
private fun ActiveBody(s: License.State.Active) {
    val af = LocalAfColors.current
    // under a week the figure turns amber — a driver who loses auto-accept
    // mid-shift because he forgot to renew blames the app, not the calendar
    val urgent = s.daysLeft <= 7

    if (s.forever) {
        StatusLine("فعّال", af.accept)
        // Arabic words in a figure style: tracking off, or the letters come apart
        Text("مدى الحياة", style = FigureLarge.copy(fontSize = 34.sp, letterSpacing = 0.sp), color = af.ink)
        return
    }

    StatusLine(if (urgent) "قرّب يخلص" else "فعّال", if (urgent) af.flag else af.accept)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            arNum(s.daysLeft),
            style = FigureLarge.copy(fontSize = 44.sp),
            color = if (urgent) af.flag else af.ink,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            // "يوم" / "أيام" — the numeral above is the figure
            if (s.daysLeft % 100 in 3..10) "أيام" else "يوم",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = af.muted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    Text("بيخلص ${arabicDate(s.expiresAt)}", fontSize = 12.5.sp, color = af.muted)
}

/**
 * Debug builds only: the lock has been skipped on this phone. Worded as a build
 * notice with its own button, because "الاشتراك متخطّى · رجّع القفل" on one line
 * read like a broken subscription rather than a developer switch.
 */
@Composable
private fun DevBypassNotice(onRelock: () -> Unit) {
    val af = LocalAfColors.current
    Spacer(Modifier.height(14.dp))
    androidx.compose.material3.Surface(
        color = af.flagBg,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "نسخة تجريبية للمطوّر: القفل مطفي",
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = af.flag,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onRelock) {
                Text("رجّع القفل", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = af.ink)
            }
        }
    }
}

@Composable
private fun StatusLine(status: String, color: androidx.compose.ui.graphics.Color) {
    val af = LocalAfColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(status, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color)
        Text("الباقة الشهرية", fontSize = 11.5.sp, color = af.muted)
    }
}
