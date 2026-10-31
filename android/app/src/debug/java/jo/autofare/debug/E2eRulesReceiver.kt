package jo.autofare.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import jo.autofare.data.RulesStore
import jo.autofare.data.SUPPORTED
import jo.autofare.rules.Mode

/**
 * Sets the driver's rules from adb. DEBUG BUILDS ONLY — it lives in src/debug and
 * is declared in the debug manifest, so a release APK does not contain it.
 *
 * ```
 * adb shell am broadcast -a jo.autofare.debug.SET_RULES -n jo.autofare/.debug.E2eRulesReceiver \
 *     --es mode PER_MINUTE --es rate 0.50 --es floor 1.00 --es ceiling 12 --es maxEta 6 \
 *     --es jeeny on --es petra on --es noPrice off
 * ```
 *
 * Every extra is optional; whatever is left out keeps its current value. Writes go
 * through [RulesStore] exactly as the rules screen's do, so the service picks them
 * up through its ordinary change listener — and that listener also clears the
 * engine's seen-offer memory and cooldown, which is what a rules change does on a
 * phone too.
 *
 * The rules are then read back and logged as the engine will see them, after the
 * store's own round trip, so the scenario script compares against what is really
 * in force rather than against what it asked for.
 */
class E2eRulesReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = RulesStore(context)

        val now = store.shared()
        val next = now.copy(
            mode = intent.getStringExtra("mode")?.let { Mode.valueOf(it.uppercase()) } ?: now.mode,
            rate = intent.getStringExtra("rate")?.toDouble() ?: now.rate,
            floor = intent.getStringExtra("floor")?.toDouble() ?: now.floor,
            ceiling = intent.getStringExtra("ceiling")?.toDouble() ?: now.ceiling,
            maxEtaMin = intent.getStringExtra("maxEta")?.toInt() ?: now.maxEtaMin,
        )
        store.putShared(next)

        for (app in SUPPORTED) {
            intent.getStringExtra(app.id)?.let { store.setEnabled(app.id, it.isOn()) }
        }
        intent.getStringExtra("noPrice")?.let { flag ->
            RulesStore.NO_PRICE_APPS.forEach { store.setAcceptNoPrice(it, flag.isOn()) }
        }

        // read back through the store: these are the values decide() will be given
        for (app in SUPPORTED.filter { it.verified }) {
            val r = store.get(app.id)
            Log.i(
                TAG,
                "rules app=${app.id} enabled=${r.enabled} mode=${r.mode} rate=${r.rate} " +
                    "floor=${r.floor} ceiling=${r.ceiling} maxEta=${r.maxEtaMin} noPrice=${r.acceptNoPrice}",
            )
        }
        resultData = "ok"
    }

    private fun String.isOn() = this == "on" || this == "true" || this == "1"

    private companion object {
        const val TAG = "FAKERIDE-RULES"
    }
}
