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
import jo.autofare.BuildConfig
import jo.autofare.data.AppPrefs
import jo.autofare.ui.ltr
import jo.autofare.ui.LocalThemeController
import jo.autofare.ui.components.*
import jo.autofare.ui.theme.FigureLarge
import jo.autofare.ui.theme.LocalAfColors

/**
 * Settings, grouped by what the driver is trying to do rather than by how the
 * code is organised: what the phone has to allow, what he is paying for, what
 * AutoFare does around a ride, and how it looks.
 *
 * Every row says what it is *for* in one plain line. The first version listed
 * Android's own names — "إمكانية الوصول", "تحسين البطارية" — and left the driver
 * to guess why AutoFare needed them; those are the names he will see in the
 * phone's settings, so they stay as titles, but the line under each one is the
 * reason he should care.
 *
 * [onOpenLearn] is only wired in debug builds; see AppScaffold.
 */
@Composable
fun SettingsScreen(onOpenLearn: (() -> Unit)? = null) {
    val af = LocalAfColors.current
    val context = LocalContext.current
    val ready = rememberReadiness()
    // written straight through to AppPrefs, where the service is listening — these
    // used to be screen-local and did nothing the moment the driver left the screen
    val prefs = remember(context) { AppPrefs(context) }
    var bubble by remember { mutableStateOf(prefs.floatingButton) }
    var sound by remember { mutableStateOf(prefs.acceptSound) }
    var vibrate by remember { mutableStateOf(prefs.rejectVibrate) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader("الإعدادات", "شو لازم الهاتف يسمح فيه، واشتراكك، والتنبيهات.")

        SectionHeader("01", "الأذونات اللازمة")
        Text(
            if (ready.accessibility && ready.battery && ready.notifications) "كل الأذونات المهمة تمام."
            else "اكبس على أي سطر عليه ! عشان نوديك ع المكان اللي بتصلّحه منه.",
            fontSize = 12.sp, color = af.muted, lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp),
        )
        Rule()
        PermRow(
            title = "إمكانية الوصول",
            why = "هاد اللي بخلّي AutoFare يشوف الطلب ويكبس «قبول». بدونه ما منقبل ولا طلب.",
            ready = ready.accessibility,
            onFix = { openAccessibilitySettings(context) },
        )
        PermRow(
            // Android's own Arabic for this setting. The driver has to find it in
            // his phone, so our label has to be the phrase he will see there.
            title = "تحسين البطارية",
            why = "لازم تطلّع AutoFare منه، غير هيك الهاتف بسكّره وإنت سايق وبوقف القبول بدون ما تحس.",
            ready = ready.battery,
            onFix = { openBatterySettings(context) },
        )
        PermRow(
            title = "الإشعارات",
            why = "عشان نحكيلك إذا وقف القبول وإنت سايق، ويضل عندك سطر بيورجيك إنه شغّال.",
            ready = ready.notifications,
            onFix = { openNotificationSettings(context) },
        )
        PermRow(
            title = "العرض فوق التطبيقات الأخرى",
            why = "عشان يطلعلك الزر العائم وبطاقة «قبلناه إلك» فوق جيني وبترا. مش ضروري للقبول نفسه.",
            ready = ready.overlay,
            onFix = { openOverlaySettings(context) },
        )

        SectionHeader("02", "الاشتراك")
        SubscriptionCard()

        SectionHeader("03", "وإنت جوّا جيني أو بترا")
        Panel {
            ToggleLine(
                "الزر العائم",
                // switched on without the permission it needs, it has to say so
                // here — otherwise it is a switch that does nothing, silently
                if (bubble && !ready.overlay) "محتاج إذن «العرض فوق التطبيقات الأخرى». شغّله من فوق."
                else "زر صغير فوق جيني وبترا. كبسة وحدة بتوقّف القبول التلقائي أو بترجّعه، بدون ما تطلع من التطبيق.",
                bubble,
                onToggle = {
                    bubble = !bubble
                    prefs.floatingButton = bubble
                    // switching it on is asking for it: take him straight to the one
                    // screen that makes it possible, rather than leave him guessing
                    // read live, not from [ready]: that is only refreshed on resume and
                    // the grant can have been taken away since
                    if (bubble && !android.provider.Settings.canDrawOverlays(context)) {
                        openOverlaySettings(context)
                    }
                },
            )
            ToggleLine("صوت لما نقبل طلب", "نغمة قصيرة، عشان تعرف إنّا قبلنا بدون ما تطلّع ع الشاشة.", sound,
                onToggle = { sound = !sound; prefs.acceptSound = sound })
            ToggleLine(
                "هزّة لما نترك طلب",
                "هزّة خفيفة لما يجي طلب ما بيطابق قواعدك، عشان تعرف إنه إجا وتركناه.",
                vibrate,
                onToggle = { vibrate = !vibrate; prefs.rejectVibrate = vibrate }, showRule = false,
            )
        }

        SectionHeader("04", "المظهر")
        val theme = LocalThemeController.current
        Panel {
            ToggleLine(
                "زي الهاتف",
                "إذا الهاتف على الوضع الغامق، AutoFare كمان بصير غامق.",
                theme.followSystem,
                onToggle = { theme.setFollowSystem(!theme.followSystem) },
            )
            ToggleLine(
                "الوضع الغامق",
                if (theme.followSystem) "طفّي «زي الهاتف» أول، عشان تختار بإيدك."
                else "ألوان غامقة، أريح للعين بالليل.",
                theme.dark,
                onToggle = {
                    theme.setFollowSystem(false)
                    theme.setDark(!theme.dark)
                },
                showRule = false,
            )
        }

        // capture tooling, for testing new offer layouts — never in a driver's build
        if (BuildConfig.DEBUG && onOpenLearn != null) {
            SectionHeader("05", "للمطوّر بس")
            PrimaryButton(
                "تسجيل شاشات (وضع التعلّم)",
                onClick = onOpenLearn,
                filled = false,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "بيظهر بنسخة المطوّر بس. السوّاق ما بيشوفه.",
                fontSize = 11.5.sp, color = af.muted, lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
            )
        }

        Spacer(Modifier.height(30.dp))
        // read from the build, not typed — it said 1.0 while the app was 0.4.
        // Separate nodes, so the Latin name and version cannot reorder the Arabic.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text("AutoFare", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = af.muted)
            Text(" · ", fontSize = 11.sp, color = af.muted)
            Text("نسخة ${ltr(BuildConfig.VERSION_NAME)}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = af.muted)
            Text(" · ", fontSize = 11.sp, color = af.muted)
            Text("عمّان", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = af.muted)
        }
    }
}

@Composable
private fun PermRow(title: String, why: String, ready: Boolean, onFix: () -> Unit) {
    val af = LocalAfColors.current
    Column {
        ListItem(
            // the whole row is the target: the status word is small and this is a
            // screen a driver uses with the engine running
            modifier = Modifier.clickable(enabled = !ready) { onFix() },
            leadingContent = { StatusTick(ready) },
            headlineContent = {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = af.ink)
            },
            supportingContent = {
                Text(why, fontSize = 11.5.sp, color = af.muted, lineHeight = 18.sp)
            },
            trailingContent = {
                Text(
                    if (ready) "تمام" else "صلّحها",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (ready) af.accept else af.flag,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Rule()
    }
}
