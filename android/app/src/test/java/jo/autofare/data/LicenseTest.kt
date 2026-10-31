package jo.autofare.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LicenseTest {

    private val day = 24 * 60 * 60 * 1000L
    private val serverAtOk = 1_800_000_000_000L
    private val okAt = 1_000_000L

    private fun state(elapsed: Long, expiresAt: Long = serverAtOk + 30 * day, reason: String? = null, lastOk: Long = okAt) =
        License.state(reason, lastOk, serverAtOk, expiresAt, elapsed)

    @Test fun freshAnswerIsActive() {
        assertEquals(License.State.Active(30, serverAtOk + 30 * day), state(okAt + 60_000))
    }

    @Test fun staleAnswerLocks() {
        assertEquals(License.State.Unverified, state(okAt + License.GRACE_MS + 1))
    }

    @Test fun rebootLocksUntilCheckIn() {
        // elapsedRealtime restarts from zero, so it is now behind the stored answer
        assertEquals(License.State.Unverified, state(okAt - 1))
    }

    @Test fun expiryIsMeasuredOnTheServerClock() {
        // expires one minute after the answer; two minutes later it is over, no
        // matter what the phone's own clock says
        assertEquals(License.State.Expired, state(okAt + 120_000, expiresAt = serverAtOk + 60_000))
    }

    @Test fun serverRefusalsWin() {
        assertEquals(License.State.Expired, state(okAt + 1, reason = "expired"))
        assertEquals(License.State.None, state(okAt + 1, reason = "none"))
    }

    @Test fun neverCheckedIsNone() {
        assertEquals(License.State.None, License.state(null, 0L, 0L, 0L, 5_000L))
    }

    @Test fun parsesPostgresTimestamps() {
        assertEquals(1_793_491_200_000L, License.parseIso("2026-11-01T00:00:00+00:00"))
        assertEquals(1_793_491_200_000L, License.parseIso("2026-11-01T03:00:00.123456+03:00"))
    }
}
