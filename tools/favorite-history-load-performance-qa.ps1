param(
    [string]$Serial = "",
    [string]$Package = "me.thenano.yamibo.yamibo_app",
    [string]$Adb = "",
    [int]$Runs = 5,
    [int]$HomeX = 120,
    [int]$FavoritesX = 835,
    [int]$HistoryX = 385,
    [int]$TabY = 2485
)

$ErrorActionPreference = "Stop"
$adb = if ($Adb) { $Adb } elseif (Get-Command adb -ErrorAction SilentlyContinue) {
    "adb"
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
}
$outputRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "..\build\qa\favorite-history-load-performance")
)
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

if (!$Serial) {
    $devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
    if ($devices.Count -ne 1) { throw "Pass -Serial when adb has zero or multiple connected devices" }
    $Serial = ($devices[0] -split "\s+")[0]
}

function Invoke-Adb {
    & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($args -join ' ')" }
}

function Measure-Tab {
    param([string]$Name, [int]$X, [int]$Run, [switch]$Cold)
    if ($Cold) {
        Invoke-Adb shell input tap $HomeX $TabY | Out-Null
        Start-Sleep -Milliseconds 500
        Invoke-Adb shell am force-stop $Package | Out-Null
        Invoke-Adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
        Start-Sleep -Seconds 4
    } else {
        $otherX = if ($Name -eq "favorite") { $HistoryX } else { $FavoritesX }
        Invoke-Adb shell input tap $otherX $TabY | Out-Null
        Start-Sleep -Milliseconds 500
    }

    Invoke-Adb shell setprop log.tag.FH_LOAD DEBUG | Out-Null
    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell dumpsys gfxinfo $Package reset | Out-Null
    Invoke-Adb shell input tap $X $TabY | Out-Null
    Start-Sleep -Seconds 4

    $prefix = Join-Path $outputRoot "$Name-$(if ($Cold) { 'cold' } else { 'warm' })-$Run"
    & $adb -s $Serial logcat -d -s "FH_LOAD:D" "*:S" |
        Set-Content -Encoding utf8 "$prefix-logcat.txt"
    if ($LASTEXITCODE -ne 0) { throw "adb logcat capture failed" }
    Invoke-Adb shell dumpsys gfxinfo $Package framestats | Set-Content -Encoding utf8 "$prefix-gfxinfo.txt"
    Invoke-Adb shell dumpsys meminfo $Package | Set-Content -Encoding utf8 "$prefix-meminfo.txt"
}

Invoke-Adb wait-for-device | Out-Null
1..$Runs | ForEach-Object {
    Measure-Tab -Name favorite -X $FavoritesX -Run $_ -Cold
    Measure-Tab -Name history -X $HistoryX -Run $_
    Measure-Tab -Name favorite -X $FavoritesX -Run $_
    Measure-Tab -Name history -X $HistoryX -Run $_ -Cold
}

$records = Get-ChildItem $outputRoot -Filter "*-logcat.txt" | ForEach-Object {
    $events = Get-Content $_.FullName | Select-String "FH_LOAD\|"
    $start = $events | Select-String "stage=request_start" | Select-Object -First 1
    $render = $events | Select-String "stage=first_content_rendered" | Select-Object -First 1
    if (!$start -or !$render) { return }
    $startAt = [long]([regex]::Match($start.Line, 'at=(\d+)').Groups[1].Value)
    $renderAt = [long]([regex]::Match($render.Line, 'at=(\d+)').Groups[1].Value)
    [pscustomobject]@{ Run = $_.BaseName; FirstContentMillis = $renderAt - $startAt }
}
$records | Sort-Object Run | Export-Csv -NoTypeInformation (Join-Path $outputRoot "summary.csv")
Write-Host "Captured favorite/history load QA under $outputRoot"
