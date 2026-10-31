package jo.autofare.service.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import jo.autofare.data.AppPrefs
import jo.autofare.ui.theme.AutoFareTheme

/**
 * Compose, drawn over another app from inside the accessibility service.
 *
 * An Activity hands Compose a lifecycle and a saved-state registry for free; a
 * window added from a Service has neither, and `ComposeView` refuses to compose
 * without them. This is the smallest owner that satisfies it: created, resumed,
 * destroyed with the window, and nothing saved, because an overlay that lives
 * for two seconds has nothing worth restoring.
 */
internal class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performRestore(null as Bundle?)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * True when Android will let this app draw over the ride app.
 *
 * Checked before every add rather than trusted from the readiness screen: the
 * driver can revoke it in system settings mid-shift, and an add without it is a
 * crash in the service that accepts his rides.
 */
internal fun canDrawOverlays(context: Context): Boolean =
    runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

/**
 * The overlay follows the app's theme choice, not just the phone's.
 *
 * Light by default, like the app. A driver who picked dark in AutoFare expects
 * the card to match what he picked, not to flip because the phone's own setting
 * says otherwise.
 */
internal fun overlayDark(context: Context, prefs: AppPrefs): Boolean =
    if (prefs.followSystemTheme) {
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    } else prefs.darkTheme

/** A ComposeView wired to its own [OverlayOwner], themed like the app. */
internal fun overlayView(
    context: Context,
    owner: OverlayOwner,
    dark: Boolean,
    content: @Composable () -> Unit,
): ComposeView = ComposeView(context).apply {
    setViewTreeLifecycleOwner(owner)
    setViewTreeSavedStateRegistryOwner(owner)
    // disposed by hand when the window is removed, not by Compose guessing
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    setContent {
        AutoFareTheme(darkTheme = dark) {
            // RTL whatever the phone's locale, as in MainActivity — the overlay is
            // the same product, and Arabic laid out left-to-right reads as broken
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
        }
    }
}

/**
 * Window parameters shared by both overlays.
 *
 * - `TYPE_APPLICATION_OVERLAY`, the only overlay type an app may use from 26 on.
 * - `NOT_FOCUSABLE`: the ride app keeps the keyboard and the back button. It
 *   also keeps our window from ever becoming the *active* window, which matters:
 *   the accept path reads `rootInActiveWindow`, and that must stay the ride app.
 * - `NOT_TOUCH_MODAL`: the window is sized to its own content, and a touch
 *   anywhere outside it goes to the ride app as if we were not there.
 */
internal fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams =
    WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

/** The full screen, in pixels — for keeping a window on it, never for finding anything. */
internal fun screenSize(wm: WindowManager): Pair<Int, Int> =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        val b = wm.currentWindowMetrics.bounds
        b.width() to b.height()
    } else {
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        p.x to p.y
    }

/** Remove a view we may or may not still have on screen, without throwing. */
internal fun WindowManager.removeQuietly(view: View?) {
    if (view == null) return
    runCatching { removeViewImmediate(view) }
}
