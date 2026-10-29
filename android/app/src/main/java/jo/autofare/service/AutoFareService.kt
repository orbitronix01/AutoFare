package jo.autofare.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import jo.autofare.auto.AutoAccept
import jo.autofare.auto.DecisionLog
import jo.autofare.data.AppPrefs
import jo.autofare.data.CaptureStore
import jo.autofare.data.RulesStore
import jo.autofare.data.SUPPORTED
import jo.autofare.detect.DETECTORS
import jo.autofare.detect.OfferHints
import jo.autofare.rules.RuleSet
import java.util.Date

/**
 * Two jobs in one service, with opposite priorities.
 *
 * **Accepting rides** is the product, and it has a deadline measured against other
 * drivers pressing their own Accept buttons. It runs first, on every event, with
 * no throttle in front of it — see [tryAccept].
 *
 * **Learning mode** is the scaffolding that made accepting possible: it writes
 * down what a ride app puts on screen so the detectors could be written against
 * the exact element ids and the exact texts instead of guesses. It is throttled,
 * rationed and happy to be late. Nothing is uploaded; captures sit in the app's
 * own folder until the driver chooses to share them.
 *
 * It stays in the build. The detectors read Jeeny's offer by its wording, and the
 * day Jeeny rewords a label the only way to find out what it says now is to ask
 * the driver for another capture.
 *
 * ## Why the learning half is shaped the way it is
 *
 * Two driver sessions have been lost, both to the same mistake in different
 * clothes: rationing captures by the clock.
 *
 * The first cap was global, and three hours of Always-On Display spent it.
 * Budgeting per app fixed that — and then it became clear that during a trip the
 * ride app floods *itself*: a navigation screen rewrites its distance and ETA every
 * second, every rewrite is a new screen by text, and sixty of those recycle the
 * app's whole quota inside a minute. The offer that started the trip is deleted
 * before the driver has left the pickup.
 *
 * Worse, the old one-capture-per-second throttle was global and blind. An offer in
 * a competitive market can live under a second — another driver takes it — so a
 * frame saved 200 ms earlier for a map would drop the single screen this whole
 * exercise exists to collect.
 *
 * So nothing here is rationed by time any more. It is rationed by *shape*: the
 * tree's structure with every text value stripped out. A navigation screen keeps
 * one shape all shift no matter how its numbers churn, and collapses to a handful
 * of captures. An offer popup is a shape of its own, so the first one is recorded
 * the instant it appears, with no cooldown in front of it.
 */
class AutoFareService : AccessibilityService() {

    private lateinit var prefs: AppPrefs
    private lateinit var store: CaptureStore
    private lateinit var notifier: LearnNotifier

    private lateinit var rulesStore: RulesStore
    private lateinit var auto: AutoAccept

    /**
     * The driver's rules, held in memory and keyed by package.
     *
     * Never read from disk on the accept path. A SharedPreferences read is a file
     * read, and the gap between an offer appearing and the button being pressed is
     * the one place in this app where that is not affordable.
     */
    @Volatile private var rules: Map<String, RuleSet> = emptyMap()

    /** True when at least one app has auto-accept switched on. The cheapest gate we have. */
    @Volatile private var armed = false

    private var rulesListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** What we know about one on-screen layout, keyed by its shape. */
    private class Shape {
        var captures = 0
        var lastAt = 0L
        val recentText = ArrayDeque<Int>()
    }

    private val shapes = LinkedHashMap<Int, Shape>()
    private var offerCaptures = 0

    private var lastCaptureAt = 0L
    private var lastWalkAt = 0L
    private var lastNotifyAt = 0L
    private var learningWasOn = false
    private var epoch = Int.MIN_VALUE

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = AppPrefs(this)
        store = CaptureStore(this)
        notifier = LearnNotifier(this)

        rulesStore = RulesStore(this)
        auto = AutoAccept(
            rulesFor = { pkg -> rules[pkg] ?: RuleSet() },
            now = { SystemClock.elapsedRealtime() },
            log = { outcome -> DecisionLog.add(outcome, System.currentTimeMillis()) },
        )
        loadRules()
        rulesListener = rulesStore.observe {
            loadRules()
            // a rule change makes every earlier decision irrelevant
            auto.reset()
        }

        running = true
        epoch = prefs.captureEpoch
        learningWasOn = prefs.learningEnabled
        if (learningWasOn) notifier.show(store.count(), store.keepCount())
        android.util.Log.i(TAG, "connected · armed=$armed learning=$learningWasOn")
    }

    override fun onDestroy() {
        running = false
        rulesListener?.let { rulesStore.stopObserving(it) }
        if (::notifier.isInitialized) notifier.hide()
        super.onDestroy()
    }

    /**
     * Pull the rules into memory, mapped from package name to rule set.
     *
     * Only apps with a confirmed package are included. Accepting rides inside an
     * app we have merely guessed the name of is not a thing this should be able to
     * do by accident.
     */
    private fun loadRules() {
        val loaded = SUPPORTED
            .filter { it.verified && it.packageName in DETECTORS }
            .associate { it.packageName to rulesStore.get(it.id) }
        rules = loaded
        armed = loaded.values.any { it.enabled }
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::prefs.isInitialized) return

        /*
         * The accept path runs first, and runs whatever else is switched on. It is
         * the only thing in this service with a deadline: the offer is on screen
         * for as long as it takes another driver to press his own Accept, and
         * everything below — the throttles, the shape bookkeeping, the file write —
         * exists to serve learning mode and must never sit in front of it.
         */
        tryAccept(event)

        if (!prefs.learningEnabled) {
            if (learningWasOn) {
                learningWasOn = false
                notifier.hide()
            }
            return
        }

        /*
         * A fresh session must start with a fresh budget. Without this, a driver who
         * clears his captures and starts again gets nothing: the shapes from the
         * cleared run are still in memory, still holding spent per-shape counts, so
         * the service sits there deduping against files that no longer exist.
         */
        if (!learningWasOn || prefs.captureEpoch != epoch) {
            learningWasOn = true
            epoch = prefs.captureEpoch
            shapes.clear()
            offerCaptures = 0
            android.util.Log.i(TAG, "session reset · epoch=$epoch")
        }

        val now = SystemClock.elapsedRealtime()

        /*
         * A new window is the one event that may *be* the offer popup, so it is never
         * rate limited. Content changes are throttled only enough to keep us from
         * walking the tree hundreds of times a second — 250 ms is far below the life
         * of even a contested offer.
         */
        val newWindow = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!newWindow && now - lastWalkAt < WALK_FLOOR_MS) return
        if (now - lastCaptureAt < GLOBAL_FLOOR_MS) return
        lastWalkAt = now

        val eventPkg = event.packageName?.toString() ?: ""
        val root = pickRoot() ?: return

        val windowPkg = root.packageName?.toString() ?: eventPkg
        if (windowPkg.isEmpty() || windowPkg == packageName) return   // never record ourselves

        val watched = windowPkg in watchedPackages()
        if (!watched) {
            if (isSensitive(windowPkg)) return                // never record private apps
            if (isNoise(windowPkg)) return                    // lock screen, launcher, keyboard
            if (!prefs.captureAllApps) return
        }

        val walked = walk(root)
        val shapeKey = (windowPkg + '|' + walked.shape).hashCode()
        val textHash = walked.body.hashCode()
        val shape = shapes[shapeKey]

        // byte-identical screen we already hold: nothing to learn from it
        if (shape != null && textHash in shape.recentText) return

        val reason = when {
            // a layout we have never seen — this is how an offer popup gets in, instantly
            shape == null -> "new-shape"

            // says accept/decline: too valuable to ration, so it bypasses the quota
            OfferHints.looksLikeOffer(walked.body) &&
                now - shape.lastAt >= OFFER_COOLDOWN_MS &&
                offerCaptures < OFFER_CAP -> "offer-words"

            // same layout, different numbers — a few are useful, a shift's worth is not
            now - shape.lastAt >= SHAPE_COOLDOWN_MS && shape.captures < SHAPE_CAP -> "same-shape"

            else -> null
        } ?: return

        val entry = shape ?: Shape().also {
            shapes[shapeKey] = it
            if (shapes.size > SHAPE_MEMORY) shapes.remove(shapes.keys.first())
        }
        entry.captures++
        entry.lastAt = now
        entry.recentText.addLast(textHash)
        if (entry.recentText.size > TEXT_MEMORY) entry.recentText.removeFirst()
        if (reason == "offer-words") offerCaptures++

        lastCaptureAt = now

        /*
         * Protected from pruning: the first sighting of any layout, and anything that
         * reads like an offer. Those are the screens we may never get a second chance
         * at. The repeat captures of a layout we already hold are the expendable ones.
         */
        val keep = reason != "same-shape"
        val body = header(eventPkg, windowPkg, reason, walked) + walked.body

        runCatching { store.save(windowPkg, body, keep) }
            .onSuccess {
                android.util.Log.i(TAG, "captured $windowPkg · $reason · keep=$keep")
                if (now - lastNotifyAt >= NOTIFY_EVERY_MS) {
                    lastNotifyAt = now
                    notifier.show(store.count(), store.keepCount())
                }
            }
            .onFailure { android.util.Log.e(TAG, "save failed for $windowPkg", it) }
    }

    /**
     * The accept path.
     *
     * Four comparisons stand between an arbitrary accessibility event and an early
     * return, and a shift produces tens of thousands of those events: a driver
     * scrolling YouTube between rides generates content changes continuously, and
     * none of them may cost anything. Only once the package is known to be a ride
     * app with rules armed does this touch the tree.
     *
     * No throttle. [onAccessibilityEvent] rate-limits the learning walk because a
     * capture 200 ms late is still a capture; an offer 200 ms late is somebody
     * else's ride.
     */
    private fun tryAccept(event: AccessibilityEvent) {
        if (!armed) return

        val pkg = event.packageName?.toString()
        val root = offerRoot(event, pkg) ?: return
        val rootPkg = root.packageName?.toString() ?: return
        if (rootPkg !in DETECTORS) return
        if (rules[rootPkg]?.enabled != true) return

        val outcome = runCatching { auto.onScreen(NodeScreen(root, rootPkg)) }
            .onFailure { android.util.Log.e(TAG, "accept path failed for $rootPkg", it) }
            .getOrNull() ?: return

        /*
         * Logged after the fact, deliberately. Nothing here — the string building,
         * the log write — happens between reading the offer and pressing the button.
         */
        android.util.Log.i(
            TAG,
            "offer $rootPkg · ${outcome.offer.fare} د.أ · ${outcome.offer.etaMin} min · " +
                "${outcome.decision} · pressed=${outcome.pressed} · ${outcome.tookMs}ms",
        )
    }

    /**
     * The window an offer might be in, or null to stop immediately.
     *
     * The common case is the cheap one: the event came from the ride app, and the
     * active window is already its window, so this is one field read.
     *
     * The fallback matters more than it looks. An offer arriving while the driver
     * is following Google Maps belongs to the ride app's window but focus may sit
     * with Maps, and on a foldable two app windows are open at once. A window list
     * scan is a binder call and too expensive to do on every content change, so it
     * is spent only on the rare events that can mean "a new window appeared".
     */
    private fun offerRoot(event: AccessibilityEvent, pkg: String?): AccessibilityNodeInfo? {
        if (pkg != null && pkg in DETECTORS) {
            val active = rootInActiveWindow
            if (active != null && active.packageName?.toString() == pkg) return active
            return windowRootFor(pkg)
        }

        val structural = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!structural) return null

        return rules.keys.firstNotNullOfOrNull { armedPkg -> windowRootFor(armedPkg) }
    }

    /** The topmost window belonging to [pkg], if it has one open. */
    private fun windowRootFor(pkg: String): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestLayer = Int.MIN_VALUE
        runCatching {
            for (w in windows) {
                val root = w.root ?: continue
                if (root.packageName?.toString() != pkg) continue
                if (w.layer > bestLayer) {
                    bestLayer = w.layer
                    best = root
                }
            }
        }
        return best
    }

    /**
     * Which window to dump.
     *
     * [rootInActiveWindow] alone is not enough. An offer can arrive in its own
     * window and lose focus the moment the driver — or another driver — acts on it,
     * and a foldable can have two app windows open at once. So the ride app's window
     * is preferred over whatever currently holds focus, and the topmost one wins.
     *
     * Needs flagRetrieveInteractiveWindows in the service config; without it
     * [windows] comes back empty and we fall back to the old behaviour.
     */
    private fun pickRoot(): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        runCatching {
            val watched = watchedPackages()
            for (w in windows) {
                val root = w.root ?: continue
                val pkg = root.packageName?.toString() ?: continue
                if (pkg == packageName) continue
                val isWatched = pkg in watched
                if (!isWatched && w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                var score = w.layer
                if (isWatched) score += 1_000_000
                if (w.isActive) score += 1_000
                if (score > bestScore) {
                    bestScore = score
                    best = root
                }
            }
        }
        return best ?: rootInActiveWindow
    }

    private class Walked(val body: String, val shape: String, val nodes: Int)

    private fun header(eventPkg: String, windowPkg: String, reason: String, walked: Walked): String =
        buildString {
            appendLine("package: $windowPkg")
            if (eventPkg.isNotEmpty() && eventPkg != windowPkg) appendLine("event-package: $eventPkg")
            appendLine("time: ${Date()}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("why: $reason · nodes=${walked.nodes}")
            appendLine("---")
        }

    /**
     * One pass over the tree producing two things: the element list the detector
     * will be written against, and the shape — the same tree with every text value
     * removed.
     *
     * The shape is what makes a ticking navigation screen recognisable as the screen
     * we already have. Node count and depth are part of it on purpose: a Flutter or
     * React Native app can expose a flat tree of identical `android.view.View`
     * nodes with no ids at all, and in that case the size and outline of the tree is
     * the only thing left that tells an offer card apart from a map.
     */
    private fun walk(root: AccessibilityNodeInfo): Walked {
        val body = StringBuilder()
        val shape = StringBuilder()
        var nodes = 0

        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40) return

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val id = node.viewIdResourceName

            // a node is worth recording if it says something or can be pressed
            if (!text.isNullOrBlank() || !desc.isNullOrBlank() || node.isClickable) {
                val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                body.append("  ".repeat(depth))
                body.append(node.className ?: "?")
                if (id != null) body.append(" id=$id")
                if (!text.isNullOrBlank()) body.append(" text=\"$text\"")
                if (!desc.isNullOrBlank()) body.append(" desc=\"$desc\"")
                if (node.isClickable) body.append(" CLICKABLE")
                if (!node.isVisibleToUser) body.append(" hidden")
                body.append(" bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                body.append('\n')

                // the same node with its words taken out
                nodes++
                shape.append(depth)
                shape.append(node.className ?: "?")
                if (id != null) shape.append(id)
                if (!text.isNullOrBlank()) shape.append('T')
                if (!desc.isNullOrBlank()) shape.append('D')
                if (node.isClickable) shape.append('C')
                shape.append(';')
            }

            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }

        visit(root, 0)
        return Walked(body.toString(), "n$nodes:$shape", nodes)
    }

    /**
     * [SUPPORTED] holds guesses; what the driver picked off his own phone is fact.
     * Both are watched, and a watched app is recorded even with capture-all off.
     */
    private fun watchedPackages(): Set<String> = builtInPackages + prefs.pickedPackages

    private val builtInPackages: Set<String> by lazy { SUPPORTED.map { it.packageName }.toSet() }

    /**
     * Recording everything is the safe default for catching the ride apps, but a
     * driver's messages, email and banking must never end up in a file he is about
     * to send us. These are skipped even with "capture all apps" on.
     *
     * A package the driver picked himself is never tested against this: he told us
     * it is his ride app, and a hint as broad as "pay" must not quietly overrule him.
     */
    private fun isSensitive(pkg: String): Boolean =
        pkg in SENSITIVE || SENSITIVE_HINTS.any { pkg.contains(it, ignoreCase = true) }

    /**
     * Screens that can never hold a ride offer.
     *
     * The first driver session was three hours on a charger, and the Always-On
     * Display clock ticking once a minute produced a genuinely new screen every
     * time — 287 captures of the time and the battery percentage. Skipping these
     * outright is what keeps a shift's worth of room for the ride app.
     */
    private fun isNoise(pkg: String): Boolean =
        pkg in NOISE || NOISE_HINTS.any { pkg.contains(it, ignoreCase = true) }

    companion object {
        private const val TAG = "AutoFareSvc"

        /** Don't walk the tree more than this often on content changes. */
        private const val WALK_FLOOR_MS = 250L

        /** Smallest gap between two saved files, as a floor against runaway loops. */
        private const val GLOBAL_FLOOR_MS = 120L

        /** Repeat captures of a layout we already hold: useful, not urgent. */
        private const val SHAPE_COOLDOWN_MS = 4_000L
        private const val SHAPE_CAP = 40

        /** An accept/decline screen jumps the queue, within reason. */
        private const val OFFER_COOLDOWN_MS = 1_500L
        private const val OFFER_CAP = 120

        /** How many layouts we remember, and how many texts per layout. */
        private const val SHAPE_MEMORY = 96
        private const val TEXT_MEMORY = 8

        /** The driver should see the count move, not watch it tick. */
        private const val NOTIFY_EVERY_MS = 3_000L

        /** Lock screen, launcher, keyboard, share sheet — no offer ever lives here. */
        private val NOISE = setOf(
            "android",
            "com.android.systemui",
            "com.samsung.android.app.aodservice",
            "com.sec.android.app.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.huawei.android.launcher",
            "com.miui.home",
            "com.android.intentresolver",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            // What a driver actually does between rides. The 2026-10-27 shift spent
            // 300 of 835 captures on YouTube alone — a full per-app quota on video.
            "com.google.android.youtube",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.tachyon",
            "com.sec.android.gallery3d",
            "com.samsung.android.incallui",
            "com.samsung.android.dialer",
            "com.android.incallui",
            "com.android.dialer",
            "com.wssyncmldm",
        )

        private val NOISE_HINTS = listOf(
            "systemui", "aodservice", "launcher", "keyguard", "wallpaper",
            "inputmethod", "keyboard", "honeyboard",
        )

        /** Messaging, mail, social, photos — never recorded. */
        private val SENSITIVE = setOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "com.facebook.orca", "com.facebook.katana",
            "com.instagram.android", "com.snapchat.android", "com.twitter.android",
            "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
            "com.google.android.gm", "com.microsoft.office.outlook",
            "com.android.chrome", "com.google.android.apps.photos", "com.android.gallery3d",
            "com.android.contacts", "com.google.android.contacts", "com.android.dialer",
            "com.android.settings", "com.huawei.systemmanager",
        )

        /** Catches bank and wallet apps, whose package names vary per bank. */
        private val SENSITIVE_HINTS = listOf(
            "bank", "wallet", "pay", "cliq", "zaincash", "orange.money", "efawateer",
            "password", "authenticator", "keeper", "vault", "health",
        )

        /** Set while the service is bound; the UI uses it to show real status. */
        @Volatile
        var running: Boolean = false
            private set

        /** Is the service switched on in Android's accessibility settings? */
        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = "${context.packageName}/${AutoFareService::class.java.name}"
            val meShort = "${context.packageName}/.service.AutoFareService"
            return flat.split(':').any { it.equals(me, true) || it.equals(meShort, true) }
        }
    }
}
