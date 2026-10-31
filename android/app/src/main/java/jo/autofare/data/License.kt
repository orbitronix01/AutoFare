package jo.autofare.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The subscription: what the driver paid for, and whether it is still good.
 *
 * ## The rule that outranks everything else here
 *
 * **A licence check must never be on the path of accepting a ride.** An offer
 * lives about three seconds and AutoFare wins on forty milliseconds; a network
 * call in that window loses the race every time the driver is in a tunnel, on a
 * weak Umniah cell, or simply roaming between towers on the airport road. So
 * nothing in this file is ever called from the service's hot path. The expiry is
 * written to disk, and the engine reads a boolean.
 *
 * ## Why the paid period *is* the grace period
 *
 * The naive design checks in with the server and refuses to work when it cannot
 * reach it. That design takes auto-accept away from a paying driver because of a
 * dead cell, which is worse than any piracy it prevents. Instead the phone keeps
 * [LicenseStore.expiresAt] and trusts it until it passes. Being offline for a
 * week changes nothing; the month he paid for runs out on the day it runs out.
 *
 * The cost is that a revoked key keeps working until the end of its paid month.
 * That is a fair trade for a 5 JOD subscription.
 */
object License {

    /** What the app knows about the subscription right now. */
    sealed interface State {
        /** No key has ever been entered. */
        data object None : State

        /** Good, with however many whole days are left. */
        data class Active(val daysLeft: Int, val expiresAt: Long) : State

        /** The paid period ran out. */
        data object Expired : State

        /** The server refused: wrong key, revoked, or bound to another phone. */
        data class Refused(val reason: String) : State {
            val ar: String
                get() = when (reason) {
                    "unknown_key" -> "الرمز غلط"
                    "revoked" -> "الاشتراك موقوف"
                    "device_limit" -> "الرمز مستعمل ع جهاز تاني"
                    "expired" -> "الاشتراك خلص"
                    else -> "ما قدرنا نفعّل الرمز"
                }
        }
    }

    /**
     * The phone's identity, so one key cannot run a WhatsApp group of drivers.
     *
     * `ANDROID_ID` is per-app-signing-key and per-user, survives updates, and
     * resets on factory reset — which is the right behaviour: a wiped phone is a
     * rebind, and the driver has two of those.
     */
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"

    fun state(store: LicenseStore, now: Long = System.currentTimeMillis()): State {
        val refused = store.refusedReason
        if (refused != null) return State.Refused(refused)
        if (store.key == null) return State.None
        if (store.expiresAt <= now) return State.Expired

        val days = ((store.expiresAt - now + DAY_MS - 1) / DAY_MS).toInt()
        return State.Active(days, store.expiresAt)
    }

    /* ─────────────────────────── the network ─────────────────────────── */

    /** What one call to Supabase came back with. */
    sealed interface Result {
        data class Ok(val expiresAt: Long) : Result
        data class Refused(val reason: String) : Result

        /** Could not reach the server. Deliberately distinct from a refusal. */
        data object Offline : Result
    }

    /** Bind a key to this phone. Call off the main thread. */
    fun activate(context: Context, key: String): Result =
        call("activate_license", key, deviceId(context))

    /** Re-check an already bound key. Will not bind or consume a rebind. */
    fun check(context: Context, key: String): Result =
        call("check_license", key, deviceId(context))

    private fun call(fn: String, key: String, device: String): Result {
        val body = JSONObject()
            .put("p_key", key)
            .put("p_device_id", device)
            .toString()

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("${Supabase.URL}/rest/v1/rpc/$fn").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("apikey", Supabase.PUBLISHABLE_KEY)
                    setRequestProperty("Authorization", "Bearer ${Supabase.PUBLISHABLE_KEY}")
                    setRequestProperty("Content-Type", "application/json")
                }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode !in 200..299) return Result.Offline

            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(text)

            if (!json.optBoolean("ok", false)) {
                return Result.Refused(json.optString("reason", "unknown"))
            }
            val expires = parseIso(json.optString("expires_at"))
                ?: return Result.Offline
            Result.Ok(expires)
        } catch (e: Exception) {
            // every network failure lands here, and every one of them means
            // "unknown", never "unpaid"
            Result.Offline
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Postgres timestamptz, e.g. `2026-11-30T14:33:14.025806+00:00`.
     *
     * Parsed by hand because the fractional seconds run to six digits, which
     * `SimpleDateFormat` cannot express, and `java.time` needs API 26 desugaring
     * this project does not have switched on.
     */
    internal fun parseIso(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            val date = s.substringBefore('T')
            val rest = s.substringAfter('T')
            val time = rest.take(8)

            val (y, mo, d) = date.split('-').map { it.toInt() }
            val (h, mi, sec) = time.split(':').map { it.toInt() }

            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(y, mo - 1, d, h, mi, sec)

            // offset: trailing Z, or ±HH:MM after the seconds
            val tail = rest.drop(8)
            val sign = tail.firstOrNull { it == '+' || it == '-' }
            var millis = cal.timeInMillis
            if (sign != null) {
                val off = tail.substringAfter(sign)
                val oh = off.substringBefore(':').toIntOrNull() ?: 0
                val om = off.substringAfter(':', "0").take(2).toIntOrNull() ?: 0
                val delta = (oh * 60 + om) * 60_000L
                millis += if (sign == '+') -delta else delta
            }
            millis
        } catch (e: Exception) {
            null
        }
    }

    private const val TIMEOUT_MS = 10_000
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** How often to re-check a healthy licence. Once a day is plenty. */
    const val RECHECK_MS = 24 * 60 * 60 * 1000L
}

/**
 * Where the subscription lives between launches.
 *
 * SharedPreferences rather than the history database: this is four values, it
 * has to be readable before anything else is ready, and it must survive the
 * history being cleared.
 */
class LicenseStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("autofare_license", Context.MODE_PRIVATE)

    var key: String?
        get() = sp.getString(KEY, null)
        set(v) = sp.edit().putString(KEY, v).apply()

    var expiresAt: Long
        get() = sp.getLong(EXPIRES, 0L)
        set(v) = sp.edit().putLong(EXPIRES, v).apply()

    var lastCheckAt: Long
        get() = sp.getLong(CHECKED, 0L)
        set(v) = sp.edit().putLong(CHECKED, v).apply()

    /** Set only when the server positively said no. Network failures never write here. */
    var refusedReason: String?
        get() = sp.getString(REFUSED, null)
        set(v) = sp.edit().putString(REFUSED, v).apply()

    fun save(key: String, expiresAt: Long, at: Long = System.currentTimeMillis()) {
        sp.edit()
            .putString(KEY, key)
            .putLong(EXPIRES, expiresAt)
            .putLong(CHECKED, at)
            .remove(REFUSED)
            .apply()
    }

    fun clear() = sp.edit().clear().apply()

    /**
     * Call [onChange] whenever the subscription changes.
     *
     * The service caches the expiry, so without this a driver who activates a key
     * mid-shift keeps being refused until something restarts the service — which
     * on EMUI means he reinstalls, or gives up. Same weak-reference caveat as
     * `RulesStore.observe`: the caller has to hold the listener.
     */
    fun observe(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        sp.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val KEY = "key"
        const val EXPIRES = "expires_at"
        const val CHECKED = "last_check_at"
        const val REFUSED = "refused_reason"
    }
}
