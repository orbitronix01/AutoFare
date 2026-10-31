package jo.autofare.service

import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import jo.autofare.MainActivity
import jo.autofare.auto.AutoAccept
import jo.autofare.auto.cueFor
import jo.autofare.auto.DecisionLog
import jo.autofare.data.AppPrefs
import jo.autofare.data.CaptureStore
import jo.autofare.data.DETECTED_APPS
import jo.autofare.data.History
import jo.autofare.data.License
import jo.autofare.data.LicenseStore
import jo.autofare.data.RulesStore
import jo.autofare.data.SUPPORTED
import jo.autofare.detect.DETECTORS
import jo.autofare.detect.OfferHints
import jo.autofare.rules.Decision
import jo.autofare.rules.Reason
import jo.autofare.rules.RuleSet
import jo.autofare.service.overlay.AcceptMoment
import jo.autofare.service.overlay.BubbleTap
import jo.autofare.service.overlay.FloatingButton
import jo.autofare.service.overlay.bubbleTap
import jo.autofare.service.overlay.momentCard
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
 * It stays in the source, but only debug builds run it. The detectors read
 * Jeeny's offer by its wording, and the day Jeeny rewords a label the only way
 * to find out what it says now is a fresh capture — taken on a debug build on a
 * test phone. A release build has no way in (the screen is not reachable), no
 * notification, and never records: see [captureAllowed].
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
    private lateinit var accepts: AcceptNotifier

    private lateinit var rulesStore: RulesStore
    private lateinit var licenseStore: LicenseStore
    private lateinit var auto: AutoAccept
    private lateinit var history: History

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

    /**
     * The `elapsedRealtime` instant until which the engine may press, cached from disk.
     *
     * Held as the instant rather than a boolean so the last server answer going
     * stale mid-shift takes effect on the next offer without anything having to
     * re-read it — the accept path compares two longs and never touches
     * SharedPreferences or the network.
     */
    @Volatile private var paidUntil = 0L

    /** Checks in with the server every [License.CHECK_MS], off the accept path entirely. */
    private val timer = Handler(Looper.getMainLooper())
    private val licenseTick = object : Runnable {
        override fun run() {
            // re-derive first: a missed check-in has to lock on time even when
            // there is no network to bring an answer back
            loadLicense()
            showStatus()
            // also the fallback for an overlay grant the op watcher did not report
            refreshBubble()
            License.checkAsync(this@AutoFareService)
            timer.postDelayed(this, License.CHECK_MS)
        }
    }

    /**
     * Today's tally for the shade line, kept in memory.
     *
     * Seeded from the database once at connect and incremented after each press,
     * rather than re-querying per accept: the log callback runs on this service's
     * main thread, and a SQLite aggregate there delays the *next* offer.
     */
    private var acceptedToday = 0
    private var earningsToday = 0.0

    /** Rate-limits the "cannot accept" alert so a busy junction cannot spam him. */
    private var lastAlertAt = 0L
    private var lastAlertReason: Reason? = null

    private var rulesListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var licenseListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var overlayOpListener: AppOpsManager.OnOpChangedListener? = null

    /** AutoFare's own screen came up or went away: the bubble hides over it. Main thread. */
    private val appVisibilityListener: () -> Unit = { refreshBubble() }

    /*
     * What happens around a press, never in front of it: the tone and buzz, the
     * floating button, the accept-moment card. Their settings are held in memory
     * for the same reason the rules are — the decision callback runs inside the
     * accept path's call stack, and it does not get to read a file either.
     */
    private lateinit var feedback: Feedback
    private lateinit var bubble: FloatingButton
    private lateinit var moment: AcceptMoment
    @Volatile private var acceptSound = false
    @Volatile private var rejectVibrate = false
    @Volatile private var bubbleOn = false

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
        accepts = AcceptNotifier(this)

        rulesStore = RulesStore(this)
        licenseStore = LicenseStore(this)
        history = History.get(this)
        auto = AutoAccept(
            rulesFor = { pkg -> rules[pkg] ?: RuleSet() },
            now = { SystemClock.elapsedRealtime() },
            log = { outcome ->
                val at = System.currentTimeMillis()
                // in-memory, for "what did it just do"; on disk, for the stats screens
                DecisionLog.add(outcome, at)
                history.record(outcome, at)
                onDecision(outcome, at)
            },
            paid = { SystemClock.elapsedRealtime() < paidUntil },
        )
        loadRules()
        loadLicense()
        seedTodayTally()
        feedback = Feedback(this)
        bubble = FloatingButton(this, prefs, onTap = ::onBubbleTap)
        moment = AcceptMoment(this, prefs)
        loadOverlaySettings()

        rulesListener = rulesStore.observe {
            loadRules()
            // a rule change makes every earlier decision irrelevant
            auto.reset()
            showStatus()
            refreshBubble()
        }
        licenseListener = licenseStore.observe {
            loadLicense()
            showStatus()
            refreshBubble()
        }
        prefsListener = prefs.observe {
            loadOverlaySettings()
            refreshBubble()
        }
        watchOverlayPermission()
        AppVisibility.observe(appVisibilityListener)
        showStatus()
        refreshBubble()
        timer.post(licenseTick)

        running = true
        epoch = prefs.captureEpoch
        learningWasOn = captureAllowed && prefs.learningEnabled
        if (learningWasOn) notifier.show(store.count(), store.keepCount())
        android.util.Log.i(TAG, "connected · armed=$armed learning=$learningWasOn")
    }

    override fun onDestroy() {
        running = false
        timer.removeCallbacks(licenseTick)
        rulesListener?.let { rulesStore.stopObserving(it) }
        licenseListener?.let { licenseStore.stopObserving(it) }
        prefsListener?.let { prefs.stopObserving(it) }
        overlayOpListener?.let { l ->
            runCatching { getSystemService(AppOpsManager::class.java)?.stopWatchingMode(l) }
        }
        // nothing of ours may outlive the service on top of the ride app
        AppVisibility.stopObserving(appVisibilityListener)
        if (::bubble.isInitialized) bubble.hide()
        if (::moment.isInitialized) moment.hide()
        if (::feedback.isInitialized) feedback.release()
        if (::notifier.isInitialized) notifier.hide()
        // the line vanishing is the driver's signal that we were killed, but on an
        // orderly shutdown clear it ourselves rather than leave a lie in the shade
        if (::accepts.isInitialized) accepts.hide()
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

    /** Cache the press deadline off disk. See [LicenseStore.paidUntilElapsed]. */
    private fun loadLicense() {
        paidUntil = licenseStore.paidUntilElapsed()
    }

    /** Cache the around-the-press settings off disk, for the same reason as [loadRules]. */
    private fun loadOverlaySettings() {
        acceptSound = prefs.acceptSound
        rejectVibrate = prefs.rejectVibrate
        bubbleOn = prefs.floatingButton
    }

    /**
     * The floating button, shown only while it can mean something.
     *
     * Not on a locked app: the licence gate is what decides whether AutoFare
     * works at all, and a bubble offering to switch on something that will
     * refuse every offer is a control that lies. The shade alert is what speaks
     * to a lapsed driver. Main thread only.
     */
    private fun refreshBubble() {
        if (!::bubble.isInitialized) return
        val paid = SystemClock.elapsedRealtime() < paidUntil
        // and not over AutoFare itself, where it sat on top of every screen's title
        // and offered a switch the screen underneath already shows
        bubble.update(visible = bubbleOn && paid && !AppVisibility.inForeground, armed = canAccept())
    }

    /**
     * Notice the driver granting or revoking draw-over-apps while we run.
     *
     * There is no broadcast for it; the op watcher is the only signal, and it can
     * arrive on a binder thread a moment before `canDrawOverlays` agrees, so the
     * refresh is posted to the main thread with a short delay. [licenseTick]
     * re-checks as well, for the vendor builds that never call this.
     */
    private fun watchOverlayPermission() {
        runCatching {
            val ops = getSystemService(AppOpsManager::class.java) ?: return
            val listener = AppOpsManager.OnOpChangedListener { _, _ ->
                timer.postDelayed({ refreshBubble() }, 300L)
            }
            ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, listener)
            overlayOpListener = listener
        }
    }

    /**
     * One tap on the floating button. What it means is decided in [bubbleTap];
     * this only carries it out. A tap is the driver, not an offer, so writing
     * the rules from here is no cost to the accept path — and the write comes
     * straight back through [rulesListener], which re-arms the engine exactly as
     * a switch on Home would.
     */
    private fun onBubbleTap() {
        val ids = DETECTED_APPS.map { it.id }.toSet()
        val armedNow = DETECTED_APPS.filter { rulesStore.get(it.id).enabled }.map { it.id }.toSet()
        val tap = bubbleTap(armedNow, ids, prefs.pausedApps)
        android.util.Log.i(TAG, "bubble tap · armed=$armedNow · $tap")
        when (tap) {
            is BubbleTap.Apply -> {
                prefs.pausedApps = tap.remember
                for (app in DETECTED_APPS) {
                    val r = rulesStore.get(app.id)
                    val on = app.id in tap.armed
                    if (r.enabled != on) rulesStore.put(app.id, r.copy(enabled = on))
                }
            }
            BubbleTap.OpenApp -> runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }.onFailure { android.util.Log.w(TAG, "could not open the app from the bubble", it) }
        }
    }

    /** Read today's totals once, so a restart mid-shift does not show him a zero. */
    private fun seedTodayTally() {
        runCatching {
            val s = history.summary(History.startOfToday())
            acceptedToday = s.accepted
            earningsToday = s.earnings
        }
    }

    /**
     * True only when an offer arriving now would actually be pressed.
     *
     * The same three conditions the Home band reports. The service cannot be
     * running and switched off at once, so [armed] and the subscription are all
     * that is left to check here.
     */
    private fun canAccept(): Boolean = armed && SystemClock.elapsedRealtime() < paidUntil

    private fun showStatus() {
        if (::accepts.isInitialized) {
            accepts.showStatus(canAccept(), acceptedToday, earningsToday)
        }
    }

    /**
     * Everything the driver should hear about a decision, done after the press.
     *
     * He is inside Jeeny or Petra while this runs, so the shade is the only place
     * any of it can reach him.
     */
    private fun onDecision(outcome: AutoAccept.Outcome, at: Long) {
        /*
         * The press has already happened by the time this runs; even so, the cue
         * is only handed to its own thread here, and the card is posted to the
         * back of the main queue. Neither runs inside the event that carried the
         * offer, so neither can be the reason a press was late.
         */
        cueFor(outcome, acceptSound, rejectVibrate)?.let { feedback.play(it) }

        if (outcome.pressed) {
            acceptedToday++
            earningsToday += outcome.offer.fare ?: 0.0
            showStatus()
            val offer = outcome.offer
            val tookMs = outcome.tookMs
            timer.post {
                val name = SUPPORTED.firstOrNull { it.packageName == offer.pkg }?.name ?: offer.pkg
                moment.show(momentCard(offer, rules[offer.pkg], name, tookMs))
            }
            return
        }

        /*
         * A refusal that is the rules working as asked is not news — he set those
         * rules. A refusal because the app *cannot* accept at all is, and it is
         * the one he will otherwise discover at the end of a wasted shift.
         */
        val reason = (outcome.decision as? Decision.Skip)?.reason ?: return
        if (reason != Reason.UNPAID && reason != Reason.OFF) return

        if (reason == lastAlertReason && at - lastAlertAt < ALERT_COOLDOWN_MS) return
        lastAlertReason = reason
        lastAlertAt = at
        accepts.alertCannotAccept(reason.ar)
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

        // always false in release, so nothing below ever runs on a driver's phone
        if (!captureAllowed || !prefs.learningEnabled) {
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

        /**
         * Learning mode exists only in debug builds. In a release build this is
         * false for the life of the process, so the capture branch never runs and a
         * `learning_enabled` left over in prefs from a debug install does nothing.
         */
        private val captureAllowed: Boolean = jo.autofare.BuildConfig.DEBUG

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

        /**
         * How long before the same "cannot accept" alert may fire again.
         *
         * Offers arrive in clusters at a busy junction, and one alert per refused
         * offer would be a phone buzzing in his hand while he drives. Five minutes
         * is often enough to be noticed and rare enough to be ignorable.
         */
        private const val ALERT_COOLDOWN_MS = 5 * 60 * 1000L

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
