# AutoFare — build the APK.
#   .\build.ps1                     debug build
#   .\build.ps1 -Install            debug build, then install on the connected phone
#   .\build.ps1 -Release            signed release build, copied to dist\ for sending
#   .\build.ps1 -Release -Install   both
param([switch]$Install, [switch]$Release)

$ErrorActionPreference = 'Stop'

$env:JAVA_HOME    = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
# Gradle 9.3.1 is the floor for AGP 9.1, which Material 3 Expressive requires.
$gradle = "C:\Users\96277\.gradle-dist\gradle-9.3.1\bin\gradle.bat"
$adb    = "$env:ANDROID_HOME\platform-tools\adb.exe"

Set-Location $PSScriptRoot

if ($Release) {
    & $gradle assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "build failed" }

    $built = Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk"

    # name the file after the version, so a driver can tell two builds apart over WhatsApp
    $version = (Select-String -Path (Join-Path $PSScriptRoot "app\build.gradle.kts") `
        -Pattern 'versionName\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
    $dist = Join-Path (Split-Path $PSScriptRoot -Parent) "dist"
    if (-not (Test-Path $dist)) { New-Item -ItemType Directory -Path $dist | Out-Null }
    $apk = Join-Path $dist "AutoFare-v$version.apk"
    Copy-Item $built $apk -Force
} else {
    & $gradle assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
    $apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
}

Write-Host "APK: $apk"

if ($Install) {
    $devices = & $adb devices | Select-String -Pattern "\tdevice$"
    if (-not $devices) { throw "No phone connected. Enable USB debugging and plug it in." }
    & $adb install -r $apk
    & $adb shell monkey -p jo.autofare -c android.intent.category.LAUNCHER 1 | Out-Null
    Write-Host "Installed and launched."
    # EMUI and One UI both switch the service off on reinstall — turn it back on
    Write-Host "Reminder: re-enable AutoFare in Accessibility settings."
}
