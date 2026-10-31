package jo.autofare.auto

import jo.autofare.rules.Decision
import jo.autofare.rules.Reason

/**
 * What the driver hears or feels about one decision, worked out after the press.
 *
 * Pure, like the rest of `auto/`, so the one question that matters — does this
 * outcome make a noise — is tested without a phone. Playing the cue is
 * `service/Feedback.kt`'s job, and it only ever does so once the press is done.
 */
enum class Cue {
    /** A short tone: AutoFare took a ride for him. */
    ACCEPTED,

    /** A short buzz: an offer came in and the rules let it go. */
    SKIPPED,
}

/**
 * The cue for one outcome, or null for silence.
 *
 * Only a press that landed makes the accept sound. An Accept that could not be
 * pressed is reported as a skip by [AutoAccept], and playing the "got it" tone
 * over a ride still sitting on his screen would be the app lying out loud.
 *
 * [Reason.UNPAID] never buzzes. It is not the rules turning a ride down, it is
 * the app being unable to work at all, and the shade alert already says so —
 * once per five minutes, not once per offer. A buzz per refused offer from a
 * locked app would be the same message, louder and more often.
 */
fun cueFor(outcome: AutoAccept.Outcome, sound: Boolean, vibrate: Boolean): Cue? {
    if (outcome.pressed) return if (sound) Cue.ACCEPTED else null
    val skip = outcome.decision as? Decision.Skip ?: return null
    if (skip.reason == Reason.UNPAID) return null
    return if (vibrate) Cue.SKIPPED else null
}
