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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.ui.LocalThemeController
import jo.autofare.ui.components.*
import jo.autofare.ui.theme.FigureLarge
import jo.autofare.ui.theme.LocalAfColors

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLearn: () -> Unit = {}) {
    val af = LocalAfColors.current
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("الإعدادات", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = af.ink)
                Spacer(Modifier.height(4.dp))
                Text("جاهزية الجهاز والاشتراك.", fontSize = 12.5.sp, color = af.muted)
            }
            Text(
                "رجوع",
                fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = af.ink,
                modifier = Modifier.clickable { onBack() }.padding(8.dp),
            )
        }
        Spacer(Modifier.height(18.dp))

        SectionHeader("01", "جاهزية الجهاز")
        Rule()
        PermRow("إمكانية الوصول", "مفعّلة — AutoFare شايف الطلبات", ready = true)
        PermRow("حماية البطارية", "النظام مش راح يسكّر التطبيق", ready = true)
        PermRow("الظهور فوق التطبيقات", "لازمك إياها للزر العائم", ready = false)

        SectionHeader("02", "الاشتراك")
        // The one card drawn in ink rather than hairline — the subscription is the
        // only thing on this screen that can stop the app working.
        OutlinedCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.outlinedCardColors(containerColor = af.raised),
            border = BorderStroke(1.5.dp, af.ink),
        ) {
        Column(Modifier.padding(19.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("فعّال", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = af.accept)
                Text("الباقة الشهرية", fontSize = 11.5.sp, color = af.muted)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("27", style = FigureLarge.copy(fontSize = 44.sp), color = af.ink)
                Spacer(Modifier.width(8.dp))
                Text("يوم", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = af.muted,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            Text("بينتهي 21 تشرين الثاني", fontSize = 12.5.sp, color = af.muted)

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
                    Text("AF-••••-••••-3JB6", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = af.ink)
                    Text("انسخ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = af.ink)
                }
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("جدّد — 5 د.أ بالشهر", onClick = {})
            Text(
                "ادفع بـ CliQ أو زين كاش · التفعيل فوري",
                fontSize = 11.5.sp, color = af.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
        }

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
            "AutoFare 1.0 · عمّان",
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = af.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PermRow(title: String, detail: String, ready: Boolean) {
    val af = LocalAfColors.current
    Column {
        ListItem(
            leadingContent = { StatusTick(ready) },
            headlineContent = {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = af.ink)
            },
            supportingContent = {
                Text(detail, fontSize = 11.5.sp, color = af.muted)
            },
            trailingContent = {
                Text(
                    if (ready) "تمام" else "فعّلها",
                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                    color = if (ready) af.accept else af.flag,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Rule()
    }
}
