package jo.autofare.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Small synchronous settings store. Deliberately SharedPreferences rather than
 * DataStore: these are a handful of booleans read during composition, and the
 * theme must be known on the very first frame with no async gap.
 */
class AppPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("autofare", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(KEY_ONBOARDED, v).apply()

    /** When true the app follows the phone's light/dark setting. */
    var followSystemTheme: Boolean
        get() = sp.getBoolean(KEY_FOLLOW_SYSTEM, true)
        set(v) = sp.edit().putBoolean(KEY_FOLLOW_SYSTEM, v).apply()

    /** Only consulted when [followSystemTheme] is false. */
    var darkTheme: Boolean
        get() = sp.getBoolean(KEY_DARK, false)
        set(v) = sp.edit().putBoolean(KEY_DARK, v).apply()

    /** Learning mode: record what other apps put on screen, record nothing else. */
    var learningEnabled: Boolean
        get() = sp.getBoolean(KEY_LEARNING, false)
        set(v) = sp.edit().putBoolean(KEY_LEARNING, v).apply()

    /**
     * Defaults to ON during the learning phase, and that is deliberate: the ride-app
     * package names are still guesses, so filtering by them risks recording nothing
     * at all and wasting a driver's entire shift. Private apps are skipped regardless
     * (see AutoFareService.SENSITIVE).
     */
    var captureAllApps: Boolean
        get() = sp.getBoolean(KEY_CAPTURE_ALL, true)
        set(v) = sp.edit().putBoolean(KEY_CAPTURE_ALL, v).apply()

    /**
     * Ride apps the driver pointed out himself, from a list of what is on his phone.
     *
     * The packages in [SUPPORTED] are guesses typed from the outside; this set is
     * ground truth, so the service watches it with the same priority — a picked app
     * is recorded even when "capture all apps" is off.
     */
    var pickedPackages: Set<String>
        get() = sp.getStringSet(KEY_PICKED, null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_PICKED, v).apply()

    /**
     * Bumped whenever a new recording session starts — learning switched on, or the
     * captures wiped.
     *
     * The service keeps per-layout counts in memory to stop one screen from flooding
     * the quota. Those counts have to die with the session they belong to: without
     * this, a driver who clears his captures and sets off again is deduping against
     * files that are no longer on the phone, and records almost nothing.
     */
    var captureEpoch: Int
        get() = sp.getInt(KEY_EPOCH, 0)
        set(v) = sp.edit().putInt(KEY_EPOCH, v).apply()

    fun newSession() {
        captureEpoch = captureEpoch + 1
    }

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_FOLLOW_SYSTEM = "follow_system_theme"
        const val KEY_DARK = "dark_theme"
        const val KEY_LEARNING = "learning_enabled"
        const val KEY_CAPTURE_ALL = "capture_all_apps"
        const val KEY_PICKED = "picked_packages"
        const val KEY_EPOCH = "capture_epoch"
    }
}
