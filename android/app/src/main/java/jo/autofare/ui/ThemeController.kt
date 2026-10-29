package jo.autofare.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets any screen read and change the app's light/dark preference without
 * threading callbacks through every composable.
 */
data class ThemeController(
    val followSystem: Boolean,
    val dark: Boolean,
    val setFollowSystem: (Boolean) -> Unit,
    val setDark: (Boolean) -> Unit,
)

val LocalThemeController = staticCompositionLocalOf {
    ThemeController(true, false, {}, {})
}
