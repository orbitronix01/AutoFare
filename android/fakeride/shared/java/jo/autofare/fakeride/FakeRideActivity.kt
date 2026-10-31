package jo.autofare.fakeride

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * A stand-in ride app, for watching AutoFare's rules work on an emulator.
 *
 * DEBUG / EMULATOR ONLY. The two APKs built from this sit on the real package
 * names of Jeeny and Petra Ride, because that is the only way to make the real,
 * unmodified detectors look at them. Never install them on a phone that has the
 * real apps — see fakeride/README.md.
 *
 * Driven entirely from adb:
 *
 * ```
 * adb shell am start -n <pkg>/.FakeOfferActivity --es cmd offer \
 *     --es id S07 --es fare 2.50 --es eta 5.72 --es km 1.4
 * adb shell am start -n <pkg>/.FakeOfferActivity --es cmd home
 * ```
 *
 * `fare` may be `none` for an offer with no price. On fake Jeeny, `kmDigits latin`
 * writes the distance in Western digits (the default is Arabic-Indic; captures show both). Each offer is timed from the
 * first frame it is drawn in to the moment its accept button receives a click,
 * and ends in exactly one logcat line:
 *
 * ```
 * FAKERIDE result id=S07 pkg=... fare=2.50 eta=5.72 km=1.4 accepted=true pressed=accept latencyMs=41 sinceShowMs=58
 * ```
 *
 * The measurement is the ride app's own view of it — draw to click — so it
 * includes event delivery, AutoFare's detection, its decision and the dispatch of
 * the click back into this process. Nothing AutoFare reports about itself is used.
 */
abstract class FakeRideActivity : Activity() {

    /** What one offer says on screen. */
    class Offer(
        val id: String,
        /** Null for a ride with no price. */
        val fare: Double?,
        val eta: String,
        val km: String,
        /** Made-up addresses, unique per offer so AutoFare sees each as a new ride. */
        val pickup: String,
        val dropoff: String,
    )

    /** The app's name for the home screen and the log. */
    protected abstract val appLabel: String

    /** The offer screen, wired so [onAccept] and [onReject] are called on a press. */
    protected abstract fun buildOffer(offer: Offer): View

    private val main = Handler(Looper.getMainLooper())

    private var current: Offer? = null
    private var shownAt = 0L
    private var drawnAt = 0L
    private var done = true
    private var timeoutMs = DEFAULT_TIMEOUT_MS

    private val timeout = Runnable { finish(accepted = false, pressed = "none") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.getStringExtra("cmd")) {
            "offer" -> showOffer(intent)
            else -> showHome()
        }
    }

    /* ───────────────────────────── screens ───────────────────────────── */

    private fun showHome() {
        abandonOffer()
        setContentView(column(Color.WHITE).apply {
            addView(big("$appLabel (تجريبي)", 30f, Color.DKGRAY))
            addView(big("بانتظار طلب تجريبي", 22f, Color.GRAY))
            addView(big("للمحاكي بس · ممنوع على تلفون سائق حقيقي", 14f, Color.GRAY))
        })
    }

    private fun showOffer(intent: Intent) {
        abandonOffer()
        val id = intent.getStringExtra("id") ?: "X${SystemClock.uptimeMillis() % 100000}"
        val fareArg = intent.getStringExtra("fare") ?: "2.50"
        val offer = Offer(
            id = id,
            fare = if (fareArg == "none") null else fareArg.toDouble(),
            eta = intent.getStringExtra("eta") ?: "4",
            km = intent.getStringExtra("km") ?: "1.2",
            // the id is folded into both addresses so no two offers share a fingerprint
            pickup = "شارع التجربة ${arabicDigits(id)}، حي الاختبار",
            dropoff = "دوار الوهمي ${arabicDigits(id)}، عمّان",
        )
        timeoutMs = intent.getStringExtra("timeout")?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS

        current = offer
        done = false
        drawnAt = 0L
        shownAt = SystemClock.uptimeMillis()

        val view = buildOffer(offer)
        setContentView(view)
        view.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (drawnAt == 0L) drawnAt = SystemClock.uptimeMillis()
                // cannot remove a draw listener from inside onDraw
                main.post { view.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(this) }
            }
        })
        Log.i(TAG, "shown id=$id pkg=$packageName fare=${fmtFare(offer.fare)} eta=${offer.eta} km=${offer.km}")
        main.postDelayed(timeout, timeoutMs)
    }

    private fun showResult(offer: Offer, accepted: Boolean, pressed: String, latency: Long?) {
        val bg = if (accepted) Color.rgb(0x1A, 0x7F, 0x37) else Color.rgb(0xB3, 0x26, 0x1E)
        val headline = when {
            accepted -> "✓ انقبل خلال ${seconds(latency ?: 0)} ثانية"
            pressed == "reject" -> "✗ انضغط رفض!"
            else -> "✗ ما انقبل"
        }
        val fare = offer.fare?.let { "${arabicDigits(fmtFare(it))} د.أ" } ?: "بدون سعر"
        // at the bottom: AutoFare's own accept card covers the top half after a press
        setContentView(column(bg).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(96))
            addView(big(headline, 40f, Color.WHITE))
            addView(big(arabicDigits(offer.id), 22f, Color.WHITE))
            addView(big("السعر $fare · يبعد ${arabicDigits(offer.eta)} دقيقة · ${arabicDigits(offer.km)} كم", 20f, Color.WHITE))
            addView(big("$appLabel (تجريبي)", 16f, Color.WHITE))
        })
    }

    /* ───────────────────────────── presses ───────────────────────────── */

    /** Call from the accept button's click listener. */
    protected fun onAccept() = finish(accepted = true, pressed = "accept")

    /** Call from the reject button's click listener. AutoFare must never do this. */
    protected fun onReject() = finish(accepted = false, pressed = "reject")

    private fun finish(accepted: Boolean, pressed: String) {
        val offer = current ?: return
        if (done) return
        done = true
        val at = SystemClock.uptimeMillis()
        main.removeCallbacks(timeout)

        // a press that beat the first frame counts from the frame that never came: 0
        val latency = if (pressed == "none") null else (at - (drawnAt.takeIf { it != 0L } ?: at)).coerceAtLeast(0)
        val sinceShow = if (pressed == "none") null else at - shownAt

        Log.i(
            TAG,
            "result id=${offer.id} pkg=$packageName fare=${fmtFare(offer.fare)} eta=${offer.eta} " +
                "km=${offer.km} accepted=$accepted pressed=$pressed " +
                "latencyMs=${latency ?: -1} sinceShowMs=${sinceShow ?: -1}",
        )
        showResult(offer, accepted, pressed, latency)
    }

    /** A new command arriving mid-offer ends that offer, on the record, rather than silently. */
    private fun abandonOffer() {
        if (!done) finish(accepted = false, pressed = "none")
        main.removeCallbacks(timeout)
        current = null
    }

    /* ───────────────────────────── helpers ───────────────────────────── */

    /** Milliseconds as seconds in Arabic-Indic digits: 140 -> ٠٫١٤ */
    private fun seconds(ms: Long) = arabicDigits(String.format(Locale.US, "%.2f", ms / 1000.0))

    /**
     * Western digits to Arabic-Indic, and a decimal point between digits to ٫.
     * Only for text the detectors do not depend on, or fields the real app's
     * captures show in Arabic-Indic (Jeeny's distance: `يبعُد ٢٫٧كم`).
     */
    protected fun arabicDigits(s: String): String {
        val out = StringBuilder(s.length)
        s.forEachIndexed { i, ch ->
            out.append(
                when {
                    ch in '0'..'9' -> '٠' + (ch - '0')
                    ch == '.' && i > 0 && i < s.length - 1 && s[i - 1].isDigit() && s[i + 1].isDigit() -> '٫'
                    else -> ch
                },
            )
        }
        return out.toString()
    }

    protected fun fmtFare(fare: Double?): String =
        fare?.let { String.format(Locale.US, "%.2f", it) } ?: "none"

    protected fun column(bg: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(bg)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    protected fun big(s: String, sp: Float, color: Int) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setPadding(24, 16, 24, 16)
    }

    protected fun text(s: String, sp: Float = 18f, color: Int = Color.BLACK, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 6, 8, 6)
        }

    protected fun wrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    protected fun full(h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "FAKERIDE"
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
