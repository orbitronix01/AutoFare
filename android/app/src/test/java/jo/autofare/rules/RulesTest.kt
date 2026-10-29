package jo.autofare.rules

import jo.autofare.detect.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule engine, against the fares that actually came off the two apps.
 *
 * The numbers in these tests are not invented. `1.50 د.أ` four minutes away and
 * `3.43 د.أ` two minutes away are both real Jeeny offers from the 2026-10-27
 * shift, and they sit either side of a sensible rate — which is the whole reason
 * the per-minute mode exists.
 */
class RulesTest {

    private fun offer(fare: Double?, eta: Int?) =
        Offer(pkg = "me.com.easytaxista", fare = fare, etaMin = eta)

    private val armed = RuleSet(enabled = true)

    private fun reasonOf(d: Decision) = (d as Decision.Skip).reason

    @Test
    fun `disarmed accepts nothing at all`() {
        val perfect = offer(20.0, 1)
        assertEquals(Reason.OFF, reasonOf(decide(perfect, RuleSet(enabled = false))))
    }

    @Test
    fun `per minute mode takes the near ride and leaves the far one`() {
        val rules = armed.copy(mode = Mode.PER_MINUTE, rate = 0.60, floor = 1.00, maxEtaMin = 6)

        // 3.43 over 2 minutes is 1.72 a minute
        assertEquals(Decision.Accept, decide(offer(3.43, 2), rules))
        // 1.50 over 4 minutes is 0.375 a minute
        assertEquals(Reason.LOW_PER_MINUTE, reasonOf(decide(offer(1.50, 4), rules)))
    }

    @Test
    fun `the floor still applies in per minute mode`() {
        /*
         * A 0.50 fare one minute away is 0.50 a minute and clears a 0.45 rate, but
         * it is still fifty piastres. Without the floor the app would spend a shift
         * taking the cheapest work on the platform at a flattering rate.
         */
        val rules = armed.copy(mode = Mode.PER_MINUTE, rate = 0.45, floor = 2.50)
        assertEquals(Reason.TOO_CHEAP, reasonOf(decide(offer(0.50, 1), rules)))
    }

    @Test
    fun `distance to the passenger applies even in take-everything mode`() {
        val rules = armed.copy(mode = Mode.EVERYTHING, maxEtaMin = 6)
        assertEquals(Decision.Accept, decide(offer(1.00, 6), rules))
        assertEquals(Reason.TOO_FAR, reasonOf(decide(offer(50.0, 7), rules)))
    }

    @Test
    fun `floor mode compares the fare only`() {
        val rules = armed.copy(mode = Mode.FLOOR, floor = 2.50, maxEtaMin = 10)
        assertEquals(Decision.Accept, decide(offer(2.50, 9), rules))
        assertEquals(Reason.TOO_CHEAP, reasonOf(decide(offer(2.49, 1), rules)))
    }

    @Test
    fun `band mode refuses the long ride as well as the cheap one`() {
        val rules = armed.copy(mode = Mode.BAND, floor = 2.0, ceiling = 8.0, maxEtaMin = 10)
        assertEquals(Decision.Accept, decide(offer(5.0, 3), rules))
        assertEquals(Reason.TOO_CHEAP, reasonOf(decide(offer(1.9, 3), rules)))
        assertEquals(Reason.TOO_EXPENSIVE, reasonOf(decide(offer(8.1, 3), rules)))
    }

    @Test
    fun `a missing fare is never treated as free`() {
        // Petra Ride's bonus rides arrive with no price node on the screen.
        val cautious = armed.copy(mode = Mode.FLOOR, floor = 2.50, acceptNoPrice = false)
        assertEquals(Reason.NO_PRICE, reasonOf(decide(offer(null, 3), cautious)))

        val willing = cautious.copy(acceptNoPrice = true)
        assertEquals(Decision.Accept, decide(offer(null, 3), willing))
    }

    @Test
    fun `a no-price ride still has to be close`() {
        val willing = armed.copy(acceptNoPrice = true, maxEtaMin = 6)
        assertEquals(Reason.TOO_FAR, reasonOf(decide(offer(null, 11), willing)))
    }

    @Test
    fun `a missing eta is never treated as next door`() {
        val rules = armed.copy(mode = Mode.EVERYTHING)
        assertEquals(Reason.NO_ETA, reasonOf(decide(offer(9.0, null), rules)))
    }

    @Test
    fun `every reason has wording the driver can read`() {
        Reason.entries.forEach { assertTrue(it.name, it.ar.isNotBlank()) }
    }
}
