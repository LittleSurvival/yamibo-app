$ErrorActionPreference = "Stop"
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()

$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$sourceRoots = @("composeApp", "shared", "buildSrc") | ForEach-Object { Join-Path $root $_ }
$files = Get-ChildItem -Path $sourceRoots -Recurse -Include *.kt,*.kts -File |
    Where-Object {
        $_.FullName -notmatch '\\(build|\.gradle|\.kotlin|\.tmp)\\'
    }

$violations = New-Object System.Collections.Generic.List[string]

function Add-Violation {
    param(
        [string] $Path,
        [int] $Line,
        [string] $Reason
    )
    $relative = $Path
    if ($relative.StartsWith($root)) {
        $relative = $relative.Substring($root.Length).TrimStart('\', '/')
    }
    $violations.Add("${relative}:${Line}: ${Reason}")
}

foreach ($file in $files) {
    $relative = $file.FullName
    if ($relative.StartsWith($root)) {
        $relative = $relative.Substring($root.Length).TrimStart('\', '/')
    }
    $text = Get-Content -Raw -Path $file.FullName -Encoding UTF8
    $lines = $text -split "`r?`n"

    $runCatchingStartLine = 0
    $runCatchingHasOnFailure = $false

    for ($i = 0; $i -lt $lines.Length; $i++) {
        $line = $lines[$i]
        $lineNumber = $i + 1
        $isLoggerActual = $relative -eq "shared\src\nativeMain\kotlin\me\thenano\yamibo\yamibo_app\Logger.native.kt"

        if (!$isLoggerActual -and $line -match '(^|[^A-Za-z0-9_])println\s*\(') {
            Add-Violation $file.FullName $lineNumber "production println must go through Logger"
        }
        if (!$isLoggerActual -and $line -match '\.printStackTrace\s*\(') {
            Add-Violation $file.FullName $lineNumber "printStackTrace must go through Logger"
        }
        if ($line -match 'catch\s*\(\s*_\s*:\s*CancellationException\s*\)') {
            continue
        }
        if ($line -match 'catch\s*\(\s*_\s*:\s*[^)]+\)') {
            Add-Violation $file.FullName $lineNumber "catch block must name the throwable and log it"
        }
        if ($line -match '\.onFailure\s*\{\s*(snackbarHostState\.showSnackbar|on[A-Za-z]+Failed)') {
            Add-Violation $file.FullName $lineNumber "onFailure must log before user-facing fallback"
        }

        if ($line -match 'runCatching\s*\{') {
            $runCatchingStartLine = $lineNumber
            $runCatchingHasOnFailure = $false
        }
        if ($runCatchingStartLine -gt 0 -and $line -match '\.onFailure\s*\{') {
            $runCatchingHasOnFailure = $true
        }
        if ($runCatchingStartLine -gt 0 -and $line -match '\.(getOrNull|getOrDefault|getOrElse)\s*\(') {
            if (!$runCatchingHasOnFailure) {
                Add-Violation $file.FullName $runCatchingStartLine "runCatching fallback must log with onFailure"
            }
            $runCatchingStartLine = 0
            $runCatchingHasOnFailure = $false
        }
        if ($runCatchingStartLine -gt 0 -and $line -match '^\s*$') {
            $runCatchingStartLine = 0
            $runCatchingHasOnFailure = $false
        }
    }

    $emptyCatchMatches = [regex]::Matches($text, 'catch\s*\([^)]*\)\s*\{\s*\}')
    foreach ($match in $emptyCatchMatches) {
        $prefix = $text.Substring(0, $match.Index)
        $lineNumber = ($prefix -split "`r?`n").Length
        Add-Violation $file.FullName $lineNumber "empty catch block must log or rethrow"
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host "Logger coverage check passed."
