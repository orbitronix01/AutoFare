package jo.autofare.ui.learn

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import jo.autofare.data.AppPrefs
import jo.autofare.data.CaptureStore
import jo.autofare.data.SUPPORTED
import jo.autofare.service.AutoFareService
import jo.autofare.ui.components.*
import jo.autofare.ui.theme.FigureLarge
import jo.autofare.ui.theme.LocalAfColors

@Composable
fun LearnScreen(onBack: () -> Unit) {
    val af = LocalAfColors.current
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val store = remember { CaptureStore(context) }

    var serviceOn by remember { mutableStateOf(AutoFareService.isEnabled(context)) }
    var learning by remember { mutableStateOf(prefs.learningEnabled) }
    var captureAll by remember { mutableStateOf(prefs.captureAllApps) }
    var count by remember { mutableStateOf(store.count()) }
    var kept by remember { mutableStateOf(store.keepCount()) }
    var perApp by remember { mutableStateOf(store.countsByPackage()) }
    var perAppKept by remember { mutableStateOf(store.keepCountsByPackage()) }
    var picked by remember { mutableStateOf(prefs.pickedPackages) }
    var picking by remember { mutableStateOf(false) }

    fun refresh() {
        serviceOn = AutoFareService.isEnabled(context)
        count = store.count()
        kept = store.keepCount()
        perApp = store.countsByPackage()
        perAppKept = store.keepCountsByPackage()
    }

    // only for the ongoing line in the shade; capture does not depend on it
    val askNotify = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    if (picking) {
        AppPickerScreen(
            picked = picked,
            onPickedChange = { picked = it; prefs.pickedPackages = it },
            onDone = { picking = false },
        )
        return
    }

    // refresh when the driver comes back from Android's accessibility settings
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

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
                Text("وضع التعلّم", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = af.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "منسجّل شو بتعرض تطبيقات الرحلات، وبس. ما منقبل ولا منرفض أي طلب بهالنسخة.",
                    fontSize = 12.5.sp, color = af.muted, lineHeight = 21.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "رجوع",
                fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = af.ink,
                modifier = Modifier.clickable { onBack() }.padding(8.dp),
            )
        }
        Spacer(Modifier.height(18.dp))

        SectionHeader("01", "الخدمة")
        Rule()
        ListItem(
            leadingContent = { StatusTick(serviceOn) },
            headlineContent = {
                Text("إمكانية الوصول", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = af.ink)
            },
            supportingContent = {
                Text(
                    if (serviceOn) "مفعّلة — AutoFare شايف الشاشة" else "مش مفعّلة — اضغط فعّلها",
                    fontSize = 11.5.sp, color = af.muted,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Rule()

        if (!serviceOn) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                "فعّلها من الإعدادات",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        SectionHeader("02", "التسجيل")
        Panel {
            ToggleLine(
                "وضع التعلّم",
                "لما يشتغل، كل ما تفتح شاشة بتطبيق الرحلات منسجّلها بملف ع جهازك.",
                learning,
                onToggle = {
                    learning = !learning
                    prefs.learningEnabled = learning
                    if (learning) {
                        // a new run starts with a clean budget — see AppPrefs.newSession
                        prefs.newSession()
                        if (Build.VERSION.SDK_INT >= 33) {
                            askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    refresh()
                },
            )
            ToggleLine(
                "سجّل من كل التطبيقات",
                "خلّيها مفتوحة — أسماء تطبيقات الرحلات لسا تخمين، وهيك ما منفوّت إشي. " +
                    "الواتس والإيميل والبنوك ما منسجّلهم أبداً.",
                captureAll,
                onToggle = { captureAll = !captureAll; prefs.captureAllApps = captureAll },
                showRule = false,
            )
        }

        if (learning && !serviceOn) {
            Warning(
                "التسجيل مفتوح بس إذن إمكانية الوصول مطفي، معناها AutoFare ما بيشوف " +
                    "الشاشة وما رح يسجّل ولا إشي. فعّله من فوق."
            )
        }
        if (learning && picked.isEmpty() && !captureAll) {
            Warning(
                "ما اخترت تطبيقات الرحلات، و«سجّل من كل التطبيقات» مطفي — هيك ما رح " +
                    "يتسجّل ولا إشي. افتح واحد من الاثنين."
            )
        } else if (learning && picked.isEmpty()) {
            Warning(
                "لسا ما اخترت تطبيقات الرحلات من جهازك. عنجد منصح تختارهم من تحت — " +
                    "هيك منتأكد إنه جيني وبترا رايد مراقبين بالزبط."
            )
        }

        Spacer(Modifier.height(10.dp))
        AfCard(Modifier.padding(horizontal = 20.dp).clickable { refresh() }) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$count", style = FigureLarge, color = af.ink)
            Spacer(Modifier.height(6.dp))
            Text("شاشة متسجّلة", fontSize = 12.5.sp, color = af.muted)

            /*
             * The number that actually matters. A total going up only proves the
             * service is awake; this one is the count of screens we flagged as
             * unlosable — first time we see a layout, or it said accept/decline. If
             * the driver can read a number above zero off this line we have the
             * offer popup, and he does not need to drive a third session.
             */
            if (kept > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "منها $kept شاشة مهمة",
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = af.accept,
                )
            }

            if (perApp.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Rule()
                perApp.entries.sortedByDescending { it.value }.forEach { (pkg, n) ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            appLabel(context, pkg),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = af.ink,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${perAppKept[pkg] ?: 0} / $n",
                            fontSize = 12.sp, color = af.muted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "اضغط ع هالمربّع عشان يحدّث الأرقام.",
                fontSize = 11.5.sp, color = af.muted, textAlign = TextAlign.Center,
            )
        }
        }

        SectionHeader("03", "الإرسال")
        Column(Modifier.padding(horizontal = 20.dp)) {
            PrimaryButton(
                "شارك الملف",
                onClick = {
                    val file = store.export() ?: return@PrimaryButton
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.files", file
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "AutoFare — تسجيل الشاشات")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(send, "شارك الملف")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
            Spacer(Modifier.height(9.dp))
            PrimaryButton(
                "امسح التسجيلات",
                filled = false,
                onClick = { store.clear(); prefs.newSession(); refresh() },
            )
            Hint("الملف بينحفظ ع جهازك بس. ما بينرسل لحدا إلا لما تضغط شارك.")
        }

        SectionHeader("04", "التطبيقات اللي منراقبها")
        Rule()
        picked.forEach { pkg ->
            WatchedAppRow(
                badge = { StatusTick(ready = true, size = 34) },
                name = appLabel(context, pkg),
                packageName = pkg,
                verified = true,
            )
        }
        SUPPORTED.filter { it.packageName !in picked }.forEach { app ->
            WatchedAppRow(
                badge = {
                    Surface(color = app.badge, shape = MaterialTheme.shapes.small, modifier = Modifier.size(34.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                app.letter,
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                            )
                        }
                    }
                },
                name = app.name,
                packageName = app.packageName,
                verified = app.verified,
            )
        }

        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            if (picked.isEmpty()) "اختار تطبيق الرحلات من جهازك" else "عدّل التطبيقات",
            filled = picked.isEmpty(),
            onClick = { picking = true },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Hint(
            if (picked.isEmpty())
                "الأسماء فوق تخمين. اضغط الزر واختار تطبيق الرحلات من ع جهازك، وبصير متأكد."
            else
                "منراقب اللي اخترتهم ع طول، حتى لو طفّيت «سجّل من كل التطبيقات».",
            Modifier.padding(horizontal = 20.dp),
        )
    }
}

/** One app in the watched list — badge, name, package, and how sure we are of it. */
@Composable
private fun WatchedAppRow(
    badge: @Composable () -> Unit,
    name: String,
    packageName: String,
    verified: Boolean,
) {
    val af = LocalAfColors.current
    ListItem(
        leadingContent = badge,
        headlineContent = {
            Text(name, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = af.ink)
        },
        supportingContent = {
            Text(packageName, fontSize = 10.5.sp, color = af.muted)
        },
        trailingContent = {
            Text(
                if (verified) "متأكد" else "تخمين",
                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = if (verified) af.accept else af.flag,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    Rule()
}

/**
 * The loud box. Only for the two mistakes that silently waste a whole shift:
 * recording with the accessibility permission off, and recording with nothing
 * being watched.
 */
@Composable
private fun Warning(text: String) {
    val af = LocalAfColors.current
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 14.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = af.flagBg),
        border = BorderStroke(1.dp, af.flag),
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
            Text("!", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = af.flag)
            Spacer(Modifier.width(11.dp))
            Text(text, fontSize = 12.sp, color = af.ink, lineHeight = 22.sp)
        }
    }
}
