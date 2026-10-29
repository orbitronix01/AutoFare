package jo.autofare.detect

/**
 * Reading numbers off an Arabic screen.
 *
 * The first real driver capture came back from a phone in Arabic locale, where
 * every figure on screen is rendered in Arabic-Indic digits: the battery read
 * `%` followed by U+0665 U+0662, the clock read U+0661 U+0660 `:` U+0660 U+0667.
 * A ride offer on that phone shows its fare and its ETA the same way, so a rule
 * like "at least 3 JOD" compared against a fare written in those digits would
 * never once fire. Everything the detector reads goes through [normalize] first.
 *
 * Two details worth keeping in mind:
 *
 *  - The dinar carries **three** decimals — `1.500 JOD` is one and a half dinars,
 *    not fifteen hundred. So a lone separator is always a decimal point here, and
 *    only repeated separators mean thousands.
 *  - Arabic has its own separators: U+066B decimal and U+066C thousands. They are
 *    not commas and periods, and they sit alongside bidi control marks that are
 *    invisible but very much present in the text a node hands back.
 *
 * Characters are written as escapes throughout, deliberately: half of what this
 * function exists to delete cannot be seen in an editor.
 */
object Numbers {

    /** Arabic-Indic and Persian digits to ASCII, Arabic separators to ASCII, bidi marks dropped. */
    fun normalize(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch in '٠'..'٩' -> out.append('0' + (ch - '٠'))  // Arabic-Indic ٠-٩
                ch in '۰'..'۹' -> out.append('0' + (ch - '۰'))  // Persian ۰-۹
                ch == '٫' -> out.append('.')   // Arabic decimal separator
                ch == '٬' -> Unit              // Arabic thousands separator
                ch == '٪' -> out.append('%')   // Arabic percent sign
                ch == '،' -> out.append(',')   // Arabic comma
                isMark(ch) -> Unit             // harakat: Jeeny writes يبعُد, with a damma
                isInvisible(ch) -> Unit
                isOddSpace(ch) -> out.append(' ')
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * The fare on a screen, in dinars.
     *
     * An offer popup holds several numbers at once — fare, ETA, distance, rating —
     * so when a currency marker is present the number nearest to it wins rather
     * than simply the first one found. A figure quoted in fils is divided down.
     */
    fun money(raw: String): Double? {
        val s = normalize(raw)
        val nums = numbers(s)
        if (nums.isEmpty()) return null
        val marker = CURRENCY.find(s) ?: return nums.first().value
        val nearest = nums.minByOrNull { gap(it.range, marker.range) }?.value ?: return null
        return if (FILS.containsMatchIn(s)) nearest / 1000.0 else nearest
    }

    /**
     * The fare, but only from a string that actually names a currency.
     *
     * [money] falls back to the first number it can find when no marker is present,
     * which is right for a node already known to hold a price and badly wrong for
     * sorting an offer card out node by node: Jeeny's distance label `يبعُد 1.8كم`
     * would read as a fare of 1.8 dinars and clear any floor the driver set. Use
     * this one whenever the question is *whether* this node is the price.
     */
    fun moneyStrict(raw: String): Double? {
        val s = normalize(raw)
        return if (CURRENCY.containsMatchIn(s)) money(raw) else null
    }

    /** True when the text names dinars or fils in any spelling. */
    fun hasCurrency(raw: String): Boolean = CURRENCY.containsMatchIn(normalize(raw))

    /** An ETA in whole minutes, summing hours and minutes when both are written out. */
    fun minutes(raw: String): Int? {
        val s = normalize(raw)
        val hours = valueFor(s, HOURS)
        val mins = valueFor(s, MINUTES)
        if (hours == null && mins == null) return null
        return ((hours ?: 0.0) * 60.0 + (mins ?: 0.0)).toInt()
    }

    /** A distance in kilometres, accepting a figure written in metres. */
    fun km(raw: String): Double? {
        val s = normalize(raw)
        valueFor(s, KM)?.let { return it }
        valueFor(s, METRES)?.let { return it / 1000.0 }
        return null
    }

    /** The whole point of the app: what this ride pays per minute of the driver's time. */
    fun perMinute(fare: Double, minutes: Int): Double? =
        if (minutes <= 0) null else fare / minutes

    /* ───────────────────────── internals ───────────────────────── */

    /** LRM, RLM, ALM, the embedding and isolate controls, and a stray BOM. */
    private fun isInvisible(ch: Char): Boolean =
        ch == '\u200E' || ch == '\u200F' || ch == '\u061C' ||
            ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' || ch == '\uFEFF'

    /** Harakat and the Quranic marks — invisible to a reader, real code points to us. */
    private fun isMark(ch: Char): Boolean =
        ch in 'ً'..'ْ' || ch == 'ٰ' || ch == 'ٓ' || ch == 'ٔ' || ch == 'ٕ'

    /** Non-breaking, narrow non-breaking, and figure spaces all read as plain spaces. */
    private fun isOddSpace(ch: Char): Boolean =
        ch == '\u00A0' || ch == '\u202F' || ch == '\u2007' || ch == '\u2009'

    private val NUMBER = Regex("""\d[\d.,]*\d|\d""")

    // دينار / دنانير / فلس / فلوس / د.أ, plus the Latin forms
    private val CURRENCY = Regex(
        """د\s*\.?\s*أ|دينار|دنانير|""" +
            """فلس|فلوس|JOD|JD""",
        RegexOption.IGNORE_CASE,
    )

    /** فلس / فلوس — a fare quoted in fils is a thousandth of a dinar. */
    private val FILS = Regex("""فلس|فلوس""")

    // ساعات / ساعة / ساعه
    private val HOURS = Regex(
        """ساعات|ساعة|ساعه|hours?|hrs?""",
        RegexOption.IGNORE_CASE,
    )

    // دقائق / دقيقة / دقيقه
    private val MINUTES = Regex(
        """دقائق|دقيقة|دقيقه|""" +
            """minutes?|mins?""",
        RegexOption.IGNORE_CASE,
    )

    // كيلومتر before متر, or the longer unit gets read as the shorter one
    private val KM = Regex(
        """كيلو\s*متر|كم|كيلو|km""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * متر — and the bare م that Jeeny uses for anything under a kilometre: the
     * capture is full of `يبعُد 963م` and `يبعُد ٧٧٩م`. Read as kilometres those
     * offers look 1000× too far away and every one of them gets rejected.
     *
     * The bare form has to be anchored to a digit and refused in front of another
     * Arabic letter, or it matches the م starting any ordinary word — مكافأة,
     * مسافات — and drags an unrelated number in with it.
     */
    private val METRES = Regex(
        """متر|\d\s*م(?![ء-ي])|\bm\b""",
        RegexOption.IGNORE_CASE,
    )

    private class Found(val value: Double, val range: IntRange)

    private fun numbers(s: String): List<Found> =
        NUMBER.findAll(s).mapNotNull { m -> toDouble(m.value)?.let { Found(it, m.range) } }.toList()

    /**
     * A figure and its unit: `5 minutes`. The number sits before the unit in both
     * Arabic and English, so a preceding number is preferred, with the nearest
     * number as a fallback for layouts that split the two into separate nodes.
     */
    private fun valueFor(s: String, unit: Regex): Double? {
        val m = unit.find(s) ?: return null
        val nums = numbers(s)
        if (nums.isEmpty()) return null
        val before = nums.filter { it.range.last < m.range.first }
        val pick = before.maxByOrNull { it.range.last } ?: nums.minByOrNull { gap(it.range, m.range) }
        return pick?.value
    }

    private fun gap(a: IntRange, b: IntRange): Int = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }

    private fun toDouble(token: String): Double? {
        val t = token.trim('.', ',')
        val dots = t.count { it == '.' }
        val commas = t.count { it == ',' }
        val cleaned = when {
            dots + commas == 0 -> t
            // a single separator is a decimal point — the dinar has three of them
            dots + commas == 1 -> t.replace(',', '.')
            dots > 0 && commas > 0 -> t.replace(",", "")        // 1,500.75
            dots > 1 -> t.replace(".", "")                      // 1.500.000
            else -> t.replace(",", "")                          // 1,500,000
        }
        return cleaned.toDoubleOrNull()
    }
}
