package jo.autofare.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.ui.components.Rule
import jo.autofare.ui.home.HomeScreen
import jo.autofare.ui.learn.LearnScreen
import jo.autofare.ui.rules.RulesScreen
import jo.autofare.ui.settings.SettingsScreen
import jo.autofare.ui.smart.SmartScreen
import jo.autofare.ui.stats.StatsScreen
import jo.autofare.ui.theme.LocalAfColors

enum class Dest(val label: String) {
    Home("الرئيسية"),
    Rules("القواعد"),
    Smart("الأوضاع"),
    Stats("الإحصائيات"),
    Settings("الإعدادات"),
    Learn("وضع التعلّم"),
}

private val BOTTOM_TABS = listOf(Dest.Home, Dest.Rules, Dest.Smart, Dest.Stats)

@Composable
fun AppScaffold() {
    val af = LocalAfColors.current
    var dest by rememberSaveable { mutableStateOf(Dest.Home) }

    Column(Modifier.fillMaxSize().background(af.paper)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Short crossfade only — a tab must feel instant. Each screen runs its
            // own entrance animation underneath, which carries the sense of motion.
            Crossfade(targetState = dest, animationSpec = tween(170), label = "dest") { d ->
                when (d) {
                    Dest.Home -> HomeScreen(onOpenSettings = { dest = Dest.Settings })
                    Dest.Rules -> RulesScreen()
                    Dest.Smart -> SmartScreen()
                    Dest.Stats -> StatsScreen()
                    Dest.Settings -> SettingsScreen(
                        onBack = { dest = Dest.Home },
                        onOpenLearn = { dest = Dest.Learn },
                    )
                    Dest.Learn -> LearnScreen(onBack = { dest = Dest.Settings })
                }
            }
        }
        BottomBar(current = dest, onSelect = { dest = it })
    }
}

/**
 * The Material navigation bar.
 *
 * The icons stay hand-drawn — they are the brand — but the bar around them is
 * now M3, which brings the selected-item indicator, the expressive press and
 * selection motion, correct insets, and a real `Tab` role per item for TalkBack.
 */
@Composable
private fun BottomBar(current: Dest, onSelect: (Dest) -> Unit) {
    val af = LocalAfColors.current
    Column(Modifier.fillMaxWidth()) {
        Rule()
        NavigationBar(
            containerColor = af.paper,
            tonalElevation = 0.dp,
        ) {
            BOTTOM_TABS.forEach { tab ->
                // Settings and Learn are pushed off Home, so Home stays lit there
                val selected = current == tab ||
                    ((current == Dest.Settings || current == Dest.Learn) && tab == Dest.Home)
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        val tint = if (selected) af.ink else af.muted
                        Canvas(Modifier.size(21.dp)) { drawTabIcon(tab, tint) }
                    },
                    label = {
                        Text(
                            tab.label,
                            fontSize = 10.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = af.ink,
                        unselectedTextColor = af.muted,
                        indicatorColor = af.sunk,
                    ),
                )
            }
        }
    }
}

/**
 * Icons are drawn rather than imported so they share one stroke weight and
 * match the hand-drawn feel of the rest of the system.
 */
private fun DrawScope.drawTabIcon(dest: Dest, color: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(s * x, s * y)

    when (dest) {
        Dest.Home -> {
            drawPath(Path().apply {
                moveTo(s * .12f, s * .45f); lineTo(s * .5f, s * .13f); lineTo(s * .88f, s * .45f)
            }, color, style = stroke)
            drawPath(Path().apply {
                moveTo(s * .22f, s * .40f); lineTo(s * .22f, s * .87f)
                lineTo(s * .78f, s * .87f); lineTo(s * .78f, s * .40f)
            }, color, style = stroke)
        }
        Dest.Rules -> {
            listOf(.25f, .5f, .75f).forEach { y ->
                drawLine(color, p(.14f, y), p(.86f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            drawLine(color, p(.36f, .11f), p(.36f, .39f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, p(.64f, .36f), p(.64f, .64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        Dest.Smart -> {
            drawCircle(color, radius = s * .38f, style = stroke)
            drawPath(Path().apply {
                moveTo(s * .5f, s * .28f); lineTo(s * .5f, s * .52f); lineTo(s * .68f, s * .62f)
            }, color, style = stroke)
        }
        Dest.Stats, Dest.Settings, Dest.Learn -> {
            drawLine(color, p(.10f, .86f), p(.90f, .86f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            listOf(.26f to .58f, .5f to .18f, .74f to .40f).forEach { (x, top) ->
                drawLine(color, p(x, top), p(x, .86f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}
