package jo.autofare.ui.learn

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import jo.autofare.ui.components.Hint
import jo.autofare.ui.components.Rule
import jo.autofare.ui.components.SectionHeader
import jo.autofare.ui.theme.LocalAfColors

/** One launchable app on the driver's phone. */
data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap?,
    val likelyRideApp: Boolean,
)

/**
 * Words that mark an app as worth floating to the top of the list.
 *
 * A working driver has ninety-odd apps installed — the first file we got back came
 * from a phone carrying seven different driver apps — so the list has to put the
 * candidates in front of him rather than make him hunt.
 */
private val RIDE_HINTS = listOf(
    "taxi", "ride", "driver", "captain", "cab", "fare", "trip", "jeeny", "petra",
    "careem", "uber", "bolt", "yandex",
    "سائق", "سواق", "تاكسي", "رحلات", "جيني", "بترا", "كريم", "سفير", "كابتن",
)

/** The app's own name for a package, falling back to the package if it is gone. */
fun appLabel(context: Context, pkg: String): String =
    runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

fun installedApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launchable, 0)
        .mapNotNull { resolved ->
            val pkg = resolved.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null   // not ourselves
            val label = runCatching { resolved.loadLabel(pm).toString() }.getOrNull() ?: pkg
            val icon = runCatching {
                resolved.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
            val haystack = "$label $pkg"
            InstalledApp(
                label = label,
                packageName = pkg,
                icon = icon,
                likelyRideApp = RIDE_HINTS.any { haystack.contains(it, ignoreCase = true) },
            )
        }
        .distinctBy { it.packageName }
        .sortedWith(compareByDescending<InstalledApp> { it.likelyRideApp }.thenBy { it.label.lowercase() })
}

/**
 * The driver taps his own ride apps, and that settles the package names for good.
 *
 * Every package in [jo.autofare.data.SUPPORTED] was typed from guesswork, and the
 * first real capture confirmed none of them. One tap here is worth any amount of
 * guessing from the outside.
 */
@Composable
fun AppPickerScreen(
    picked: Set<String>,
    onPickedChange: (Set<String>) -> Unit,
    onDone: () -> Unit,
) {
    val af = LocalAfColors.current
    val context = LocalContext.current
    val apps = remember { installedApps(context) }
    val suggested = apps.count { it.likelyRideApp }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("تطبيقات الرحلات", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = af.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "اضغط ع كل تطبيق رحلات عندك ع الجهاز. هيك منعرف اسمه بالزبط بدل ما نخمّن.",
                    fontSize = 12.5.sp, color = af.muted, lineHeight = 21.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "تم",
                fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = af.ink,
                modifier = Modifier.clickable { onDone() }.padding(8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                // the two group headings ride along with the first row of each group
                if (index == 0 && suggested > 0) {
                    SectionHeader("01", "يمكن تكون هاي")
                    Rule()
                }
                if (index == suggested) {
                    SectionHeader(if (suggested > 0) "02" else "01", "كل التطبيقات")
                    Rule()
                }
                AppRow(app, app.packageName in picked) {
                    onPickedChange(
                        if (app.packageName in picked) picked - app.packageName
                        else picked + app.packageName
                    )
                }
                Rule()
            }
        }

        if (picked.isNotEmpty()) {
            Rule()
            Hint(
                "متسجّل ${picked.size} تطبيق. افتح وضع التعلّم واشتغل شوي.",
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, selected: Boolean, onToggle: () -> Unit) {
    val af = LocalAfColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(af.sunk, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (app.icon != null) {
                Image(app.icon, contentDescription = null, modifier = Modifier.size(28.dp))
            } else {
                Text(
                    app.label.take(1),
                    fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = af.muted,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = af.ink)
            Text(app.packageName, fontSize = 10.5.sp, color = af.muted)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(26.dp)
                .background(if (selected) af.accept else af.raised, RoundedCornerShape(8.dp))
                .border(1.dp, if (selected) af.accept else af.rule2, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text("✓", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = af.paper)
            }
        }
    }
}
