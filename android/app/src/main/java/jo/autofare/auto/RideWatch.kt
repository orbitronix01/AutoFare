package jo.autofare.auto

import jo.autofare.detect.Arabic
import jo.autofare.detect.Screen

/**
 * Follows the one ride AutoFare last accepted, from the press until the driver
 * is free again.
 *
 * Two jobs:
 *  - **One ride at a time.** While a ride is followed the driver is [busy], and
 *    AutoFare accepts nothing from any app. A driver once got a Petra ride and
 *    then a Jeeny ride on top of it; a ride is now over only when its app is back
 *    to waiting for orders (or after [FOLLOW_MS], as a safety net).
 *  - **Earnings.** A ride that ends before it started is [Verdict.CANCELLED] and
 *    comes off the money made; one that ran is [Verdict.FINISHED].
 *
 * Never on the accept path: the service calls it after [AutoAccept] is done with
 * an event, and on a timer while a ride is followed.
 *
 * What each app shows, from live captures on 2026-11-14:
 *  - **Petra** puts the trip on screen with `trip_flow_button` reading `الوصول`
 *    (arrived) until the driver reaches the passenger. Any other label on that
 *    button means the trip moved on. Back on the home screen it reads
 *    `قيد البحث عن الطلبات` (searching for orders): the driver is free.
 *  - **Jeeny**'s trip screens have not been captured. Its home screen says
 *    `أنت متاح الآن` under `home_lbl_status`: the driver is free. Free again
 *    within the pickup ETA plus a minute means the ride was cancelled.
 */
class RideWatch(private val now: () -> Long) {

    enum class Verdict { NONE, STARTED, CANCELLED, FINISHED, EXPIRED }

    /** The ride being followed. [at] is the wall-clock key it was stored under. */
    data class Ride(val pkg: String, val at: Long, val fare: Double?, val etaMin: Int?, val since: Long)

    var ride: Ride? = null
        private set

    /** True from a press until that ride's app is waiting for orders again. */
    val busy: Boolean get() = ride != null

    private var started = false

    fun follow(pkg: String, at: Long, fare: Double?, etaMin: Int?) {
        ride = Ride(pkg, at, fare, etaMin, now())
        started = false
    }

    /** Give up on a ride old enough to be over, its app on screen or not. Returns true if it did. */
    fun expireIfOld(): Boolean {
        val r = ride ?: return false
        if (now() - r.since <= FOLLOW_MS) return false
        stop()
        return true
    }

    fun stop() {
        ride = null
        started = false
    }

    /**
     * Look at one screen of the ride's app. [Verdict.STARTED] is reported once and
     * the ride stays followed; CANCELLED, FINISHED and EXPIRED end it.
     */
    fun onScreen(screen: Screen): Verdict {
        val r = ride ?: return Verdict.NONE
        if (screen.pkg != r.pkg) return Verdict.NONE
        val age = now() - r.since
        if (age > FOLLOW_MS) return end(Verdict.EXPIRED)

        return when (r.pkg) {
            PETRA -> petra(screen, r, age)
            JEENY -> jeeny(screen, r, age)
            else -> end(Verdict.EXPIRED)
        }
    }

    private fun petra(screen: Screen, r: Ride, age: Long): Verdict {
        if (screen.byText(PETRA_SEARCHING).isNotEmpty()) {
            if (started) return end(Verdict.FINISHED)
            /*
             * Back to searching without ever being seen to start. Within the
             * window that is a cancel. Past it, a missed "started" screen is the
             * likelier story, and wrongly erasing a ride he drove is worse than
             * wrongly keeping one he cancelled.
             */
            val window = ((r.etaMin ?: 0) * 60_000L) + PETRA_GRACE_MS
            return end(if (age <= window) Verdict.CANCELLED else Verdict.FINISHED)
        }
        if (!started) {
            val flow = screen.byId(PETRA_FLOW).firstOrNull()?.text?.trim()
            // exactly "arrived" means still heading to the passenger; any other label is the trip moving on
            if (!flow.isNullOrEmpty() && flow != PETRA_ARRIVED) {
                started = true
                return Verdict.STARTED
            }
        }
        return Verdict.NONE
    }

    private fun jeeny(screen: Screen, r: Ride, age: Long): Verdict {
        // Compose: the platform text search sees nothing, so walk
        val available = screen.walk().take(400).any {
            it.desc == JEENY_STATUS && Arabic.has(it.text, JEENY_AVAILABLE) && !Arabic.has(it.text, JEENY_NOT)
        }
        if (!available) return Verdict.NONE
        val window = ((r.etaMin ?: 0) * 60_000L) + JEENY_GRACE_MS
        return end(if (age <= window) Verdict.CANCELLED else Verdict.FINISHED)
    }

    private fun end(v: Verdict): Verdict {
        stop()
        return v
    }

    companion object {
        const val PETRA = "com.PetraRide_Captain"
        const val JEENY = "me.com.easytaxista"

        private const val PETRA_FLOW = "$PETRA:id/trip_flow_button"
        private const val PETRA_ARRIVED = "الوصول"
        private const val PETRA_SEARCHING = "قيد البحث عن الطلبات"

        private const val JEENY_STATUS = "home_lbl_status"
        private const val JEENY_AVAILABLE = "متاح"
        /** `غير متاح` — not available — contains the word for available. */
        private const val JEENY_NOT = "غير"

        /**
         * Pickup ETA plus this, and Jeeny being free again means the ride never
         * happened. Kept short: a quick pickup and a short hop must not read as a
         * cancel, so a slow cancel is sometimes missed instead.
         */
        private const val JEENY_GRACE_MS = 60_000L

        /** Petra sees its own trip screens, so it can afford a longer window for a cancel at pickup. */
        private const val PETRA_GRACE_MS = 15 * 60_000L

        /**
         * The safety net: after this, the driver is taken to be free even if the
         * ride's app was never seen waiting for orders again. Long enough for an
         * airport run; short enough that a missed end costs one quiet stretch,
         * not a shift.
         */
        const val FOLLOW_MS = 90 * 60_000L
    }
}
