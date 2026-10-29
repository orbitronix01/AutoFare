package jo.autofare.data

import androidx.compose.ui.graphics.Color

/**
 * A ride-hailing app AutoFare can watch.
 *
 * Everything the UI needs lives here, so supporting a new app is one entry in
 * [SUPPORTED] plus a detector — no edits to the screens.
 *
 * [packageName] is confirmed per app before shipping: it is what the
 * accessibility service matches on to know which detector to run. Entries marked
 * UNVERIFIED still need checking against a real install.
 */
data class RideApp(
    val id: String,
    val name: String,
    val packageName: String,
    val badge: Color,
    val letter: String,
    val currency: String = "د.أ",
    val verified: Boolean = false,
)

val SUPPORTED: List<RideApp> = listOf(
    RideApp(
        // Jeeny's driver app still ships under its Easy Taxi ancestry — the package
        // says easytaxista, the offer sheet says "Jeeny Comfort". Confirmed from a
        // real driver shift, 2026-10-27.
        id = "jeeny",
        name = "Jeeny",
        packageName = "me.com.easytaxista",
        badge = Color(0xFF5B3FE8),
        letter = "J",
        verified = true,
    ),
    RideApp(
        // The driver-side app is "سفير بترا رايد"; the passenger app is a separate
        // install and is not what we watch. Confirmed 2026-10-27.
        id = "petra",
        name = "Petra Ride",
        packageName = "com.PetraRide_Captain",
        badge = Color(0xFF0E76CC),
        letter = "P",
        verified = true,
    ),
    RideApp(
        id = "careem",
        name = "Careem",
        packageName = "com.careem.captain", // UNVERIFIED
        badge = Color(0xFF0B8A3E),
        letter = "C",
    ),
    RideApp(
        id = "uber",
        name = "Uber Driver",
        packageName = "com.ubercab.driver", // UNVERIFIED
        badge = Color(0xFF111111),
        letter = "U",
    ),
)
