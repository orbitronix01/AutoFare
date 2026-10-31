package jo.autofare.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import jo.autofare.data.History
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read something out of [History], off the main thread, and re-read it whenever
 * the service writes a new decision.
 *
 * Returns null until the first read lands. Screens treat that as "not known yet"
 * and show a dash — never a zero, because a zero is a claim and this is an
 * absence of one.
 */
@Composable
fun <T> rememberHistory(load: (History) -> T): T? {
    val context = LocalContext.current
    val history = remember(context) { History.get(context) }
    val version by history.version.collectAsState()

    var value by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(version) {
        value = withContext(Dispatchers.IO) { load(history) }
    }
    return value
}
