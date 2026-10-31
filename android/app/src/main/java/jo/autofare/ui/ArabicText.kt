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
 *   3–10     3 دقائق      plural
 *   11+      12 دقيقة     back to the singular
 *
 * And it repeats on every hundred, so 103 takes the plural again while 112 does
 * not. [counted] is the one place that rule lives.
 */
fun counted(n: Int, one: String, two: String, few: String, many: String): String {
    val mod100 = n % 100
    return when {
        n == 1 -> one
        n == 2 -> two
        mod100 in 3..10 -> "$n $few"
        else -> "$n $many"
    }
}

/** دقيقة · دقيقتين · 5 دقائق · 12 دقيقة */
fun minutes(n: Int): String = counted(n, "دقيقة", "دقيقتين", "دقائق", "دقيقة")

/**
 * Just the noun, for places like the stepper where the numeral is a control and
 * has to stay on screen. 3–10 still take the plural; everything else does not.
 */
fun minutesUnit(n: Int): String = if (n % 100 in 3..10) "دقائق" else "دقيقة"

/** طلب · طلبين · 5 طلبات · 12 طلب */
fun requests(n: Int): String = counted(n, "طلب", "طلبين", "طلبات", "طلب")

/** رحلة · رحلتين · 5 رحلات · 12 رحلة */
fun rides(n: Int): String = counted(n, "رحلة", "رحلتين", "رحلات", "رحلة")

/** يوم · يومين · 5 أيام · 12 يوم */
fun days(n: Int): String = counted(n, "يوم", "يومين", "أيام", "يوم")
