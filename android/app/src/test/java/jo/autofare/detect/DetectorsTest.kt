package jo.autofare.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detectors, run against screens a real driver really saw.
 *
 * Every fixture under `test/resources/captures` is a verbatim copy of one block
 * from the capture that came back on 2026-10-27 — same bytes, including the two
 * things that cannot be typed by hand and that both break naive parsing: the
 * invisible left-to-right mark in front of `JOD 1.50`, and the damma in `يبعُد`.
 * Transcribing these by eye would have meant testing against a tidied-up screen
 * that no phone ever displayed.
 */
class DetectorsTest {

    private fun screen(name: String, pkg: String): CaptureTree {
        val dump = javaClass.classLoader!!
            .getResourceAsStream("captures/$name.txt")!!
            .bufferedReader()
            .readText()
        return CaptureTree.of(pkg, dump)
    }

    private fun jeeny(name: String) = screen(name, JeenyDetector.pkg)
    private fun petra(name: String) = screen(name, PetraDetector.pkg)

    /* ───────────────────────────── Petra Ride ───────────────────────────── */

    @Test
    fun `petra offer is read from view ids`() {
        val found = PetraDetector.detect(petra("petra-offer-basic"))
        assertNotNull(found)
        val offer = found!!.offer

        assertEquals(1.58, offer.fare!!, 0.001)
        // `1.13 دقيقة • 210 متر` — one string, two units, and the distance in metres
        assertEquals(2, offer.etaMin)  // 1.13 min, rounded up
        assertEquals(0.210, offer.km!!, 0.001)
        assertEquals("Abu Ghosh, Amman, Jordan", offer.dropoff)
    }

    @Test
    fun `petra accept press lands on the accept button`() {
        val screen = petra("petra-offer-basic")
        val found = PetraDetector.detect(screen)!!

        assertEquals("com.PetraRide_Captain:id/accept_button", found.accept.viewId)
        assertTrue(found.accept.click())
        assertEquals(listOf("com.PetraRide_Captain:id/accept_button"), screen.presses)
    }

    @Test
    fun `petra bonus ride has no price and is still a usable offer`() {
        /*
         * The slot where the fare belongs reads `مكافأة مسافات بعيدة` instead. An
         * offer reported with a fare of zero here would be rejected by any floor
         * the driver set, which on this app means refusing the bonus work.
         */
        val found = PetraDetector.detect(petra("petra-offer-bonus-no-price"))
        assertNotNull(found)
        val offer = found!!.offer

        assertNull(offer.fare)
        assertEquals(11, offer.etaMin)  // 10.22 min, rounded up
        assertEquals(5.75, offer.km!!, 0.001)
        assertTrue(offer.usable)
        assertNull("no fare means no rate", offer.perMinute)
    }

    @Test
    fun `petra unfolded screen reads the same as folded`() {
        // Same ids, a window twice as wide, no reject button in the tree at all.
        val found = PetraDetector.detect(petra("petra-offer-unfolded"))!!
        assertEquals(1.95, found.offer.fare!!, 0.001)
        assertEquals(5, found.offer.etaMin)  // 4.37 min, rounded up
        assertEquals(1.34, found.offer.km!!, 0.001)
    }

    @Test
    fun `petra negative bounds are irrelevant`() {
        // bounds=-418,2092,357,2222 — the right edge is left of the left edge.
        val found = PetraDetector.detect(petra("petra-offer-negative-bounds"))!!
        assertEquals(1.74, found.offer.fare!!, 0.001)
        assertEquals(3, found.offer.etaMin)  // 2.93 min, rounded up
        assertEquals(1.41, found.offer.km!!, 0.001)
    }

    @Test
    fun `petra idle home screen is not an offer`() {
        // `أنا متاح · قيد البحث عن الطلبات` — available, waiting, nothing to accept.
        assertNull(PetraDetector.detect(petra("petra-idle-home")))
    }

    /* ─────────────────────────────── Jeeny ─────────────────────────────── */

    @Test
    fun `jeeny offer is read from text`() {
        val found = JeenyDetector.detect(jeeny("jeeny-offer-basic"))
        assertNotNull(found)
        val offer = found!!.offer

        assertEquals(1.50, offer.fare!!, 0.001)
        assertEquals(4, offer.etaMin)
        assertEquals(1.8, offer.km!!, 0.001)
        assertEquals("إيكونومي", offer.carClass)
        assertEquals("Tariq - sameer abu eqab street, 1", offer.pickup)
        assertEquals("Raba Street 6", offer.dropoff)
    }

    @Test
    fun `jeeny accept press climbs off the text to the clickable wrapper`() {
        /*
         * `قبول العرض` is a TextView that is not clickable. Pressing it does
         * nothing, and does nothing silently — indistinguishable from a detector
         * that never fired.
         */
        val screen = jeeny("jeeny-offer-basic")
        val found = JeenyDetector.detect(screen)!!

        assertTrue("must press a clickable node", found.accept.clickable)
        assertNull("the text node itself is not the target", found.accept.text)
        assertTrue(found.accept.click())
        assertEquals(1, screen.presses.size)
    }

    @Test
    fun `jeeny eta and distance are told apart by unit not by order`() {
        // Both labels begin `يبعُد`: `يبعُد 6 دقائق` and `يبعُد ٢٫٧كم`.
        val offer = JeenyDetector.detect(jeeny("jeeny-offer-arabic-digits"))!!.offer
        assertEquals(6, offer.etaMin)
        assertEquals(2.7, offer.km!!, 0.001)
        assertEquals(1.50, offer.fare!!, 0.001)
    }

    @Test
    fun `jeeny short distance in bare metres is not read as kilometres`() {
        // `يبعُد 963م`. Read as km this is 963 kilometres away and auto-rejected.
        val offer = JeenyDetector.detect(jeeny("jeeny-offer-metres"))!!.offer
        assertEquals(0.963, offer.km!!, 0.001)
        assertEquals(2, offer.etaMin)
        assertEquals(3.43, offer.fare!!, 0.001)
    }

    @Test
    fun `jeeny surge badge is read and does not become the fare`() {
        // `‎JOD 3.58` beside a `1.1x` badge and `٧٧٩م` of distance.
        val offer = JeenyDetector.detect(jeeny("jeeny-offer-surge"))!!.offer
        assertEquals(3.58, offer.fare!!, 0.001)
        assertEquals(3, offer.etaMin)
        assertEquals(0.779, offer.km!!, 0.001)
        assertEquals("1.1x", offer.surge)
    }

    @Test
    fun `jeeny folded screen with invalid bounds reads normally`() {
        val offer = JeenyDetector.detect(jeeny("jeeny-offer-folded"))!!.offer
        assertEquals(2.03, offer.fare!!, 0.001)
        assertEquals(1, offer.etaMin)
        assertEquals(0.401, offer.km!!, 0.001)
    }

    @Test
    fun `jeeny post-trip rating dialog is not an offer`() {
        /*
         * This screen is why the detector does not share OfferHints' word list.
         * The learning-mode safety net flags it, correctly — it says `تخطي` — and
         * flagging it costs one saved file. Treating it as an offer would cost a
         * press on `قدم تقييم`.
         */
        assertNull(JeenyDetector.detect(jeeny("jeeny-rating-dialog")))
        assertTrue(OfferHints.looksLikeOffer("تخطي"))
    }

    /* ──────────────────────────── cross-app ──────────────────────────── */

    @Test
    fun `detectors are registered under the packages the capture confirmed`() {
        assertEquals(setOf("me.com.easytaxista", "com.PetraRide_Captain"), DETECTORS.keys)
    }

    @Test
    fun `the same offer fingerprints the same across repaints`() {
        /*
         * Petra repainted this offer identically as focus moved to the status bar.
         * The two frames differ only in their bounds, which nothing reads.
         */
        val a = PetraDetector.detect(petra("petra-offer-negative-bounds"))!!.offer
        val b = PetraDetector.detect(petra("petra-offer-basic"))!!.offer
        assertEquals(a.fingerprint, a.copy().fingerprint)
        assertTrue("different rides must not collide", a.fingerprint != b.fingerprint)
    }
}
