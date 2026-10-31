package jo.autofare.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import jo.autofare.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The subscription: whether this phone is allowed to auto-accept right now.
 *
 * ## The model
 *
 * A code is a one-time voucher worth some months. Redeeming it on a phone pushes
 * that phone's expiry out on the server, and the code is spent. The phone itself
 * keeps no key — the subscription belongs to the device, so a renewal is simply
 * another code typed into the same box.
 *
 * ## Strict, but never on the accept path
 *
 * The owner chose strict: a phone the server has not vouched for recently does
 * not accept rides, and one whose time ran out stops on the minute. That is enforced by
 * checking in every [CHECK_MS] from the service and treating the answer as good
 * for [GRACE_MS] only.
 *
 * **Still, nothing here is ever called from the accept path.** An offer lives
 * about three seconds; the service turns the last answer into a single
 * `elapsedRealtime` deadline ([LicenseStore.paidUntilElapsed]) and the engine
 * compares two longs.
 *
 * ## Why elapsedRealtime and server time, not the phone's clock
 *
 * The wall clock belongs to the driver: wind it back and any expiry stored
 * against it lasts forever. So every answer carries the server's own time, and
 * the phone measures from that answer with the monotonic clock, which cannot be
 * set and resets on reboot — after which the app simply needs to check in again.
 */
object License {

    /** What the app knows about the subscription right now. */
    sealed interface State {
        /** No code has ever been redeemed on this phone. */
        data object None : State

        /** Good, with however many whole days are left. */
        data class Active(val daysLeft: Int, val expiresAt: Long) : State {
            /** On a forever code: the server sets the expiry to 2100. */
            val forever: Boolean get() = daysLeft > 365 * 50
        }

        /** The paid period ran out. */
        data object Expired : State

        /** Paid as far as we know, but the server has not confirmed it recently. */
        data object Unverified : State

        val locked: Boolean get() = this !is Active
    }

    /**
     * Who the phone is, for the server and for the driver to read out to support.
     *
     * `ANDROID_ID` is per-app-signing-key and per-user, survives updates, and
     * resets on factory reset.
     */
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"

    /** The first eight characters, upper case — what the portal lists phones by. */
    fun shortDeviceId(context: Context): String = deviceId(context).take(8).uppercase()

    /** The developer skip on the lock screen. Debug builds only; a release ignores the flag. */
    fun bypassed(store: LicenseStore): Boolean = BuildConfig.DEBUG && store.devBypass

    /** True when the app is unlocked: a fresh good answer, or the debug skip. */
    fun unlocked(store: LicenseStore): Boolean = bypassed(store) || state(store) is State.Active

    fun state(store: LicenseStore, elapsed: Long = SystemClock.elapsedRealtime()): State =
        state(
            reason = store.reason,
            lastOkElapsed = store.lastOkElapsed,
            serverTimeAtOk = store.serverTimeAtOk,
            expiresAt = store.expiresAt,
            elapsed = elapsed,
        )

    /** Pure, so the rules can be tested without a phone. */
    internal fun state(
        reason: String?,
        lastOkElapsed: Long,
        serverTimeAtOk: Long,
        expiresAt: Long,
        elapsed: Long,
    ): State {
        when (reason) {
            "expired" -> return State.Expired
            "none" -> return State.None
        }
        if (lastOkElapsed <= 0L) return if (expiresAt > 0L) State.Unverified else State.None

        // negative after a reboot: the old answer cannot be measured from any more
        val since = elapsed - lastOkElapsed
        if (since < 0 || since > GRACE_MS) return State.Unverified

        val serverNow = serverTimeAtOk + since
        if (expiresAt <= serverNow) return State.Expired

        val days = ((expiresAt - serverNow + DAY_MS - 1) / DAY_MS).toInt()
        return State.Active(days, expiresAt)
    }

    /* ─────────────────────────── the network ─────────────────────────── */

    /** What one call to Supabase came back with. */
    sealed interface Result {
        data class Ok(val expiresAt: Long, val serverTime: Long) : Result
        data class Refused(val reason: String) : Result

        /** Could not reach the server. Deliberately distinct from a refusal. */
        data object Offline : Result
    }

    /** Redeem a code on this phone. Call off the main thread. */
    fun redeem(context: Context, code: String): Result =
        call(context, "redeem_code", JSONObject().put("p_code", code))
            .also { record(LicenseStore(context), it, fromRedeem = true) }

    /** Ask where this phone stands, and remember the answer. Call off the main thread. */
    fun check(context: Context): Result =
        call(context, "device_status", JSONObject())
            .also { record(LicenseStore(context), it, fromRedeem = false) }

    /** [check] on a background thread; for the UI and the service's timer. */
    fun checkAsync(context: Context, then: ((Result) -> Unit)? = null) {
        val app = context.applicationContext
        io.execute {
            val r = check(app)
            then?.invoke(r)
        }
    }

    /** One thread, so a timer check and a lock-screen check never race each other's writes. */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "autofare-license").apply { isDaemon = true } }

    private fun record(store: LicenseStore, r: Result, fromRedeem: Boolean) {
        when (r) {
            is Result.Ok -> store.saveOk(r.expiresAt, r.serverTime, SystemClock.elapsedRealtime())
            is Result.Refused -> when {
                // a mistyped or spent code says nothing about the subscription
                // already on this phone, so it must not lock a paying driver out
                fromRedeem -> Unit
                r.reason in setOf("none", "expired") -> store.saveRefused(r.reason)
                else -> Unit
            }
            Result.Offline -> Unit
        }
    }

    private fun call(context: Context, fn: String, body: JSONObject): Result {
        body.put("p_device_id", deviceId(context))
            .put("p_app_version", BuildConfig.VERSION_NAME)

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
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode !in 200..299) return Result.Offline

            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(text)

            if (!json.optBoolean("ok", false)) {
                return Result.Refused(json.optString("reason", "unknown"))
            }
            val expires = parseIso(json.optString("expires_at")) ?: return Result.Offline
            val serverTime = parseIso(json.optString("server_time")) ?: return Result.Offline
            Result.Ok(expires, serverTime)
        } catch (e: Exception) {
            // every network failure lands here, and every one of them means
            // "unknown", never "unpaid"
            Result.Offline
        } finally {
            conn?.disconnect()
        }
    }

    /** The driver-facing line for a refusal. */
    fun refusalAr(reason: String): String = when (reason) {
        "unknown_code" -> "الرمز غلط. تأكّد منه وجرّب كمان مرة"
        "used" -> "هاد الرمز انستعمل قبل"
        "other_phone" -> "هاد الرمز مربوط بجهاز ثاني"
        "rate_limited" -> "جرّبت كثير. استنّى ساعة وجرّب كمان مرة"
        else -> "ما قدرنا نفعّل الرمز"
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

    /** How often the service checks in while it runs. */
    const val CHECK_MS = 5 * 60 * 1000L

    /**
     * How long one good answer counts for. Three missed check-ins: enough to ride
     * out a tunnel or a dead cell, short enough that an offline phone stops soon.
     */
    const val GRACE_MS = 15 * 60 * 1000L
}

/**
 * Where the last server answer lives between launches.
 *
 * SharedPreferences rather than the history database: a handful of values that
 * must be readable before anything else is ready and survive the history being
 * cleared.
 */
class LicenseStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("autofare_license_v2", Context.MODE_PRIVATE)

    val expiresAt: Long get() = sp.getLong(EXPIRES, 0L)
    val serverTimeAtOk: Long get() = sp.getLong(SERVER_TIME, 0L)
    val lastOkElapsed: Long get() = sp.getLong(OK_ELAPSED, 0L)

    /** Set only when the server positively said none / expired. */
    val reason: String? get() = sp.getString(REASON, null)

    var devBypass: Boolean
        get() = sp.getBoolean(BYPASS, false)
        set(v) = sp.edit().putBoolean(BYPASS, v).apply()

    fun saveOk(expiresAt: Long, serverTime: Long, elapsed: Long) {
        sp.edit()
            .putLong(EXPIRES, expiresAt)
            .putLong(SERVER_TIME, serverTime)
            .putLong(OK_ELAPSED, elapsed)
            .remove(REASON)
            .apply()
    }

    fun saveRefused(reason: String) {
        sp.edit().putString(REASON, reason).putLong(OK_ELAPSED, 0L).apply()
    }

    /**
     * The `elapsedRealtime` instant until which the engine may press, or 0.
     *
     * The earlier of "the last answer goes stale" and "the paid period ends",
     * both measured on the monotonic clock from the moment of that answer.
     */
    fun paidUntilElapsed(): Long {
        if (License.bypassed(this)) return Long.MAX_VALUE
        if (License.state(this) !is License.State.Active) return 0L
        val ok = lastOkElapsed
        return ok + minOf(License.GRACE_MS, expiresAt - serverTimeAtOk)
    }

    /**
     * Call [onChange] whenever the subscription changes. Same weak-reference
     * caveat as `RulesStore.observe`: the caller has to hold the listener.
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
        const val EXPIRES = "expires_at"
        const val SERVER_TIME = "server_time_at_ok"
        const val OK_ELAPSED = "ok_elapsed"
        const val REASON = "reason"
        const val BYPASS = "dev_bypass"
    }
}
