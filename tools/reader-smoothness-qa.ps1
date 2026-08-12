param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "me.thenano.yamibo.yamibo_app",
    [string]$Adb = "",
    [int]$Run = 1,
    [switch]$WarmupOnly
)

$ErrorActionPreference = "Stop"
$adb = if ($Adb) {
    $Adb
} elseif (Get-Command adb -ErrorAction SilentlyContinue) {
    "adb"
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
}
if (!(Get-Command $adb -ErrorAction SilentlyContinue) -and !(Test-Path $adb)) {
    throw "adb was not found; pass -Adb with the platform-tools adb path"
}
$outputRoot = Join-Path $PSScriptRoot "..\build\qa\reader-smoothness"
$outputRoot = [System.IO.Path]::GetFullPath($outputRoot)
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

Invoke-Adb wait-for-device | Out-Null

if ($WarmupOnly) {
    Write-Host "Warm-up: keep thread 572400 page 3 visible, then reposition to floor 57 after this finishes."
    1..3 | ForEach-Object {
        Invoke-Adb shell input swipe 540 1850 540 650 350 | Out-Null
        Start-Sleep -Milliseconds 250
    }
    exit 0
}

Write-Host "Starting measured run $Run. The reader must already be at page 3, floor 57."
$prefix = Join-Path $outputRoot ("run-{0}" -f $Run)
Invoke-Adb shell dumpsys gfxinfo $Package reset | Out-Null
Invoke-Adb logcat -c | Out-Null

1..14 | ForEach-Object {
    Invoke-Adb shell input swipe 540 1850 540 650 350 | Out-Null
    Start-Sleep -Milliseconds 250
}
Start-Sleep -Seconds 2

Invoke-Adb shell dumpsys gfxinfo $Package framestats | Set-Content -Encoding utf8 "$prefix-gfxinfo.txt"
$appPid = (Invoke-Adb shell pidof -s $Package | Select-Object -First 1).Trim()
if (!$appPid) { throw "App process is not running: $Package" }
& $adb -s $Serial logcat -d "--pid=$appPid" -v threadtime |
    Set-Content -Encoding utf8 "$prefix-logcat.txt"
if ($LASTEXITCODE -ne 0) { throw "adb logcat failed" }
Invoke-Adb shell uiautomator dump /sdcard/reader-smoothness-ui.xml | Out-Null
Invoke-Adb pull /sdcard/reader-smoothness-ui.xml "$prefix-ui.xml" | Out-Null

Write-Host "Captured $prefix-gfxinfo.txt, $prefix-logcat.txt, and $prefix-ui.xml"
Write-Host "Repeat with -Run 2 and -Run 3 after returning to the same floor-57 anchor."
