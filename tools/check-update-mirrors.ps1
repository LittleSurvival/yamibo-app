#Requires -Version 5.1
<#
Checks that the app update feeds and the download proxy are usable:

- Requests the GitHub / Gitee / Gitea update manifests in order and verifies
  HTTP 200 + parseable JSON.
- Warns when mirror versionCode values disagree (mirror release workflow
  should keep them in sync).
- HEAD-checks the GitHub release APK asset directly and through
  https://gh-proxy.com/, and rejects HTML responses (login/error pages).

Usage:
  powershell -NoProfile -File .\tools\check-update-mirrors.ps1
Exit code: 0 = all checks passed; 1 = at least one hard failure.
#>
param(
    [int]$TimeoutSec = 25
)

$ErrorActionPreference = 'Stop'
$failed = @()

$manifestUrls = [ordered]@{
    'GitHub' = 'https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json'
    'Gitee'  = 'https://gitee.com/LittleSurvival/ymb-apk-release/raw/main/update/stable.json'
    'Gitea'  = 'https://gitea.com/api/v1/repos/LittleSurvival/ymb-apk-release/raw/update/stable.json?ref=main'
}

$feeds = @{}
foreach ($entry in $manifestUrls.GetEnumerator()) {
    try {
        $response = Invoke-WebRequest -Uri $entry.Value -UseBasicParsing -TimeoutSec $TimeoutSec
        $manifest = $response.Content | ConvertFrom-Json
        $feeds[$entry.Key] = $manifest
        "OK   $($entry.Key)  versionCode=$($manifest.versionCode)  versionName=$($manifest.versionName)  isReady=$($manifest.isReady)"
    } catch {
        $failed += "$($entry.Key) manifest: $($_.Exception.Message)"
        "ERR  $($entry.Key)  $($_.Exception.Message)"
    }
}

$versions = @($feeds.Values | ForEach-Object { $_.versionCode } | Sort-Object -Unique)
if ($versions.Count -gt 1) {
    "WARN mirror versionCode mismatch: $($versions -join ', ') (run the mirror release workflow to sync)"
}

$githubAsset = $feeds['GitHub'].assets | Where-Object { $_.type -in @('universal-apk', 'apk') } | Select-Object -First 1
if ($null -ne $githubAsset) {
    foreach ($url in @($githubAsset.url, "https://gh-proxy.com/$($githubAsset.url)")) {
        try {
            $head = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $TimeoutSec -Method Head
            $contentType = $head.Headers['Content-Type']
            if ($contentType -match 'text/html') {
                throw "unexpected HTML content type: $contentType"
            }
            "OK   HEAD  $url"
            "     status=$($head.StatusCode)  type=$contentType  len=$($head.Headers['Content-Length'])"
        } catch {
            $failed += "HEAD $url : $($_.Exception.Message)"
            "ERR  HEAD  $url"
            "     $($_.Exception.Message)"
        }
    }
} else {
    "WARN GitHub manifest has no installable APK asset"
}

if ($failed.Count -gt 0) {
    ""
    "FAILED:"
    $failed | ForEach-Object { "  - $_" }
    exit 1
}

""
"ALL OK"
exit 0
