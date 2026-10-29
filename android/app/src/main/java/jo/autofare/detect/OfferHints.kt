package jo.autofare.detect

/**
 * "Does this screen look like it is offering the driver a ride?"
 *
 * This is deliberately NOT the detector. It is a safety net for learning mode: a
 * screen that matches here is marked as one we must not lose, so a long shift of
 * navigation screens can never prune it away.
 *
 * It keys on the action words only — accept / decline — and not on fare or time.
 * A trip-in-progress screen is full of dinars and minutes; it is the accept and
 * reject pair that belongs to an offer and nowhere else. Being wrong in the
 * cautious direction costs one file; being wrong the other way costs a shift.
 */
object OfferHints {

    /**
     * Arabic spellings vary by app and by keyboard — أ / ا / إ are typed almost
     * interchangeably — so every form a Jordanian ride app might ship is listed
     * rather than normalised, which keeps this readable in a log.
     */
    private val ACTION_WORDS = listOf(
        "قبول", "اقبل", "أقبل", "إقبل", "موافق", "موافقة", "استلام", "استلم",
        "رفض", "ارفض", "أرفض", "تجاهل", "تخطي", "تخطى",
        "accept", "decline", "reject", "ignore", "dismiss", "take trip", "new trip",
        "new request", "ride request",
    )

    fun looksLikeOffer(dump: String): Boolean {
        val lower = dump.lowercase()
        return ACTION_WORDS.any { lower.contains(it) }
    }
}
