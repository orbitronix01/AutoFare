package jo.autofare.rules

import jo.autofare.detect.Offer

/**
 * The driver's rules, and the one function that applies them.
 *
 * Pure on purpose: no Android, no clock, no storage. What the app accepts on a
 * driver's behalf is the single thing in here that must never be wrong by
 * accident, so it is testable to the last branch without a phone involved.
 */

/** How the fare is judged. Matches the four choices on the rules screen, in order. */
enum class Mode {
    /** أي طلب قريب — take anything close enough. */
    EVERYTHING,

    /** أجرة أقل شي — a floor on the fare. */
    FLOOR,

    /** أجرة بين حدّين — a floor and a ceiling, for refusing long rides too. */
    BAND,

    /** حسب الربح بالدقيقة — dinars per minute of the driver's time. */
    PER_MINUTE,
}

data class RuleSet(
    /** Auto-accept is armed for this app. Off means AutoFare watches and does nothing. */
    val enabled: Boolean = false,
    val mode: Mode = Mode.PER_MINUTE,
    /** Dinars per minute, for [Mode.PER_MINUTE]. */
    val rate: Double = 0.60,
    /** Never accept below this fare, in dinars. Applies in every mode but [Mode.EVERYTHING]. */
    val floor: Double = 2.50,
    /** Only consulted in [Mode.BAND]. */
    val ceiling: Double = 12.00,
    /** Minutes to the passenger. */
    val maxEtaMin: Int = 6,
    /**
     * Petra Ride sends bonus rides with no price on them at all. Off, those are
     * left for the driver to judge; on, they are accepted on the ETA alone.
     */
    val acceptNoPrice: Boolean = false,
)

/** What to do about an offer. */
sealed interface Decision {

    /** Press accept. */
    data object Accept : Decision

    /** Leave it alone, for this reason. */
    data class Skip(val reason: Reason) : Decision
}

/**
 * Why an offer was not taken.
 *
 * Carries its own Jordanian-Arabic wording because this is what the driver reads
 * on the intercept overlay a second after it happens, and a decision he cannot
 * read is a decision he cannot trust. Each one names the thing that failed in
 * the same words the rules screen uses to set it — "الأجرة", "الزبون بعيد",
 * "الربح بالدقيقة" — so he can find the knob that made the call.
 */
enum class Reason(val ar: String) {
    OFF("القبول التلقائي مطفي"),
    UNPAID("ما في اشتراك فعّال"),
    TOO_CHEAP("الأجرة أقل من حدّك"),
    TOO_EXPENSIVE("الأجرة أعلى من حدّك"),
    TOO_FAR("الزبون بعيد"),
    LOW_PER_MINUTE("الربح بالدقيقة قليل"),
    NO_PRICE("الطلب ما عليه أجرة"),
    NO_ETA("ما بيّن قديش الزبون بعيد"),
}

/**
 * Judge one offer.
 *
 * Order matters, and it is the cautious order: anything unknown stops the ride
 * rather than being treated as a zero. A fare the app did not print is not a free
 * ride and an ETA it did not print is not "the passenger is next to you".
 */
fun decide(offer: Offer, rules: RuleSet): Decision {
    if (!rules.enabled) return Decision.Skip(Reason.OFF)

    /*
     * Distance to the passenger is the one limit that applies in every mode,
     * including "take everything" — a ride at any price is a bad ride if the
     * driver burns twelve minutes getting to it. An offer with no ETA cannot be
     * checked against it, so it is never auto-accepted.
     */
    val eta = offer.etaMin ?: return Decision.Skip(Reason.NO_ETA)
    if (eta > rules.maxEtaMin) return Decision.Skip(Reason.TOO_FAR)

    val fare = offer.fare
    if (fare == null) {
        return if (rules.acceptNoPrice) Decision.Accept else Decision.Skip(Reason.NO_PRICE)
    }

    return when (rules.mode) {
        Mode.EVERYTHING -> Decision.Accept

        Mode.FLOOR ->
            if (fare >= rules.floor) Decision.Accept else Decision.Skip(Reason.TOO_CHEAP)

        Mode.BAND -> when {
            fare < rules.floor -> Decision.Skip(Reason.TOO_CHEAP)
            fare > rules.ceiling -> Decision.Skip(Reason.TOO_EXPENSIVE)
            else -> Decision.Accept
        }

        Mode.PER_MINUTE -> when {
            // the floor still applies: a great rate on a tiny fare is still a tiny fare
            fare < rules.floor -> Decision.Skip(Reason.TOO_CHEAP)
            (offer.perMinute ?: 0.0) < rules.rate -> Decision.Skip(Reason.LOW_PER_MINUTE)
            else -> Decision.Accept
        }
    }
}
