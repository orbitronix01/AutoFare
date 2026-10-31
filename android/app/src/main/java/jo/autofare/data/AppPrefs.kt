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

    /* ─────────────── while he is inside the ride app ─────────────── */

    /**
     * The floating button over the ride app. On by default, matching the design:
     * it only ever appears once the driver has also granted draw-over-apps, so the
     * default costs nothing on a phone where he has not.
     */
    var floatingButton: Boolean
        get() = sp.getBoolean(KEY_BUBBLE, true)
        set(v) = sp.edit().putBoolean(KEY_BUBBLE, v).apply()

    /** A short tone when AutoFare takes a ride for him. */
    var acceptSound: Boolean
        get() = sp.getBoolean(KEY_SOUND, true)
        set(v) = sp.edit().putBoolean(KEY_SOUND, v).apply()

    /**
     * A short buzz when an offer came in and the rules let it go.
     *
     * Off by default: a phone buzzing on every refused offer at a busy junction
     * is the kind of noise a driver should opt into, not discover.
     */
    var rejectVibrate: Boolean
        get() = sp.getBoolean(KEY_VIBRATE, false)
        set(v) = sp.edit().putBoolean(KEY_VIBRATE, v).apply()

    /**
     * Where he last dropped the floating button, in screen pixels, or null for
     * "never moved". Display placement only — nothing decides anything by it.
     */
    var bubblePosition: Pair<Int, Int>?
        get() {
            if (!sp.contains(KEY_BUBBLE_X)) return null
            return sp.getInt(KEY_BUBBLE_X, 0) to sp.getInt(KEY_BUBBLE_Y, 0)
        }
        set(v) {
            val e = sp.edit()
            if (v == null) e.remove(KEY_BUBBLE_X).remove(KEY_BUBBLE_Y)
            else e.putInt(KEY_BUBBLE_X, v.first).putInt(KEY_BUBBLE_Y, v.second)
            e.apply()
        }

    /**
     * The apps that were armed when he paused everything from the floating
     * button, so the next tap brings back exactly those — see `BubbleToggle`.
     */
    var pausedApps: Set<String>
        get() = sp.getStringSet(KEY_PAUSED, null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_PAUSED, v).apply()

    /**
     * Same contract as `RulesStore.observe`: hold the listener, because
     * SharedPreferences only keeps a weak reference to it.
     */
    fun observe(onChange: (key: String?) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onChange(key) }
        sp.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_FOLLOW_SYSTEM = "follow_system_theme"
        const val KEY_DARK = "dark_theme"
        const val KEY_LEARNING = "learning_enabled"
        const val KEY_CAPTURE_ALL = "capture_all_apps"
        const val KEY_PICKED = "picked_packages"
        const val KEY_EPOCH = "capture_epoch"
        const val KEY_BUBBLE = "floating_button"
        const val KEY_SOUND = "accept_sound"
        const val KEY_VIBRATE = "reject_vibrate"
        const val KEY_BUBBLE_X = "bubble_x"
        const val KEY_BUBBLE_Y = "bubble_y"
        const val KEY_PAUSED = "bubble_paused_apps"
    }
}
