package jo.autofare.auto

import jo.autofare.detect.Offer
import jo.autofare.rules.Decision
import jo.autofare.rules.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which outcomes make a noise. The rule that matters most: the "got it" tone is
 * for a press that landed, never for an Accept the engine could not press.
 */
class CuesTest {

    private val offer = Offer(pkg = "me.com.easytaxista", fare = 2.5, etaMin = 4)

    private fun outcome(decision: Decision, pressed: Boolean) =
        AutoAccept.Outcome(offer, decision, pressed, tookMs = 60)

    @Test
    fun `a landed press plays the accept tone when sound is on`() {
        assertEquals(Cue.ACCEPTED, cueFor(outcome(Decision.Accept, true), sound = true, vibrate = true))
    }

    @Test
    fun `a landed press is silent when sound is off, and never buzzes`() {
        assertNull(cueFor(outcome(Decision.Accept, true), sound = false, vibrate = true))
    }

    @Test
    fun `a rule refusal buzzes only when vibrate is on`() {
        val skip = outcome(Decision.Skip(Reason.TOO_CHEAP), false)
        assertEquals(Cue.SKIPPED, cueFor(skip, sound = true, vibrate = true))
        assertNull(cueFor(skip, sound = true, vibrate = false))
    }

    @Test
    fun `an accept that could not be pressed is a skip, not a tone`() {
        // AutoAccept reports this as Skip(OFF); it must never sound like a win
        val failed = outcome(Decision.Skip(Reason.OFF), false)
        assertEquals(Cue.SKIPPED, cueFor(failed, sound = true, vibrate = true))
        assertNull(cueFor(failed, sound = true, vibrate = false))
    }

    @Test
    fun `a locked app never buzzes per offer`() {
        assertNull(cueFor(outcome(Decision.Skip(Reason.UNPAID), false), sound = true, vibrate = true))
    }
}
