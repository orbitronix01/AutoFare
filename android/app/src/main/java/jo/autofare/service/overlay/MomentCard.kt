package jo.autofare.service.overlay

import jo.autofare.detect.Offer
import jo.autofare.rules.Mode
import jo.autofare.rules.RuleSet
import jo.autofare.ui.minutes
import jo.autofare.ui.arFixed
import jo.autofare.ui.arNum

/**
 * What the accept-moment card says, worked out from the accepted offer.
 *
 * Kept apart from the drawing so the arithmetic — the line a driver reads to
 * decide whether he trusts the app with his rides — is tested on the JVM. Every
 * figure comes from the [Offer] that was pressed and the [RuleSet] it was judged
 * by; nothing is re-read from the screen and nothing is estimated.
 */
data class MomentCard(
    /** `٥٫٢٠`, or null for a ride the app sent with no price. */
    val fare: String?,
    /** App name, and the car class when the offer printed one. */
    val source: String,
    /** `تل الرمان ← الصويفية`, when the offer named both ends. */
    val route: String?,
    /** `الزبون على بعد ٤ دقائق · ١٫٢ كم` */
    val reach: String?,
    /** The rule, applied to this offer, one line per step. */
    val rows: List<Row>,
    /** How long detect-judge-press took, as the stamp shows it. */
    val took: String,
    /** One line on why it was an easy yes. */
    val why: String?,
) {
    data class Row(val label: String, val value: String, val key: Boolean = false)
}

fun momentCard(offer: Offer, rules: RuleSet?, appName: String, tookMs: Long): MomentCard {
    val fare = offer.fare
    val eta = offer.etaMin

    val route = when {
        offer.pickup != null && offer.dropoff != null -> "${offer.pickup} ← ${offer.dropoff}"
        else -> offer.pickup ?: offer.dropoff
    }
    val reach = listOfNotNull(
        eta?.let { "الزبون على بعد ${minutes(it)}" },
        offer.km?.let { "${km(it)} كم" },
    ).joinToString(" · ").ifEmpty { null }

    val rows = ArrayList<MomentCard.Row>()
    var why: String? = null

    if (rules != null) {
        if (fare == null) {
            // only reachable with "accept with no price" on: say that is why
            rows += MomentCard.Row("الأجرة", "—")
            eta?.let { rows += MomentCard.Row("وقت الوصول", minutes(it), key = true) }
            why = "ما عليه أجرة، وقبلناه لأن الزبون قريب وإنت سامح بطلبات المكافأة."
        } else when (rules.mode) {
            Mode.PER_MINUTE -> {
                val per = offer.perMinute
                if (eta != null) rows += MomentCard.Row("الأجرة ÷ دقائق الوصول", "${jod(fare)} ÷ ${arNum(eta)}")
                if (per != null) rows += MomentCard.Row("الربح بالدقيقة", jod(per), key = true)
                rows += MomentCard.Row("حدّك", jod(rules.rate))
                why = per?.let { above(it, rules.rate) }
            }
            Mode.FLOOR -> {
                rows += MomentCard.Row("الأجرة", jod(fare), key = true)
                rows += MomentCard.Row("أقل أجرة بتقبلها", jod(rules.floor))
                why = above(fare, rules.floor)
            }
            Mode.BAND -> {
                rows += MomentCard.Row("الأجرة", jod(fare), key = true)
                rows += MomentCard.Row("الحدّين اللي حطّيتهم", "${jod(rules.floor)} – ${jod(rules.ceiling)}")
                why = "الأجرة بين الحدّين اللي حطّيتهم."
            }
            Mode.EVERYTHING -> {
                // units spelled out, never "٦ د": the counted word, as everywhere else
                eta?.let { rows += MomentCard.Row("وقت الوصول", minutes(it), key = true) }
                rows += MomentCard.Row("أقصى وقت بتقبله", minutes(rules.maxEtaMin))
                why = "بتقبل أي طلب قريب، وهاد قريب."
            }
        }
    }

    return MomentCard(
        fare = fare?.let { jod(it) },
        source = listOfNotNull(appName, offer.carClass).joinToString(" · "),
        route = route,
        reach = reach,
        rows = rows,
        took = seconds(tookMs),
        why = why,
    )
}

/**
 * The design's line, but only when it is true.
 *
 * "٢٫٢ ضعف" is a boast, and a boast on a ride that barely cleared the floor
 * would teach him the number means nothing. Below one and a half times the
 * limit it just says the ride met his rule.
 */
private fun above(value: Double, limit: Double): String {
    if (limit <= 0.0) return "بيطابق قواعدك."
    val ratio = value / limit
    return if (ratio >= 1.5) {
        "قد حدّك ${arFixed(ratio, 1)} مرّة، ما بدها تفكير."
    } else {
        "فوق حدّك، وبيطابق قواعدك."
    }
}

/** Dinars to two places, as the rest of the app prints them: Arabic-Indic, `٥٫٢٠`. */
internal fun jod(v: Double): String = arFixed(v)

private fun km(v: Double): String = arFixed(v, 1)

/**
 * The press time, in seconds with two places.
 *
 * Seconds rather than milliseconds because the design states it that way and a
 * driver thinks in seconds; two places because the real figure is 40–120 ms and
 * one place would round most of them to a meaningless "٠٫١". A fraction of a
 * second takes the singular noun in Arabic ("٠٫٠٨ ثانية"), so [counted] does not
 * apply; the unit is spelled out, never "ث".
 */
private fun seconds(ms: Long): String =
    "${arFixed(ms.coerceAtLeast(0) / 1000.0)} ثانية"
