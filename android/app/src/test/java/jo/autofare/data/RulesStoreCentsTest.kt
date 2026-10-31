package jo.autofare.data

import jo.autofare.detect.Offer
import jo.autofare.rules.Decision
import jo.autofare.rules.Mode
import jo.autofare.rules.RuleSet
import jo.autofare.rules.decide
import org.junit.Assert.assertEquals
import org.junit.Test

class RulesStoreCentsTest {

    @Test fun `limits come back exactly as set`() {
        for (v in listOf(0.60, 1.10, 1.20, 2.50, 12.00, 0.05)) assertEquals(v, cents(v.toFloat()), 0.0)
    }

    /** The bug the emulator found: a fare exactly at a 1.10 floor was skipped. */
    @Test fun `a fare exactly at a stored floor is accepted`() {
        val rules = RuleSet(enabled = true, mode = Mode.FLOOR, floor = cents(1.10f), maxEtaMin = 6)
        assertEquals(Decision.Accept, decide(Offer(pkg = "", fare = 1.10, etaMin = 3), rules))
    }
}
