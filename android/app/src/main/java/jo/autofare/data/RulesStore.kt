package jo.autofare.data

import android.content.Context
import android.content.SharedPreferences
import jo.autofare.rules.Mode
import jo.autofare.rules.RuleSet

/**
 * The driver's accept rules: one shared set for every ride app, plus two small
 * things that stay per app.
 *
 * It used to be one full set per app, on the theory that Jeeny and Petra pay
 * differently and a driver would want a different floor on each. In practice a
 * driver read the Jeeny tab, set it, and never found the Petra tab — so Petra ran
 * on defaults he had never seen, and the screen could not say in one sentence
 * what AutoFare would do. One set he can read back is worth more than two he
 * half-remembers.
 *
 * What stays per app:
 *  - **enabled** — whether this app is armed at all. Home, the rules screen and
 *    the floating button all flip it, and [RuleSet.enabled] still reaches the
 *    engine per package, so `decide(offer, rules)` is untouched.
 *  - **acceptNoPrice** — only Petra sends bonus rides with no price on them. A
 *    shared flag would also take any Jeeny offer whose fare failed to read, on
 *    the ETA alone; that is a misread turning into a ride, so the flag is only
 *    honoured for the apps in [NO_PRICE_APPS].
 *
 * Still SharedPreferences, for the reason [AppPrefs] gives — but here there is a
 * second, harder reason. The accessibility service may not read these from disk
 * while an offer is on screen; it holds them in memory and is told when they
 * change, which is what [observe] is for.
 */
class RulesStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("autofare_rules", Context.MODE_PRIVATE)

    init {
        migrateToShared()
    }

    /** The rules the engine applies to [appId]: the shared set plus that app's own two flags. */
    fun get(appId: String): RuleSet = shared().copy(
        enabled = isEnabled(appId),
        acceptNoPrice = acceptsNoPrice(appId),
    )

    /**
     * Write a full rule set for one app. Kept for callers that think per app (the
     * floating button): the per-app flags go to [appId], everything else to the
     * shared set every app reads.
     */
    fun put(appId: String, rules: RuleSet) {
        val e = sp.edit()
        writeShared(e, rules)
        e.putBoolean(key(appId, ENABLED), rules.enabled)
        if (appId in NO_PRICE_APPS) e.putBoolean(key(appId, NO_PRICE), rules.acceptNoPrice)
        e.apply()
    }

    /** The shared part only. [RuleSet.enabled] and [RuleSet.acceptNoPrice] are left at their defaults. */
    fun shared(): RuleSet {
        val d = RuleSet()
        return RuleSet(
            mode = Mode.entries.getOrElse(sp.getInt(key(SHARED, MODE), d.mode.ordinal)) { d.mode },
            rate = cents(sp.getFloat(key(SHARED, RATE), d.rate.toFloat())),
            floor = cents(sp.getFloat(key(SHARED, FLOOR), d.floor.toFloat())),
            ceiling = cents(sp.getFloat(key(SHARED, CEILING), d.ceiling.toFloat())),
            maxEtaMin = sp.getInt(key(SHARED, MAX_ETA), d.maxEtaMin),
        )
    }

    /** Write the shared part, leaving every app's on/off and no-price flags alone. */
    fun putShared(rules: RuleSet) {
        val e = sp.edit()
        writeShared(e, rules)
        e.apply()
    }

    fun isEnabled(appId: String): Boolean = sp.getBoolean(key(appId, ENABLED), RuleSet().enabled)

    /**
     * Flip one app on or off and nothing else. Preferred over [put] for a switch:
     * a screen holding a copy of the rules from a minute ago must not be able to
     * write that stale copy back over the shared set just to change a switch.
     */
    fun setEnabled(appId: String, on: Boolean) {
        sp.edit().putBoolean(key(appId, ENABLED), on).apply()
    }

    fun acceptsNoPrice(appId: String): Boolean =
        appId in NO_PRICE_APPS && sp.getBoolean(key(appId, NO_PRICE), RuleSet().acceptNoPrice)

    fun setAcceptNoPrice(appId: String, on: Boolean) {
        if (appId !in NO_PRICE_APPS) return
        sp.edit().putBoolean(key(appId, NO_PRICE), on).apply()
    }

    /**
     * Call [onChange] whenever any rule changes.
     *
     * The returned listener has to be held by the caller: SharedPreferences keeps
     * only a weak reference to it, and a listener that has been collected is a
     * service quietly accepting rides by yesterday's rules.
     */
    fun observe(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        sp.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun writeShared(e: SharedPreferences.Editor, rules: RuleSet) {
        e.putInt(key(SHARED, MODE), rules.mode.ordinal)
            .putFloat(key(SHARED, RATE), rules.rate.toFloat())
            .putFloat(key(SHARED, FLOOR), rules.floor.toFloat())
            .putFloat(key(SHARED, CEILING), rules.ceiling.toFloat())
            .putInt(key(SHARED, MAX_ETA), rules.maxEtaMin)
    }

    /**
     * One-time move from per-app rules to the shared set.
     *
     * Jeeny's numbers become the shared ones: it was the first tab, so it is the
     * set the driver actually looked at and tuned. Each app keeps its own on/off
     * exactly as it was, so nothing is armed or disarmed by an update. Jeeny's
     * no-price flag is cleared — the screen no longer offers it for Jeeny, and a
     * setting the driver cannot see must not be quietly accepting rides.
     *
     * `commit()` rather than `apply()` so a crash straight after cannot leave the
     * marker written without the values. It runs once per install, never on the
     * accept path.
     */
    private fun migrateToShared() {
        if (sp.getBoolean(MIGRATED, false)) return
        val e = sp.edit()
        val from = LEGACY_SOURCE
        if (sp.contains(key(from, MODE))) {
            sp.getInt(key(from, MODE), RuleSet().mode.ordinal).let { e.putInt(key(SHARED, MODE), it) }
            listOf(RATE, FLOOR, CEILING).forEach { f ->
                if (sp.contains(key(from, f))) e.putFloat(key(SHARED, f), sp.getFloat(key(from, f), 0f))
            }
            if (sp.contains(key(from, MAX_ETA))) e.putInt(key(SHARED, MAX_ETA), sp.getInt(key(from, MAX_ETA), 0))
        }
        e.remove(key(LEGACY_SOURCE, NO_PRICE))
        e.putBoolean(MIGRATED, true)
        e.commit()
    }

    private fun key(appId: String, field: String) = "$appId.$field"

    companion object {
        /** Apps that send offers with no price on purpose. Only Petra's bonus rides, so far. */
        val NO_PRICE_APPS = setOf("petra")

        private const val SHARED = "shared"
        private const val LEGACY_SOURCE = "jeeny"
        private const val MIGRATED = "shared.migrated_v1"

        private const val ENABLED = "enabled"
        private const val MODE = "mode"
        private const val RATE = "rate"
        private const val FLOOR = "floor"
        private const val CEILING = "ceiling"
        private const val MAX_ETA = "max_eta"
        private const val NO_PRICE = "accept_no_price"
    }
}

/**
 * A stored limit back to the cents it was set in.
 *
 * Preferences keep these as Float, and 1.10 comes back as 1.1000000238. Compared
 * against a fare of exactly 1.10, that made "1.10 or more" skip a 1.10 ride, which
 * the emulator scenario test caught on 31 Oct 2026. Every limit is set in whole
 * cents, so rounding here restores the exact figure, for values already saved too.
 */
internal fun cents(v: Float): Double = Math.round(v.toDouble() * 100.0) / 100.0
