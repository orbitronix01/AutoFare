package jo.autofare.auto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The last few decisions the service made, for the driver to look at.
 *
 * In-memory and deliberately small. This is not the earnings history — it is the
 * answer to "the app just did something, what was it", which is the question a
 * driver asks in the thirty seconds after an offer disappears off his screen.
 * It survives the app's UI being closed, because the service keeps running; it
 * does not survive the service being killed, and does not need to.
 *
 * A process-wide object rather than something injected, because the writer is an
 * accessibility service and the reader is an activity, and the two have no
 * lifecycle in common.
 */
object DecisionLog {

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    /** One decision, stamped with the wall clock so the UI can say "منذ دقيقتين". */
    data class Entry(
        val at: Long,
        val outcome: AutoAccept.Outcome,
    )

    fun add(outcome: AutoAccept.Outcome, at: Long) {
        _entries.update { current ->
            (listOf(Entry(at, outcome)) + current).take(MAX)
        }
    }

    fun clear() = _entries.update { emptyList() }

    private const val MAX = 40
}
