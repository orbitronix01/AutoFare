package jo.autofare.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import jo.autofare.auto.AutoAccept
import jo.autofare.rules.Decision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Every decision the service has ever made, on disk.
 *
 * This is the thing that turns the home and stats screens from a mock-up into the
 * app: before it, `DecisionLog` held forty entries in memory and died with the
 * service, which is why every figure on those screens was a literal.
 *
 * ## Plain SQLite, not Room
 *
 * Room would mean KSP, and this project sits on AGP 9.1 / Kotlin 2.2.20 /
 * compileSdk 37 where the annotation-processor version matrix is the most
 * fragile part of the build. One table and a handful of aggregates do not earn
 * that risk.
 *
 * ## Writes never block the accept path
 *
 * [record] is called from `AutoAccept`'s log callback, which already runs after
 * the button has been pressed — so it cannot slow a press down. It still hands
 * the insert to a background thread, because the caller is the accessibility
 * service's main thread and a stalled disk write there delays the *next* offer.
 */
class History private constructor(context: Context) {

    private val helper = Helper(context.applicationContext)
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "autofare-history").apply { isDaemon = true }
    }

    /**
     * Bumped after every insert.
     *
     * The service writes and the activity reads, in the same process but with no
     * lifecycle in common, so the UI needs something to watch. A counter is
     * enough: the screens re-run their queries when it changes.
     */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** One decision as the UI wants to show it. */
    data class Row(
        val at: Long,
        val pkg: String,
        val fare: Double?,
        val etaMin: Int?,
        val perMinute: Double?,
        val accepted: Boolean,
        val reason: String?,
        val pickup: String?,
        val dropoff: String?,
    )

    /**
     * A window of history, already reduced.
     *
     * Everything the screens need in one pass, because the alternative is six
     * round trips to SQLite on every recomposition.
     */
    data class Summary(
        /** Dinars from rides AutoFare accepted. Rides the driver took himself are invisible here. */
        val earnings: Double = 0.0,
        val accepted: Int = 0,
        /** Offers judged, accepted or not. */
        val seen: Int = 0,
        /** Null until there is enough of a span to divide by. */
        val perHour: Double? = null,
        val firstAt: Long? = null,
        val lastAt: Long? = null,
    ) {
        /** Share of offers taken, 0..1. Null when nothing was offered. */
        val acceptRate: Double? get() = if (seen == 0) null else accepted.toDouble() / seen
    }

    // ─────────────────────────── writing ───────────────────────────

    fun record(outcome: AutoAccept.Outcome, at: Long) {
        val offer = outcome.offer
        val reason = (outcome.decision as? Decision.Skip)?.reason?.name

        val values = ContentValues().apply {
            put(COL_AT, at)
            put(COL_PKG, offer.pkg)
            offer.fare?.let { put(COL_FARE, it) } ?: putNull(COL_FARE)
            offer.etaMin?.let { put(COL_ETA, it) } ?: putNull(COL_ETA)
            offer.perMinute?.let { put(COL_PER_MIN, it) } ?: putNull(COL_PER_MIN)
            // `pressed`, not the decision: an Accept we could not press is not a ride
            put(COL_ACCEPTED, if (outcome.pressed) 1 else 0)
            reason?.let { put(COL_REASON, it) } ?: putNull(COL_REASON)
            offer.pickup?.let { put(COL_PICKUP, it) } ?: putNull(COL_PICKUP)
            offer.dropoff?.let { put(COL_DROPOFF, it) } ?: putNull(COL_DROPOFF)
        }

        writer.execute {
            try {
                helper.writableDatabase.insert(TABLE, null, values)
                _version.value += 1
            } catch (e: Exception) {
                // A failed insert must never take the service down with it — the
                // driver loses a row of history, not his auto-accept.
                android.util.Log.w("AutoFare", "history insert failed", e)
            }
        }
    }

    /** Drop everything. Offered in settings, because this is the driver's own data. */
    fun clear() = writer.execute {
        helper.writableDatabase.delete(TABLE, null, null)
        _version.value += 1
    }

    // ─────────────────────────── reading ───────────────────────────

    /** Reduce one time window. Call off the main thread. */
    fun summary(fromAt: Long, toAt: Long = Long.MAX_VALUE): Summary {
        helper.readableDatabase.rawQuery(
            """
            SELECT
                COALESCE(SUM(CASE WHEN $COL_ACCEPTED = 1 THEN $COL_FARE END), 0) AS earnings,
                COALESCE(SUM($COL_ACCEPTED), 0)                                  AS accepted,
                COUNT(*)                                                         AS seen,
                MIN($COL_AT)                                                     AS first_at,
                MAX($COL_AT)                                                     AS last_at
            FROM $TABLE WHERE $COL_AT >= ? AND $COL_AT <= ?
            """.trimIndent(),
            arrayOf(fromAt.toString(), toAt.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return Summary()

            val earnings = c.getDouble(0)
            val accepted = c.getInt(1)
            val seen = c.getInt(2)
            val firstAt = if (c.isNull(3)) null else c.getLong(3)
            val lastAt = if (c.isNull(4)) null else c.getLong(4)

            /*
             * Hours worked is the span from the first offer to the last, not the
             * clock since midnight — a driver who starts at 4pm has not been
             * earning nothing for sixteen hours. Below the floor the figure is
             * meaningless (one ride ten minutes in reads as 60 د.أ/hour), so it
             * is withheld rather than flattered.
             */
            val spanMs = if (firstAt != null && lastAt != null) lastAt - firstAt else 0L
            val perHour =
                if (accepted > 0 && spanMs >= MIN_SPAN_FOR_RATE_MS) {
                    earnings / (spanMs / 3_600_000.0)
                } else null

            return Summary(earnings, accepted, seen, perHour, firstAt, lastAt)
        }
    }

    /**
     * Earnings per hour-slot over a window, oldest first — the sparkline.
     *
     * Returns exactly [buckets] values, zero-filled, so the chart keeps its shape
     * on a quiet day instead of collapsing to one bar.
     */
    fun buckets(fromAt: Long, bucketMs: Long, buckets: Int): List<Double> {
        val out = DoubleArray(buckets)
        helper.readableDatabase.rawQuery(
            """
            SELECT CAST(($COL_AT - ?) / ? AS INTEGER) AS slot,
                   COALESCE(SUM($COL_FARE), 0)        AS taken
            FROM $TABLE
            WHERE $COL_ACCEPTED = 1 AND $COL_AT >= ?
            GROUP BY slot
            """.trimIndent(),
            arrayOf(fromAt.toString(), bucketMs.toString(), fromAt.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val slot = c.getInt(0)
                if (slot in 0 until buckets) out[slot] = c.getDouble(1)
            }
        }
        return out.toList()
    }

    /**
     * Earnings summed by hour of the day (0..23) across a window — the stats chart.
     *
     * Different question from [buckets]: that one asks "what happened in the last
     * twelve hours", this one asks "which hours of the day are worth working".
     */
    fun byHourOfDay(fromAt: Long): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        helper.readableDatabase.rawQuery(
            """
            SELECT CAST(strftime('%H', $COL_AT / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
                   COALESCE(SUM($COL_FARE), 0) AS taken
            FROM $TABLE
            WHERE $COL_ACCEPTED = 1 AND $COL_AT >= ?
            GROUP BY hour
            """.trimIndent(),
            arrayOf(fromAt.toString()),
        ).use { c ->
            while (c.moveToNext()) out[c.getInt(0)] = c.getDouble(1)
        }
        return out
    }

    /** Earnings per ride app over a window, keyed by package — the "من وين إجت المصاري" split. */
    fun byApp(fromAt: Long): Map<String, Double> {
        val out = HashMap<String, Double>()
        helper.readableDatabase.rawQuery(
            """
            SELECT $COL_PKG, COALESCE(SUM($COL_FARE), 0) AS taken
            FROM $TABLE
            WHERE $COL_ACCEPTED = 1 AND $COL_AT >= ?
            GROUP BY $COL_PKG
            """.trimIndent(),
            arrayOf(fromAt.toString()),
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getDouble(1)
        }
        return out
    }

    /**
     * Why offers were refused, counted — `Reason.name` to how many.
     *
     * Only genuine refusals: rows where the rules said no. A row with no reason
     * is one that was accepted, and counting those as a refusal would make the
     * driver's own settings look stricter than they are.
     */
    fun byReason(fromAt: Long): Map<String, Int> {
        val out = HashMap<String, Int>()
        helper.readableDatabase.rawQuery(
            """
            SELECT $COL_REASON, COUNT(*) AS n
            FROM $TABLE
            WHERE $COL_ACCEPTED = 0 AND $COL_REASON IS NOT NULL AND $COL_AT >= ?
            GROUP BY $COL_REASON
            ORDER BY n DESC
            """.trimIndent(),
            arrayOf(fromAt.toString()),
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        }
        return out
    }

    /** The newest decisions, whatever they were. */
    fun recent(limit: Int): List<Row> {
        val out = ArrayList<Row>(limit)
        helper.readableDatabase.rawQuery(
            "SELECT $COL_AT, $COL_PKG, $COL_FARE, $COL_ETA, $COL_PER_MIN, $COL_ACCEPTED, " +
                "$COL_REASON, $COL_PICKUP, $COL_DROPOFF FROM $TABLE ORDER BY $COL_AT DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += Row(
                    at = c.getLong(0),
                    pkg = c.getString(1),
                    fare = if (c.isNull(2)) null else c.getDouble(2),
                    etaMin = if (c.isNull(3)) null else c.getInt(3),
                    perMinute = if (c.isNull(4)) null else c.getDouble(4),
                    accepted = c.getInt(5) == 1,
                    reason = if (c.isNull(6)) null else c.getString(6),
                    pickup = if (c.isNull(7)) null else c.getString(7),
                    dropoff = if (c.isNull(8)) null else c.getString(8),
                )
            }
        }
        return out
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, NAME, null, VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID       INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_AT       INTEGER NOT NULL,
                    $COL_PKG      TEXT    NOT NULL,
                    $COL_FARE     REAL,
                    $COL_ETA      INTEGER,
                    $COL_PER_MIN  REAL,
                    $COL_ACCEPTED INTEGER NOT NULL,
                    $COL_REASON   TEXT,
                    $COL_PICKUP   TEXT,
                    $COL_DROPOFF  TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX ${TABLE}_at ON $TABLE($COL_AT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            // Nothing to migrate yet. History is derived data the driver can lose
            // without losing anything he paid for, so a rebuild beats a bad migration.
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val NAME = "autofare-history.db"
        private const val VERSION = 1

        private const val TABLE = "decisions"
        private const val COL_ID = "id"
        private const val COL_AT = "at"
        private const val COL_PKG = "pkg"
        private const val COL_FARE = "fare"
        private const val COL_ETA = "eta_min"
        private const val COL_PER_MIN = "per_minute"
        private const val COL_ACCEPTED = "accepted"
        private const val COL_REASON = "reason"
        private const val COL_PICKUP = "pickup"
        private const val COL_DROPOFF = "dropoff"

        /** Below twenty minutes of shift, a per-hour rate is noise dressed as a number. */
        private const val MIN_SPAN_FOR_RATE_MS = 20 * 60 * 1000L

        @Volatile private var instance: History? = null

        /**
         * One instance per process. The service and the activity both want it and
         * neither owns the other's lifetime.
         */
        fun get(context: Context): History =
            instance ?: synchronized(this) {
                instance ?: History(context).also { instance = it }
            }

        /** Midnight this morning, local time. */
        fun startOfToday(now: Long = System.currentTimeMillis()): Long =
            Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

        /** Midnight seven days ago, local time — the stats window. */
        fun startOfWeek(now: Long = System.currentTimeMillis()): Long =
            startOfToday(now) - 6 * 24 * 60 * 60 * 1000L
    }
}
