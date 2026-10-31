package jo.autofare.service.overlay

/**
 * What one tap on the floating button does. Pure, so it is tested off the phone.
 *
 * "Auto-accept on" is not one switch in this app — it is a switch per ride app,
 * each with its own rules. The bubble is one button, so a tap has to mean
 * something sensible across all of them:
 *
 *  - **anything armed → pause everything**, and remember which apps were on.
 *    The bubble is the brake: at a red light, with a passenger he already has,
 *    he needs one tap that makes it stop, not a choice of which app to stop.
 *  - **nothing armed → bring back exactly what he paused.** Not "everything":
 *    he may have switched Petra off on purpose this morning, and a bubble tap
 *    that quietly arms it again takes rides he decided against.
 *  - **nothing armed and nothing to bring back → open AutoFare.** He has never
 *    armed an app from here, so there are no rules he has chosen. Arming an app
 *    on default rules he never looked at is not a decision the bubble gets to
 *    make for him.
 */
sealed interface BubbleTap {
    /** Arm exactly [armed] (every other app off) and store [remember] for next time. */
    data class Apply(val armed: Set<String>, val remember: Set<String>) : BubbleTap

    data object OpenApp : BubbleTap
}

/**
 * @param armedNow ids of the apps currently armed
 * @param available ids of the apps AutoFare can act on at all
 * @param paused what the last pause from the bubble switched off
 */
fun bubbleTap(armedNow: Set<String>, available: Set<String>, paused: Set<String>): BubbleTap {
    val on = armedNow intersect available
    if (on.isNotEmpty()) return BubbleTap.Apply(armed = emptySet(), remember = on)

    val back = paused intersect available
    if (back.isNotEmpty()) return BubbleTap.Apply(armed = back, remember = emptySet())

    return BubbleTap.OpenApp
}

/**
 * Keep a remembered bubble position on screen.
 *
 * The position was saved on whatever screen he had then — portrait, or the
 * other half of a foldable — and replaying it raw on a smaller one leaves the
 * bubble somewhere he cannot reach to drag it back.
 */
fun clampBubble(x: Int, y: Int, size: Int, screenW: Int, screenH: Int): Pair<Int, Int> =
    x.coerceIn(0, (screenW - size).coerceAtLeast(0)) to y.coerceIn(0, (screenH - size).coerceAtLeast(0))
