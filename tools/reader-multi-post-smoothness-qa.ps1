param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "me.thenano.yamibo.yamibo_app",
    [string]$Adb = "",
    [int]$Run = 1,
    [int]$Cycles = 2,
    [string]$OutputGroup = "",
    [int]$GestureX = 540,
    [int]$GestureTopY = 650,
    [int]$GestureBottomY = 1850,
    [int]$OverlayTapY = 400,
    [int]$RetryTapY = 1200,
    [switch]$Instrumentation,
    [switch]$SkipReset,
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

$outputRoot = Join-Path $PSScriptRoot "..\build\qa\reader-multi-post-smoothness"
$outputRoot = [System.IO.Path]::GetFullPath($outputRoot)
if ($OutputGroup) {
    $outputRoot = Join-Path $outputRoot $OutputGroup
}
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

function Get-UiTree {
    param([string]$Name)
    $devicePath = "/sdcard/$Name.xml"
    $hostPath = Join-Path $outputRoot "$Name.xml"
    Invoke-Adb shell uiautomator dump $devicePath | Out-Null
    Invoke-Adb pull $devicePath $hostPath | Out-Null
    return [xml](Get-Content -Raw $hostPath)
}

function Find-UiNode {
    param([xml]$Tree, [string]$Value)
    return $Tree.SelectNodes("//node") |
        Where-Object { $_.text -eq $Value -or $_.'content-desc' -eq $Value } |
        Select-Object -First 1
}

function Invoke-NodeTap {
    param($Node)
    if (!$Node) { throw "UI node was not found" }
    $match = [regex]::Match($Node.bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (!$match.Success) { throw "Unsupported UI bounds: $($Node.bounds)" }
    $x = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
    $y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
    Invoke-Adb shell input tap ([int]$x) ([int]$y) | Out-Null
}

function Reset-ReaderAnchor {
    $tree = Get-UiTree "run-$Run-reset-initial"
    $catalogFloor = Find-UiNode $tree "6#"
    $catalogTitle = Find-UiNode $tree "目錄"

    if (!$catalogTitle) {
        $menu = Find-UiNode $tree "☰"
        if (!$menu) {
            if (Find-UiNode $tree "取消") {
                Invoke-Adb shell input keyevent 4 | Out-Null
                Start-Sleep -Milliseconds 300
            }
            # Use the upper content gutter; the center of a short post can be a rating action.
            Invoke-Adb shell input tap $GestureX $OverlayTapY | Out-Null
            Start-Sleep -Milliseconds 500
            $tree = Get-UiTree "run-$Run-reset-controls"
            $menu = Find-UiNode $tree "☰"
        }
        if (!$menu) { throw "Reader overlay menu was not found; open the multi-post fixture first" }
        Invoke-NodeTap $menu
        Start-Sleep -Milliseconds 500
        $tree = Get-UiTree "run-$Run-reset-catalog"
        $catalogFloor = Find-UiNode $tree "6#"
    }

    if (!$catalogFloor) {
        1..4 | ForEach-Object {
            Invoke-Adb shell input swipe $GestureX $GestureTopY $GestureX $GestureBottomY 250 | Out-Null
            Start-Sleep -Milliseconds 150
        }
        $tree = Get-UiTree "run-$Run-reset-catalog-top"
        $catalogFloor = Find-UiNode $tree "6#"
    }
    if (!$catalogFloor) { throw "Fixture floor 6 was not found in the reader catalog" }

    Invoke-NodeTap $catalogFloor
    Start-Sleep -Seconds 1
    $tree = Get-UiTree "run-$Run-reset-result"
    if (Find-UiNode $tree "重新整理") {
        Invoke-Adb shell input tap $GestureX $RetryTapY | Out-Null
        Start-Sleep -Milliseconds 400
        $tree = Get-UiTree "run-$Run-reset-hidden"
    }
    if (!(Find-UiNode $tree "6#")) {
        throw "Reader did not return to the expected floor-6 anchor"
    }
}

function Invoke-MultiPostScroll {
    param([int]$CycleCount)
    1..$CycleCount | ForEach-Object {
        1..4 | ForEach-Object {
            Invoke-Adb shell input swipe $GestureX $GestureBottomY $GestureX $GestureTopY 350 | Out-Null
            Start-Sleep -Milliseconds 250
        }
        1..4 | ForEach-Object {
            Invoke-Adb shell input swipe $GestureX $GestureTopY $GestureX $GestureBottomY 350 | Out-Null
            Start-Sleep -Milliseconds 250
        }
    }
}

Invoke-Adb wait-for-device | Out-Null
if (!$SkipReset) {
    Reset-ReaderAnchor
}

if ($WarmupOnly) {
    Invoke-MultiPostScroll 1
    Reset-ReaderAnchor
    Write-Host "Warm-up complete; reader returned to floor 6."
    exit 0
}

$prefix = Join-Path $outputRoot ("run-{0}" -f $Run)
Invoke-Adb shell setprop log.tag.TR_PROF $(if ($Instrumentation) { "DEBUG" } else { "INFO" }) | Out-Null
Invoke-Adb shell dumpsys gfxinfo $Package reset | Out-Null
Invoke-Adb logcat -c | Out-Null
Invoke-Adb shell dumpsys meminfo $Package | Set-Content -Encoding utf8 "$prefix-meminfo-before.txt"

Invoke-MultiPostScroll $Cycles
Start-Sleep -Milliseconds 500

Invoke-Adb shell dumpsys gfxinfo $Package framestats | Set-Content -Encoding utf8 "$prefix-gfxinfo.txt"
Invoke-Adb shell dumpsys meminfo $Package | Set-Content -Encoding utf8 "$prefix-meminfo-after.txt"
$appPid = (Invoke-Adb shell pidof -s $Package | Select-Object -First 1).Trim()
if (!$appPid) { throw "App process is not running: $Package" }
& $adb -s $Serial logcat -d "--pid=$appPid" -v threadtime |
    Set-Content -Encoding utf8 "$prefix-logcat.txt"
if ($LASTEXITCODE -ne 0) { throw "adb logcat failed" }

$finalTree = Get-UiTree "run-$Run-final"
$visibleFloors = $finalTree.SelectNodes("//node") |
    ForEach-Object { $_.text } |
    Where-Object { $_ -match '^\d+#$' }
$visibleFloors -join ', ' | Set-Content -Encoding utf8 "$prefix-visible-floors.txt"

Write-Host "Captured run $Run under $outputRoot"
Write-Host "Visible floors at completion: $($visibleFloors -join ', ')"
