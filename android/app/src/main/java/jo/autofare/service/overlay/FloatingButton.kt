package jo.autofare.service.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import jo.autofare.R
import jo.autofare.data.AppPrefs
import jo.autofare.ui.theme.LocalAfColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating button — الزر العائم — over the ride app.
 *
 * The driver arms AutoFare once and then lives inside Jeeny or Petra. Without
 * this, stopping it means leaving the ride app, finding ours, and coming back,
 * which is exactly the moment an offer arrives. The bubble says, at a glance,
 * whether it is taking rides, and one tap changes that (see [bubbleTap]).
 *
 * Drawn and moved only. It never reads anything off the ride app's screen, and
 * where it sits has no bearing on what gets accepted — the accept path finds
 * buttons by id and text and presses them through accessibility, which no
 * window on top can get in the way of.
 *
 * Dragged with the platform's raw coordinates rather than Compose gestures: the
 * window moves under the finger, and a gesture measured inside the window that
 * is moving reports the window's own motion back as finger motion, so the
 * bubble shudders. Compose only paints it.
 */
class FloatingButton(
    private val context: Context,
    private val prefs: AppPrefs,
    private val onTap: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val windowPx = (WINDOW_DP * density).roundToInt()

    private var view: android.view.View? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var shownDark: Boolean? = null

    /** Read by the composition; written on the main thread by [update]. */
    private var armed by mutableStateOf(false)

    val showing: Boolean get() = view != null

    /**
     * Put the bubble up, take it down, or just repaint it.
     *
     * Idempotent and cheap when nothing changed, so the service can call it from
     * every place the answer might have moved — a rule switch, the licence, the
     * setting, the permission — without keeping track of which.
     */
    fun update(visible: Boolean, armed: Boolean) {
        if (!visible || !canDrawOverlays(context)) {
            hide()
            return
        }
        this.armed = armed
        val dark = overlayDark(context, prefs)
        if (view != null && dark == shownDark) return
        hide()
        show(dark)
    }

    fun hide() {
        wm.removeQuietly(view)
        owner?.destroy()
        view = null
        owner = null
        params = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(dark: Boolean) {
        val o = OverlayOwner()
        val v = TouchShield(context).apply {
            addView(overlayView(context, o, dark) { Bubble(armed) })
            // ComposeView looks up its lifecycle from its parents too, but the
            // frame is the window's root, so give it the owners directly as well
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
        }

        val (sw, sh) = screenSize(wm)
        val (x, y) = prefs.bubblePosition
            ?: ((DEFAULT_START_DP * density).roundToInt() to sh - ((DEFAULT_BOTTOM_DP + WINDOW_DP) * density).roundToInt())
        val (cx, cy) = clampBubble(x, y, windowPx, sw, sh)

        val p = overlayParams(windowPx, windowPx).apply {
            // absolute, not START: a saved position must not mirror when the
            // phone's layout direction does
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = cx
            this.y = cy
            // whole-screen coordinates, so the clamp and the saved position mean
            // the same thing; otherwise y is counted from under the status bar and
            // a bubble clamped to the screen's height can sit off its bottom edge.
            // The cutout mode matters as much: without it a phone with a notch
            // shifts the whole window down by the notch's height regardless.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        v.setOnTouchListener(DragOrTap(p))

        val added = runCatching { wm.addView(v, p) }
            .onFailure { android.util.Log.w(TAG, "bubble not shown", it) }
            .isSuccess
        if (!added) {
            o.destroy()
            return
        }
        view = v
        owner = o
        params = p
        shownDark = dark
    }

    /** A drag moves it and remembers where; a touch that never travelled is a tap. */
    private inner class DragOrTap(private val p: WindowManager.LayoutParams) : android.view.View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val (sw, sh) = screenSize(wm)
                        val (nx, ny) = clampBubble(
                            startX + dx.roundToInt(), startY + dy.roundToInt(), windowPx, sw, sh,
                        )
                        p.x = nx; p.y = ny
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // one write when he lets go, not one per frame of the drag
                    if (dragging) prefs.bubblePosition = p.x to p.y
                    else {
                        v.performClick()
                        onTap()
                    }
                }
            }
            return true
        }
    }

    /**
     * Takes every touch before Compose sees it. The composition only paints;
     * left to itself, the inner ComposeView swallows the events and the drag
     * listener never hears about them.
     */
    private class TouchShield(context: Context) : android.widget.FrameLayout(context) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
    }

    private companion object {
        const val TAG = "AutoFareBubble"

        /** The 56 dp button plus room for its shadow. */
        const val WINDOW_DP = 76

        /** Where the design puts it before he has moved it: low, on the left. */
        const val DEFAULT_START_DP = 12
        const val DEFAULT_BOTTOM_DP = 120
    }
}

/**
 * The design's `.fab`: a raised 56 dp circle with a 1.5 dp ink ring and the
 * mark. The pip in the corner is the state — green while it is taking rides,
 * amber while it is not, the same pair the Home band uses — and the mark fades
 * back when it is off, so the answer survives a glance in sunlight.
 */
@androidx.compose.runtime.Composable
private fun Bubble(armed: Boolean) {
    val af = LocalAfColors.current
    val said = if (armed) "القبول التلقائي شغّال. اكبس لتوقّفه"
               else "القبول التلقائي مطفي. اكبس لتشغّله"
    Box(Modifier.fillMaxSize().semantics { contentDescription = said }, contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = af.raised,
            border = BorderStroke(1.5.dp, af.ink),
            shadowElevation = 4.dp,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.logo_mark),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (armed) af.ink else af.muted),
                    modifier = Modifier.width(29.dp),
                )
            }
        }
        // the pip sits on the ring, top corner, ringed in the raised colour so it
        // reads as a badge on the button rather than a dot beside it
        Surface(
            shape = CircleShape,
            color = if (armed) af.accept else af.flag,
            border = BorderStroke(2.dp, af.raised),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 12.dp)
                .size(15.dp),
        ) {}
    }
}
