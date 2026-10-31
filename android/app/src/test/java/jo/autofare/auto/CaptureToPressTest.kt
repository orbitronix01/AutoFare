package jo.autofare.auto

import jo.autofare.detect.CaptureTree
import jo.autofare.detect.JeenyDetector
import jo.autofare.detect.Node
import jo.autofare.detect.PetraDetector
import jo.autofare.detect.Screen
import jo.autofare.rules.Decision
import jo.autofare.rules.Mode
import jo.autofare.rules.Reason
import jo.autofare.rules.RuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * From a captured screen to a press, for every capture and a spread of rules.
 *
 * Each cell of the table below runs the real chain — detector, rules, and
 * [AutoAccept]'s decision — on a verbatim screen from a driver's shift, through a
 * fresh engine so no cell can lean on another's memory. What is asserted is the
 * thing the driver cares about: **which node got pressed**, by identity, or that
 * nothing was.
 *
 * The expected column is written by hand from the figures on each screen, not
 * computed by calling `decide()`, so a change in the engine shows up here as a
 * changed answer rather than being agreed with.
 */
class CaptureToPressTest {

    /* ──────────────────────────────── rule sets ──────────────────────────────── */

    private val rules = mapOf(
        "greedy" to RuleSet(enabled = true, mode = Mode.EVERYTHING, maxEtaMin = 30),
        "eta<=3" to RuleSet(enabled = true, mode = Mode.EVERYTHING, maxEtaMin = 3),
        "floor 2.00" to RuleSet(enabled = true, mode = Mode.FLOOR, floor = 2.00, maxEtaMin = 6),
        "band 1.60-3.50" to RuleSet(enabled = true, mode = Mode.BAND, floor = 1.60, ceiling = 3.50, maxEtaMin = 6),
        "0.60/min" to RuleSet(enabled = true, mode = Mode.PER_MINUTE, rate = 0.60, floor = 1.00, maxEtaMin = 6),
        "bonus ok" to RuleSet(enabled = true, mode = Mode.FLOOR, floor = 2.00, maxEtaMin = 12, acceptNoPrice = true),
        "off" to RuleSet(enabled = false, mode = Mode.EVERYTHING, maxEtaMin = 30),
    )

    private val columns = rules.keys.toList()

    /*
     * One row per capture: fare and ETA as printed, then the expected outcome per
     * column in the order of [rules]. `A` is accept (and a press), anything else is
     * the reason it was skipped, `-` means "not an offer, nothing happens".
     */
    private val table = """
        petra-offer-basic           1.58   1.13min    A        A        CHEAP    CHEAP    A        CHEAP    OFF
        petra-offer-negative-bounds 1.74   2.93min    A        A        CHEAP    A        PERMIN   CHEAP    OFF
        petra-offer-unfolded        1.95   4.37min    A        FAR      CHEAP    A        PERMIN   CHEAP    OFF
        petra-offer-bonus-no-price  none   10.22min   NOPRICE  FAR      FAR      FAR      FAR      A        OFF
        petra-idle-home             -      -          -        -        -        -        -        -        -
        jeeny-offer-basic           1.50   4min       A        FAR      CHEAP    CHEAP    PERMIN   CHEAP    OFF
        jeeny-offer-arabic-digits   1.50   6min       A        FAR      CHEAP    CHEAP    PERMIN   CHEAP    OFF
        jeeny-offer-folded          2.03   1min       A        A        A        A        A        A        OFF
        jeeny-offer-metres          3.43   2min       A        A        A        A        A        A        OFF
        jeeny-offer-surge           3.58   3min       A        A        A        DEAR     A        A        OFF
        jeeny-rating-dialog         -      -          -        -        -        -        -        -        -
    """.trimIndent()

    private val codes = mapOf(
        "CHEAP" to Reason.TOO_CHEAP,
        "DEAR" to Reason.TOO_EXPENSIVE,
        "FAR" to Reason.TOO_FAR,
        "PERMIN" to Reason.LOW_PER_MINUTE,
        "NOPRICE" to Reason.NO_PRICE,
        "OFF" to Reason.OFF,
    )

    @Test
    fun `every capture under every rule set presses exactly the accept target or nothing`() {
        var cells = 0
        table.lines().filter { it.isNotBlank() }.forEach { line ->
            val cols = line.trim().split(Regex("\\s+"))
            val fixture = cols[0]
            val expected = cols.drop(3)
            assertEquals("row $fixture", columns.size, expected.size)

            columns.forEachIndexed { i, ruleName ->
                check(fixture, ruleName, rules.getValue(ruleName), expected[i])
                cells++
            }
        }
        assertEquals(11 * 7, cells)
    }

    @Test
    fun `an unpaid driver gets nothing pressed on any offer capture`() {
        offerFixtures().forEach { fixture ->
            val spy = PressSpy(load(fixture))
            val outcome = engine(rules.getValue("greedy"), paid = false).onScreen(spy)
            assertEquals(fixture, Decision.Skip(Reason.UNPAID), outcome?.decision)
            assertEquals(fixture, emptyList<Node>(), spy.pressed)
        }
    }

    @Test
    fun `petra press lands on the accept_button view itself`() {
        val screen = load("petra-offer-basic")
        val spy = PressSpy(screen)
        engine(rules.getValue("greedy")).onScreen(spy)

        val button = screen.byId("com.PetraRide_Captain:id/accept_button").single()
        assertSame(button, spy.pressed.single())
        assertEquals("android.widget.Button", button.className)
    }

    @Test
    fun `jeeny press lands on the clickable wrapper, never on the قبول العرض text`() {
        val screen = load("jeeny-offer-basic")
        val spy = PressSpy(screen)
        engine(rules.getValue("greedy")).onScreen(spy)

        val text = screen.byText("قبول العرض").single()
        val pressed = spy.pressed.single()
        assertTrue("the text itself is not clickable", !text.clickable)
        assertSame(text.parent, pressed)
        assertTrue(pressed.clickable)
        assertEquals("android.view.View", pressed.className)
    }

    /* ─────────────────────────────── machinery ─────────────────────────────── */

    private fun check(fixture: String, ruleName: String, rule: RuleSet, expected: String) {
        val screen = load(fixture)
        val spy = PressSpy(screen)
        val outcome = engine(rule).onScreen(spy)
        val where = "$fixture × $ruleName"

        when (expected) {
            "-" -> {
                assertNull("$where: not an offer, so no outcome", outcome)
                assertEquals("$where: nothing pressed", emptyList<Node>(), spy.pressed)
            }

            "A" -> {
                if (outcome == null) fail("$where: expected a press, the screen was not read as an offer")
                assertEquals(where, Decision.Accept, outcome!!.decision)
                assertTrue(where, outcome.pressed)
                assertSame("$where: pressed the accept target", acceptTarget(screen), spy.pressed.single())
            }

            else -> {
                val reason = codes[expected] ?: error("unknown code $expected")
                assertEquals(where, Decision.Skip(reason), outcome?.decision)
                assertEquals("$where: nothing pressed", emptyList<Node>(), spy.pressed)
            }
        }
    }

    /** What a human would point at: Petra's button by id, Jeeny's wrapper around the words. */
    private fun acceptTarget(screen: CaptureTree): Node = when (screen.pkg) {
        PetraDetector.pkg -> screen.byId("com.PetraRide_Captain:id/accept_button").single()
        else -> screen.byText("قبول العرض").single().parent!!
    }

    private fun engine(rule: RuleSet, paid: Boolean = true) = AutoAccept(
        rulesFor = { rule },
        now = { 1_000L },
        paid = { paid },
    )

    private fun offerFixtures() =
        table.lines().map { it.trim().substringBefore(' ') }.filter { "offer" in it }

    private fun load(name: String): CaptureTree {
        val dump = javaClass.classLoader!!
            .getResourceAsStream("captures/$name.txt")!!
            .bufferedReader()
            .readText()
        val pkg = if (name.startsWith("petra")) PetraDetector.pkg else JeenyDetector.pkg
        return CaptureTree.of(pkg, dump)
    }

    /**
     * Passes a screen through untouched, except that it remembers which node —
     * by identity — received the press. [CaptureTree.presses] only records a
     * label, and Jeeny's wrapper has none.
     */
    private class PressSpy(private val inner: Screen) : Screen {
        val pressed = mutableListOf<Node>()

        override val pkg: String get() = inner.pkg
        override fun byId(viewId: String) = inner.byId(viewId).map { Spied(it) }
        override fun byText(text: String) = inner.byText(text).map { Spied(it) }
        override fun walk() = inner.walk().map { Spied(it) }

        private inner class Spied(val node: Node) : Node by node {
            override val parent: Node? get() = node.parent?.let { Spied(it) }
            override val children: List<Node> get() = node.children.map { Spied(it) }

            // the interface's own climb, so the parents it reaches are spied too
            override fun pressTarget(maxClimb: Int): Node? = super<Node>.pressTarget(maxClimb)
            override fun click(): Boolean {
                pressed += node
                return node.click()
            }
        }
    }
}
