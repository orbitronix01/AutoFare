package jo.autofare.detect

/**
 * Matching Arabic words that a ride app wrote, not Arabic words as a dictionary
 * spells them.
 *
 * Jeeny labels its pickup ETA `يبعُد 4 دقائق` — with a damma sitting on the ع. It
 * is one invisible code point, it is not in anybody's keyword list, and a plain
 * `contains("يبعد")` walks straight past it. The first driver capture also showed
 * the same app writing `إيكونومي` and `إيكو لايت`, and alef in Arabic text is typed
 * as ا / أ / إ / آ almost at random.
 *
 * So nothing in the detector compares raw strings. Both sides go through [fold]
 * first, which removes the marks that carry no meaning for us and collapses the
 * letter forms that drivers and developers treat as the same letter.
 */
object Arabic {

    /**
     * A form of the text suitable for keyword matching: no diacritics, no tatweel,
     * one spelling per letter, lowercased, single-spaced.
     *
     * Lossy on purpose. Never display the result — it is for comparison only.
     */
    fun fold(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                isMark(ch) -> Unit
                ch == TATWEEL -> Unit
                ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ' -> out.append('ا')
                ch == 'ى' -> out.append('ي')
                ch == 'ة' -> out.append('ه')
                ch == 'ؤ' -> out.append('و')
                ch == 'ئ' -> out.append('ي')
                else -> out.append(ch)
            }
        }
        return out.toString().lowercase().replace(WHITESPACE, " ").trim()
    }

    /** True when [needle] appears in [haystack] once both are folded. */
    fun has(haystack: String?, needle: String): Boolean =
        haystack != null && fold(haystack).contains(fold(needle))

    /** True when any of [needles] appears in [haystack]. */
    fun hasAny(haystack: String?, needles: Collection<String>): Boolean {
        if (haystack == null) return false
        val folded = fold(haystack)
        return needles.any { folded.contains(fold(it)) }
    }

    /** True when [haystack] folds to exactly [needle] — for short button labels. */
    fun eq(haystack: String?, needle: String): Boolean =
        haystack != null && fold(haystack) == fold(needle)

    /**
     * The harakat and the Quranic marks. These sit on top of a letter and change
     * nothing we care about, but they are full code points in the string a node
     * hands back.
     */
    private fun isMark(ch: Char): Boolean =
        ch in 'ً'..'ْ' ||   // fathatan … sukun
            ch == 'ٓ' || ch == 'ٔ' || ch == 'ٕ' ||
            ch == 'ٰ' ||          // superscript alef
            ch in 'ۖ'..'ۭ'

    private const val TATWEEL = 'ـ'

    private val WHITESPACE = Regex("""\s+""")
}
