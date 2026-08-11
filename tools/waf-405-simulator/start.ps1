param(
    [string]$Serial = "emulator-5554",
    [int]$Port = 8080,
    [string]$ApplicationId = "me.thenano.yamibo.yamibo_app.debug",
    [switch]$PrepareOnly,
    [switch]$ProxyOnly,
    [string]$GeneratedResourceDirectory = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$runtimeRoot = Join-Path $repoRoot "build\qa\waf-405-simulator"
$localRuntimeRoot = Join-Path $env:LOCALAPPDATA "Yamibo\waf-405-simulator"
$packagesRoot = Join-Path $localRuntimeRoot "python-packages"
$mitmConfig = Join-Path $localRuntimeRoot "mitmproxy"
$pidFile = Join-Path $runtimeRoot "mitmdump.pid"
$readyFile = Join-Path $runtimeRoot "simulator.ready"
$stdoutLog = Join-Path $runtimeRoot "mitmdump.stdout.log"
$stderrLog = Join-Path $runtimeRoot "mitmdump.stderr.log"
$addon = Join-Path $PSScriptRoot "waf_405_addon.py"
$runner = Join-Path $PSScriptRoot "run_mitmdump.py"
$stdinFile = Join-Path $runtimeRoot "mitmdump.stdin"

$sdkRoot = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb.exe was not found at $adb"
}

New-Item -ItemType Directory -Force -Path $runtimeRoot, $localRuntimeRoot, $mitmConfig, $packagesRoot | Out-Null
Remove-Item -LiteralPath $readyFile -Force -ErrorAction SilentlyContinue

$bundledPython = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
$python = if (Test-Path -LiteralPath $bundledPython) {
    $bundledPython
} else {
    (Get-Command python -ErrorAction Stop).Source
}
if (-not (Test-Path -LiteralPath (Join-Path $packagesRoot "mitmproxy"))) {
    $pipArguments = @(
        "-m", "pip", "install",
        "--disable-pip-version-check",
        "--target", $packagesRoot,
        "mitmproxy==12.2.3"
    )
    & $python @pipArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to install the local mitmproxy runtime"
    }
}

if (Test-Path -LiteralPath $pidFile) {
    $oldPid = [int](Get-Content -Raw $pidFile)
    Stop-Process -Id $oldPid -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $pidFile -Force
}

$proxyArguments = @(
    $runner,
    "--set", "confdir=$mitmConfig",
    "--set", "flow_detail=1",
    "--listen-host", "127.0.0.1",
    "--listen-port", $Port,
    "--scripts", $addon
)
if (-not (Test-Path -LiteralPath $stdinFile)) {
    New-Item -ItemType File -Path $stdinFile | Out-Null
}
$previousPythonPath = $env:PYTHONPATH
$env:PYTHONPATH = $packagesRoot
try {
    $proxy = Start-Process `
        -FilePath $python `
        -ArgumentList $proxyArguments `
        -WorkingDirectory $repoRoot `
        -RedirectStandardInput $stdinFile `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -WindowStyle Hidden `
        -PassThru
} finally {
    $env:PYTHONPATH = $previousPythonPath
}
$proxyPid = $proxy.Id
$proxyPid | Set-Content -NoNewline $pidFile

$certificate = Join-Path $mitmConfig "mitmproxy-ca-cert.cer"
for ($attempt = 0; $attempt -lt 50 -and -not (Test-Path -LiteralPath $certificate); $attempt++) {
    Start-Sleep -Milliseconds 200
}
if (-not (Test-Path -LiteralPath $certificate)) {
    throw "mitmproxy did not generate its CA certificate; inspect $stderrLog"
}

if ($GeneratedResourceDirectory) {
    $rawDirectory = Join-Path $GeneratedResourceDirectory "raw"
    $xmlDirectory = Join-Path $GeneratedResourceDirectory "xml"
    New-Item -ItemType Directory -Force -Path $rawDirectory, $xmlDirectory | Out-Null
    Copy-Item -LiteralPath $certificate -Destination (Join-Path $rawDirectory "waf_simulator_ca.cer") -Force
    @'
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="@raw/waf_simulator_ca" />
        </trust-anchors>
    </base-config>
</network-security-config>
'@ | Set-Content -Encoding UTF8 (Join-Path $xmlDirectory "debug_waf_network_security_config.xml")
}

if ($PrepareOnly) {
    Stop-Process -Id $proxyPid -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    Write-Output "Generated debug WAF CA resources at $GeneratedResourceDirectory"
    return
}

& $adb -s $Serial get-state | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Android target $Serial is unavailable"
}

& $adb -s $Serial shell settings put global http_proxy "10.0.2.2:$Port"
if ($ProxyOnly) {
    $proxyPid | Set-Content -NoNewline $readyFile
    Write-Output "HTTP 405 simulator is active for $Serial on proxy 10.0.2.2:$Port."
    Write-Output "The IDE remains responsible for installing, launching, and debugging the app."
    return
}

& $adb -s $Serial shell am force-stop $ApplicationId
$monkey = Start-Process `
    -FilePath $adb `
    -ArgumentList @("-s", $Serial, "shell", "monkey", "-p", $ApplicationId, "-c", "android.intent.category.LAUNCHER", "1") `
    -WindowStyle Hidden `
    -Wait `
    -PassThru
if ($monkey.ExitCode -ne 0) {
    throw "Could not launch Android application $ApplicationId"
}
$proxyPid | Set-Content -NoNewline $readyFile

Write-Output "HTTP 405 simulator is active for $Serial on proxy 10.0.2.2:$Port."
Write-Output "Run :composeApp:stopDebugWafEnvironment to restore normal networking."
