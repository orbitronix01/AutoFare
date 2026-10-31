package jo.autofare.service.overlay

import jo.autofare.detect.Offer
import jo.autofare.rules.Mode
import jo.autofare.rules.RuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card's arithmetic, against the design's own example: ٥٫٢٠ ÷ ٤ = ١٫٣٠ against ٠٫٦٠. */
class MomentCardTest {

    private val offer = Offer(
        pkg = "com.PetraRide_Captain",
        fare = 5.2,
        etaMin = 4,
        km = 1.24,
        pickup = "تل الرمان",
        dropoff = "الصويفية",
        carClass = "إيكونومي",
    )

    @Test
    fun `per-minute rules show the division, the result and the limit`() {
        val card = momentCard(offer, RuleSet(enabled = true, mode = Mode.PER_MINUTE, rate = 0.60), "Petra Ride", 84)

        assertEquals("٥٫٢٠", card.fare)
        assertEquals("Petra Ride · إيكونومي", card.source)
        assertEquals("تل الرمان ← الصويفية", card.route)
        assertEquals("الزبون على بعد ٤ دقائق · ١٫٢ كم", card.reach)
        assertEquals(
            listOf(
                MomentCard.Row("الأجرة ÷ دقائق الوصول", "٥٫٢٠ ÷ ٤"),
                MomentCard.Row("الربح بالدقيقة", "١٫٣٠", key = true),
                MomentCard.Row("حدّك", "٠٫٦٠"),
            ),
            card.rows,
        )
        assertEquals("قد حدّك ٢٫٢ مرّة، ما بدها تفكير.", card.why)
        assertEquals("٠٫٠٨ ثانية", card.took)
    }

    @Test
    fun `a ride that barely clears the floor does not boast`() {
        val card = momentCard(offer.copy(fare = 2.6), RuleSet(enabled = true, mode = Mode.FLOOR, floor = 2.5), "Jeeny", 50)
        assertEquals("فوق حدّك، وبيطابق قواعدك.", card.why)
        assertEquals(MomentCard.Row("الأجرة", "٢٫٦٠", key = true), card.rows.first())
    }

    @Test
    fun `a ride with no price says why it was taken anyway`() {
        val card = momentCard(
            offer.copy(fare = null),
            RuleSet(enabled = true, acceptNoPrice = true),
            "Petra Ride",
            70,
        )
        assertNull(card.fare)
        assertEquals(MomentCard.Row("الأجرة", "—"), card.rows.first())
        assertTrue(card.why!!.startsWith("ما عليه أجرة"))
    }

    @Test
    fun `missing ends of the route are left out, not invented`() {
        val card = momentCard(offer.copy(dropoff = null, km = null, etaMin = 1), null, "Jeeny", 40)
        assertEquals("تل الرمان", card.route)
        assertEquals("الزبون على بعد دقيقة", card.reach)
        assertTrue(card.rows.isEmpty())
        assertNull(card.why)
    }

    @Test
    fun `take-anything rules spell the minutes out, never a one-letter unit`() {
        val card = momentCard(offer, RuleSet(enabled = true, mode = Mode.EVERYTHING, maxEtaMin = 6), "Jeeny", 60)
        assertEquals(
            listOf(
                MomentCard.Row("وقت الوصول", "٤ دقائق", key = true),
                MomentCard.Row("أقصى وقت بتقبله", "٦ دقائق"),
            ),
            card.rows,
        )
    }

    @Test
    fun `band rules show both limits in Arabic-Indic digits`() {
        val card = momentCard(offer, RuleSet(enabled = true, mode = Mode.BAND, floor = 2.5, ceiling = 12.0), "Jeeny", 60)
        assertEquals(MomentCard.Row("الحدّين اللي حطّيتهم", "٢٫٥٠ – ١٢٫٠٠"), card.rows[1])
    }

    @Test
    fun `no Latin digit reaches the card`() {
        val card = momentCard(offer, RuleSet(enabled = true, mode = Mode.PER_MINUTE, rate = 0.60), "Petra Ride", 84)
        val all = listOfNotNull(card.fare, card.reach, card.why, card.took) + card.rows.map { it.value }
        all.forEach { assertTrue(it, it.none { c -> c in '0'..'9' }) }
    }
}
