package jo.autofare.auto

import jo.autofare.detect.DETECTORS
import jo.autofare.detect.Offer
import jo.autofare.detect.Screen
import jo.autofare.rules.Decision
import jo.autofare.rules.Reason
import jo.autofare.rules.RuleSet
import jo.autofare.rules.decide

/**
 * Detect, judge, press — the whole accept path, with nothing Android-specific in
 * it so that all of it can be tested.
 *
 * ## This class exists to be fast
 *
 * The driver is not racing the passenger, he is racing every other driver holding
 * the same popup. Whoever lands on Accept first gets the ride and everyone else
 * gets the next frame of an offer that no longer exists. So the budget here is a
 * few milliseconds, and the shape of the code follows from that:
 *
 *  - no disk, ever — the rules arrive through [rulesFor] from a value already held
 *    in memory, because reading SharedPreferences on this path would put a file
 *    read between the offer appearing and the button being pressed
 *  - no tree walk for an app that has view ids (see `PetraDetector`)
 *  - the package check comes first and costs one map lookup, so the thousands of
 *    events per shift that are not a ride app die immediately
 *  - the press happens the moment the decision is known, and everything a human
 *    might want to see about it — the log, the notification, the overlay — is
 *    worked out after the finger is already down
 *
 * ## Why it refuses to decide twice
 *
 * An offer repaints while it is on screen, so the same ride arrives here many
 * times over. Worse, the capture shows the final frame before an offer vanishes
 * still holding an Accept button with no Reject beside it. Pressing that is
 * accepting a ride another driver already took. [seen] is what makes the decision
 * happen once, on the first frame that is complete enough to judge.
 */
class AutoAccept(
    private val rulesFor: (String) -> RuleSet,
    private val now: () -> Long,
    private val log: (Outcome) -> Unit = {},
) {

    /** What happened on one offer. */
    data class Outcome(
        val offer: Offer,
        val decision: Decision,
        /** True when the button was actually pressed and the press was accepted. */
        val pressed: Boolean,
        /** Milliseconds from entering [onScreen] to the press returning. */
        val tookMs: Long,
    )

    private val seen = HashMap<String, Long>()

    /**
     * Null until something has actually been accepted.
     *
     * Not zero. [now] is `elapsedRealtime`, which counts from boot, so a zero here
     * reads as "accepted a ride at boot" and the cooldown then swallows every
     * offer in the first eight seconds of uptime — exactly the window a driver who
     * restarts his phone mid-shift is in.
     */
    private var lastAcceptAt: Long? = null

    /**
     * Run the accept path against one window.
     *
     * Returns null when this screen is not an offer, or is an offer already dealt
     * with — both of which are the overwhelmingly common case and both of which
     * must cost almost nothing.
     */
    fun onScreen(screen: Screen): Outcome? {
        val detector = DETECTORS[screen.pkg] ?: return null

        val started = now()
        val found = detector.detect(screen) ?: return null
        val offer = found.offer

        val fp = offer.fingerprint
        val at = now()
        if (seen[fp]?.let { at - it < REMEMBER_MS } == true) return null

        /*
         * A driver can be on one ride at a time. Accepting, then accepting again
         * two seconds later because the app repainted or queued a second offer,
         * hands him a ride he cannot serve and a cancellation he has to explain.
         */
        lastAcceptAt?.let { if (at - it < ACCEPT_COOLDOWN_MS) return null }

        seen[fp] = at
        if (seen.size > REMEMBER_MAX) prune(at)

        val decision = decide(offer, rulesFor(screen.pkg))

        var pressed = false
        if (decision is Decision.Accept) {
            pressed = found.accept.click()
            if (pressed) lastAcceptAt = now()
        }

        /*
         * An Accept we could not press is not an Accept. Saying otherwise in the
         * log would have the driver believe the app took a ride that is still
         * sitting on his screen going to somebody else.
         */
        val reported =
            if (decision is Decision.Accept && !pressed) Decision.Skip(Reason.OFF) else decision

        val outcome = Outcome(offer, reported, pressed, now() - started)
        log(outcome)
        return outcome
    }

    /** Forget everything — called when the driver changes his rules or toggles the service. */
    fun reset() {
        seen.clear()
        lastAcceptAt = null
    }

    private fun prune(at: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (at - iterator.next().value >= REMEMBER_MS) iterator.remove()
        }
        // still oversized means a flood of distinct offers; drop the lot rather than grow
        if (seen.size > REMEMBER_MAX) seen.clear()
    }

    private companion object {
        /**
         * How long one offer stays decided.
         *
         * Long enough to cover an offer's whole life on screen and the repaints
         * after it, short enough that the same route offered again later in the
         * shift is a fresh ride and not a ghost.
         */
        const val REMEMBER_MS = 90_000L

        /** One ride at a time. */
        const val ACCEPT_COOLDOWN_MS = 8_000L

        const val REMEMBER_MAX = 256
    }
}
