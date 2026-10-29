# Jarvis: pobiera nową wersję, włącza emulator (jeśli nie działa), instaluje i otwiera aplikację.
# Uruchomienie: powershell -ExecutionPolicy Bypass -File .\uruchom.ps1
$ErrorActionPreference = "Stop"
# SDK jak w local.properties (C:\Android\sdk) - stare SDK w %LOCALAPPDATA% ma zepsuty emulator 36.x
$sdk = "C:\Android\sdk"
$sdkLine = Get-Content "$PSScriptRoot\local.properties" -ErrorAction SilentlyContinue | Where-Object { $_ -match "^sdk\.dir=" }
if ($sdkLine) { $sdk = ($sdkLine -replace "^sdk\.dir=", "") -replace "\\:", ":" -replace "\\\\", "\" }
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
# Gradle 8.14 nie dziala na Javie 25 (jbr z Android Studio) - bierzemy JDK 21
$jdk21 = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter "*21*" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk21) { $env:JAVA_HOME = $jdk21.FullName }
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

# Norton podmienia HTTPS na tym komputerze - emulator musi ufac jego certyfikatowi,
# inaczej nie dziala rozpoznawanie mowy Google, AI ani pobieranie glosu. Dziala do restartu emulatora.
$norton = Get-ChildItem Cert:\LocalMachine\Root, Cert:\CurrentUser\Root | Where-Object Subject -like "*Norton Web/Mail Shield Root*" | Select-Object -First 1
$openssl = "C:\Program Files\Git\usr\bin\openssl.exe"
if ($norton -and (Test-Path $openssl)) {
    $pem = "$env:TEMP\norton_root.pem"
    "-----BEGIN CERTIFICATE-----`n" + [Convert]::ToBase64String($norton.RawData, 'InsertLineBreaks') + "`n-----END CERTIFICATE-----" | Set-Content $pem -Encoding ascii
    $hash = "$(& $openssl x509 -in $pem -noout -subject_hash_old)".Trim()
    & $adb root | Out-Null
    Start-Sleep 2
    & $adb wait-for-device
    $zygote = "$(& $adb shell pidof zygote64)".Trim()
    $present = "$(& $adb shell "nsenter --mount=/proc/$zygote/ns/mnt -- ls /apex/com.android.conscrypt/cacerts/$hash.0 2>/dev/null")".Trim()
    if (-not $present) {
        Write-Host "== Dodaje certyfikat Nortona do emulatora ==" -ForegroundColor Cyan
        & $adb push $pem "/data/local/tmp/$hash.0" | Out-Null
        & $adb push "$PSScriptRoot\narzedzia\ca-systemowy.sh" /data/local/tmp/ca-systemowy.sh | Out-Null
        & $adb shell sh /data/local/tmp/ca-systemowy.sh "/data/local/tmp/$hash.0"
        & $adb shell am force-stop com.google.android.tts
        & $adb shell am force-stop com.google.android.googlequicksearchbox
    }
}

Write-Host "== Buduje i instaluje Jarvisa ==" -ForegroundColor Cyan
.\gradlew installDebug
if ($LASTEXITCODE -ne 0) { Write-Host "Budowanie nie wyszlo - wklej Claude'owi to, co wyzej." -ForegroundColor Red; exit 1 }

& $adb shell monkey -p pl.jarvis -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "== Gotowe - Jarvis otwarty na emulatorze ==" -ForegroundColor Green
