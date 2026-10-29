# Jarvis: pobiera nową wersję, włącza emulator (jeśli nie działa), instaluje i otwiera aplikację.
# Uruchomienie: powershell -ExecutionPolicy Bypass -File .\uruchom.ps1
$ErrorActionPreference = "Stop"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
Set-Location $PSScriptRoot

Write-Host "== Pobieram nowa wersje ==" -ForegroundColor Cyan
git pull

if (-not (& $adb devices | Select-String "emulator-")) {
    $avd = & $emu -list-avds | Where-Object { $_ -and $_ -notmatch "^INFO" } | Select-Object -First 1
    if (-not $avd) { Write-Host "Brak emulatora - utworz go w Android Studio (Device Manager)." -ForegroundColor Red; exit 1 }
    Write-Host "== Wlaczam emulator $avd ==" -ForegroundColor Cyan
    Start-Process $emu -ArgumentList "-avd", $avd, "-allow-host-audio"
    & $adb wait-for-device
    do { Start-Sleep 2 } until ("$(& $adb shell getprop sys.boot_completed)".Trim() -eq "1")
}

Write-Host "== Buduje i instaluje Jarvisa ==" -ForegroundColor Cyan
.\gradlew installDebug
if ($LASTEXITCODE -ne 0) { Write-Host "Budowanie nie wyszlo - wklej Claude'owi to, co wyzej." -ForegroundColor Red; exit 1 }

& $adb shell monkey -p pl.jarvis -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "== Gotowe - Jarvis otwarty na emulatorze ==" -ForegroundColor Green
