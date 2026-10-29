package jo.autofare.data

import android.content.Context
import android.content.SharedPreferences
import jo.autofare.rules.Mode
import jo.autofare.rules.RuleSet

/**
 * The driver's accept rules, one set per ride app.
 *
 * Per app and not global, because the two apps do not pay the same: the capture
 * has Jeeny offering 1.50 د.أ rides alongside Petra Ride at 1.74 for a comparable
 * hop, and a driver who runs both will want a different floor on each.
 *
 * Still SharedPreferences, for the reason [AppPrefs] gives — but here there is a
 * second, harder reason. The accessibility service may not read these from disk
 * while an offer is on screen; it holds them in memory and is told when they
 * change, which is what [observe] is for.
 */
class RulesStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("autofare_rules", Context.MODE_PRIVATE)

    fun get(appId: String): RuleSet {
        val d = RuleSet()
        return RuleSet(
            enabled = sp.getBoolean(key(appId, ENABLED), d.enabled),
            mode = Mode.entries.getOrElse(sp.getInt(key(appId, MODE), d.mode.ordinal)) { d.mode },
            rate = sp.getFloat(key(appId, RATE), d.rate.toFloat()).toDouble(),
            floor = sp.getFloat(key(appId, FLOOR), d.floor.toFloat()).toDouble(),
            ceiling = sp.getFloat(key(appId, CEILING), d.ceiling.toFloat()).toDouble(),
            maxEtaMin = sp.getInt(key(appId, MAX_ETA), d.maxEtaMin),
            acceptNoPrice = sp.getBoolean(key(appId, NO_PRICE), d.acceptNoPrice),
        )
    }

    fun put(appId: String, rules: RuleSet) {
        sp.edit()
            .putBoolean(key(appId, ENABLED), rules.enabled)
            .putInt(key(appId, MODE), rules.mode.ordinal)
            .putFloat(key(appId, RATE), rules.rate.toFloat())
            .putFloat(key(appId, FLOOR), rules.floor.toFloat())
            .putFloat(key(appId, CEILING), rules.ceiling.toFloat())
            .putInt(key(appId, MAX_ETA), rules.maxEtaMin)
            .putBoolean(key(appId, NO_PRICE), rules.acceptNoPrice)
            .apply()
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

    private fun key(appId: String, field: String) = "$appId.$field"

    private companion object {
        const val ENABLED = "enabled"
        const val MODE = "mode"
        const val RATE = "rate"
        const val FLOOR = "floor"
        const val CEILING = "ceiling"
        const val MAX_ETA = "max_eta"
        const val NO_PRICE = "accept_no_price"
    }
}
