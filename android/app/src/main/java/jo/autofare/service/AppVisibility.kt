package jo.autofare.service

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Whether AutoFare's own activity is on screen, told by the activity itself.
 *
 * The floating button is for use *inside* Jeeny and Petra. Over AutoFare it
 * covered every screen's title and offered a switch the screen underneath was
 * already showing. The service and the activity share a process, so the
 * activity simply says when it starts and stops — a push, not a poll, and
 * nothing anywhere near the accept path: the listener only repaints the bubble.
 *
 * Started/stopped rather than resumed/paused, so the bubble does not flicker
 * back for the instant a system dialog takes focus.
 */
object AppVisibility {

    @Volatile
    var inForeground: Boolean = false
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** Called from the activity's lifecycle, on the main thread. */
    fun set(visible: Boolean) {
        if (inForeground == visible) return
        inForeground = visible
        listeners.forEach { it() }
    }

    fun observe(listener: () -> Unit) {
        listeners += listener
    }

    fun stopObserving(listener: () -> Unit) {
        listeners -= listener
    }
}
