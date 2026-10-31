package jo.autofare.detect

/**
 * A ride being offered to the driver, as read off the screen.
 *
 * Every figure is nullable because every figure really can be missing. Petra Ride
 * sends bonus rides with no price node at all — the slot where the fare normally
 * sits reads `مكافأة مسافات بعيدة` instead — and a detector that reported those as
 * `0.00 د.أ` would have the rule engine reject the best-paying work on the app.
 * Missing means unknown here, and unknown is a decision the driver gets to make
 * once, in settings, rather than one we quietly make for him on every offer.
 */
data class Offer(
    val pkg: String,
    /** Dinars. Null when the app showed no price. */
    val fare: Double? = null,
    /** Minutes to reach the passenger. Null when the app showed no ETA. */
    val etaMin: Int? = null,
    /** Kilometres to reach the passenger. */
    val km: Double? = null,
    val pickup: String? = null,
    val dropoff: String? = null,
    /** `إيكونومي`, `إيكو لايت`, `Jeeny Comfort`, `$$ Economy +`. */
    val carClass: String? = null,
    /** Jeeny's surge badge, e.g. `1.1x`. Informational — the fare already includes it. */
    val surge: String? = null,
    /** [etaMin] before rounding up, when the screen gave a fraction. */
    val etaExact: Double? = null,
) {

    /** What this ride pays for a minute of the driver's time, the whole point of the app. */
    val perMinute: Double?
        get() {
            val f = fare ?: return null
            val m = etaExact ?: etaMin?.toDouble() ?: return null
            return Numbers.perMinute(f, m)
        }

    /**
     * Identity, for "have we already decided about this one".
     *
     * An offer repaints several times while it is on screen, and the last frame
     * before it vanishes can still be holding a live-looking Accept button. Without
     * an identity we would evaluate that corpse and press it, accepting a ride
     * another driver has already taken. The route and the fare together are what
     * make two frames the same offer; nothing from the frame itself is used,
     * because the point is to recognise it across frames.
     */
    val fingerprint: String
        get() = listOf(pkg, fare?.toString(), etaMin?.toString(), pickup, dropoff)
            .joinToString("|") { it ?: "-" }

    /** True when there is enough here to judge the ride at all. */
    val usable: Boolean get() = fare != null || etaMin != null
}

/** An [Offer] together with the buttons to act on it. */
class Detected(
    val offer: Offer,
    val accept: Node,
    val reject: Node?,
)
