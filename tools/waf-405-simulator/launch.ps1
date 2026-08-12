param(
    [string]$Serial = "emulator-5554",
    [int]$Port = 8080,
    [string]$ApplicationId = "me.thenano.yamibo.yamibo_app.debug",
    [switch]$ProxyOnly
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$runtimeRoot = Join-Path $repoRoot "build\qa\waf-405-simulator"
$pidFile = Join-Path $runtimeRoot "mitmdump.pid"
$readyFile = Join-Path $runtimeRoot "simulator.ready"
$bootstrapLog = Join-Path $runtimeRoot "launcher.log"
$startScript = Join-Path $PSScriptRoot "start.ps1"
$stopScript = Join-Path $PSScriptRoot "stop.ps1"

New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
& $stopScript -Serial $Serial | Out-Null

function Quote-PowerShellLiteral([string]$Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

$startCommand = @(
    "`$ErrorActionPreference = 'Stop'"
    "& $(Quote-PowerShellLiteral $startScript) -Serial $(Quote-PowerShellLiteral $Serial) -Port $Port -ApplicationId $(Quote-PowerShellLiteral $ApplicationId)$(if ($ProxyOnly) { ' -ProxyOnly' } else { '' })"
) -join "; "
$loggedCommand = "try { $startCommand 2>&1 | Set-Content -Encoding UTF8 $(Quote-PowerShellLiteral $bootstrapLog) } catch { `$_ | Out-String | Set-Content -Encoding UTF8 $(Quote-PowerShellLiteral $bootstrapLog); exit 1 }"
$encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($loggedCommand))
$commandLine = "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encodedCommand"
$result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $commandLine }
if ($result.ReturnValue -ne 0) {
    throw "Could not launch the detached WAF simulator (Win32 error $($result.ReturnValue))"
}

for ($attempt = 0; $attempt -lt 100; $attempt++) {
    if ((Test-Path -LiteralPath $pidFile) -and (Test-Path -LiteralPath $readyFile)) {
        $proxyPid = [int](Get-Content -Raw $pidFile)
        $readyPid = [int](Get-Content -Raw $readyFile)
        if ($readyPid -eq $proxyPid -and (Get-Process -Id $proxyPid -ErrorAction SilentlyContinue)) {
            Write-Output "HTTP 405 simulator started in the background (PID $proxyPid)."
            Write-Output "Run :composeApp:stopDebugWafEnvironment to restore normal networking."
            return
        }
    }
    Start-Sleep -Milliseconds 200
}

$details = if (Test-Path -LiteralPath $bootstrapLog) {
    Get-Content -Raw $bootstrapLog
} else {
    "The detached launcher produced no log."
}
throw "The detached WAF simulator did not start. $details"
