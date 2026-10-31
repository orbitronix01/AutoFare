#!/usr/bin/env python3
"""
AutoFare end-to-end rule test, on an emulator, in one command.

    android/scripts/e2e-rules.py                # build, install, run, print the table
    android/scripts/e2e-rules.py --no-build     # reuse the APKs already built
    android/scripts/e2e-rules.py --screens DIR  # also save before/after screenshots
                                                # (--screens-only: just those)

What it does:

 1. Builds AutoFare (debug) and the two fake ride apps (fakeride/jeeny,
    fakeride/petra), installs all three and makes sure the accessibility
    service is on.
 2. For each group of scenarios, sets the driver's rules through the
    debug-only E2eRulesReceiver (it writes through RulesStore, just like the
    rules screen), then shows fake offers in fake Jeeny and fake Petra.
 3. Each fake app logs one line per offer - was its accept button clicked, and
   how long after the offer was first drawn. Nothing AutoFare says about itself
   is used to decide pass or fail.
 4. Prints expected (worked out here from the rules) against actual, and the
    latency min / median / max.

Refuses to run on anything that is not an emulator: the fake apps sit on the
real package names of Jeeny and Petra Ride.

Timing (see AutoAccept): an offer is remembered for 90 s by its fingerprint,
and after a press nothing is pressed for 8 s. So every offer carries a unique id
that is folded into its addresses (a new fingerprint every time), each offer
that is expected to be skipped waits out the fake app's 5 s timeout, and after
every actual press the script waits until 9 s have passed since that press
before showing the next offer.
"""

import argparse
import math
import os
import re
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
GRADLE = os.path.expanduser("~/.gradle-dist/gradle-9.3.1/bin/gradle")
JAVA_HOME = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"

JEENY = "me.com.easytaxista"
PETRA = "com.PetraRide_Captain"
APP_ID = {JEENY: "jeeny", PETRA: "petra"}
SHORT = {JEENY: "Jeeny", PETRA: "Petra"}
SERVICE = "jo.autofare/jo.autofare.service.AutoFareService"

OFFER_TIMEOUT_S = 5.0     # the fake app's own "nobody pressed" deadline
COOLDOWN_S = 9.0          # AutoAccept.ACCEPT_COOLDOWN_MS is 8 s; one spare second
SETTLE_S = 1.0            # between a rules broadcast and the next offer

# ───────────────────────────── the scenarios ─────────────────────────────

BASE = dict(mode="EVERYTHING", rate=0.50, floor=0.0, ceiling=100.0, maxEta=6,
            jeeny=True, petra=True, noPrice=False)


def rules(**kw):
    r = dict(BASE)
    r.update(kw)
    return r


# (group title, rules, [(app, fare or None, eta text, km, note)])
GROUPS = [
    ("warm-up (first offers after install run cold; not in latency stats)",
     rules(maxEta=30), [
         (PETRA, 3.00, "2", "0.9", "warm-up"),
         (JEENY, 3.00, "2", "0.9", "warm-up"),
     ]),
    ("ETA limit: take everything, max 6 min", rules(), [
        (PETRA, 2.00, "6", "2.1", "ETA exactly at max"),
        (JEENY, 2.00, "6", "2.1", "ETA exactly at max"),
        (PETRA, 2.00, "7", "2.6", "ETA one over"),
        (JEENY, 2.00, "7", "2.6", "ETA one over"),
        (PETRA, 2.00, "5.72", "2.0", "5.72 rounds up to 6"),
        (JEENY, 2.00, "5.72", "2.0", "5.72 rounds up to 6"),
        (PETRA, 2.00, "6.2", "2.3", "6.2 rounds up to 7"),
        (JEENY, 2.00, "6.2", "2.3", "6.2 rounds up to 7"),
    ]),
    ("Floor 2.50, max 6", rules(mode="FLOOR", floor=2.50), [
        (PETRA, 2.50, "3", "1.1", "fare exactly at floor"),
        (JEENY, 2.49, "3", "1.1", "cheap: 1 fils under"),
        (JEENY, 9.00, "3", "1.1", "expensive, no ceiling"),
    ]),
    ("Band 2.00-5.00, max 6", rules(mode="BAND", floor=2.00, ceiling=5.00), [
        (PETRA, 5.00, "3", "1.2", "exactly at ceiling"),
        (JEENY, 5.01, "3", "1.2", "1 fils over ceiling"),
        (PETRA, 1.99, "3", "1.2", "1 fils under floor"),
    ]),
    ("Per minute 0.50/min, floor 1.00, max 6",
     rules(mode="PER_MINUTE", rate=0.50, floor=1.00), [
         (PETRA, 2.80, "5.5", "1.9", "0.509/min on exact 5.5"),
         (JEENY, 2.40, "5", "1.9", "0.48/min"),
         (PETRA, 1.50, "2", "0.7", "0.75/min"),
     ]),
    ("No price, accept-no-price OFF", rules(mode="FLOOR", floor=1.00), [
        (PETRA, None, "3", "1.0", "no price"),
    ]),
    ("No price, accept-no-price ON (Petra only by design)",
     rules(mode="FLOOR", floor=1.00, noPrice=True), [
         (PETRA, None, "3", "1.0", "no price"),
         (JEENY, None, "3", "1.0", "no price, Jeeny"),
         (PETRA, None, "6.2", "2.5", "no price, too far"),
     ]),
    ("Jeeny switched off, Petra on", rules(jeeny=False), [
        (JEENY, 5.00, "2", "0.8", "app disabled"),
        (PETRA, 5.00, "2", "0.8", "app enabled"),
    ]),
    ("Stored-as-Float boundary: floor 1.10", rules(mode="FLOOR", floor=1.10), [
        (JEENY, 1.10, "3", "1.0", "fare exactly at floor 1.10"),
        (PETRA, 1.10, "4", "1.3", "fare exactly at floor 1.10"),
    ]),
]


def expected(app, fare, eta_text, r):
    """decide() written out again from the rules as the driver set them."""
    if not r[APP_ID[app]]:
        return False, "OFF"
    exact = float(eta_text)
    eta = math.ceil(exact - 1e-9)
    if eta > r["maxEta"]:
        return False, "TOO_FAR"
    if fare is None:
        # RulesStore honours accept-no-price for Petra only (NO_PRICE_APPS)
        ok = r["noPrice"] and app == PETRA
        return (True, "Accept") if ok else (False, "NO_PRICE")
    m = r["mode"]
    if m == "EVERYTHING":
        return True, "Accept"
    if fare < r["floor"]:
        return False, "TOO_CHEAP"
    if m == "BAND" and fare > r["ceiling"]:
        return False, "TOO_EXPENSIVE"
    if m == "PER_MINUTE":
        per = fare / exact if exact > 0 else 0.0
        if per < r["rate"]:
            return False, "LOW_PER_MINUTE"
    return True, "Accept"


# ─────────────────────────────── adb plumbing ───────────────────────────────

def adb(*args, check=True):
    out = subprocess.run([ADB, *args], capture_output=True, text=True)
    if check and out.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed:\n{out.stdout}{out.stderr}")
    return out.stdout


def shell(*args, check=True):
    return adb("shell", *args, check=check)


def logcat(*tags):
    spec = [f"{t}:I" for t in tags]
    return adb("logcat", "-d", "-v", "brief", "-s", *spec).splitlines()


def set_rules(r):
    shell("am", "broadcast", "-a", "jo.autofare.debug.SET_RULES",
          "-n", "jo.autofare/.debug.E2eRulesReceiver",
          "--es", "mode", r["mode"], "--es", "rate", str(r["rate"]),
          "--es", "floor", str(r["floor"]), "--es", "ceiling", str(r["ceiling"]),
          "--es", "maxEta", str(r["maxEta"]),
          "--es", "jeeny", "on" if r["jeeny"] else "off",
          "--es", "petra", "on" if r["petra"] else "off",
          "--es", "noPrice", "on" if r["noPrice"] else "off")
    time.sleep(SETTLE_S)


def show_offer(app, sid, fare, eta, km, timeout_ms=None):
    args = ["am", "start", "-n", f"{app}/.FakeOfferActivity", "--es", "cmd", "offer",
            "--es", "id", sid, "--es", "fare", "none" if fare is None else f"{fare:.2f}",
            "--es", "eta", eta, "--es", "km", km]
    if timeout_ms:
        args += ["--es", "timeout", str(timeout_ms)]
    shell(*args)


RESULT = re.compile(r"result id=(\S+) pkg=(\S+) fare=(\S+) eta=(\S+) km=(\S+) "
                    r"accepted=(\w+) pressed=(\w+) latencyMs=(-?\d+) sinceShowMs=(-?\d+)")


def wait_result(sid, limit_s=OFFER_TIMEOUT_S + 6):
    deadline = time.time() + limit_s
    while time.time() < deadline:
        for line in logcat("FAKERIDE"):
            m = RESULT.search(line)
            if m and m.group(1) == sid:
                return dict(accepted=m.group(6) == "true", pressed=m.group(7),
                            latency=int(m.group(8)), since_show=int(m.group(9)))
        time.sleep(0.2)
    return None


def autofare_said(app, fare):
    """AutoFare's own log line for this offer, for the reason column only."""
    want = "null" if fare is None else repr(float(round(fare, 2)))
    said = None
    for line in logcat("AutoFareSvc"):
        if f"offer {app} · {want} " in line:
            said = line
    if not said:
        return "-", None
    parts = [p.strip() for p in said.split("·")]
    decision = parts[3] if len(parts) > 3 else "?"
    decision = re.sub(r"Skip\(reason=(\w+)\)", r"\1", decision)
    took = re.search(r"(\d+)ms\s*$", said)
    return decision, int(took.group(1)) if took else None


# ─────────────────────────────── setup ───────────────────────────────

def must_be_emulator():
    if shell("getprop", "ro.kernel.qemu").strip() != "1" and \
            shell("getprop", "ro.boot.qemu").strip() != "1":
        sys.exit("Refusing: this is not an emulator. The fake ride apps take the real "
                 "package names of Jeeny and Petra Ride and must never go on a real phone.")


def build():
    env = dict(os.environ, JAVA_HOME=JAVA_HOME,
               ANDROID_HOME=os.path.expanduser("~/Library/Android/sdk"))
    print("building AutoFare debug + fake Jeeny + fake Petra ...", flush=True)
    subprocess.run([GRADLE, "-q", ":app:assembleDebug", ":fakeride:jeeny:assembleDebug",
                    ":fakeride:petra:assembleDebug"], cwd=ANDROID, env=env, check=True)


def install():
    for apk in ["app/build/outputs/apk/debug/app-debug.apk",
                "fakeride/jeeny/build/outputs/apk/debug/jeeny-debug.apk",
                "fakeride/petra/build/outputs/apk/debug/petra-debug.apk"]:
        print(f"installing {apk}", flush=True)
        adb("install", "-r", os.path.join(ANDROID, apk))


def ensure_service():
    current = shell("settings", "get", "secure", "enabled_accessibility_services").strip()
    if SERVICE not in current:
        print("accessibility service was off - turning it on", flush=True)
        shell("settings", "put", "secure", "enabled_accessibility_services", SERVICE)
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    shell("input", "keyevent", "KEYCODE_WAKEUP", check=False)
    shell("wm", "dismiss-keyguard", check=False)
    shell("svc", "power", "stayon", "true", check=False)
    # The table alternates between the two fake apps, so most offers arrive as a
    # task switch. With animations on, the press waits behind the window
    # transition and the latency measures the emulator's animation, not AutoFare.
    # A real offer pops up inside an app that is already in front.
    for scale in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        shell("settings", "put", "global", scale, "0", check=False)
    # the service has to be connected before the first offer
    deadline = time.time() + 15
    while time.time() < deadline:
        if "Bound services:{Service[label=AutoFare" in shell("dumpsys", "accessibility"):
            time.sleep(2)
            return
        time.sleep(0.5)
    sys.exit("AutoFare's accessibility service did not bind")


# ─────────────────────────────── the run ───────────────────────────────

def run():
    adb("logcat", "-c")
    rows = []
    n = 0
    stamp = time.strftime("%H%M%S")
    last_press_at = 0.0

    for title, r, offers in GROUPS:
        set_rules(r)
        for app, fare, eta, km, note in offers:
            n += 1
            sid = f"S{n:02d}-{stamp}"

            # AutoAccept presses nothing for 8 s after a press
            gap = COOLDOWN_S - (time.time() - last_press_at)
            if gap > 0:
                time.sleep(gap)

            adb("logcat", "-c")  # so AutoFare's line for this offer is the only one
            show_offer(app, sid, fare, eta, km)
            res = wait_result(sid)
            if res and res["pressed"] != "none":
                last_press_at = time.time()
            time.sleep(0.3)  # let AutoFare's after-the-press log line land
            said, took = autofare_said(app, fare)

            exp_ok, exp_why = expected(app, fare, eta, r)
            if res is None:
                actual, ok = "NO RESULT", False
            elif res["pressed"] == "reject":
                actual, ok = "PRESSED REJECT", False
            else:
                actual = "accepted" if res["accepted"] else "skipped"
                ok = res["accepted"] == exp_ok
            rows.append(dict(n=n, group=title, app=SHORT[app], fare=fare, eta=eta, note=note,
                             expected=("accept" if exp_ok else f"skip {exp_why}"),
                             actual=actual, said=said, ok=ok,
                             latency=res["latency"] if res and res["accepted"] else None,
                             took=took, warm=note != "warm-up"))
            print(f"  {n:2d} {SHORT[app]:5s} {fmt(fare):>5s} eta {eta:>4s}  "
                  f"expect {rows[-1]['expected']:<20s} got {actual:<9s} "
                  f"{'PASS' if ok else 'FAIL'}", flush=True)
    return rows


def fmt(fare):
    return "none" if fare is None else f"{fare:.2f}"


def report(rows):
    print()
    print(f"{'#':>2} {'app':5} {'fare':>5} {'eta':>5}  {'scenario':32} {'expected':22} "
          f"{'actual':9} {'AutoFare said':15} {'ms (1000 = 1 second)':>20}  result")
    print("-" * 135)
    group = None
    for r in rows:
        if r["group"] != group:
            group = r["group"]
            print(f"   [{group}]")
        ms = "" if r["latency"] is None else str(r["latency"])
        print(f"{r['n']:2d} {r['app']:5} {fmt(r['fare']):>5} {r['eta']:>5}  {r['note'][:32]:32} "
              f"{r['expected']:22} {r['actual']:9} {r['said'][:15]:15} {ms:>20}  "
              f"{'PASS' if r['ok'] else 'FAIL'}")
    print("-" * 135)

    passed = sum(r["ok"] for r in rows)
    print(f"{passed}/{len(rows)} scenarios passed")

    for label, pick in [("all", lambda r: True),
                        ("Petra", lambda r: r["app"] == "Petra"),
                        ("Jeeny", lambda r: r["app"] == "Jeeny")]:
        lat = [r["latency"] for r in rows if r["latency"] is not None and r["warm"] and pick(r)]
        if lat:
            print(f"latency draw->click, {label:5} (n={len(lat):2d}): min {min(lat)} ms, "
                  f"median {statistics.median(lat):.0f} ms, max {max(lat)} ms")
    cold = [r for r in rows if not r["warm"] and r["latency"] is not None]
    for r in cold:
        print(f"cold first offer, {r['app']}: {r['latency']} ms (warm-up, excluded above)")
    print("(ms = milliseconds; 1000 ms = 1 second)")
    took = [r["took"] for r in rows if r["took"] is not None and r["warm"] and r["actual"] == "accepted"]
    if took:
        print(f"AutoFare's own tookMs on accepts: min {min(took)}, "
              f"median {statistics.median(took):.0f}, max {max(took)}")
    return passed == len(rows)


def screens(out_dir):
    """Before/after pictures, and a uiautomator check of Petra's view ids."""
    os.makedirs(out_dir, exist_ok=True)
    stamp = time.strftime("%H%M%S")

    def cap(name):
        path = os.path.join(out_dir, name)
        with open(path, "wb") as f:
            f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"],
                                   capture_output=True).stdout)
        print(f"saved {path}")

    for app in (PETRA, JEENY):
        name = SHORT[app].lower()
        # before: the app switched off, so the offer just sits there
        set_rules(rules(**{APP_ID[app]: False}))
        time.sleep(COOLDOWN_S)
        show_offer(app, f"{name}-before-{stamp}", 3.25, "4.6", "1.7", timeout_ms=30000)
        time.sleep(1.5)
        cap(f"e2e-{name}-offer-before.png")
        if app == PETRA:
            dump = subprocess.run([ADB, "exec-out", "uiautomator", "dump", "/dev/tty"],
                                  capture_output=True, text=True).stdout
            ids = sorted(set(re.findall(r'resource-id="(com\.PetraRide_Captain:id/[^"]+)"', dump)))
            with open(os.path.join(out_dir, "e2e-petra-resource-ids.txt"), "w") as f:
                f.write("\n".join(ids) + "\n")
            print("petra view ids seen by uiautomator:", ", ".join(i.split("/")[-1] for i in ids))
        shell("am", "start", "-n", f"{app}/.FakeOfferActivity", "--es", "cmd", "home")
        # after: the same offer, armed
        set_rules(rules())
        show_offer(app, f"{name}-after-{stamp}", 3.25, "4.6", "1.7")
        wait_result(f"{name}-after-{stamp}")
        time.sleep(0.8)
        cap(f"e2e-{name}-offer-after.png")


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    p.add_argument("--no-build", action="store_true")
    p.add_argument("--no-install", action="store_true")
    p.add_argument("--screens", metavar="DIR", help="save before/after screenshots here")
    p.add_argument("--screens-only", action="store_true", help="skip the table, only take screenshots")
    a = p.parse_args()

    must_be_emulator()
    if not a.no_build:
        build()
    if not a.no_install:
        install()
    ensure_service()

    ok = True
    if not a.screens_only:
        rows = run()
        ok = report(rows)
    if a.screens:
        screens(a.screens)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
