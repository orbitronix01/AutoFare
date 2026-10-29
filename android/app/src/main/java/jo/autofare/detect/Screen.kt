package jo.autofare.detect

/**
 * What the detector is allowed to know about the screen.
 *
 * Deliberately free of `AccessibilityNodeInfo`: the detectors are the part of this
 * app that must be right on the first real offer a driver ever gets, and a driver's
 * shift is a terrible place to find out they are wrong. Behind this interface they
 * can be run on a laptop against the exact strings a real Jeeny and a real Petra
 * Ride popup produced — see the tests.
 *
 * The lookups are shaped after the two platform calls that are actually fast.
 * [byId] and [byText] map onto `findAccessibilityNodeInfosByViewId` and
 * `findAccessibilityNodeInfosByText`, which search inside the system's own indexes;
 * [walk] is a manual tree traversal and costs real time, so it exists for the app
 * that gives us no ids and is not used by the one that does.
 */
interface Screen {

    /** The package that owns this window. */
    val pkg: String

    /** Nodes with this fully-qualified view id — `pkg:id/accept_button`. */
    fun byId(viewId: String): List<Node>

    /** Nodes whose text or description contains this substring, as the platform matches it. */
    fun byText(text: String): List<Node>

    /** Every node, parents before children. The expensive one. */
    fun walk(): Sequence<Node>
}

/** One element on screen. */
interface Node {

    val viewId: String?
    val text: String?
    val desc: String?
    val className: String?
    val clickable: Boolean
    val enabled: Boolean
    val parent: Node?
    val children: List<Node>

    /** Press this node. */
    fun click(): Boolean

    /** [text] if it says anything, otherwise [desc]. */
    val label: String? get() = text?.takeIf { it.isNotBlank() } ?: desc?.takeIf { it.isNotBlank() }

    /**
     * The node that will actually respond to a press.
     *
     * Jeeny's accept button is a `TextView` reading `قبول العرض` that is not itself
     * clickable — the `View` wrapping it is. Pressing the text does nothing at all,
     * which would look exactly like a detector that never fires. Petra Ride's is a
     * real `Button` and returns itself on the first step.
     *
     * Bounds are never consulted to decide this. A foldable in RTL reports the
     * offer card at `bounds=-209,1329,694,2316` — a right edge left of its left
     * edge — so anything resembling "the big button at the bottom" is guesswork
     * dressed up as geometry.
     */
    fun pressTarget(maxClimb: Int = 6): Node? {
        var node: Node? = this
        var steps = 0
        while (node != null && steps <= maxClimb) {
            if (node.clickable && node.enabled) return node
            node = node.parent
            steps++
        }
        return null
    }
}
