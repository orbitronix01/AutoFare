package jo.autofare.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.ui.LocalThemeController
import jo.autofare.ui.components.*
import jo.autofare.ui.theme.FigureLarge
import jo.autofare.ui.theme.LocalAfColors

@Composable
fun SettingsScreen(onOpenLearn: () -> Unit = {}) {
    val af = LocalAfColors.current
    val context = LocalContext.current
    val ready = rememberReadiness()
    var bubble by rememberSaveable { mutableStateOf(true) }
    var sound by rememberSaveable { mutableStateOf(true) }
    var vibrate by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        // No رجوع any more — this is a tab, and the bar below is the way out
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp)) {
            Text("الإعدادات", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = af.ink)
            Spacer(Modifier.height(4.dp))
            Text("الأذونات والاشتراك.", fontSize = 12.5.sp, color = af.muted)
        }
        Spacer(Modifier.height(18.dp))

        SectionHeader("01", "الأذونات")
        Rule()
        PermRow(
            title = "إمكانية الوصول",
            detail = if (ready.accessibility) "مفعّلة — AutoFare شايف الطلبات"
                     else "مطفية — AutoFare ما بيشوف ولا طلب",
            ready = ready.accessibility,
            onFix = { openAccessibilitySettings(context) },
        )
        PermRow(
            // Android's own Arabic for this setting. The driver has to find it in
            // his phone, so our label has to be the phrase he will see there.
            title = "تحسين البطارية",
            detail = if (ready.battery) "النظام مش راح يسكّر التطبيق"
                     else "النظام ممكن يسكّر التطبيق وإنت شغّال",
            ready = ready.battery,
            onFix = { openBatterySettings(context) },
        )
        PermRow(
            title = "العرض فوق التطبيقات الأخرى",
            detail = "لازمك إياها للزر العائم",
            ready = ready.overlay,
            onFix = { openOverlaySettings(context) },
        )

        SectionHeader("02", "الاشتراك")
        SubscriptionCard()

        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            "وضع التعلّم",
            onClick = onOpenLearn,
            filled = false,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "سجّل شاشات تطبيقات الرحلات وأرسلها — عشان نبني القبول التلقائي.",
            fontSize = 11.5.sp, color = af.muted, lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
        )

        SectionHeader("03", "المظهر")
        val theme = LocalThemeController.current
        Panel {
            ToggleLine(
                "اتبع إعدادات الجهاز",
                "يفتح غامق لما الجهاز يصير غامق.",
                theme.followSystem,
                onToggle = { theme.setFollowSystem(!theme.followSystem) },
            )
            ToggleLine(
                "الوضع الغامق",
                if (theme.followSystem) "طفّي \"اتبع إعدادات الجهاز\" عشان تتحكّم فيه بإيدك."
                else "أريح للعين بالليل وإنت سايق.",
                theme.dark,
                onToggle = {
                    theme.setFollowSystem(false)
                    theme.setDark(!theme.dark)
                },
                showRule = false,
            )
        }

        SectionHeader("04", "عام")
        Panel {
            ToggleLine("الزر العائم", "تحكّم سريع وإنت داخل تطبيق الرحلات.", bubble,
                onToggle = { bubble = !bubble })
            ToggleLine("صوت عند القبول", "نغمة قصيرة لما نقبل طلب.", sound,
                onToggle = { sound = !sound })
            ToggleLine("اهتزاز عند الرفض", "هزّة خفيفة — بتعرف إنه الطلب إجا وانرفض.", vibrate,
                onToggle = { vibrate = !vibrate }, showRule = false)
        }

        Spacer(Modifier.height(30.dp))
        Text(
            // read from the build, not typed — it said 1.0 while the app was 0.4
            "AutoFare ${jo.autofare.BuildConfig.VERSION_NAME} · عمّان",
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = af.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PermRow(title: String, detail: String, ready: Boolean, onFix: () -> Unit) {
    val af = LocalAfColors.current
    Column {
        ListItem(
            // the whole row is the target: "تفعيل" is 11.5sp and this is a screen
            // a driver uses with the engine running
            modifier = Modifier.clickable(enabled = !ready) { onFix() },
            leadingContent = { StatusTick(ready) },
            headlineContent = {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = af.ink)
            },
            supportingContent = {
                Text(detail, fontSize = 11.5.sp, color = af.muted)
            },
            trailingContent = {
                Text(
                    if (ready) "مفعّلة" else "تفعيل",
                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                    color = if (ready) af.accept else af.flag,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Rule()
    }
}
