# AutoFare — handoff

Context for picking this project up cold. Written 2026-10-31, at commit `a7c1054`.

---

## 1. What it is

An Android app that **auto-accepts ride offers** for ride-hailing drivers in **Jordan**.
It watches the driver app's offer popup through an **AccessibilityService**, reads the
fare and the pickup ETA out of the on-screen node tree, compares them against rules the
driver set, and taps Accept — in about 40–120 ms.

Two apps are supported and both are **verified against real captures from a real driver's
shift**:

| App | Package | How the offer is found |
|---|---|---|
| Jeeny | `me.com.easytaxista` | by text — the offer sheet has **zero view ids** |
| Petra Ride | `com.PetraRide_Captain` | by view id — real stable ids |

Jeeny ships under its Easy Taxi ancestry, which is why the package looks wrong. It is
correct — confirmed from a capture.

UI is **entirely in Jordanian Arabic, RTL**. Kotlin + Jetpack Compose + Material 3
Expressive. Distributed as a direct APK; Google Play would almost certainly reject an
accessibility-for-automation app.

**Business model:** a subscription code, ~5 JOD/month. There is deliberately **no login
anywhere** — no accounts, no email, no password. A code binds to a device on first
activation and the driver renews it monthly. An admin dashboard for handing out codes is
planned but not built.

---

## 2. Where it stands

**Working and device-verified** on a Huawei nova 3i (INE-LX1r, Android 9 / API 28):

- Detection and accept for both apps — proven end to end
- Rules engine (4 modes), persisted, no save button
- SQLite history — every decision on disk
- Home / Stats / Rules / Settings screens reading real data
- Readiness checks reading actual system state
- Licence gate — refuses every offer without a subscription
- Ongoing shade notification showing armed state and today's tally

**Built but never exercised against the live server:**

- Code activation. `activate_license` has literally never been called from a phone,
  because no code has ever been issued.

**66 JVM tests, 0 failures.** `gradle testDebugUnitTest`.

---

## 3. The architecture, and why it is shaped this way

### Speed is the product

The driver is not racing the passenger. He is racing **every other driver holding the
same popup**. One observed offer lived about 3 seconds. Neither app shows a countdown, so
there is no deadline to read — being first is the only strategy.

That single fact explains most of the design:

- `tryAccept()` runs **first** in `onAccessibilityEvent`, before and independent of
  everything else, with **no throttle**
- `notificationTimeout = 0`, and `typeWindowsChanged` is used as a second, earlier popup
  signal
- **Nothing on the accept path touches disk.** Rules live in memory and are refreshed by
  a SharedPreferences change listener. The subscription is a cached `Long` compared
  against the clock. Never add a read, a query or a network call to this path.
- Petra uses `findAccessibilityNodeInfosByViewId` — a platform index, no tree walk at all
- The package gate is one map lookup, so the tens of thousands of irrelevant events per
  shift die immediately
- Logging, history and notifications all happen **after** the press

### Never use coordinates

AutoFare finds nodes by id or text and calls `performAction(ACTION_CLICK)`. Screen size,
density and font scale are irrelevant. Apps are told apart by `event.packageName`.

**Do not rank candidate buttons by bounds.** A foldable in RTL produces invalid bounds
(`bounds=0,1015,-5036,1288` — right less than left). "Accept is the wide one at the
bottom" will misfire.

### Key files

```
data/RideApp.kt        SUPPORTED apps; DETECTED_APPS filters to verified + has detector
data/RulesStore.kt     per-app rules, own prefs file
data/History.kt        SQLite, every decision; plain SQLite not Room (see below)
data/License.kt        subscription state + LicenseStore + Supabase calls
data/Supabase.kt       project URL + publishable key

detect/Screen.kt       Node/Screen interfaces — NO Android types, so detectors are JVM-testable
detect/Detectors.kt    PetraDetector (by id), JeenyDetector (by text)
detect/Numbers.kt      Arabic-Indic digits, JOD decimal rules
detect/Arabic.kt       diacritic + alef folding

rules/Rules.kt         pure engine: Mode, Reason, decide()
auto/AutoAccept.kt     detect -> judge -> press. The hot path.
service/AutoFareService.kt   the AccessibilityService; caches rules + licence
service/AcceptNotifier.kt    the shade line + the "cannot accept" alert
service/LearnNotifier.kt     same idea, for learning mode

ui/...                 Compose screens
design/prototype.html  single-file HTML mock-up, synced to the app as of 1960afd
supabase/migrations/   the licensing schema (already deployed)
```

### Decisions that look odd but are deliberate

- **Plain SQLite, not Room.** Room means KSP, and this project sits on AGP 9.1 /
  Kotlin 2.2.20 / compileSdk 37 where the annotation-processor version matrix is the most
  fragile part of the build. One table does not earn that risk.
- **Licence checks are never on the accept path.** The expiry is written to disk and
  trusted until it passes. Being offline for a week changes nothing. The cost is that a
  revoked key keeps working until its paid month ends — a fair trade at 5 JOD, and far
  better than taking auto-accept away from a paying driver because of a dead cell on the
  airport road.
- **`AutoAccept.paid` has no default value.** A gate that defaults to "paid" is one
  forgotten argument away from giving the app away.
- **It never taps Reject.** An unknown decision is left for the driver.
- **A missing fare means unknown, never zero.** Petra bonus rides have no price node at
  all (`مكافأة مسافات بعيدة` sits in that slot).
- **Staleness is handled by fingerprint dedupe, not by requiring a reject button.** A
  dying offer keeps `accept_button` after `reject_button` leaves the tree, and the
  unfolded layout has no reject either. Pressing a stale offer takes a ride another driver
  already has.

---

## 4. Gotchas that cost real time

**The driver's phone is Arabic-locale.** Captures are full of `text="%٥٢"` and
`text="١٠:٠٧ م"`. `detect/Numbers.kt` handles Arabic-Indic and Persian digits, the Arabic
decimal `٫`, thousands `٬`, percent `٪`, and invisible bidi marks. Two domain rules are
baked in: **the dinar has three decimals**, so a lone separator is always a decimal point
(`1.500 د.أ` = 1.5 JOD, not 1500); and a fare quoted in فلس is divided by 1000.

**Jeeny specifics:**
- Accept is a **non-clickable TextView** `قبول العرض` — you must climb to the clickable parent
- The fare is `‎JOD 1.50` behind an invisible **U+200E LRM**
- ETA and distance **both start with `يبعُد`** (damma on the ع), so they are told apart by
  unit, never by position
- The same app renders the same offer in Western *or* Arabic-Indic digits two seconds apart
- Short distances use a **bare `م`** (`963م`). Read as km that is 1000× too far and
  auto-rejects everything.

**EMUI / Huawei:**
- It **switches the accessibility service off** after `am force-stop` and often after an
  APK reinstall. Always re-check. Re-enable from adb:
  ```
  settings put secure enabled_accessibility_services jo.autofare/jo.autofare.service.AutoFareService
  settings put secure accessibility_enabled 1
  ```
- `animator_duration_scale = 0` ships enabled, which makes Compose complete every
  animation instantly. Check this before debugging any "animation not showing" report.
- The device drops adb authorisation constantly and sleeps mid-test.
  `adb shell svc power stayon usb` helps; `adb reconnect offline` recovers it.
- Huawei is the most aggressive brand at killing background services, which makes it a
  good worst-case device.

**Testing:**
- `test/.../detect/CaptureTree.kt` parses the learning-dump format into a fake tree, so
  **a capture a driver sends can be pasted straight into a test**.
- Capture fixtures in `app/src/test/resources/captures/*.txt` are **copied byte-exact**,
  not retyped — the LRM and the damma cannot be reliably typed by hand.
- `uiautomator dump` **fails on a ticking screen** and silently serves the previous dump.
  Use `screencap` instead.
- There is a harness at `testharness/` — two throwaway APKs whose applicationId AND
  namespace are the real package names, which is the only way to reproduce
  `com.PetraRide_Captain:id/accept_button`. **Uninstall them afterwards; they squat on
  real package names.** Leave >95s between runs or the 90s fingerprint dedupe looks like
  a failure.

**PowerShell on this machine corrupts UTF-8.** Never bulk-rewrite source with
`Get-Content`/`Set-Content` — it mangles Arabic and symbols silently. Use proper file
editing tools.

---

## 5. Build and run

```powershell
cd android
.\build.ps1                   # debug
.\build.ps1 -Install          # debug + push to connected phone
.\build.ps1 -Release -Install # signed release, copied to dist\
```

The script sets JAVA_HOME and ANDROID_HOME itself.

- **JDK 17** at `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`. The default
  JDK 25 on the original machine is too new for AGP.
- **Gradle 9.3.1**, downloaded by hand to `~\.gradle-dist\gradle-9.3.1`. **There is no
  gradle wrapper in the project.**
- AGP 9.1.1, Kotlin 2.2.20, Compose BOM 2026.09.00, material3 1.5.0-beta02,
  compileSdk 37, targetSdk 35, minSdk 26, applicationId `jo.autofare`
- Tests: `gradle testDebugUnitTest`. junit only; no instrumented tests.

**If the AGP 9 chain ever has to be redone:** material3 1.5.x drags in compose-foundation
1.12, which hard-requires compileSdk 37 **and** AGP 9.1+, which requires Gradle 9.3.1.
AGP 9 then applies the Kotlin plugin itself, so declaring `org.jetbrains.kotlin.android`
is a **fatal** error — keep only `org.jetbrains.kotlin.plugin.compose`. `kotlinOptions {}`
is gone; use a top-level `kotlin { compilerOptions { jvmTarget } }`.

**Signing:** the release keystore is at `keystore/autofare.jks` with its password in
`keystore.properties` beside it. **That whole folder is gitignored and is NOT in the
repo** — it has to be transferred separately and backed up. If it is lost, no existing
install can ever be updated; every driver would have to uninstall and reinstall. A debug
build cannot be installed over a release-signed one (signature mismatch) without
uninstalling first, which wipes the driver's data.

---

## 6. Licensing backend

Supabase. The schema is in `supabase/migrations/0001_licensing.sql` and **is already
deployed** to the live project — verified by calling `check_license`, which answers
correctly.

- The **publishable key is in the APK on purpose**. `licenses` and `activations` have RLS
  on with **no policies**, so that key cannot read either table. The only two things it
  reaches are `activate_license` and `check_license`, each answering one narrow question
  about one key.
- The **service-role key must never appear in the app.** It bypasses every policy.
- `issue_license(phone, months[, key])` mints and renews codes. It is revoked from `anon`
  and `authenticated` — **service-role only**, so codes can currently only be cut from the
  Supabase SQL editor:
  ```sql
  select * from public.issue_license('07xxxxxxxxx', 1);            -- new, 1 month
  select * from public.issue_license('07xxxxxxxxx', 1, 'AFXXXX…'); -- renew
  ```
- A code binds to `device_id` (ANDROID_ID) on first activation, so one code cannot run a
  WhatsApp group of drivers.

---

## 7. What is left

Roughly by value.

1. **Issue a code and test activation end to end.** The single most important untested
   path in the project. Everything else about the subscription is proven except the one
   call that takes money.
2. **Accept sound and reject vibrate.** The toggles exist in Settings and are wired to
   nothing — no `Vibrator`, no `ToneGenerator`, no `MediaPlayer` anywhere.
3. **The floating button (`الزر العائم`).** Design only. The Settings toggle is a local
   variable with no consumer, and the app **requests the overlay permission on the
   readiness screen and then never uses it** — which is worse than not asking.
4. **`لحظة القبول`** — the intercept overlay. Design only, no Kotlin. It is the intended
   marketing shot.
5. **Admin dashboard** for handing out and renewing codes. Currently every sale is manual
   SQL.
6. **Driver verification on real offers.** `DRIVER-CHECKLIST.md` is still entirely
   unticked. See below — this is the one that decides whether any of the rest matters.

**Explicitly out of scope:** Careem and Uber. Do not propose detectors for them.

### The thing that is not a code problem

`DRIVER-CHECKLIST.md` lists what is still unknown about real offers: a surge offer, an
offer with no price, a long-distance offer, a scheduled booking, an offer arriving while
already on a trip, the screen in English, dark mode. Every variant never seen is a case
where AutoFare silently does nothing or misreads a fare.

**And one specific unknown:** nobody has ever captured **what happens after a successful
tap of قبول**. No driver accepted during the capture session. There may be a confirmation
step the app does not handle at all.

The highest-value action available is 20 minutes with a driver's phone during peak hours
(4–7pm), online and receiving offers. That single session is worth more than any amount of
code written blind.

---

## 8. Things already decided — please do not re-litigate

- **Material 3 Expressive** was chosen deliberately, with the trade-off stated (it costs
  some of the hand-built identity). The hand-drawn tab icons, speed lines and the 12°
  slant stay — those are the brand.
- **Jordanian dialect, not MSA.** MSA was offered and declined.
- **25 strings under `detect/` are locked.** They are words AutoFare searches for *inside*
  Jeeny and Petra's own screens. Rewording them breaks detection.
- **Live tap rather than shadow mode** was chosen knowingly, with the risk stated.
- **Light theme by default** with a dark toggle. A dark "cockpit" version was rejected as
  too AI-looking.
- **No login.** Codes only.
- Ride companies forbid auto-accept. Any driver testing this is taking a real risk with
  his account, and should be told so up front.

## 9. Repo notes

- `dist/*.apk` is tracked, so the repo already carries several ~10 MB binaries in history.
  Moving these to GitHub Releases and gitignoring `dist/` is worth doing.
- `design/prototype.html` is a hand-maintained mock-up with **no build link to the app**.
  It was synced to match at commit `1960afd`, but it will drift again — the app is the
  source of truth now, not the HTML.
