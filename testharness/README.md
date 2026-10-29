# AutoFare test harness

Two throwaway APKs that pretend to be the offer screen of a ride app, so the
accept path can be proven on a real phone without a real driver account.

Each module takes the **package name of the app it stands in for**, because that
is the only way to reproduce what AutoFare actually matches on:

| module | applicationId          | stands in for  |
|--------|------------------------|----------------|
| petra  | `com.PetraRide_Captain`| سفير بترا رايد |
| jeeny  | `me.com.easytaxista`   | Jeeny driver   |

A view's `viewIdResourceName` is reported as `<package>:id/<name>`, so
`com.PetraRide_Captain:id/accept_button` — the selector the real detector uses —
only exists if the app holding that view is installed under that package.

The layouts are copies of real captured screens from the 2026-10-27 driver shift,
down to the details that break naive parsing: the invisible LRM in front of
`JOD 3.43`, the damma in `يبعُد`, the bare `م` for metres, and Jeeny's accept
button being a **non-clickable TextView inside a clickable wrapper**.

Each accept button logs `HARNESS: accept pressed` to logcat when it is clicked.
That log line appearing without anybody touching the phone is the proof.

## Running it

    .\run-harness.ps1

## Do not leave these installed

They occupy the package names of two real apps. The script uninstalls them when
it finishes; if it is interrupted, remove them by hand:

    adb uninstall com.PetraRide_Captain
    adb uninstall me.com.easytaxista
