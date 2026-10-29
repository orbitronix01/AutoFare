package jo.autofare.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The strings here are the shapes a Jordanian driver's screen actually produces.
 * Several are lifted straight from the first driver capture.
 */
class NumbersTest {

    private val d = 0.0001

    /* ───────────────────────── normalize ───────────────────────── */

    @Test
    fun `arabic indic digits become ascii`() {
        assertEquals("52", Numbers.normalize("٥٢"))
        assertEquals("0123456789", Numbers.normalize("٠١٢٣٤٥٦٧٨٩"))
    }

    @Test
    fun `persian digits become ascii`() {
        assertEquals("0123456789", Numbers.normalize("۰۱۲۳۴۵۶۷۸۹"))
    }

    /** Straight off the captured status bar: `%٥٢`. */
    @Test
    fun `battery string from the capture`() {
        assertEquals("%52", Numbers.normalize("%٥٢"))
    }

    /** And the captured clock: `١٠:٠٧ م`. */
    @Test
    fun `clock string from the capture`() {
        assertEquals("10:07 م", Numbers.normalize("١٠:٠٧ م"))
    }

    @Test
    fun `arabic separators become ascii`() {
        assertEquals("3.50", Numbers.normalize("٣٫٥٠"))
        assertEquals("1500", Numbers.normalize("١٬٥٠٠"))
        assertEquals("52%", Numbers.normalize("٥٢٪"))
    }

    @Test
    fun `bidi marks are dropped`() {
        assertEquals("5", Numbers.normalize("‏٥‎"))
        assertEquals("ZAIN JO", Numbers.normalize("‏ZAIN JO"))
        assertEquals("12", Numbers.normalize("⁦١٢⁩"))
    }

    @Test
    fun `odd spaces become plain spaces`() {
        assertEquals("3 JOD", Numbers.normalize("٣ JOD"))
    }

    @Test
    fun `plain text is untouched`() {
        assertEquals("Install", Numbers.normalize("Install"))
    }

    /* ───────────────────────── money ───────────────────────── */

    @Test
    fun `fare in arabic digits with dinar marker`() {
        assertEquals(3.50, Numbers.money("٣٫٥٠ د.أ")!!, d)
        assertEquals(12.75, Numbers.money("د.أ ١٢٫٧٥")!!, d)
        assertEquals(7.0, Numbers.money("٧ دينار")!!, d)
    }

    /** The dinar has three decimals: this is one and a half, not fifteen hundred. */
    @Test
    fun `three decimal dinar is not thousands`() {
        assertEquals(1.5, Numbers.money("1.500 JOD")!!, d)
        assertEquals(2.25, Numbers.money("٢٫٢٥٠ د.أ")!!, d)
    }

    @Test
    fun `fare quoted in fils is scaled down`() {
        assertEquals(1.2, Numbers.money("١٢٠٠ فلس")!!, d)
    }

    @Test
    fun `the number nearest the currency wins`() {
        // a real offer popup carries the fare and the ETA in one string
        assertEquals(4.25, Numbers.money("أجرة الرحلة ٤٫٢٥ د.أ · ١٢ دقيقة")!!, d)
    }

    @Test
    fun `bare number with no currency still reads`() {
        assertEquals(3.5, Numbers.money("٣٫٥٠")!!, d)
    }

    @Test
    fun `thousands separators are stripped`() {
        assertEquals(1500.75, Numbers.money("1,500.75 JOD")!!, d)
        assertEquals(1500000.0, Numbers.money("1,500,000 JOD")!!, d)
    }

    @Test
    fun `text with no number is null`() {
        assertNull(Numbers.money("ما في طلبات"))
    }

    /* ───────────────────────── minutes ───────────────────────── */

    @Test
    fun `eta in arabic minutes`() {
        assertEquals(12, Numbers.minutes("١٢ دقيقة"))
        assertEquals(5, Numbers.minutes("٥ دقائق"))
    }

    @Test
    fun `eta in english minutes`() {
        assertEquals(8, Numbers.minutes("8 min"))
        assertEquals(8, Numbers.minutes("8 minutes"))
    }

    /** Lifted from the captured charging notification, which wrote both units out. */
    @Test
    fun `hours and minutes are summed`() {
        assertEquals(75, Numbers.minutes("١ ساعة و١٥ دقيقة"))
        assertEquals(60, Numbers.minutes("١ ساعة"))
    }

    @Test
    fun `a number with no time unit is null`() {
        assertNull(Numbers.minutes("٥٢"))
        assertNull(Numbers.minutes("٣٫٥٠ د.أ"))
    }

    /* ───────────────────────── distance ───────────────────────── */

    @Test
    fun `distance in kilometres`() {
        assertEquals(3.2, Numbers.km("٣٫٢ كم")!!, d)
        assertEquals(12.0, Numbers.km("12 km")!!, d)
        assertEquals(4.5, Numbers.km("٤٫٥ كيلومتر")!!, d)
    }

    @Test
    fun `distance in metres converts`() {
        assertEquals(0.8, Numbers.km("٨٠٠ متر")!!, d)
    }

    @Test
    fun `no distance unit is null`() {
        assertNull(Numbers.km("٣٫٥٠ د.أ"))
    }

    /* ───────────────────────── the actual rule ───────────────────────── */

    @Test
    fun `profit per minute`() {
        assertEquals(0.3, Numbers.perMinute(4.5, 15)!!, d)
        assertNull(Numbers.perMinute(4.5, 0))
    }

    /** End to end: an offer line in Arabic becomes a decision. */
    @Test
    fun `an arabic offer becomes a per minute figure`() {
        val offer = "طلب جديد · ٦٫٠٠ د.أ · ١٠ دقائق · ٤٫٢ كم"
        val fare = Numbers.money(offer)!!
        val eta = Numbers.minutes(offer)!!
        assertEquals(6.0, fare, d)
        assertEquals(10, eta)
        assertEquals(0.6, Numbers.perMinute(fare, eta)!!, d)
        assertEquals(4.2, Numbers.km(offer)!!, d)
    }
}
