param(
    [string]$Serial = "emulator-5554"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$runtimeRoot = Join-Path $repoRoot "build\qa\waf-405-simulator"
$pidFile = Join-Path $runtimeRoot "mitmdump.pid"
$readyFile = Join-Path $runtimeRoot "simulator.ready"
$sdkRoot = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"

if (Test-Path -LiteralPath $pidFile) {
    $proxyPid = [int](Get-Content -Raw $pidFile)
    Stop-Process -Id $proxyPid -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $pidFile -Force
}
Remove-Item -LiteralPath $readyFile -Force -ErrorAction SilentlyContinue

if (Test-Path -LiteralPath $adb) {
    & $adb -s $Serial shell settings put global http_proxy :0 | Out-Null
}

Write-Output "HTTP 405 simulator stopped; emulator proxy was cleared."
