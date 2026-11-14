package jo.autofare.detect

/**
 * Reading an offer popup, one ride app at a time.
 *
 * Both detectors here are written against a capture from a real driver's shift on
 * 2026-10-27 — a Samsung Z Fold 4 in Arabic locale, 116 offer frames across two
 * apps — and not against anybody's idea of what a ride app looks like. Where a
 * comment below states a fact about the screen, that fact came out of that file.
 *
 * The two apps could not be less alike, and the split matters:
 *
 *  - **Petra Ride** exposes proper Android view ids, so its detector is four index
 *    lookups and no tree walk. It is the fast one, and it is the one to trust.
 *  - **Jeeny** renders its offer as a flat pile of `android.view.View` nodes with no
 *    ids whatsoever, so there is nothing to look up and its detector has to read
 *    the words. That makes it the fragile half: the day Jeeny rewords a label, it
 *    stops working, which is why the remote-config plan exists.
 */
interface OfferDetector {

    /** The package this detector understands. */
    val pkg: String

    /** The offer on screen, or null when this screen is not offering a ride. */
    fun detect(screen: Screen): Detected?
}

/** Every detector we ship, by package. */
val DETECTORS: Map<String, OfferDetector> by lazy {
    listOf(PetraDetector, JeenyDetector).associateBy { it.pkg }
}

/* ───────────────────────────── Petra Ride ───────────────────────────── */

/**
 * `سفير بترا رايد` — the driver-side app. Native Android views with real ids,
 * stable across folded and unfolded (the unfolded frame is twice as wide and
 * reports the identical ids), so no geometry is involved anywhere.
 */
object PetraDetector : OfferDetector {

    private const val PKG = "com.PetraRide_Captain"

    override val pkg = PKG

    private const val ACCEPT = "$PKG:id/accept_button"
    private const val REJECT = "$PKG:id/reject_button"
    private const val PRICE = "$PKG:id/estimated_trip_price"
    private const val PICKUP_ETA = "$PKG:id/estimated_time_to_pick_up"
    private const val DROP_OFF = "$PKG:id/drop_off_location_title_label"

    /*
     * The layout Petra shipped in November 2026 (5.25.x): the pickup and the
     * destination became a list of stop rows, each a `stop_point_item_root` with a
     * `point_label` naming it and, on the pickup row only, a `point_subtitle`
     * carrying the same `2.98 دقيقة • 708 متر` string the old ETA node did. The
     * old ids are simply gone from the offer, so both layouts are read.
     */
    private const val STOP_SUBTITLE = "$PKG:id/point_subtitle"

    override fun detect(screen: Screen): Detected? {
        val accept = screen.byId(ACCEPT).firstOrNull()?.pressTarget() ?: return null

        /*
         * One string carries both figures: `2.93 دقيقة • 1.41 كم`, and on a short
         * hop the distance switches unit rather than going fractional — `1.13 دقيقة
         * • 210 متر`. Each figure is parsed by its own unit, so the order of the two
         * and the separator between them are both free to change.
         */
        /*
         * Every lookup here is a round trip into Petra's process, so only what the
         * decision needs is read: the button, the price, the ETA. The current
         * layout is tried first. Only the pickup row carries a subtitle, and it
         * comes first in the tree, so the first subtitle with minutes in it is the
         * pickup ETA — no walk over the rows.
         */
        val subtitle = screen.byId(STOP_SUBTITLE).firstOrNull { Numbers.minutes(it.text ?: "") != null }?.text
        val etaText = subtitle ?: screen.byId(PICKUP_ETA).firstOrNull()?.text
        val offer = Offer(
            pkg = pkg,
            // Absent on bonus rides, where this slot reads `مكافأة مسافات بعيدة`.
            fare = screen.byId(PRICE).firstOrNull()?.text?.let { Numbers.moneyStrict(it) },
            etaMin = etaText?.let { Numbers.minutes(it) },
            etaExact = etaText?.let { Numbers.minutesExact(it) },
            km = etaText?.let { Numbers.km(it) },
            // the old layout's one-lookup destination; the new one has no id for it
            dropoff = if (subtitle == null) screen.byId(DROP_OFF).firstOrNull()?.text else null,
        )
        if (!offer.usable) return null

        return Detected(offer, accept) { screen.byId(REJECT).firstOrNull()?.pressTarget() }
    }
}

/* ─────────────────────────────── Jeeny ─────────────────────────────── */

/**
 * Jeeny's driver app, which still ships under the package of the Easy Taxi
 * codebase it grew out of: `me.com.easytaxista`. The offer is a bottom sheet of
 * id-less `android.view.View` nodes, so everything below is text matching.
 *
 * Three traps, all of them live in the capture:
 *
 *  - `قبول العرض` is a **TextView that is not clickable**. The press has to go to
 *    the `View` wrapping it, which is what [Node.pressTarget] is for.
 *  - The ETA and the distance **start with the same word**: `يبعُد 4 دقائق` and
 *    `يبعُد 1.8كم`. They are told apart by their unit and never by position.
 *  - The same app renders the same offer with Western or Arabic-Indic digits
 *    unpredictably — `يبعُد 2.7كم` and `يبعُد ٢٫٧كم` were two seconds apart — so
 *    every figure goes through [Numbers] rather than `toDouble`.
 */
object JeenyDetector : OfferDetector {

    override val pkg = "me.com.easytaxista"

    /** Shortest form with no diacritics in it, so a literal platform search finds it. */
    private const val ACCEPT_WORD = "قبول"
    private const val REJECT_DESC = "Reject Button"

    private val CAR_CLASSES = listOf(
        "إيكونومي", "إيكو لايت", "Jeeny Comfort", "Comfort", "Economy",
    )

    /*
     * Jeeny 24.x tags every part of the offer with a fixed content description —
     * `ride_offer_lbl_accept`, `ride_offer_lbl_fare_amount` and the rest. They are
     * test tags rather than wording, so they survive a translation and are what is
     * read first.
     */
    private const val TAG_ACCEPT_LABEL = "ride_offer_lbl_accept"
    private const val TAG_ACCEPT_BUTTON = "ride_offer_btn_accept"
    private const val TAG_FARE = "ride_offer_lbl_fare_amount"
    private const val TAG_ETA = "ride_offer_lbl_eta"
    private const val TAG_DISTANCE = "ride_offer_lbl_distance"
    private const val TAG_PICKUP = "ride_offer_lbl_pickup"
    private const val TAG_DROPOFF = "ride_offer_lbl_dropoff"
    private const val TAG_SERVICE = "ride_offer_lbl_service_name"

    override fun detect(screen: Screen): Detected? {
        /*
         * One walk, not a platform text search. Jeeny is Jetpack Compose, and on a
         * real phone `findAccessibilityNodeInfosByText` finds nothing in it — which
         * is why the simulator (plain TextViews) accepted and the real app never
         * did. The walk reads the same nodes the capture shows.
         */
        val nodes = screen.walk().take(MAX_WALK).toList()
        tagged(nodes)?.let { return it }

        val hits = nodes.filter { Arabic.has(it.label, ACCEPT_WORD) }
        if (hits.isEmpty()) return null

        // `قبول العرض` is the offer sheet's button; a bare `قبول` elsewhere is not.
        val acceptNode = hits.firstOrNull { Arabic.has(it.label, "قبول العرض") }
            ?: hits.first()
        val accept = acceptNode.pressTarget() ?: return null

        /*
         * The figures have no ids, so they have to be read out of the surrounding
         * nodes. Scanning from the button upwards keeps that to the sheet itself
         * instead of the whole window — the map underneath contributes a handful of
         * `محدّد الخريطة` markers and nothing worth parsing.
         */
        val sheet = sheetAround(acceptNode)
        val labels = labelsUnder(sheet)

        /*
         * The addresses are found by the app's own markers rather than by position.
         * Jeeny prints an icon node and then the address it belongs to — `desc="Pick
         * Up Icon"` followed by `Tariq - sameer abu eqab street, 1` — so the marker
         * says which of several free-text labels is a place.
         *
         * Taking "the first text that is not a number" instead would have read the
         * pickup as `Reject Button` and the destination as `surge_upsell`, since
         * those are content descriptions sitting in the same card.
         */
        val addresses = HashMap<Int, String?>()
        val claimed = HashSet<Int>()
        labels.forEachIndexed { i, label ->
            val mark = when {
                PICKUP_MARK.containsMatchIn(label) -> 0
                DROP_MARK.containsMatchIn(label) -> 1
                else -> return@forEachIndexed
            }
            if (addresses.containsKey(mark)) return@forEachIndexed
            addresses[mark] = labels.getOrNull(i + 1)
            claimed += i
            claimed += i + 1
        }

        var fare: Double? = null
        var etaMin: Int? = null
        var etaExact: Double? = null
        var km: Double? = null
        var carClass: String? = null
        var surge: String? = null

        labels.forEachIndexed { i, label ->
            if (i in claimed) return@forEachIndexed
            when {
                // `‎JOD 1.50`, behind an invisible left-to-right mark
                fare == null && Numbers.hasCurrency(label) ->
                    fare = Numbers.moneyStrict(label)

                etaMin == null && Numbers.minutes(label) != null ->
                    etaMin = Numbers.minutes(label).also { etaExact = Numbers.minutesExact(label) }

                km == null && Numbers.km(label) != null ->
                    km = Numbers.km(label)

                carClass == null && Arabic.hasAny(label, CAR_CLASSES) ->
                    carClass = label

                // the surge badge sits beside the fare as its own node: `1.1x`
                surge == null && SURGE.matches(label.trim()) ->
                    surge = label.trim()
            }
        }

        val offer = Offer(
            pkg = pkg,
            fare = fare,
            etaMin = etaMin,
            etaExact = etaExact,
            km = km,
            pickup = addresses[0],
            dropoff = addresses[1],
            carClass = carClass,
            surge = surge,
        )
        if (!offer.usable) return null

        return Detected(offer, accept) {
            nodes.firstOrNull { Arabic.has(it.label, REJECT_DESC) }?.pressTarget()
        }
    }

    /** The 24.x offer, read by its tags. Null when this screen does not carry them. */
    private fun tagged(nodes: List<Node>): Detected? {
        val byTag = HashMap<String, Node>()
        for (n in nodes) {
            val d = n.desc ?: continue
            if (d.startsWith("ride_offer_")) byTag.putIfAbsent(d, n)
        }
        val accept = (byTag[TAG_ACCEPT_LABEL] ?: byTag[TAG_ACCEPT_BUTTON])?.pressTarget() ?: return null

        val fareText = byTag[TAG_FARE]?.text
        val etaText = byTag[TAG_ETA]?.text
        val distText = byTag[TAG_DISTANCE]?.text
        val offer = Offer(
            pkg = pkg,
            fare = fareText?.let { Numbers.moneyStrict(it) },
            etaMin = etaText?.let { Numbers.minutes(it) },
            etaExact = etaText?.let { Numbers.minutesExact(it) },
            km = distText?.let { Numbers.km(it) },
            pickup = byTag[TAG_PICKUP]?.text,
            dropoff = byTag[TAG_DROPOFF]?.text,
            carClass = byTag[TAG_SERVICE]?.text,
        )
        if (!offer.usable) return null
        return Detected(offer, accept) { byTag["ride_offer_btn_reject"]?.pressTarget() }
    }

    private const val MAX_WALK = 400

    private val SURGE = Regex("""\d+(\.\d+)?\s*x""", RegexOption.IGNORE_CASE)

    /** `desc="Pick Up Icon"` and `desc="Destination  Icon"` — note the double space. */
    private val PICKUP_MARK = Regex("""pick\s*up""", RegexOption.IGNORE_CASE)
    private val DROP_MARK = Regex("""destination""", RegexOption.IGNORE_CASE)

    /**
     * The offer card, found by climbing out of the accept button.
     *
     * The lowest ancestor whose subtree holds a price or an ETA is the card: stop
     * any lower and the fare is out of reach, climb any higher and the map comes
     * with it. A fixed number of levels would be a guess about a layout that is
     * free to change between releases.
     */
    private fun sheetAround(accept: Node): Node {
        var node = accept
        var best = accept
        var steps = 0
        while (steps < SHEET_CLIMB) {
            val parent = node.parent ?: break
            node = parent
            best = parent
            if (labelsUnder(parent).any { Numbers.hasCurrency(it) || Numbers.minutes(it) != null }) {
                return parent
            }
            steps++
        }
        return best
    }

    /** Every thing-that-says-something under [root], parents before children. */
    private fun labelsUnder(root: Node, limit: Int = MAX_SCAN): List<String> {
        val out = ArrayList<String>(16)
        fun visit(node: Node, depth: Int) {
            if (out.size >= limit || depth > MAX_DEPTH) return
            node.label?.let { out += it }
            for (child in node.children) visit(child, depth + 1)
        }
        visit(root, 0)
        return out
    }

    private const val SHEET_CLIMB = 8
    private const val MAX_DEPTH = 24
    private const val MAX_SCAN = 120
}
