package jo.autofare.detect

/**
 * A [Screen] built from a learning-mode capture, so the detectors can be tested
 * against screens that really happened.
 *
 * The format parsed here is exactly what `AutoFareService.walk` writes and what
 * the driver sends back over WhatsApp:
 *
 * ```
 * android.widget.Button id=com.PetraRide_Captain:id/accept_button text="قبول" CLICKABLE bounds=64,2092,840,2222
 * ```
 *
 * Indentation gives the tree. The dump only records nodes that say something or
 * can be pressed, so the depths it contains jump — a child can be two levels in
 * from its parent — and a node's parent is therefore the nearest line above it
 * that is indented less. That is enough for the one structural thing a detector
 * does: climb from the accept button to the card around it.
 *
 * The point of this file is that the next capture a driver sends can be pasted
 * into a test as-is. No transcribing, so no chance of fixing a bug against a
 * screen that was quietly cleaned up on the way in.
 */
class CaptureTree private constructor(
    override val pkg: String,
    private val nodes: List<FakeNode>,
) : Screen {

    override fun byId(viewId: String): List<Node> = nodes.filter { it.viewId == viewId }

    /** Substring over text and description, case-insensitive — what the platform does. */
    override fun byText(text: String): List<Node> {
        val needle = text.lowercase()
        return nodes.filter {
            it.text?.lowercase()?.contains(needle) == true ||
                it.desc?.lowercase()?.contains(needle) == true
        }
    }

    override fun walk(): Sequence<Node> = nodes.asSequence()

    /** Every press this screen received, in order, as `id or label`. */
    val presses = mutableListOf<String>()

    companion object {

        /**
         * @param dump the indented node lines of one capture — the part after `---`.
         */
        fun of(pkg: String, dump: String): CaptureTree {
            val parsed = dump.lines()
                .filter { it.isNotBlank() && it.contains("bounds=") }
                .map { Parsed(indentOf(it), it.trim()) }

            val nodes = parsed.map { FakeNode(it.line) }
            val stack = ArrayDeque<Int>()   // indexes of candidate parents

            parsed.forEachIndexed { i, row ->
                while (stack.isNotEmpty() && parsed[stack.last()].indent >= row.indent) {
                    stack.removeLast()
                }
                stack.lastOrNull()?.let { parentIndex ->
                    nodes[i].parentNode = nodes[parentIndex]
                    nodes[parentIndex].kids += nodes[i]
                }
                stack.addLast(i)
            }

            val screen = CaptureTree(pkg, nodes)
            nodes.forEach { it.screen = screen }
            return screen
        }

        private fun indentOf(line: String) = line.length - line.trimStart().length

        private class Parsed(val indent: Int, val line: String)
    }
}

/** One line of a capture, as a [Node]. */
class FakeNode(line: String) : Node {

    override val className: String? = line.substringBefore(' ').ifBlank { null }
    override val viewId: String? = ID.find(line)?.groupValues?.get(1)
    override val text: String? = TEXT.find(line)?.groupValues?.get(1)
    override val desc: String? = DESC.find(line)?.groupValues?.get(1)
    override val clickable: Boolean = line.contains(" CLICKABLE")

    /**
     * Captures record `hidden` for a node the system says is not visible to the
     * user, and the Z Fold 4 applied it to a Reject button that was plainly on
     * screen and clickable. So visibility is recorded and never acted on; enabled
     * is true because the dump has no column for it.
     */
    val hidden: Boolean = line.contains(" hidden")
    override val enabled: Boolean = true

    internal var parentNode: FakeNode? = null
    internal val kids = mutableListOf<FakeNode>()
    internal var screen: CaptureTree? = null

    override val parent: Node? get() = parentNode
    override val children: List<Node> get() = kids

    override fun click(): Boolean {
        screen?.presses?.add(viewId ?: label ?: "?")
        return true
    }

    private companion object {
        val ID = Regex("""\bid=(\S+)""")
        val TEXT = Regex("""\btext="([^"]*)"""")
        val DESC = Regex("""\bdesc="([^"]*)"""")
    }
}
