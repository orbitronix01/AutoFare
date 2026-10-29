package jo.autofare.auto

import jo.autofare.detect.CaptureTree
import jo.autofare.detect.JeenyDetector
import jo.autofare.detect.PetraDetector
import jo.autofare.rules.Decision
import jo.autofare.rules.Mode
import jo.autofare.rules.Reason
import jo.autofare.rules.RuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accept path end to end, on real captured screens.
 *
 * The behaviour under test that matters most is the refusal to act twice. An
 * offer repaints several times while it is up, and the capture shows the last
 * frame before one vanished still holding a live-looking Accept button with the
 * Reject gone from the tree. Pressing that is taking a ride somebody else
 * already has.
 */
class AutoAcceptTest {

    private var clock = 1_000L

    private fun screen(name: String, pkg: String): CaptureTree {
        val dump = javaClass.classLoader!!
            .getResourceAsStream("captures/$name.txt")!!
            .bufferedReader()
            .readText()
        return CaptureTree.of(pkg, dump)
    }

    /** Takes anything close by, so the tests exercise the engine and not the rules. */
    private val greedy = RuleSet(enabled = true, mode = Mode.EVERYTHING, maxEtaMin = 30)

    private fun engine(
        rules: RuleSet = greedy,
        log: MutableList<AutoAccept.Outcome> = mutableListOf(),
    ) = AutoAccept(rulesFor = { rules }, now = { clock }, log = { log += it })

    @Test
    fun `an offer that passes the rules is pressed`() {
        val log = mutableListOf<AutoAccept.Outcome>()
        val auto = engine(log = log)
        val screen = screen("petra-offer-basic", PetraDetector.pkg)

        val outcome = auto.onScreen(screen)

        assertNotNull(outcome)
        assertEquals(Decision.Accept, outcome!!.decision)
        assertTrue(outcome.pressed)
        assertEquals(listOf("com.PetraRide_Captain:id/accept_button"), screen.presses)
        assertEquals(1, log.size)
    }

    @Test
    fun `the same offer repainting is not pressed twice`() {
        val auto = engine()

        val first = screen("petra-offer-negative-bounds", PetraDetector.pkg)
        assertTrue(auto.onScreen(first)!!.pressed)

        /*
         * The identical ride, one repaint later: same fare, same ETA, same
         * destination, different bounds. Nothing reads bounds, so this is the same
         * offer and must be left alone.
         */
        clock += 400
        val repaint = screen("petra-offer-negative-bounds", PetraDetector.pkg)
        assertNull(auto.onScreen(repaint))
        assertTrue(repaint.presses.isEmpty())
    }

    @Test
    fun `a second ride arriving straight after an accept is left alone`() {
        val auto = engine()
        assertTrue(auto.onScreen(screen("petra-offer-basic", PetraDetector.pkg))!!.pressed)

        // A different ride, two seconds later. The driver is already on one.
        clock += 2_000
        val next = screen("petra-offer-unfolded", PetraDetector.pkg)
        assertNull(auto.onScreen(next))
        assertTrue(next.presses.isEmpty())
    }

    @Test
    fun `a different ride once the cooldown has passed is pressed`() {
        val auto = engine()
        assertTrue(auto.onScreen(screen("petra-offer-basic", PetraDetector.pkg))!!.pressed)

        clock += 60_000
        val next = screen("petra-offer-unfolded", PetraDetector.pkg)
        assertTrue(auto.onScreen(next)!!.pressed)
    }

    @Test
    fun `the same route offered again much later is a fresh ride`() {
        val auto = engine()
        assertTrue(auto.onScreen(screen("jeeny-offer-basic", JeenyDetector.pkg))!!.pressed)

        clock += 10 * 60_000
        val again = screen("jeeny-offer-basic", JeenyDetector.pkg)
        assertTrue(auto.onScreen(again)!!.pressed)
    }

    @Test
    fun `a rejected offer is never pressed`() {
        val log = mutableListOf<AutoAccept.Outcome>()
        // 1.58 د.أ one minute out is 1.58 a minute; demand 3.00 and it fails.
        val fussy = greedy.copy(mode = Mode.PER_MINUTE, rate = 3.00, floor = 0.0)
        val auto = engine(rules = fussy, log = log)

        val screen = screen("petra-offer-basic", PetraDetector.pkg)
        val outcome = auto.onScreen(screen)!!

        assertEquals(Decision.Skip(Reason.LOW_PER_MINUTE), outcome.decision)
        assertFalse(outcome.pressed)
        assertTrue("a skip must not touch the screen", screen.presses.isEmpty())
        assertEquals(1, log.size)
    }

    @Test
    fun `disarmed rules press nothing and still report why`() {
        val auto = engine(rules = greedy.copy(enabled = false))
        val screen = screen("petra-offer-basic", PetraDetector.pkg)

        val outcome = auto.onScreen(screen)!!

        assertEquals(Decision.Skip(Reason.OFF), outcome.decision)
        assertTrue(screen.presses.isEmpty())
    }

    @Test
    fun `a screen that is not an offer costs nothing and logs nothing`() {
        val log = mutableListOf<AutoAccept.Outcome>()
        val auto = engine(log = log)

        assertNull(auto.onScreen(screen("petra-idle-home", PetraDetector.pkg)))
        assertNull(auto.onScreen(screen("jeeny-rating-dialog", JeenyDetector.pkg)))
        assertTrue(log.isEmpty())
    }

    @Test
    fun `an unknown package is ignored outright`() {
        val auto = engine()
        // Same tree, claimed by a package we ship no detector for.
        assertNull(auto.onScreen(screen("petra-offer-basic", "com.ubercab.driver")))
    }

    @Test
    fun `changing the rules clears what was already decided`() {
        val auto = engine()
        assertTrue(auto.onScreen(screen("petra-offer-basic", PetraDetector.pkg))!!.pressed)

        auto.reset()
        val again = screen("petra-offer-basic", PetraDetector.pkg)
        assertTrue(auto.onScreen(again)!!.pressed)
    }

    @Test
    fun `jeeny offers go through the same path`() {
        val screen = screen("jeeny-offer-surge", JeenyDetector.pkg)
        val outcome = engine().onScreen(screen)!!

        assertTrue(outcome.pressed)
        assertEquals(1, screen.presses.size)
        assertEquals(3.58, outcome.offer.fare!!, 0.001)
    }
}
