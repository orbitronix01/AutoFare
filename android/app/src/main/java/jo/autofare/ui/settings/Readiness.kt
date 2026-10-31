package jo.autofare.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import jo.autofare.service.AutoFareService

/**
 * What the phone will actually let AutoFare do, read from the system.
 *
 * These three rows used to be literals — `ready = true` for accessibility,
 * whatever the real state was. On EMUI the system switches the service off after
 * every install, so the screen was telling a driver he was covered while the app
 * was accepting nothing. A readiness screen that cannot be wrong is the whole
 * point of a readiness screen.
 */
data class Readiness(
    /** The service is switched on in Android's accessibility settings. */
    val accessibility: Boolean,
    /** The system has been told not to doze this app. Huawei and Xiaomi ignore it anyway. */
    val battery: Boolean,
    /** Draw-over-other-apps, for the floating button. */
    val overlay: Boolean,
    /**
     * Notifications, for the status line and the "losing rides" alarm. Android 13
     * and up blocks them until the driver allows it, and nothing else asks.
     */
    val notifications: Boolean = true,
) {
    /** Only the first one stops the app working; the others degrade it. */
    val blocking: Boolean get() = !accessibility
}

fun readReadiness(context: Context): Readiness {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return Readiness(
        accessibility = AutoFareService.isEnabled(context),
        battery = power?.isIgnoringBatteryOptimizations(context.packageName) ?: false,
        overlay = Settings.canDrawOverlays(context),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )
}

/**
 * Live readiness, re-read every time the screen comes back to the foreground.
 *
 * That matters more than it sounds: the only way to fix any of these is to leave
 * for a system settings screen and come back, so a value read once at first
 * composition is stale exactly when the driver is looking at it.
 */
@Composable
fun rememberReadiness(): Readiness {
    val context = LocalContext.current
    var state by remember { mutableStateOf(readReadiness(context)) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = readReadiness(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

/* ─────────────────── taking the driver to the fix ─────────────────── */

fun openAccessibilitySettings(context: Context) =
    context.safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

/**
 * The battery screen.
 *
 * Deliberately the *list*, not `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`:
 * that one is a direct-ask intent Google treats as policy-sensitive, and on the
 * Huawei and Xiaomi builds that matter here the real control is a vendor screen
 * the standard dialog does not touch anyway.
 */
fun openBatterySettings(context: Context) {
    val ok = context.safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    if (!ok) context.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    })
}

/** The app's own notification screen: one switch, the same on every Android we support. */
fun openNotificationSettings(context: Context) {
    val ok = context.safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    if (!ok) context.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    })
}

fun openOverlaySettings(context: Context) {
    val ok = context.safeStart(Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.fromParts("package", context.packageName, null),
    ))
    if (!ok) context.safeStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
}

/**
 * Start an intent without trusting the OEM to have the screen.
 *
 * Huawei ships a settings app with pieces missing, and an ActivityNotFound here
 * would crash the app on the one screen a stuck driver was sent to.
 */
private fun Context.safeStart(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    false
}
