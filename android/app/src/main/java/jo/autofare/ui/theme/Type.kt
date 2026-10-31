package jo.autofare.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import jo.autofare.R

/*
 * Two families, split by what they have to render.
 *
 * Until now this file was a single `FontFamily.Default`, which meant every
 * Arabic glyph in the app fell through to whatever naskh face the phone happened
 * to ship — usually Noto Naskh, a thin bookish face that looks nothing like the
 * design. IBM Plex Sans Arabic is now bundled in res/font, so the app renders the
 * same type as design/prototype.html.
 */

/** Arabic, and any Latin sitting inside an Arabic sentence. */
private val Plex = FontFamily(
    Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold),
)

/*
 * Archivo (res/font/archivo_variable.ttf) used to set the figures, back when they
 * were Latin. It has no Arabic-Indic glyphs, so now that every figure is printed
 * in ٠١٢٣٤٥٦٧٨٩ (see ui/ArabicText.kt) the figure styles below use Plex, which
 * does. Plex stops at Bold, so the Black and ExtraBold figures become Bold, its
 * heaviest cut; sizes are unchanged so the hierarchy holds.
 */

/*
 * Tracking rule, and the reason the Arabic looked broken as much as it looked wrong:
 *
 * Arabic is cursive — letters join. Any letterSpacing, negative OR positive, is
 * inserted between glyphs and pulls those joins apart, so a tracked Arabic string
 * reads as disconnected shapes. Every style below that can carry Arabic is set to
 * zero tracking. Tight negative tracking survives only on the figure styles:
 * Arabic-Indic digits do not join, so they take it fine. A figure style that has
 * to carry Arabic words (e.g. "مدى الحياة") must reset letterSpacing to zero.
 */

/** Figures: heavy weight, no tracking — numbers are the main visual element. Negative tracking made Arabic-Indic figures mis-measure and wrap. */
val FigureHuge = TextStyle(
    fontFamily = Plex,
    fontWeight = FontWeight.Bold,
    fontSize = 62.sp,
    letterSpacing = 0.sp,
)

val FigureLarge = TextStyle(
    fontFamily = Plex,
    fontWeight = FontWeight.Bold,
    fontSize = 30.sp,
    letterSpacing = 0.sp,
)

val FigureMedium = TextStyle(
    fontFamily = Plex,
    fontWeight = FontWeight.Bold,
    fontSize = 21.sp,
    letterSpacing = 0.sp,
)

/** Numbered section markers: ٠١ / ٠٢ / ٠٣ — Arabic-Indic digits, so Plex. */
val SectionIndex = TextStyle(
    fontFamily = Plex,
    fontWeight = FontWeight.Bold,
    fontSize = 10.5.sp,
    letterSpacing = 0.4.sp,
)

/** The Arabic label beside it. Tracking is zero on purpose — see above. */
val SectionLabel = TextStyle(
    fontFamily = Plex,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    letterSpacing = 0.sp,
)

/** Re-point a Material default at Plex and strip its Latin tracking. */
private fun TextStyle.ar() = copy(fontFamily = Plex, letterSpacing = 0.sp)

/**
 * EVERY role is overridden, not just the handful the screens name explicitly.
 *
 * A bare `Text("…", fontSize = 13.sp)` carries no style of its own, so it resolves
 * through LocalTextStyle, which Material3 fills from `bodyLarge`. Most of this app
 * is written that way. Leaving any role on the Material default would quietly send
 * that text back to the system font and undo the whole fix.
 */
private val Defaults = Typography()

val AfTypography = Typography(
    displayLarge = Defaults.displayLarge.ar(),
    displayMedium = Defaults.displayMedium.ar(),
    displaySmall = Defaults.displaySmall.ar(),
    headlineLarge = Defaults.headlineLarge.ar(),
    headlineMedium = TextStyle(
        fontFamily = Plex, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, lineHeight = 36.sp, letterSpacing = 0.sp,
    ),
    headlineSmall = Defaults.headlineSmall.ar(),
    titleLarge = Defaults.titleLarge.ar(),
    titleMedium = TextStyle(
        fontFamily = Plex, fontWeight = FontWeight.Bold,
        fontSize = 14.5.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
    ),
    titleSmall = Defaults.titleSmall.ar(),
    // Arabic needs more leading than Latin at the same size: vowel marks sit above
    // the line and descenders below, so Latin leading makes lines collide.
    bodyLarge = TextStyle(
        fontFamily = Plex, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 25.sp, letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Plex, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 23.sp, letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Plex, fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Plex, fontWeight = FontWeight.Bold,
        fontSize = 15.sp, letterSpacing = 0.sp,
    ),
    labelMedium = Defaults.labelMedium.ar(),
    labelSmall = Defaults.labelSmall.ar(),
)
