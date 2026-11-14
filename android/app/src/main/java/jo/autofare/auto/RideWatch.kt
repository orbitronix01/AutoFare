package jo.autofare.auto

import jo.autofare.detect.Arabic
import jo.autofare.detect.Screen

/**
 * Follows the one ride AutoFare last accepted, to notice when it is cancelled.
 *
 * An accepted ride is counted as money made the moment it is pressed. If the
 * driver or the passenger then cancels, that money never arrives, and the home
 * screen would be lying. This watches the ride app after a press and says
 * [Verdict.CANCELLED] when the app is back to "waiting for orders" before the
 * trip ever started.
 *
 * Never on the accept path: the service calls it after [AutoAccept] is done with
 * an event, throttled, and only while a ride is being followed.
 *
 * What each app shows, from live captures on 2026-11-14:
 *  - **Petra** puts the trip on screen with `trip_flow_button` reading `الوصول`
 *    (arrived) until the driver reaches the passenger. Any other label on that
 *    button means the trip moved on. Back on the home screen it reads
 *    `قيد البحث عن الطلبات` (searching for orders).
 *  - **Jeeny**'s screens after an accept have not been captured yet. Its home
 *    screen says `أنت متاح الآن` under `home_lbl_status`. Until the trip screens
 *    are known, being available again within the pickup ETA plus a minute is
 *    what counts as a cancel.
 */
class RideWatch(private val now: () -> Long) {

    enum class Verdict { NONE, STARTED, CANCELLED, EXPIRED }

    /** The ride being followed. [at] is the wall-clock key it was stored under. */
    data class Ride(val pkg: String, val at: Long, val fare: Double?, val etaMin: Int?, val since: Long)

    var ride: Ride? = null
        private set

    private var started = false

    fun follow(pkg: String, at: Long, fare: Double?, etaMin: Int?) {
        ride = Ride(pkg, at, fare, etaMin, now())
        started = false
    }

    /** Stop following once the ride is old enough to have happened, app on screen or not. */
    fun expireIfOld() {
        val r = ride ?: return
        if (now() - r.since > FOLLOW_MS) stop()
    }

    fun stop() {
        ride = null
        started = false
    }

    /** Look at one screen of the ride's app. Anything but [Verdict.NONE] ends the watch. */
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
        val flow = screen.byId(PETRA_FLOW).firstOrNull()?.text?.trim()
        // exactly "arrived" means still heading to the passenger; any other label is the trip moving on
        if (!flow.isNullOrEmpty() && flow != PETRA_ARRIVED) {
            started = true
            return end(Verdict.STARTED)
        }
        if (screen.byText(PETRA_SEARCHING).isNotEmpty()) {
            /*
             * Back to searching without ever being seen to start. Within the
             * window that is a cancel. Past it, a missed "started" screen is the
             * likelier story, and wrongly erasing a ride he drove is worse than
             * wrongly keeping one he cancelled.
             */
            val window = ((r.etaMin ?: 0) * 60_000L) + PETRA_GRACE_MS
            return end(if (age <= window) Verdict.CANCELLED else Verdict.STARTED)
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
        return end(if (age <= window) Verdict.CANCELLED else Verdict.STARTED)
    }

    private fun end(v: Verdict): Verdict {
        ride = null
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

        /** Long enough for any pickup; after this the ride is assumed to have happened. */
        private const val FOLLOW_MS = 45 * 60_000L
    }
}
