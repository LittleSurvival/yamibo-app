#Requires -Version 5.1
<#
Checks that the GitHub update feed and its third-party mirrors are usable.

All feeds point at the same GitHub repository lmc2007/yamibo-app
(raw.githubusercontent.com update-release/update/stable.json), reached either
directly or through third-party GitHub acceleration mirrors. The script:

- Requests every update-check feed and verifies HTTP 200 + parseable JSON.
  Proxy feeds use a 5 second timeout and the GitHub direct feed uses 10 seconds,
  matching DefaultAppUpdateRepository per-source timeouts.
- Warns when versionCode values disagree (a mirror may be serving stale cache).
- HEAD-checks the GitHub release APK asset directly and through
  https://gh-proxy.com/ (gh-proxy.com is only used for downloads, not checks),
  and rejects HTML responses (login/error pages).

Usage:
  powershell -NoProfile -File .\tools\check-update-mirrors.ps1
Exit code: 0 = all checks passed; 1 = at least one hard failure.
#>
param(
    [int]$ProxyTimeoutSec = 5,
    [int]$GithubTimeoutSec = 10
)

$ErrorActionPreference = 'Stop'
$failed = @()

$githubRaw = 'https://raw.githubusercontent.com/lmc2007/yamibo-app/update-release/update/stable.json'

$manifestUrls = [ordered]@{
    'github.cnxiaobai.com'        = "https://github.cnxiaobai.com/$githubRaw"
    'gh.halonice.com'             = "https://gh.halonice.com/$githubRaw"
    'ghproxy.sakuramoe.dev'       = "https://ghproxy.sakuramoe.dev/$githubRaw"
    'gh.padao.fun'                = "https://gh.padao.fun/$githubRaw"
    'gh.jasonzeng.dev'            = "https://gh.jasonzeng.dev/$githubRaw"
    'ghproxy.mirror.skybyte.me'   = "https://ghproxy.mirror.skybyte.me/$githubRaw"
    'GitHub'                      = $githubRaw
}

$feeds = @{}
foreach ($entry in $manifestUrls.GetEnumerator()) {
    try {
        $timeoutSec = if ($entry.Key -eq 'GitHub') { $GithubTimeoutSec } else { $ProxyTimeoutSec }
        $response = Invoke-WebRequest -Uri $entry.Value -UseBasicParsing -TimeoutSec $timeoutSec
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
    "WARN versionCode mismatch: $($versions -join ', ') (a mirror may be serving stale cache)"
}

$githubAsset = $feeds['GitHub'].assets | Where-Object { $_.type -in @('universal-apk', 'apk') } | Select-Object -First 1
if ($null -eq $githubAsset) {
    $githubAsset = $feeds.Values | ForEach-Object { $_.assets } | Where-Object { $_.type -in @('universal-apk', 'apk') } | Select-Object -First 1
}
if ($null -ne $githubAsset) {
    foreach ($url in @("https://gh-proxy.com/$($githubAsset.url)", $githubAsset.url)) {
        try {
            $headTimeout = if ($url -eq $githubAsset.url) { $GithubTimeoutSec } else { $ProxyTimeoutSec }
            $head = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $headTimeout -Method Head
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
    "WARN no installable APK asset in any feed"
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
