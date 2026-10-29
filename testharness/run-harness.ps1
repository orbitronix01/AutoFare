<#
    Proves the accept path on a real phone, end to end, without a driver account.

    Builds and installs two stand-in apps that reproduce the captured offer screens
    under the real packages, arms AutoFare's rules to take anything, launches each
    fake offer and checks that the button was actually clicked.

    The proof is the line `HARNESS: accept pressed` appearing with nobody touching
    the phone. AutoFare reporting `pressed=true` only means performAction returned
    true, which is also what happens when you press a node that ignores clicks.

    Requires the DEBUG build of AutoFare to be installed, because rules are written
    with run-as and that does not work on a release build.

    The stand-in apps are uninstalled on the way out, including on failure: they sit
    on the package names of two real apps and must not be left behind.
#>
[CmdletBinding()]
param(
    # Skip gradle and use whatever APKs are already built.
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$gradle = "C:\Users\96277\.gradle-dist\gradle-8.9\bin\gradle.bat"
$root = $PSScriptRoot
$service = "jo.autofare/jo.autofare.service.AutoFareService"

$apps = @(
    @{ id = "petra"; pkg = "com.PetraRide_Captain" }
    @{ id = "jeeny"; pkg = "me.com.easytaxista" }
)

function Enable-Service {
    # EMUI switches this off after a reinstall and after a force-stop, every time.
    & $adb shell settings put secure enabled_accessibility_services $service | Out-Null
    & $adb shell settings put secure accessibility_enabled 1 | Out-Null
    Start-Sleep -Seconds 6
}

function Set-Rules([string]$xml) {
    $b64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($xml))
    # Force-stop first or the running process never sees the new file. The shell
    # strips quotes out of XML, hence base64.
    & $adb shell am force-stop jo.autofare | Out-Null
    & $adb shell "echo $b64 | base64 -d > /data/local/tmp/ar.xml" | Out-Null
    & $adb shell "run-as jo.autofare cp /data/local/tmp/ar.xml /data/data/jo.autofare/shared_prefs/autofare_rules.xml" | Out-Null
    Enable-Service
}

function Invoke-Offer([string]$pkg) {
    & $adb shell am force-stop $pkg | Out-Null
    & $adb logcat -c | Out-Null
    & $adb shell am start -n "$pkg/.OfferActivity" | Out-Null
    Start-Sleep -Seconds 4

    $log = & $adb logcat -d -v threadtime
    [pscustomobject]@{
        Pressed  = [bool]($log | Select-String "HARNESS.*accept pressed")
        Verdict  = ($log | Select-String "AutoFareSvc.*offer $pkg" | Select-Object -First 1)
        Latency  = Measure-Latency $log $pkg
    }
}

function Measure-Latency($log, [string]$pkg) {
    $shown = $log | Select-String "Displayed $pkg" | Select-Object -First 1
    $hit = $log | Select-String "HARNESS.*accept pressed" | Select-Object -First 1
    if (-not $shown -or -not $hit) { return $null }
    $a = [datetime]::ParseExact(($shown -split '\s+')[1], "HH:mm:ss.fff", $null)
    $b = [datetime]::ParseExact(($hit -split '\s+')[1], "HH:mm:ss.fff", $null)
    [math]::Round(($b - $a).TotalMilliseconds)
}

$takeAnything = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="jeeny.enabled" value="true" />
    <int name="jeeny.mode" value="0" />
    <int name="jeeny.max_eta" value="30" />
    <boolean name="jeeny.accept_no_price" value="true" />
    <boolean name="petra.enabled" value="true" />
    <int name="petra.mode" value="0" />
    <int name="petra.max_eta" value="30" />
    <boolean name="petra.accept_no_price" value="true" />
</map>
"@

$refuseEverything = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="petra.enabled" value="true" />
    <int name="petra.mode" value="3" />
    <int name="petra.max_eta" value="30" />
    <float name="petra.rate" value="99.0" />
    <float name="petra.floor" value="0.0" />
</map>
"@

try {
    if (-not $NoBuild) {
        Write-Host "building stand-in apps..."
        Push-Location $root
        & $gradle :petra:assembleDebug :jeeny:assembleDebug --console=plain -q
        Pop-Location
    }

    foreach ($app in $apps) {
        & $adb install -r "$root\$($app.id)\build\outputs\apk\debug\$($app.id)-debug.apk" | Out-Null
    }

    Write-Host "`n=== accepts an offer that passes the rules ==="
    Set-Rules $takeAnything
    foreach ($app in $apps) {
        # Past the 8s one-ride cooldown AND the 90s same-offer memory, since the
        # harness shows a byte-identical offer every single launch.
        Start-Sleep -Seconds 95
        $r = Invoke-Offer $app.pkg
        $ms = if ($null -ne $r.Latency) { "$($r.Latency) ms" } else { "n/a" }
        Write-Host ("{0,-6} pressed={1,-5} displayed-to-press={2}" -f $app.id, $r.Pressed, $ms)
        if ($r.Verdict) { Write-Host "       $($r.Verdict.Line.Trim())" }
    }

    Write-Host "`n=== refuses an offer that fails the rules ==="
    Set-Rules $refuseEverything
    $r = Invoke-Offer "com.PetraRide_Captain"
    Write-Host ("petra  pressed={0}  (expected False)" -f $r.Pressed)
    if ($r.Verdict) { Write-Host "       $($r.Verdict.Line.Trim())" }

    Write-Host "`n=== presses nothing when switched off ==="
    Set-Rules "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><boolean name=`"petra.enabled`" value=`"false`" /></map>"
    $r = Invoke-Offer "com.PetraRide_Captain"
    Write-Host ("petra  pressed={0}  (expected False)" -f $r.Pressed)
}
finally {
    Write-Host "`nremoving stand-in apps..."
    foreach ($app in $apps) { & $adb uninstall $app.pkg | Out-Null }
    Write-Host "done. AutoFare itself is left installed and enabled."
}
