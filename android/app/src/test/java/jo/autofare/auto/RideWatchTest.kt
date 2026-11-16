package jo.autofare.auto

import jo.autofare.auto.RideWatch.Verdict
import jo.autofare.detect.CaptureTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines lifted from the 2026-11-14 live captures, trimmed to what the watch reads. */
class RideWatchTest {

    private var clock = 0L
    private val watch = RideWatch(now = { clock })

    private val petraTrip = CaptureTree.of(
        RideWatch.PETRA,
        """
        android.widget.Button id=com.PetraRide_Captain:id/trip_flow_button text="الوصول" CLICKABLE bounds=46,2124,858,2254
        """.trimIndent(),
    )
    private fun petraFlow(label: String) = CaptureTree.of(
        RideWatch.PETRA,
        "android.widget.Button id=com.PetraRide_Captain:id/trip_flow_button text=\"$label\" CLICKABLE bounds=46,2124,858,2254",
    )
    private val petraHome = CaptureTree.of(
        RideWatch.PETRA,
        "android.widget.TextView text=\"قيد البحث عن الطلبات\" bounds=300,2100,700,2160",
    )
    private val jeenyHome = CaptureTree.of(
        RideWatch.JEENY,
        "android.widget.TextView text=\"أنت متاح الآن\" desc=\"home_lbl_status\" bounds=148,2105,767,2191",
    )

    @Test
    fun `petra back to searching before the trip started is a cancel`() {
        watch.follow(RideWatch.PETRA, at = 1, fare = 3.1, etaMin = 1)
        assertEquals(Verdict.NONE, watch.onScreen(petraTrip))
        clock += 60_000
        assertEquals(Verdict.CANCELLED, watch.onScreen(petraHome))
        assertNull(watch.ride)
    }

    @Test
    fun `petra trip that moves past arrival is kept, and the driver stays busy until it ends`() {
        watch.follow(RideWatch.PETRA, at = 1, fare = 3.1, etaMin = 1)
        assertEquals(Verdict.NONE, watch.onScreen(petraTrip))
        assertEquals(Verdict.STARTED, watch.onScreen(petraFlow("بدء الرحلة")))
        assertTrue("on the trip: no other ride may be taken", watch.busy)
        assertEquals(Verdict.NONE, watch.onScreen(petraFlow("إنهاء الرحلة")))
        assertTrue(watch.busy)
        // back to searching after it started: finished, never a cancel
        clock += 60_000
        assertEquals(Verdict.FINISHED, watch.onScreen(petraHome))
        assertFalse(watch.busy)
    }

    @Test
    fun `the driver is released after the safety window even if the end is never seen`() {
        watch.follow(RideWatch.JEENY, at = 1, fare = 6.63, etaMin = 2)
        clock += RideWatch.FOLLOW_MS - 1
        assertFalse(watch.expireIfOld())
        assertTrue(watch.busy)
        clock += 2
        assertTrue(watch.expireIfOld())
        assertFalse(watch.busy)
    }

    @Test
    fun `jeeny available again within pickup time is a cancel`() {
        watch.follow(RideWatch.JEENY, at = 1, fare = 6.63, etaMin = 1)
        clock += 90_000
        assertEquals(Verdict.CANCELLED, watch.onScreen(jeenyHome))
    }

    @Test
    fun `jeeny available long after the accept is a finished ride`() {
        watch.follow(RideWatch.JEENY, at = 1, fare = 6.63, etaMin = 2)
        clock += 20 * 60_000
        assertEquals(Verdict.FINISHED, watch.onScreen(jeenyHome))
    }

    @Test
    fun `petra back to searching long after the accept is kept, not cancelled`() {
        // a "started" screen missed in between must not erase a ride he drove
        watch.follow(RideWatch.PETRA, at = 1, fare = 3.1, etaMin = 2)
        clock += 30 * 60_000
        assertEquals(Verdict.FINISHED, watch.onScreen(petraHome))
    }

    @Test
    fun `jeeny not-available is not read as available`() {
        val offline = CaptureTree.of(
            RideWatch.JEENY,
            "android.widget.TextView text=\"أنت غير متاح الآن\" desc=\"home_lbl_status\" bounds=148,2105,767,2191",
        )
        watch.follow(RideWatch.JEENY, at = 1, fare = 6.63, etaMin = 1)
        clock += 30_000
        assertEquals(Verdict.NONE, watch.onScreen(offline))
    }

    @Test
    fun `another app's screen is ignored`() {
        watch.follow(RideWatch.PETRA, at = 1, fare = 3.1, etaMin = 1)
        assertEquals(Verdict.NONE, watch.onScreen(jeenyHome))
    }
}
