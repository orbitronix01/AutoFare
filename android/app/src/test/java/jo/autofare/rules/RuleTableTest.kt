package jo.autofare.rules

import jo.autofare.detect.Numbers
import jo.autofare.detect.Offer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole rule table, edge by edge.
 *
 * [RulesTest] shows the engine doing sensible things with real fares. This file
 * is the spec: every mode, every limit, and the value exactly on it, a hair
 * under it and a hair over it. Run it and the test names read as the list of
 * promises the app makes to a driver.
 *
 * ETAs are given as the text the ride app prints — `5.72 دقيقة` — and go through
 * [Numbers] exactly as the detectors send them, so the rounding being tested is
 * the rounding that happens on a phone, not a copy of it.
 */
class RuleTableTest {

    /** An offer as the detector would build it from the app's own ETA string. */
    private fun offer(fare: Double?, etaText: String?) = Offer(
        pkg = "com.PetraRide_Captain",
        fare = fare,
        etaMin = etaText?.let { Numbers.minutes(it) },
        etaExact = etaText?.let { Numbers.minutesExact(it) },
    )

    private fun min(m: String) = "$m دقيقة"

    private val armed = RuleSet(enabled = true, mode = Mode.EVERYTHING, maxEtaMin = 6)

    private fun accepts(rules: RuleSet, fare: Double?, eta: String?) =
        assertEquals(Decision.Accept, decide(offer(fare, eta), rules))

    private fun skips(reason: Reason, rules: RuleSet, fare: Double?, eta: String?) =
        assertEquals(Decision.Skip(reason), decide(offer(fare, eta), rules))

    /* ─────────────────────── the ETA limit, in every mode ─────────────────────── */

    @Test
    fun `eta - the parser rounds a fractional eta up to the next whole minute`() {
        assertEquals(6, Numbers.minutes(min("5.72")))
        assertEquals(7, Numbers.minutes(min("6.2")))
        assertEquals(7, Numbers.minutes(min("6.01")))
        assertEquals(6, Numbers.minutes(min("6")))
        assertEquals(6, Numbers.minutes(min("6.00")))
        assertEquals(1, Numbers.minutes(min("0.4")))
    }

    @Test
    fun `eta - exactly at the max is accepted (6 with max 6)`() =
        accepts(armed, 3.0, min("6"))

    @Test
    fun `eta - one minute over the max is skipped as too far (7 with max 6)`() =
        skips(Reason.TOO_FAR, armed, 3.0, min("7"))

    @Test
    fun `eta - one minute under the max is accepted (5 with max 6)`() =
        accepts(armed, 3.0, min("5"))

    @Test
    fun `eta - 5_72 rounds up to 6 and is accepted with max 6`() =
        accepts(armed, 3.0, min("5.72"))

    @Test
    fun `eta - 6_2 rounds up to 7 and is skipped with max 6`() =
        skips(Reason.TOO_FAR, armed, 3.0, min("6.2"))

    @Test
    fun `eta - 6_01 is already too far with max 6`() =
        skips(Reason.TOO_FAR, armed, 3.0, min("6.01"))

    @Test
    fun `eta - Arabic-Indic digits are judged the same as Western ones`() {
        // ٥٫٧٢ دقيقة and ٦٫٢ دقيقة
        accepts(armed, 3.0, "٥٫٧٢ دقيقة")
        skips(Reason.TOO_FAR, armed, 3.0, "٦٫٢ دقيقة")
    }

    @Test
    fun `eta - the limit applies in every fare mode`() {
        Mode.entries.forEach { mode ->
            val rules = armed.copy(mode = mode, floor = 0.0, ceiling = 100.0, rate = 0.0)
            assertEquals(mode.name, Decision.Accept, decide(offer(5.0, min("6")), rules))
            assertEquals(mode.name, Decision.Skip(Reason.TOO_FAR), decide(offer(5.0, min("6.2")), rules))
        }
    }

    @Test
    fun `eta - the limit is checked before the fare, so a far rich ride says too far`() {
        val floor = armed.copy(mode = Mode.FLOOR, floor = 2.50)
        skips(Reason.TOO_FAR, floor, 0.10, min("9"))
    }

    @Test
    fun `eta - a max of 1 takes only a 1-minute pickup`() {
        val tight = armed.copy(maxEtaMin = 1)
        accepts(tight, 3.0, min("0.5"))
        accepts(tight, 3.0, min("1"))
        skips(Reason.TOO_FAR, tight, 3.0, min("1.1"))
    }

    @Test
    fun `eta - a pickup quoted in hours is summed into minutes`() {
        val wide = armed.copy(maxEtaMin = 60)
        accepts(wide, 3.0, "1 ساعة")
        skips(Reason.TOO_FAR, wide, 3.0, "1 ساعة 1 دقيقة")
    }

    /* ─────────────────────────────── floor mode ─────────────────────────────── */

    private val floor = armed.copy(mode = Mode.FLOOR, floor = 2.50)

    @Test
    fun `floor - a fare exactly at the floor is accepted (2_50 with floor 2_50)`() =
        accepts(floor, 2.50, min("3"))

    @Test
    fun `floor - one fils under the floor is skipped as too cheap (2_499)`() =
        skips(Reason.TOO_CHEAP, floor, 2.499, min("3"))

    @Test
    fun `floor - one fils over the floor is accepted (2_501)`() =
        accepts(floor, 2.501, min("3"))

    @Test
    fun `floor - there is no ceiling in floor mode`() =
        accepts(floor, 500.0, min("3"))

    /* ─────────────────────────────── band mode ─────────────────────────────── */

    private val band = armed.copy(mode = Mode.BAND, floor = 2.00, ceiling = 8.00)

    @Test
    fun `band - exactly at the floor is accepted (2_00)`() = accepts(band, 2.00, min("3"))

    @Test
    fun `band - just under the floor is skipped as too cheap (1_999)`() =
        skips(Reason.TOO_CHEAP, band, 1.999, min("3"))

    @Test
    fun `band - just over the floor is accepted (2_001)`() = accepts(band, 2.001, min("3"))

    @Test
    fun `band - exactly at the ceiling is accepted (8_00)`() = accepts(band, 8.00, min("3"))

    @Test
    fun `band - just under the ceiling is accepted (7_999)`() = accepts(band, 7.999, min("3"))

    @Test
    fun `band - just over the ceiling is skipped as too expensive (8_001)`() =
        skips(Reason.TOO_EXPENSIVE, band, 8.001, min("3"))

    @Test
    fun `band - floor equal to ceiling takes that one fare only`() {
        val exact = band.copy(floor = 4.0, ceiling = 4.0)
        accepts(exact, 4.0, min("3"))
        skips(Reason.TOO_CHEAP, exact, 3.999, min("3"))
        skips(Reason.TOO_EXPENSIVE, exact, 4.001, min("3"))
    }

    /* ─────────────────────────── take-everything mode ─────────────────────────── */

    @Test
    fun `everything - any fare is accepted, even under the floor`() {
        val all = armed.copy(mode = Mode.EVERYTHING, floor = 50.0, ceiling = 1.0)
        accepts(all, 0.10, min("3"))
        accepts(all, 99.0, min("3"))
    }

    /* ─────────────────────────────── per-minute mode ─────────────────────────────── */

    private val perMin = armed.copy(mode = Mode.PER_MINUTE, rate = 0.50, floor = 1.00)

    @Test
    fun `per minute - exactly at the rate is accepted (2_50 over 5 min at 0_50)`() =
        accepts(perMin, 2.50, min("5"))

    @Test
    fun `per minute - just under the rate is skipped (2_49 over 5 min at 0_50)`() =
        skips(Reason.LOW_PER_MINUTE, perMin, 2.49, min("5"))

    @Test
    fun `per minute - just over the rate is accepted (2_51 over 5 min at 0_50)`() =
        accepts(perMin, 2.51, min("5"))

    @Test
    fun `per minute - divides by the exact eta, not the rounded one`() {
        /*
         * 2.80 over `5.5 دقيقة` is 0.509 a minute and clears 0.50. Divided by the
         * rounded-up 6 it would be 0.467 and be refused — so this only passes if
         * the engine reads Offer.etaExact.
         */
        val o = offer(2.80, min("5.5"))
        assertEquals(6, o.etaMin)
        assertEquals(5.5, o.etaExact!!, 1e-9)
        assertEquals(2.80 / 5.5, o.perMinute!!, 1e-9)
        assertEquals(Decision.Accept, decide(o, perMin))
    }

    @Test
    fun `per minute - falls back to the whole-minute eta when no exact one exists`() {
        val o = Offer(pkg = "me.com.easytaxista", fare = 2.80, etaMin = 6, etaExact = null)
        assertEquals(Decision.Skip(Reason.LOW_PER_MINUTE), decide(o, perMin))
    }

    @Test
    fun `per minute - the floor is checked before the rate`() =
        skips(Reason.TOO_CHEAP, perMin, 0.99, min("0.5"))

    @Test
    fun `per minute - exactly at the floor with a good rate is accepted`() =
        accepts(perMin, 1.00, min("1"))

    @Test
    fun `per minute - the eta limit still applies to a great rate`() =
        skips(Reason.TOO_FAR, perMin, 50.0, min("6.2"))

    @Test
    fun `per minute - a zero eta has no rate and is skipped, not divided by zero`() {
        // Numbers.perMinute refuses a zero divisor; a null rate reads as 0 per minute
        skips(Reason.LOW_PER_MINUTE, perMin, 5.0, min("0"))
    }

    /* ───────────────────────────── missing figures ───────────────────────────── */

    @Test
    fun `no price - skipped when acceptNoPrice is off, in every mode`() {
        Mode.entries.forEach { mode ->
            val rules = armed.copy(mode = mode, acceptNoPrice = false)
            assertEquals(mode.name, Decision.Skip(Reason.NO_PRICE), decide(offer(null, min("3")), rules))
        }
    }

    @Test
    fun `no price - accepted when acceptNoPrice is on, in every mode`() {
        Mode.entries.forEach { mode ->
            val rules = armed.copy(mode = mode, acceptNoPrice = true, floor = 50.0, rate = 9.0)
            assertEquals(mode.name, Decision.Accept, decide(offer(null, min("3")), rules))
        }
    }

    @Test
    fun `no price - acceptNoPrice still obeys the eta limit, at and over the max`() {
        val willing = armed.copy(acceptNoPrice = true)
        accepts(willing, null, min("6"))
        skips(Reason.TOO_FAR, willing, null, min("6.2"))
    }

    @Test
    fun `no eta - skipped in every mode, even with a great fare`() {
        Mode.entries.forEach { mode ->
            val rules = armed.copy(mode = mode, floor = 0.0, rate = 0.0, acceptNoPrice = true)
            assertEquals(mode.name, Decision.Skip(Reason.NO_ETA), decide(offer(99.0, null), rules))
        }
    }

    @Test
    fun `no eta and no price - skipped as no eta`() =
        skips(Reason.NO_ETA, armed.copy(acceptNoPrice = true), null, null)

    /* ─────────────────────────────── disabled ─────────────────────────────── */

    @Test
    fun `disabled - nothing is accepted in any mode, whatever the offer`() {
        Mode.entries.forEach { mode ->
            val off = RuleSet(enabled = false, mode = mode, floor = 0.0, rate = 0.0, acceptNoPrice = true)
            assertEquals(mode.name, Decision.Skip(Reason.OFF), decide(offer(99.0, min("1")), off))
            assertEquals(mode.name, Decision.Skip(Reason.OFF), decide(offer(null, null), off))
        }
    }

    @Test
    fun `disabled - is the first check, ahead of a missing eta`() =
        skips(Reason.OFF, RuleSet(enabled = false), 3.0, null)
}
