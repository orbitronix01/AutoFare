package jo.autofare.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ArabicTextTest {

    @Test
    fun `digits map to Arabic-Indic`() {
        assertEquals("٠١٢٣٤٥٦٧٨٩", arDigits("0123456789"))
    }

    @Test
    fun `a dot becomes the Arabic decimal only between digits`() {
        assertEquals("٢٫٥٠", arDigits("2.50"))
        assertEquals("د.أ", arDigits("د.أ"))
        assertEquals("٥.", arDigits("5."))
        assertEquals(".٥", arDigits(".5"))
        assertEquals("٢٫٥٠ د.أ.", arDigits("2.50 د.أ."))
    }

    @Test
    fun `other characters pass through, and it is idempotent`() {
        assertEquals("١٤:٠٠ — XYZ", arDigits("14:00 — XYZ"))
        assertEquals("٢٫٥٠", arDigits(arDigits("2.50")))
        assertEquals("", arDigits(""))
    }

    @Test
    fun `latinDigits reverses it, for input`() {
        assertEquals("2.50", latinDigits("٢٫٥٠"))
        assertEquals("1234", latinDigits("۱۲۳۴"))
        assertEquals("AB12-34", latinDigits("AB١٢-٣٤"))
        assertEquals("12.5", latinDigits(arDigits("12.5")))
    }

    @Test
    fun `formatters`() {
        assertEquals("١٢", arNum(12))
        assertEquals("٠", arNum(0))
        assertEquals("٢٫٥٠", arFixed(2.5))
        assertEquals("١٫٢", arFixed(1.24, 1))
        assertEquals("٢٥٪", arPercent(25))
        assertEquals("٢٫٥٠ د.أ", dinars(2.5))
        assertEquals("٠٫٦٠", fig(0.6))
    }

    @Test
    fun `figures carry no bidi isolates`() {
        listOf(dinars(12.0), fig(2.5), arPercent(5)).forEach {
            assert(!it.contains('⁨') && !it.contains('⁩')) { it }
        }
    }

    @Test
    fun `counted nouns use Arabic-Indic numerals`() {
        assertEquals("دقيقة", minutes(1))
        assertEquals("دقيقتين", minutes(2))
        assertEquals("٦ دقائق", minutes(6))
        assertEquals("١٢ دقيقة", minutes(12))
        assertEquals("١٠٣ أيام", days(103))
        assertEquals("١١٢ يوم", days(112))
        assertEquals("٥ رحلات", rides(5))
        assertEquals("طلبين", requests(2))
    }

    @Test
    fun `a band reads in logical order with no isolates`() {
        assertEquals("بين ٢٫٥٠ و١٢٫٠٠ د.أ", "بين ${fig(2.5)} و${dinars(12.0)}")
    }
}
