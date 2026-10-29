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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import jo.autofare.data.AppPrefs
import jo.autofare.ui.AppScaffold
import jo.autofare.ui.LocalThemeController
import jo.autofare.ui.ThemeController
import jo.autofare.ui.onboard.OnboardingScreen
import jo.autofare.ui.splash.SplashScreen
import jo.autofare.ui.theme.AutoFareTheme
import jo.autofare.ui.theme.LocalAfColors

/** Reset only when the process dies — i.e. on a genuine cold start. */
private var splashPlayed = false

class MainActivity : ComponentActivity() {
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

                        val stage = when {
                            showSplash -> Stage.Splash
                            !onboarded -> Stage.Onboarding
                            else -> Stage.App
                        }

                        Crossfade(targetState = stage, label = "stage") { s ->
                            when (s) {
                                Stage.Splash -> SplashScreen(onFinished = {
                                    splashPlayed = true
                                    showSplash = false
                                })
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

private enum class Stage { Splash, Onboarding, App }
