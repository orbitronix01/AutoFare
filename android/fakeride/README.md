# fakeride: fake Jeeny and Petra Ride for the emulator

> **EMULATOR ONLY. NEVER install these on a real driver's phone.**
> The two APKs use the **real package names** `me.com.easytaxista` (Jeeny) and
> `com.PetraRide_Captain` (Petra Ride). On a phone that has the real apps they
> would either fail to install or, after an uninstall, **replace the driver's
> real ride app**. `scripts/e2e-rules.py` refuses to run on anything that is not
> an emulator. If they ever end up on a phone:
>
>     adb uninstall me.com.easytaxista
>     adb uninstall com.PetraRide_Captain

## Why they exist

To watch AutoFare's rules work on an emulator: offers that meet the rules get
accepted, offers that don't get left alone, and you can see how fast the press
lands. The real, unmodified AutoFare detectors see these apps as Jeeny and
Petra, because:

- `applicationId` **and** `namespace` are the real package. The namespace is
  what view ids are reported under, so Petra's ids come out as
  `com.PetraRide_Captain:id/accept_button`. This was checked with
  `uiautomator dump`.
- The offer screens copy the structure of the captured screens in
  `app/src/test/resources/captures`: Petra's view ids; Jeeny's id-less texts,
  `يبعُد` with the damma, the LRM before `JOD`, `desc="Reject Button"`, and the
  non-clickable `قبول العرض` text inside a clickable wrapper. The addresses are
  made up.

Debug only: the release variant is disabled in both modules.

## Driving them

    adb shell am start -n com.PetraRide_Captain/.FakeOfferActivity \
        --es cmd offer --es id T1 --es fare 2.50 --es eta 5.72 --es km 1.4
    adb shell am start -n me.com.easytaxista/.FakeOfferActivity --es cmd home

`fare` can be `none` (an offer with no price). `timeout` (ms, default 5000)
sets how long the offer waits for a press. Each offer ends with one logcat line:

    FAKERIDE result id=T1 pkg=com.PetraRide_Captain fare=2.50 eta=5.72 km=1.4 accepted=true pressed=accept latencyMs=48 sinceShowMs=71

`latencyMs` runs from the first frame the offer is drawn in to the moment the
accept button's click listener runs, measured inside the fake app. It covers
accessibility event delivery, AutoFare's detection and decision, and the click
being sent back. `sinceShowMs` starts earlier, at `setContentView`.
On screen the result is plain Arabic with Arabic-Indic digits and seconds
(`✓ انقبل خلال ٠٫١٤ ثانية` / `✗ ما انقبل`). In the offer itself every figure
uses the digits the real app's captures use for that field: Latin everywhere on
Petra, and on Jeeny Latin for the ETA and fare and Arabic-Indic for the distance
(`--es kmDigits latin` switches the distance to Latin, which is also seen).
`pressed=reject` would mean AutoFare pressed Reject, which it must never do.

## The one-command test

    android/scripts/e2e-rules.py [--no-build] [--screens DIR]

This builds and installs everything, sets the rules through the debug-only
`E2eRulesReceiver` (`app/src/debug`), runs the scenario table through both
fake apps, and prints expected vs actual plus latency stats.
