package jo.autofare.service

import android.view.accessibility.AccessibilityNodeInfo
import jo.autofare.detect.Node
import jo.autofare.detect.Screen

/**
 * The thin layer between Android's accessibility tree and the detectors.
 *
 * All it does is forward. The reason it exists is that `AccessibilityNodeInfo`
 * cannot be constructed in a unit test, so keeping every judgement on the far side
 * of [Screen] and [Node] is what lets the detectors be proven against a real
 * driver's capture on a laptop.
 *
 * Nodes are not recycled here. `recycle()` has been a no-op since API 33 and the
 * press path is measured in milliseconds; a handful of short-lived wrappers per
 * offer is the cheaper mistake.
 */
class NodeScreen(
    private val root: AccessibilityNodeInfo,
    override val pkg: String,
) : Screen {

    /** Backed by the platform's id index, so this is a lookup and not a search. */
    override fun byId(viewId: String): List<Node> =
        runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }
            .getOrNull()
            ?.filterNotNull()
            ?.map(::InfoNode)
            .orEmpty()

    /** Matches text and content description, case-insensitively, as the platform does. */
    override fun byText(text: String): List<Node> =
        runCatching { root.findAccessibilityNodeInfosByText(text) }
            .getOrNull()
            ?.filterNotNull()
            ?.map(::InfoNode)
            .orEmpty()

    override fun walk(): Sequence<Node> = sequence {
        suspend fun SequenceScope<Node>.visit(info: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return
            yield(InfoNode(info))
            for (i in 0 until info.childCount) {
                val child = info.getChild(i) ?: continue
                visit(child, depth + 1)
            }
        }
        visit(root, 0)
    }

    private companion object {
        const val MAX_DEPTH = 40
    }
}

private class InfoNode(private val info: AccessibilityNodeInfo) : Node {

    override val viewId: String? get() = info.viewIdResourceName
    override val text: String? get() = info.text?.toString()
    override val desc: String? get() = info.contentDescription?.toString()
    override val className: String? get() = info.className?.toString()
    override val clickable: Boolean get() = info.isClickable
    override val enabled: Boolean get() = info.isEnabled

    override val parent: Node? get() = info.parent?.let(::InfoNode)

    override val children: List<Node>
        get() = (0 until info.childCount).mapNotNull { info.getChild(it)?.let(::InfoNode) }

    /**
     * `ACTION_CLICK`, never a tap at a coordinate.
     *
     * The node is asked to click itself, so screen size, density, font scale and
     * fold state are all irrelevant — which is the only reason this works at all
     * on a Z Fold 4 that reports the offer card with a negative right edge.
     */
    override fun click(): Boolean =
        runCatching { info.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
}
