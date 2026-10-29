package jo.autofare.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cost of these two cases is asymmetric, which is what the tests are really
 * checking: a missed offer screen costs the driver another shift of recording, a
 * false positive costs one 2 KB file.
 */
class OfferHintsTest {

    @Test
    fun `an offer popup with an accept button is flagged`() {
        val dump = """
            android.widget.TextView text="طلب جديد"
            android.widget.TextView text="٢.٧٥٠ د.أ"
            android.widget.TextView text="٦ دقائق"
            android.widget.Button text="قبول" CLICKABLE
            android.widget.Button text="رفض" CLICKABLE
        """.trimIndent()
        assertTrue(OfferHints.looksLikeOffer(dump))
    }

    @Test
    fun `an english offer popup is flagged`() {
        assertTrue(OfferHints.looksLikeOffer("""Button text="Accept" CLICKABLE"""))
        assertTrue(OfferHints.looksLikeOffer("""Button text="DECLINE" CLICKABLE"""))
    }

    @Test
    fun `the alif spellings are all flagged`() {
        assertTrue(OfferHints.looksLikeOffer("""text="اقبل الطلب""""))
        assertTrue(OfferHints.looksLikeOffer("""text="أقبل الطلب""""))
        assertTrue(OfferHints.looksLikeOffer("""text="إقبل الطلب""""))
    }

    /**
     * The whole point of keying on accept/decline and not on money: a trip screen is
     * full of dinars and minutes, and if those counted, every second of navigation
     * would be flagged as unlosable and the real offers would be pruned away behind
     * them. This is the bug that cost the second driver session.
     */
    @Test
    fun `a trip in progress screen is not flagged`() {
        val dump = """
            android.widget.TextView text="المسافة ٣.٢ كم"
            android.widget.TextView text="الوصول بعد ٨ دقائق"
            android.widget.TextView text="الأجرة ٢.٧٥٠ د.أ"
            android.widget.TextView text="شارع الملكة رانيا"
        """.trimIndent()
        assertFalse(OfferHints.looksLikeOffer(dump))
    }

    @Test
    fun `an idle map screen is not flagged`() {
        val dump = """
            android.widget.TextView text="أنت أونلاين"
            android.widget.TextView text="أرباح اليوم ١٤.٢٥٠ د.أ"
            android.view.View CLICKABLE
        """.trimIndent()
        assertFalse(OfferHints.looksLikeOffer(dump))
    }
}
