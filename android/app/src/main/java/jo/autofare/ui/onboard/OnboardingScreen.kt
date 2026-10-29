package jo.autofare.ui.onboard

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.R
import jo.autofare.ui.components.PrimaryButton
import jo.autofare.ui.motion.FillRing
import jo.autofare.ui.motion.Motion
import jo.autofare.ui.motion.enterProgress
import jo.autofare.ui.theme.LocalAfColors

private data class Step(
    val headline: String,
    val body: String,
    val cta: String,
    val reassurance: String,
)

private val STEPS = listOf(
    Step(
        headline = "خلّي AutoFare\nيشوف الطلبات",
        body = "عشان نقبل بدالك، لازم نشوف الشاشة لما يجيك طلب. اضغط الزر وفعّل AutoFare من قائمة إمكانية الوصول.",
        cta = "افتح الإعدادات",
        reassurance = "ما منقرأ ولا منخزّن أي إشي خارج تطبيقات الرحلات.",
    ),
    Step(
        headline = "خلّي التطبيق\nشغّال دايماً",
        body = "هواوي وشاومي بيسكّروا التطبيقات لحالهم عشان البطارية. لازم تستثني AutoFare، غير هيك بيوقف وإنت بالطريق.",
        cta = "افتح إعدادات البطارية",
        reassurance = "هاي أهم خطوة — بدونها التطبيق ما بظل شغّال.",
    ),
    Step(
        headline = "حدّد\nقواعدك",
        body = "قلّنا شو الطلب اللي بدك إياه — السعر، وقت الوصول، ولا الربح بالدقيقة. وإحنا منقرّر بدالك بأقل من ثانية.",
        cta = "يلا نبلّش",
        reassurance = "بتقدر تغيّرها بأي وقت من شاشة القواعد.",
    ),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val af = LocalAfColors.current
    val context = LocalContext.current
    var index by rememberSaveable { mutableStateOf(0) }
    val step = STEPS[index]

    Column(
        Modifier
            .fillMaxSize()
            .background(af.paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 26.dp),
    ) {
        Spacer(Modifier.height(14.dp))

        // progress — three slanted bars, same motif as everything else
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            STEPS.indices.forEach { i ->
                Canvas(Modifier.weight(1f).height(3.dp)) {
                    val shear = 2.dp.toPx()
                    drawPath(
                        Path().apply {
                            moveTo(shear, 0f); lineTo(size.width, 0f)
                            lineTo(size.width - shear, size.height); lineTo(0f, size.height); close()
                        },
                        color = if (i <= index) af.ink else af.rule2,
                    )
                }
            }
        }

        Spacer(Modifier.height(34.dp))

        // Forced LTR: in an RTL layout "01 / 03" otherwise renders as "03 / 01".
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                "0${index + 1} / 0${STEPS.size}",
                fontWeight = FontWeight.Black, fontSize = 13.sp,
                letterSpacing = 1.sp, color = af.muted,
            )
        }

        Spacer(Modifier.height(12.dp))

        // No negative tracking here: this headline is Arabic, and tracking pulls
        // the cursive joins apart. See the note in theme/Type.kt.
        Text(
            step.headline,
            fontWeight = FontWeight.Bold, fontSize = 33.sp,
            lineHeight = 46.sp, letterSpacing = 0.sp, color = af.ink,
        )

        Spacer(Modifier.height(14.dp))

        Text(step.body, fontSize = 13.5.sp, color = af.muted, lineHeight = 26.sp)

        // visual — the wheel fills up each time a step appears
        val ring = enterProgress(key = index, durationMillis = Motion.SWEEP)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            FillRing(
                progress = ring,
                diameter = 154.dp,
                track = af.rule2,
                fill = af.accept,
                pip = af.accept,
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.logo_mark),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(af.ink),
                    modifier = Modifier
                        .height(58.dp)
                        .alpha(0.25f + 0.75f * ring)
                        .scale(0.9f + 0.1f * ring),
                )
            }
        }

        PrimaryButton(
            text = step.cta,
            onClick = {
                when (index) {
                    0 -> context.safeStart(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    1 -> context.safeStart(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                }
                if (index < STEPS.lastIndex) index++ else onDone()
            },
        )

        Text(
            step.reassurance,
            fontSize = 11.5.sp, color = af.muted, lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )

        Text(
            if (index < STEPS.lastIndex) "تخطّى" else " ",
            fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = af.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = index < STEPS.lastIndex) { onDone() }
                .padding(vertical = 14.dp),
        )
    }
}

/** Some OEM builds lack these settings screens — never crash the onboarding over it. */
private fun android.content.Context.safeStart(action: String) {
    runCatching {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
