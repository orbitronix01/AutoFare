package jo.autofare

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

import kotlinx.coroutines.delay
import jo.autofare.data.AppPrefs
import jo.autofare.data.License
import jo.autofare.data.LicenseStore
import jo.autofare.ui.AppScaffold
import jo.autofare.ui.LocalThemeController
import jo.autofare.ui.ThemeController
import jo.autofare.ui.license.LockScreen
import jo.autofare.ui.onboard.OnboardingScreen
import jo.autofare.ui.splash.SplashScreen
import jo.autofare.ui.theme.AutoFareTheme
import jo.autofare.ui.theme.LocalAfColors

/** Reset only when the process dies — i.e. on a genuine cold start. */
private var splashPlayed = false

class MainActivity : ComponentActivity() {

    // the floating button hides while this is on screen — see AppVisibility
    override fun onStart() {
        super.onStart()
        jo.autofare.service.AppVisibility.set(true)
    }

    override fun onStop() {
        jo.autofare.service.AppVisibility.set(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the status/navigation bars; each screen pads itself with
        // the real insets so it lays out correctly on notches and gesture bars.
        // Fully transparent bars — the default adds a light scrim that reads as a
        // white strip against our warm paper background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        val prefs = AppPrefs(this)
        val licenseStore = LicenseStore(this)

        setContent {
            var followSystem by remember { mutableStateOf(prefs.followSystemTheme) }
            var dark by remember { mutableStateOf(prefs.darkTheme) }
            val systemDark = isSystemInDarkTheme()
            val useDark = if (followSystem) systemDark else dark

            val controller = ThemeController(
                followSystem = followSystem,
                dark = if (followSystem) systemDark else dark,
                setFollowSystem = { followSystem = it; prefs.followSystemTheme = it },
                setDark = { dark = it; prefs.darkTheme = it },
            )

            AutoFareTheme(darkTheme = useDark) {
                // The whole product is Arabic — force RTL regardless of device locale.
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalThemeController provides controller,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = LocalAfColors.current.paper,
                    ) {
                        // Process-scoped, deliberately NOT rememberSaveable: the splash
                        // should play once per cold start, survive a rotation, and never
                        // replay just because Android recreated the activity.
                        var showSplash by remember { mutableStateOf(!splashPlayed) }
                        var onboarded by remember { mutableStateOf(prefs.onboarded) }
                        val license = rememberLicense(licenseStore)

                        val stage = when {
                            showSplash -> Stage.Splash
                            !license.unlocked -> Stage.Locked
                            !onboarded -> Stage.Onboarding
                            else -> Stage.App
                        }

                        Crossfade(targetState = stage, label = "stage") { s ->
                            when (s) {
                                Stage.Splash -> SplashScreen(onFinished = {
                                    splashPlayed = true
                                    showSplash = false
                                })
                                Stage.Locked -> LockScreen(
                                    state = license.state,
                                    checking = license.checking,
                                    onRetry = license.retry,
                                )
                                Stage.Onboarding -> OnboardingScreen(onDone = {
                                    prefs.onboarded = true
                                    onboarded = true
                                })
                                Stage.App -> AppScaffold()
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class Stage { Splash, Locked, Onboarding, App }

private class LicenseView(
    val state: License.State,
    val unlocked: Boolean,
    val checking: Boolean,
    val retry: () -> Unit,
)

/**
 * The gate's view of the subscription, kept live.
 *
 * Three things can change it while the app is open: a check-in coming back
 * (seen through the store's listener), the last good answer going stale with no
 * write at all (seen by re-deriving every few seconds), and the driver coming
 * back from somewhere else (a fresh check-in on every resume).
 */
@Composable
private fun rememberLicense(store: LicenseStore): LicenseView {
    val context = LocalContext.current
    fun read() = License.state(store) to License.unlocked(store)

    var snapshot by remember { mutableStateOf(read()) }
    // only the first check of a launch shows "لحظة"; later ones happen silently
    var checking by remember { mutableStateOf(!snapshot.second) }

    val check = remember {
        {
            License.checkAsync(context) {
                runOnMain { checking = false; snapshot = read() }
            }
        }
    }

    DisposableEffect(store) {
        val listener = store.observe { snapshot = read() }
        onDispose { store.stopObserving(listener) }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) check()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        var ticks = 0L
        while (true) {
            delay(10_000)
            snapshot = read()
            ticks += 10_000
            if (ticks >= License.CHECK_MS) { ticks = 0; check() }
        }
    }

    return LicenseView(snapshot.first, snapshot.second, checking, retry = { checking = true; check() })
}

private fun runOnMain(block: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(block)
