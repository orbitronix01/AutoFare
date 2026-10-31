package jo.autofare.ui

/**
 * Counted nouns, in Arabic rather than in English with Arabic words.
 *
 * Arabic does not pluralise the way the string `"$n دقايق"` assumes. The number
 * governs the noun in four different ways, and getting it wrong is the single
 * most obvious sign that an app was written by someone who does not speak the
 * language:
 *
 *   1        دقيقة        the numeral is dropped entirely
 *   2        دقيقتين      dual form, numeral also dropped
 *   3–10     ٣ دقائق      plural
 *   11+      ١٢ دقيقة     back to the singular
 *
 * And it repeats on every hundred, so 103 takes the plural again while 112 does
 * not. [counted] is the one place that rule lives.
 *
 * The numeral is printed in Arabic-Indic digits ([arNum]): a Latin "5" inside
 * an Arabic phrase is the other giveaway, and the one the owner flagged.
 */
fun counted(n: Int, one: String, two: String, few: String, many: String): String {
    val mod100 = n % 100
    return when {
        n == 1 -> one
        n == 2 -> two
        mod100 in 3..10 -> "${arNum(n)} $few"
        else -> "${arNum(n)} $many"
    }
}

/** دقيقة · دقيقتين · ٥ دقائق · ١٢ دقيقة */
fun minutes(n: Int): String = counted(n, "دقيقة", "دقيقتين", "دقائق", "دقيقة")

/**
 * Just the noun, for places like the stepper where the numeral is a control and
 * has to stay on screen. 3–10 still take the plural; everything else does not.
 */
fun minutesUnit(n: Int): String = if (n % 100 in 3..10) "دقائق" else "دقيقة"

/** طلب · طلبين · ٥ طلبات · ١٢ طلب */
fun requests(n: Int): String = counted(n, "طلب", "طلبين", "طلبات", "طلب")

/** رحلة · رحلتين · ٥ رحلات · ١٢ رحلة */
fun rides(n: Int): String = counted(n, "رحلة", "رحلتين", "رحلات", "رحلة")

/** يوم · يومين · ٥ أيام · ١٢ يوم */
fun days(n: Int): String = counted(n, "يوم", "يومين", "أيام", "يوم")

/* ───────────────────────── digits ───────────────────────── */

/*
 * Every number the driver reads is printed in Arabic-Indic digits, ٠١٢٣٤٥٦٧٨٩,
 * with ٫ as the decimal point. Latin digits in the middle of an Arabic sentence
 * read as a foreign object, and a Jordanian driver reading Arabic text expects
 * the numerals that go with it.
 *
 * Deliberately NOT routed through here, and kept Latin: the subscription code
 * and its placeholder (typed on a Latin keyboard), the device ID (read out to
 * support and matched by the admin site), the app version, anything sent to the
 * server, stored in preferences or written to logs, and everything under
 * detect/, which parses the ride apps' own text.
 *
 * The figure styles in ui/theme/Type.kt are set in IBM Plex Sans Arabic for the
 * same reason: Archivo has no Arabic-Indic glyphs.
 */

private const val AR_ZERO = '\u0660'

/** Arabic decimal separator, ٫ (U+066B). */
const val AR_DECIMAL = '\u066B'

/** Arabic percent sign, ٪ (U+066A). */
const val AR_PERCENT = '\u066A'

/**
 * Latin digits 0–9 to Arabic-Indic ٠–٩, and a '.' to '٫' only where it sits
 * between two digits — so "2.50" becomes "٢٫٥٠" while the dot in "د.أ" or at
 * the end of a sentence is left alone. Everything else passes through.
 */
fun arDigits(s: String): String {
    val out = StringBuilder(s.length)
    for (i in s.indices) {
        val c = s[i]
        out.append(
            when {
                c in '0'..'9' -> AR_ZERO + (c - '0')
                c == '.' && i > 0 && i < s.length - 1 && isDigit(s[i - 1]) && isDigit(s[i + 1]) -> AR_DECIMAL
                else -> c
            }
        )
    }
    return out.toString()
}

/**
 * The reverse, for input: Arabic-Indic (and Persian) digits to Latin, and ٫ or
 * the Arabic comma between digits to '.', so a number typed on either keyboard
 * parses with toInt()/toDouble().
 */
fun latinDigits(s: String): String {
    val out = StringBuilder(s.length)
    for (c in s) {
        out.append(
            when (c) {
                in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                AR_DECIMAL -> '.'
                else -> c
            }
        )
    }
    return out.toString()
}

private fun isDigit(c: Char) = c in '0'..'9' || c in '\u0660'..'\u0669'

/** A whole number in Arabic-Indic digits: 12 → ١٢. */
fun arNum(n: Int): String = arDigits(n.toString())

/** A fixed-point figure in Arabic-Indic digits: (2.5, 2) → ٢٫٥٠. */
fun arFixed(v: Double, places: Int = 2): String =
    arDigits(String.format(java.util.Locale.US, "%.${places}f", v))

/** A percentage: 25 → ٢٥٪. The sign follows the digits, as Arabic writes it. */
fun arPercent(n: Int): String = "${arNum(n)}$AR_PERCENT"

/* ───────────────────────── bidi ───────────────────────── */

/**
 * Wrap a left-to-right run — a code, a version, a Latin name — in FSI … PDI so
 * it cannot reorder the Arabic around it.
 *
 * NOT for figures any more. Arabic-Indic digits are bidi class AN: a run of them
 * keeps its own digit order and sits in the right-to-left flow like a word, so
 * "بين ٢٫٥٠ و١٢٫٠٠ د.أ" reads correctly bare. An isolate around them would
 * instead force the run, and anything with it such as ٪, into left-to-right.
 */
fun ltr(s: String): String = "\u2068$s\u2069"

/**
 * Dinars to two places in Arabic-Indic digits: `٢٫٥٠ د.أ`. No bidi isolate is
 * needed, see [ltr].
 */
fun dinars(v: Double): String = "${arFixed(v)} د.أ"

/** A bare figure to two places, Arabic-Indic, for sentences that name the unit once: `٠٫٦٠`. */
fun fig(v: Double): String = arFixed(v)
