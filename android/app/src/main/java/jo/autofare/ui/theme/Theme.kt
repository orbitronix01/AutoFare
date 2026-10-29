package jo.autofare.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * AutoFare palette — mirrors design/prototype.html.
 * Direction: precision instrument on warm paper. Colour is functional only
 * (green = accepted, red = refused); the brand itself stays monochrome.
 */
data class AfColors(
    val paper: Color,
    val raised: Color,
    val sunk: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val rule: Color,
    val rule2: Color,
    val accept: Color,
    val acceptBg: Color,
    val reject: Color,
    val rejectBg: Color,
    val flag: Color,
    val flagBg: Color,
    val isDark: Boolean,
)

private val LightAf = AfColors(
    paper = Color(0xFFF4F2ED),
    raised = Color(0xFFFFFFFF),
    sunk = Color(0xFFEDEAE3),
    ink = Color(0xFF121417),
    ink2 = Color(0xFF3A3F45),
    muted = Color(0xFF71767C),
    rule = Color(0xFFDFDBD2),
    rule2 = Color(0xFFC9C4B8),
    accept = Color(0xFF0F6B43),
    acceptBg = Color(0xFFE3EFE8),
    reject = Color(0xFFA83420),
    rejectBg = Color(0xFFF6E8E5),
    flag = Color(0xFF8A6412),
    flagBg = Color(0xFFF4EDDC),
    isDark = false,
)

private val DarkAf = AfColors(
    paper = Color(0xFF121315),
    raised = Color(0xFF191B1E),
    sunk = Color(0xFF0D0E10),
    ink = Color(0xFFF2F1EC),
    ink2 = Color(0xFFC6C8CB),
    muted = Color(0xFF898E94),
    rule = Color(0xFF272A2E),
    rule2 = Color(0xFF3A3E43),
    accept = Color(0xFF4FCB8C),
    acceptBg = Color(0xFF132420),
    reject = Color(0xFFF07A66),
    rejectBg = Color(0xFF251715),
    flag = Color(0xFFD7A64C),
    flagBg = Color(0xFF241E12),
    isDark = true,
)

val LocalAfColors = staticCompositionLocalOf { LightAf }

/** The logo's 12° lean, reused as a system motif. */
const val SLANT_DEGREES = -12f

/**
 * The full mapping, not the five-colour version this used to carry.
 *
 * Material components colour themselves from the scheme, so anything left unset
 * falls back to Material's default purple. Every role a Switch, Card, Button,
 * SegmentedButton, Divider or ListItem reads has to be pointed at the AutoFare
 * palette or the restyle arrives looking like a stock Google app.
 *
 * `primary` is ink rather than a hue on purpose: colour in this product is
 * functional (green accepted, red refused) and the brand itself is monochrome.
 */
private fun schemeFor(af: AfColors) = (
    if (af.isDark) darkColorScheme() else lightColorScheme()
).copy(
    primary = af.ink,
    onPrimary = af.paper,
    primaryContainer = af.sunk,
    onPrimaryContainer = af.ink,
    background = af.paper,
    onBackground = af.ink,
    surface = af.paper,
    onSurface = af.ink,
    surfaceVariant = af.sunk,
    onSurfaceVariant = af.muted,
    surfaceContainerLowest = af.paper,
    surfaceContainerLow = af.paper,
    surfaceContainer = af.raised,
    surfaceContainerHigh = af.raised,
    surfaceContainerHighest = af.raised,
    secondary = af.accept,
    onSecondary = if (af.isDark) Color(0xFF0C1A14) else Color.White,
    secondaryContainer = af.acceptBg,
    onSecondaryContainer = af.accept,
    tertiary = af.flag,
    onTertiary = if (af.isDark) Color(0xFF241E12) else Color.White,
    tertiaryContainer = af.flagBg,
    onTertiaryContainer = af.flag,
    error = af.reject,
    onError = if (af.isDark) Color(0xFF251715) else Color.White,
    errorContainer = af.rejectBg,
    onErrorContainer = af.reject,
    outline = af.rule2,
    outlineVariant = af.rule,
    inverseSurface = af.ink,
    inverseOnSurface = af.paper,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AutoFareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val af = if (darkTheme) DarkAf else LightAf

    CompositionLocalProvider(LocalAfColors provides af) {
        // MaterialExpressiveTheme is the M3 Expressive entry point: it installs the
        // expressive motion scheme and shape scale that the 1.5 components animate
        // and clip against. Plain MaterialTheme would give the components without
        // the behaviour that makes them expressive.
        MaterialExpressiveTheme(
            colorScheme = schemeFor(af),
            motionScheme = MotionScheme.expressive(),
            typography = AfTypography,
            content = content,
        )
    }
}
