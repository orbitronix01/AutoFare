package jo.autofare.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jo.autofare.ui.theme.*

/* ───────────────────────── structure ───────────────────────── */

/** Hairline divider — the app leans on rules rather than boxing everything. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = LocalAfColors.current.rule)
}

@Composable
fun VRule() {
    VerticalDivider(Modifier.fillMaxHeight(), color = LocalAfColors.current.rule)
}

/** Numbered section marker: `01 ─── التطبيقات ──────────` */
@Composable
fun SectionHeader(index: String, label: String, modifier: Modifier = Modifier) {
    val af = LocalAfColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 30.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(index, style = SectionIndex, color = af.muted)
        Spacer(Modifier.width(11.dp))
        Text(label, style = SectionLabel, color = af.muted)
        Spacer(Modifier.width(11.dp))
        HorizontalDivider(Modifier.weight(1f), color = af.rule)
    }
}

/** Screen title block used at the top of the non-home screens. */
@Composable
fun ScreenHeader(title: String, subtitle: String) {
    val af = LocalAfColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = af.ink)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = af.muted)
        }
        Spacer(Modifier.width(12.dp))
        SpeedLines(af.ink, Modifier.padding(top = 8.dp))
    }
}

/**
 * Grouping surface. An M3 [OutlinedCard] rather than a hand-rolled Box: it picks
 * up the expressive shape scale and the card elevation/outline tokens.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val af = LocalAfColors.current
    OutlinedCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = af.raised),
        border = BorderStroke(1.dp, af.rule),
    ) {
        Column(Modifier.padding(17.dp), content = content)
    }
}

/**
 * A card with no padding of its own, for the blocks that lay their own content
 * out — the score dial, the advice strip, the capture counter. [Panel] is the
 * same surface with the standard 17dp inset already applied.
 */
@Composable
fun AfCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val af = LocalAfColors.current
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = af.raised),
        border = BorderStroke(1.dp, af.rule),
        content = content,
    )
}

/* ───────────────────────── brand motif ───────────────────────── */

/** The logo's three speed lines, sheared to the same lean as the wordmark. */
@Composable
fun SpeedLines(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 23.dp, height = 15.dp)) { drawSpeedLines(color) }
}

private fun DrawScope.drawSpeedLines(color: Color) {
    val h = 2.5.dp.toPx()
    val gap = 3.dp.toPx()
    val shear = 2.6.dp.toPx()
    listOf(20.dp.toPx() to 1f, 13.dp.toPx() to 0.5f, 7.dp.toPx() to 0.26f)
        .forEachIndexed { i, (w, alpha) ->
            val y = i * (h + gap)
            val p = Path().apply {
                moveTo(shear, y); lineTo(shear + w, y); lineTo(w, y + h); lineTo(0f, y + h); close()
            }
            drawPath(p, color = color, alpha = alpha)
        }
}

/** Status band — an M3 surface carrying the shift state. */
@Composable
fun StatusBand(text: String, running: Boolean) {
    val af = LocalAfColors.current
    val bg = if (running) af.accept else af.flag
    val fg = if (af.isDark) Color(0xFF0C1A14) else Color.White
    Surface(color = bg, shape = MaterialTheme.shapes.extraSmall) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(fg, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(text, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = fg)
        }
    }
}

/**
 * The green tick / amber bang used wherever the app reports readiness —
 * permissions on Settings, the accessibility service on Learn.
 */
@Composable
fun StatusTick(ready: Boolean, size: Int = 30) {
    val af = LocalAfColors.current
    Surface(
        color = if (ready) af.acceptBg else af.flagBg,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                if (ready) "✓" else "!",
                fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                color = if (ready) af.accept else af.flag,
            )
        }
    }
}

/* ───────────────────────── controls ───────────────────────── */

/**
 * The Material switch.
 *
 * This replaces a Canvas with a `clickable` on it. The drawn version looked right
 * but was invisible to TalkBack: no role, no checked state, no minimum touch
 * target. A driver using the phone with accessibility on could not tell whether
 * auto-accept was armed — which is the one thing on the screen that matters.
 */
@Composable
fun AfSwitch(on: Boolean, onToggle: () -> Unit) {
    val af = LocalAfColors.current
    Switch(
        checked = on,
        onCheckedChange = { onToggle() },
        colors = SwitchDefaults.colors(
            checkedThumbColor = if (af.isDark) Color(0xFF0C1A14) else Color.White,
            checkedTrackColor = af.accept,
            checkedBorderColor = af.accept,
            uncheckedThumbColor = af.muted,
            uncheckedTrackColor = af.sunk,
            uncheckedBorderColor = af.rule2,
        ),
    )
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    val af = LocalAfColors.current
    val shape = MaterialTheme.shapes.medium
    val padding = PaddingValues(vertical = 18.dp, horizontal = 12.dp)
    val label: @Composable () -> Unit = {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) af.paper else af.ink,
            textAlign = TextAlign.Center,
        )
    }
    if (filled) {
        Button(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(containerColor = af.ink, contentColor = af.paper),
        ) { label() }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = af.ink),
            border = BorderStroke(1.dp, af.rule2),
        ) { label() }
    }
}

/**
 * Segmented control, now the real M3 one.
 *
 * It still wraps to as many rows as needed: Arabic option labels are long, and a
 * single `SingleChoiceSegmentedButtonRow` holding four of them clips on a narrow
 * phone. Each row is its own M3 row, and [columns] decides where the wrap falls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    columns: Int = 2,
    modifier: Modifier = Modifier,
) {
    val af = LocalAfColors.current
    val rows = options.chunked(columns)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                row.forEachIndexed { colIndex, label ->
                    val index = rowIndex * columns + colIndex
                    SegmentedButton(
                        selected = index == selected,
                        onClick = { onSelect(index) },
                        shape = SegmentedButtonDefaults.itemShape(colIndex, row.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = af.raised,
                            activeContentColor = af.ink,
                            activeBorderColor = af.rule2,
                            inactiveContainerColor = af.sunk,
                            inactiveContentColor = af.muted,
                            inactiveBorderColor = af.rule,
                        ),
                        icon = {},
                    ) {
                        Text(
                            label,
                            fontSize = 12.5.sp,
                            fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (index == selected) af.ink else af.muted,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/** Number field with − / + on either side, on M3 icon buttons. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Stepper(
    label: String,
    value: String,
    unit: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val af = LocalAfColors.current
    Column(Modifier.fillMaxWidth().padding(top = 15.dp)) {
        if (label.isNotEmpty()) {
            Text(label, fontSize = 12.sp, color = af.muted)
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth().height(50.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepKey("−", onMinus)
            Card(
                Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = af.raised),
                border = BorderStroke(1.dp, af.ink),
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(value, style = FigureMedium.copy(fontSize = 22.sp), color = af.ink)
                    Spacer(Modifier.width(7.dp))
                    Text(unit, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = af.muted)
                }
            }
            StepKey("+", onPlus)
        }
    }
}

@Composable
private fun StepKey(glyph: String, onClick: () -> Unit) {
    val af = LocalAfColors.current
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(width = 44.dp, height = 50.dp),
        shape = MaterialTheme.shapes.medium,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = af.raised,
            contentColor = af.ink,
        ),
    ) {
        Text(glyph, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = af.ink)
    }
}

/**
 * A labelled row with a switch on the end, as an M3 [ListItem] so the title and
 * supporting text carry the right roles for a screen reader.
 */
@Composable
fun ToggleLine(
    title: String,
    detail: String,
    checked: Boolean,
    onToggle: () -> Unit,
    showRule: Boolean = true,
) {
    val af = LocalAfColors.current
    Column {
        ListItem(
            headlineContent = {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = af.ink)
            },
            supportingContent = {
                Text(detail, fontSize = 11.5.sp, color = af.muted, lineHeight = 18.sp)
            },
            trailingContent = { AfSwitch(checked, onToggle) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.padding(vertical = 4.dp),
        )
        if (showRule) Rule()
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    val af = LocalAfColors.current
    Text(text, fontSize = 11.5.sp, color = af.muted, lineHeight = 19.sp, modifier = modifier.padding(top = 12.dp))
}
