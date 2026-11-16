# AutoFare — handoff

Everything a fresh Claude session needs to pick this project up. Written 2026-11-15,
updated 2026-11-16 for v0.7 (one ride at a time, accept chime, type-in amounts,
notifications setup step — built but not yet tried on a phone).
at commit `9ef0010` on `main`. The app is **finished and being handed to drivers**
(v0.6). Read this whole file before touching anything.

---

## 1. What it is

An Android app that **auto-accepts ride offers** for ride-hailing drivers in **Jordan**.
An AccessibilityService watches the driver app's offer popup, reads the fare and the
pickup ETA from the on-screen node tree, compares them with the driver's rules, and
presses Accept — typically **20–150 ms** after the offer appears.

| App | Package | Version proven | UI toolkit | How the offer is read |
|---|---|---|---|---|
| Jeeny | `me.com.easytaxista` | 24.1.0 | **Jetpack Compose** | one tree walk, by `ride_offer_*` content-description tags |
| Petra Ride | `com.PetraRide_Captain` | 5.25.3 | classic Views | view-id lookups |

Jeeny ships under its old Easy Taxi package name; that is correct.

UI is **entirely Jordanian Arabic, RTL**. Kotlin + Jetpack Compose + Material 3
Expressive. Distributed as a direct APK (Google Play would reject it).

**Business model:** ~5 JD/month subscription. **No login anywhere.** One code per phone,
typed once; the owner controls each phone's time from an admin dashboard.

**Explicitly out of scope:** Careem and Uber. Do not propose detectors for them.

---

## 2. Status (2026-11-15)

Tested live on the owner's **Samsung Galaxy Z Fold 4 (SM-F936N, Android 15)**, folded
(cover screen), with real offers:

- Jeeny accepts (17–106 ms), Petra accepts (76–227 ms); both skip offers under the
  minimum fare; Petra skips no-price offers
- cancelled rides (by driver or passenger) come off the earnings, both apps
- one-code-per-phone licensing, "Add time" and "Remove access" from the dashboard,
  lock screen — all verified on the phone
- 156 JVM unit tests, 0 failures

**Not tested:** the phone **unfolded** (inner display is a separate display id — the
owner chose to skip it). Jeeny's trip screens after an accept have never been captured,
so Jeeny cancel detection is a timing heuristic (see §5).

**Release file:** `dist/AutoFare-v0.6.apk` (versionCode 6), signed with the release key.
It is **not committed** (the earlier `dist/*.apk` are, which bloats the repo — don't add more).

### Why the first real-phone test accepted nothing (fixed — keep in mind)

1. **Jeeny is Jetpack Compose.** `findAccessibilityNodeInfosByText` returns **nothing** on
   Compose screens on a real phone. The simulator (`android/fakeride/`) uses plain
   TextViews, where it works — so it passed there and failed live. **Never use the
   platform text search for Jeeny.** The `ComposeLike` wrapper in `DetectorsTest` blinds
   text search to keep tests honest.
2. The old default rules (per-minute 0.60 JD + 2.50 JD floor) refused nearly every city ride.
3. Petra 5.25 moved the pickup ETA from `estimated_time_to_pick_up` to `point_subtitle`.

---

## 3. Architecture

### Speed is the product

The driver races every other driver holding the same popup; offers live ~3 s.

- `tryAccept()` runs **first** in `onAccessibilityEvent`, no throttle
- `notificationTimeout = 0`; `typeWindowsChanged` is a second, earlier popup signal
- **Nothing on the accept path touches disk or network.** Rules and the subscription
  are cached in memory and refreshed by SharedPreferences listeners
- Only what the decision needs is read before the press (fare, ETA, the button); the
  reject button is looked up lazily (`Detected.reject`) and never pressed
- Logging, history, notifications, ride-following all happen **after** the press
- Log line per decision: `offer <pkg> · <fare> · <eta> min · <decision> · pressed=… · Nms (read N · press N)`

### Never use coordinates

Nodes are found by id/tag/text and pressed with `performAction(ACTION_CLICK)`. Do not
rank buttons by bounds — a foldable in RTL reports invalid bounds.

### Key files (`android/app/src/main/java/jo/autofare/`)

```
detect/Detectors.kt    PetraDetector (ids), JeenyDetector (tree walk, tags + wording fallback)
detect/Offer.kt        Offer + fingerprint (dedupe), Detected (accept node, lazy reject)
detect/Numbers.kt      Arabic-Indic digits, JOD decimals, units
detect/Arabic.kt       diacritic + alef folding
rules/Rules.kt         decide(); SHOWN_MODES; Reason (incl. CANCELLED)
auto/AutoAccept.kt     detect -> judge -> press; dedupe + 8 s accept cooldown; dry-run for diagnostics
auto/RideWatch.kt      follows the last accepted ride; says CANCELLED / STARTED
service/AutoFareService.kt   the AccessibilityService; caches; watchRide + 3 s rideTick
data/History.kt        SQLite of every decision; markCancelled()
data/RulesStore.kt     ONE shared rule set + per-app enabled / acceptNoPrice
data/License.kt        subscription state, Supabase RPC calls
ui/license/LockScreen.kt     code box only when never activated
MainActivity.kt        one-time pin-to-home-screen request
```

### Rules

Only two modes are offered: **أي طلب قريب** (anything close — ignores the fare) and
**أجرة أقل شي** (minimum fare, the default). `BAND` and `PER_MINUTE` still exist in the
`Mode` enum so stored values read, but `Mode.usable()` maps them to `FLOOR`. Max pickup
ETA applies in both modes. The rule set is **shared by Jeeny and Petra**; only on/off
and Petra's "accept bonus rides with no price" are per app. A common driver confusion:
setting a 10 JD minimum while "anything close" is selected does nothing.

### Cancel detection (`RideWatch`)

After a press, the ride is followed (on its app's events, plus every 3 s via `rideTick`,
because a still screen sends no events; gives up after 45 min):

- **Petra:** trip screen has `trip_flow_button` reading exactly `الوصول` until arrival.
  Any other label → STARTED (kept). Back to `قيد البحث عن الطلبات` while not started and
  within ETA + 15 min → CANCELLED; later than that → treated as a ride.
- **Jeeny:** `home_lbl_status` reading `أنت متاح الآن` (but not `غير متاح`) within
  ETA + 1 min of the accept → CANCELLED, else STARTED. A heuristic: a real cancel after
  that window is missed; a very short real trip inside it would be erased. Capturing
  Jeeny's post-accept screens (capture build, §4) would allow a proper rule.

CANCELLED rewrites the history row to `accepted=0, reason=CANCELLED`; every earnings
query reads only `accepted=1`. The stats screen's refusal breakdown excludes CANCELLED.

---

## 4. Build, install, diagnose

```powershell
cd android
.\build.ps1                    # debug
.\build.ps1 -Release           # signed release -> dist\AutoFare-v<version>.apk
.\build.ps1 -Release -Install  # and push to the phone
```

From bash (what this project's sessions use):
```bash
cd android
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
~/.gradle-dist/gradle-9.3.1/bin/gradle testDebugUnitTest assembleRelease --console=plain -q
"$ANDROID_HOME/platform-tools/adb.exe" install -r app/build/outputs/apk/release/app-release.apk
```

- JDK 17 (the default JDK 25 is too new). Gradle 9.3.1 at `~/.gradle-dist`. **No wrapper.**
- AGP 9.1.1, Kotlin 2.2.20, compileSdk 37, targetSdk 35, minSdk 26, `jo.autofare`
- adb is **not on PATH**: `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`
- If the AGP chain ever has to be redone: material3 1.5.x → compose-foundation 1.12 →
  compileSdk 37 + AGP 9.1+ → Gradle 9.3.1. Do not declare `org.jetbrains.kotlin.android`.

**Two diagnostic release builds** (signed like the real one, install over it keeping data):

| Flag | What it does |
|---|---|
| `-Pobserve=true` → `0.6-observe` | judges every offer, **never presses**, logs everything (`AutoFareDiag`, `AutoFareDump`) — safe for testing without taking rides |
| `-Pcapture=true` → `0.6-capture` | presses normally **and** dumps every new screen to logcat (`AutoFareDump`) — how the Jeeny 24 screen was captured |

They cannot be combined. Both log passenger addresses/names — **never ship them**.
Learning mode (file captures) also exists in debug builds; see `CaptureStore`.

A captured screen pasted from logcat into `app/src/test/resources/captures/*.txt` (copied
byte-exact; strip the logcat prefix) becomes a test fixture via `CaptureTree`.

### Signing

`keystore/autofare.jks` + `keystore/keystore.properties`, **gitignored, not in the repo**.
Lost key = no update can ever install over existing installs. A debug build cannot be
installed over a release one without uninstalling (which wipes the driver's data).

---

## 5. The test phone — Samsung Z Fold 4 (owner's)

- **Two displays.** Folded = cover display (904×2316). Plain `screencap` warns about
  multiple displays; use `adb exec-out screencap -p -d 4630946213010294403 > x.png`
  (cover). `dumpsys SurfaceFlinger --display-id` lists ids. `input tap` hits the cover.
- **Never `am force-stop jo.autofare`** — Samsung switches the accessibility service off,
  and only the owner can turn it back on (Settings → Accessibility → Installed apps →
  AutoFare). Reinstalling with `adb install -r` keeps it on.
- **Android 15 restricted settings:** a sideloaded APK's accessibility switch is blocked
  until Settings → Apps → AutoFare → ⋮ → *Allow restricted settings*.
- `pm clear jo.autofare` = fresh install state (stats zero, rules default) but also turns
  the accessibility service off. ANDROID_ID survives, so the server still knows the phone.
- `uiautomator dump` works for reading text but fails on constantly ticking screens.
- Another auto-accept app, **Sniper Orders Pro** (`com.sayad.orders.pro`), is installed;
  the owner switched its service off. It must stay off while AutoFare runs.
- `com.taxif.driver` draws an overlay; harmless.
- Watch decisions live: `adb logcat -v time -T 1 AutoFareSvc:I '*:S'`

---

## 6. Licensing — one code per phone

Supabase project `https://mxugmesfstgdwjsknqlf.supabase.co`. The **publishable key** is in
the APK and the dashboard on purpose; tables are RLS-locked so it reaches only
`redeem_code` and `device_status`. **The service-role key must never appear anywhere.**
No Supabase CLI or service key is on this machine: **migrations are applied by the owner
pasting them into the Supabase SQL editor.** All four are applied:

| Migration | What it adds |
|---|---|
| `0001_licensing.sql` | original schema |
| `0002_codes.sql` | codes, devices, events, admins/resellers, portal RPCs, `admin_stats` |
| `0003_remove_access.sql` | `remove_access(device)` — ends a phone's time now |
| `0004_one_code_per_phone.sql` | `set_access(device, months \| until \| forever)`; re-entering a phone's own code is accepted; `extra_months` counted as sold |

The model now:
- A phone is identified by ANDROID_ID. The driver types **one code, once**; it binds to
  that phone. The same code on another phone → `other_phone` ("مربوط بجهاز ثاني").
  The same code again on the same phone (after reinstall/wipe) → OK, adds nothing.
- The owner (or the reseller who sold the phone's current code) changes the phone's time
  from the dashboard: **Add time** (1–12 months, an end date, or forever — owner only)
  or **Remove access**. The phone picks it up at its next check-in: every 5 min while
  the service runs, and whenever the app is opened. Offline phones lock after 15 min
  (`GRACE_MS`).
- App lock screen: code box **only** when the phone was never activated; an expired
  phone says "contact us to renew" with a re-check button. Settings has no code box.
- Licence checks are **never on the accept path** (a cached `elapsedRealtime` deadline).

---

## 7. Admin dashboard (`admin/`)

React + Vite + supabase-js. Deployed on **Vercel**, project `autofare-admin`, from GitHub
`orbitronix01/AutoFare` branch `main`, **Root Directory = `admin`** (every git deploy
failed until that was set). Pushing `main` deploys it.

```bash
npm --prefix admin ci && npm --prefix admin run build     # check it builds
npm --prefix admin run dev                                # http://localhost:5191, ?demo for sample data
```

The Claude desktop browser preview in these sessions was pinned to another project's
launch config and kept starting the wrong server — don't burn time on it.

---

## 8. Working with the owner

- Wants **short, simple answers**. Explain what to tap, in order. Doesn't need things
  explained twice (e.g. what "unfolded" means).
- **Ask before installing a different build on the phone or switching versions mid-test**
  — they stop it otherwise. They run the live tests (going online, cancelling rides);
  you read the log and report each order: fare, decision, reason, speed.
- Don't trigger real accepts without them knowing; every test accept is cancelled by
  hand and counts against the driver account.
- Pushing to `main` deploys the dashboard — they approved pushing for that purpose.
- Ride companies forbid auto-accept; drivers take a risk with their accounts.

### Decided — don't re-litigate

- Material 3 Expressive; hand-drawn tab icons, speed lines, the 12° slant stay
- Jordanian dialect, not MSA. Words AutoFare searches for inside Jeeny/Petra are locked
- Live tap, not shadow mode. It never taps Reject
- Light theme by default. No login; codes only, one per phone
- Only two rule modes
- Unfolded testing skipped by the owner's choice

### Gotchas

- **PowerShell corrupts UTF-8.** Never rewrite source with `Get-Content`/`Set-Content`.
- The dinar has three decimals; a lone separator is a decimal point. Fares in فلس ÷ 1000.
- Jeeny's ETA and distance both start with `يبعُد` — told apart by unit. Short distances
  are a bare `م` (metres). The same offer may switch between Western and Arabic digits.
- Jeeny repaints distance and even fare while one offer is on screen; the fingerprint
  includes km **only** when an offer has no route (Petra's new layout).
- `uiautomator dump` serves a stale dump on ticking screens; prefer `screencap`.

---

## 9. What could come next (none requested)

1. Capture Jeeny's post-accept trip screens with the capture build and replace the timing
   heuristic in `RideWatch` with real markers.
2. Test the inner (unfolded) display.
3. Two rides at once: the accept cooldown is 8 s, so with both apps armed a second ride
   in the other app can be accepted while on the first.
4. Jeeny walks up to 400 nodes on every Jeeny event (main thread). Fine at the measured
   speeds; worth profiling on a slow phone.
5. Move `dist/*.apk` out of git history into GitHub Releases.
