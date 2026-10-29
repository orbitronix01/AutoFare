package jo.autofare.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where learning-mode captures live.
 *
 * App-private external storage, so no storage permission is needed and the files
 * disappear when the app is uninstalled. Nothing here leaves the phone unless the
 * driver taps share.
 */
class CaptureStore(context: Context) {

    private val base: File = context.getExternalFilesDir(null) ?: context.filesDir

    /** Raw per-screen captures, and nothing else — see [exportDir]. */
    private val dir: File = File(base, "captures").apply { mkdirs() }

    /**
     * The shared bundle gets its own folder.
     *
     * It used to be written into [dir], where [files] picked it up as just another
     * capture: every export swallowed the previous export, and the stale copy ate a
     * slot in the prune budget. The first driver file came back with five nested
     * copies of itself inside it.
     */
    private val exportDir: File = File(base, "exports").apply { mkdirs() }

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    private val exportStamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)

    init {
        // a build from before the move left its bundle among the captures
        File(dir, LEGACY_EXPORT_NAME).delete()
    }

    /**
     * @param keep protects the file from pruning — the first sighting of a layout, or
     *   a screen that reads like a ride offer. See AutoFareService for why.
     */
    fun save(packageName: String, body: String, keep: Boolean) {
        val safePkg = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val suffix = if (keep) ".keep.txt" else ".txt"
        File(dir, "${stamp.format(Date())}_$safePkg$suffix").writeText(body)
        prune()
    }

    /** Oldest first: the timestamp prefix sorts chronologically. */
    fun files(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }?.sortedBy { it.name } ?: emptyList()

    fun count(): Int = files().size

    /** Screens we have marked as unlosable — the number the driver can report back. */
    fun keepCount(): Int = files().count { isKeep(it) }

    /** How many screens we hold per app — the ride app should be in here. */
    fun countsByPackage(): Map<String, Int> =
        files().groupingBy { packageOf(it) }.eachCount()

    fun keepCountsByPackage(): Map<String, Int> =
        files().filter { isKeep(it) }.groupingBy { packageOf(it) }.eachCount()

    fun clear() {
        files().forEach { it.delete() }
        exportDir.listFiles()?.forEach { it.delete() }
    }

    /** One file is far easier for a driver to send than forty. */
    fun export(): File? {
        val all = files()
        if (all.isEmpty()) return null

        /*
         * A fresh name every time, and the old bundles deleted.
         *
         * The previous build always wrote `autofare-captures.txt`, which is how a
         * driver ends up re-sending yesterday's file by mistake over WhatsApp — and
         * how it lands in Downloads on top of the copy we are still working from.
         */
        exportDir.listFiles()?.forEach { it.delete() }
        val out = File(exportDir, "autofare-captures-${exportStamp.format(Date())}.txt")

        val keeps = all.count { isKeep(it) }
        out.writeText(buildString {
            appendLine("AutoFare learning capture")
            appendLine("captures: ${all.size}  (marked: $keeps)")
            appendLine("exported: ${Date()}")
            appendLine()
            appendLine("per app  (marked / total):")
            val totals = countsByPackage()
            val marked = keepCountsByPackage()
            totals.entries.sortedByDescending { it.value }.forEach { (pkg, n) ->
                appendLine("  ${marked[pkg] ?: 0} / $n  $pkg")
            }
            appendLine()
            appendLine("MARKED means: first sighting of a layout, or the screen said accept/decline.")
            appendLine("Each capture's `why:` line says which rule saved it.")
            appendLine()
            all.forEach { f ->
                appendLine("================================================")
                appendLine("FILE: ${f.name}")
                appendLine("================================================")
                appendLine(f.readText())
                appendLine()
            }
        })
        return out
    }

    /** Captures are named `<stamp>_<package>[.keep].txt`; the stamp holds no underscore. */
    private fun packageOf(file: File): String =
        file.name.substringAfter('_', "")
            .removeSuffix(".txt")
            .removeSuffix(".keep")
            .ifBlank { "unknown" }

    private fun isKeep(file: File): Boolean = file.name.endsWith(".keep.txt")

    /**
     * Keep a long shift from filling the phone — per app, and never at the expense of
     * a screen we cannot get back.
     *
     * A global cap is what cost us the first driver session: his phone sat on the
     * Always-On Display for three hours, and 287 of the 300 slots went to the clock.
     * A per-app quota fixed that but not the next failure, which is an app flooding
     * itself: during a trip the navigation screen rewrites its ETA every second, and
     * those repeats would evict the offer that started the trip. So repeats are
     * deleted first, and a marked screen is only touched if marked screens alone have
     * overrun the quota.
     */
    private fun prune() {
        files().groupBy { packageOf(it) }.forEach { (_, forPkg) -> trim(forPkg, PER_PACKAGE) }
        trim(files(), TOTAL)
    }

    /** [group] arrives oldest-first, which is the order we delete in. */
    private fun trim(group: List<File>, cap: Int) {
        var over = group.size - cap
        if (over <= 0) return
        for (f in group.filterNot { isKeep(it) }) {
            if (over <= 0) return
            if (f.delete()) over--
        }
        for (f in group.filter { isKeep(it) }) {
            if (over <= 0) return
            if (f.delete()) over--
        }
    }

    private companion object {
        const val LEGACY_EXPORT_NAME = "autofare-captures.txt"
        const val PER_PACKAGE = 300
        const val TOTAL = 1500
    }
}
