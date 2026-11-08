# TestFlight

中文：[简体](testflight.zh-Hans.md)、[繁體](testflight.zh-Hant.md)。

`.github/workflows/testflight.yml` signs an iPhone Release build and can upload it to App Store Connect for TestFlight. It is manual and independent of `.github/workflows/release.yml`.

The release workflow still builds the unsigned IPA and publishes it with the Android APK to GitHub, Gitee, and Gitea. Do not attach the signed IPA to those releases. A signed App Store build is installed through TestFlight, not as a sideload file.

## One-time Apple setup

1. Join the Apple Developer Program and sign the App Store Connect agreements.
2. Register the App ID `com.yamibo.bbs`. That value comes from `YAMIBO_IOS_BUNDLE_ID` in `iosApp/Configuration/AppIdentifiers.xcconfig`. Enable Background Modes on the App ID. The app registers `fetch` and `processing` background tasks. Local notifications do not need the Push capability.
3. Create the iOS app in App Store Connect with that bundle ID. If another team already owns `com.yamibo.bbs`, choose a new bundle ID and change `YAMIBO_IOS_BUNDLE_ID` before the first upload.
4. On this Mac, create an Apple Distribution certificate from the Xcode app menu, not macOS System Settings and not the project Signing page. Choose **Xcode → Settings…** (⌘,); the window title is Settings. Select **Accounts** in the sidebar. That page is grouped under **Apple Accounts**. If the sidebar item is not visible, widen the window or search for Accounts in the Settings search field. If you are not signed in, click **Add Apple Account…** and use the Apple ID enrolled in the Developer Program. Select that Apple ID, select the team on the right, then click **Manage Certificates…**. Click **+** at the lower left and choose **Apple Distribution**. Control-click that certificate and choose **Export Certificate…**, set a password, and save the `.p12`.
5. As Account Holder or Admin, open [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/profiles/list) and confirm the team in the upper right is the team that owns the Apple Distribution certificate from step 4. Click Profiles in the sidebar, then **+** at the upper left. Under Distribution, select **App Store Connect**. Do not select iOS App Development, Ad Hoc, or Enterprise. Choose the explicit App ID `com.yamibo.bbs`, not a wildcard. Select only the Apple Distribution certificate exported in step 4, continue, enter a profile name, click Generate, then Download. The file is a `.mobileprovision`. If a profile for this App ID already exists but uses a different certificate, create a new one that includes this certificate.
6. Create an App Store Connect API key with the Admin role and download the `.p8` once. Admin can upload builds. App Manager can upload after the certificate and profile already exist, but it cannot create the distribution certificate.

Keep the `.p12`, its password, the profile, and the `.p8` out of the repository. CI imports the certificate into a temporary keychain on each run. Do not let CI create a new Apple Distribution certificate: the private key disappears with the runner, and the Apple account then accumulates certificates that cannot sign anything.

Replace `APPLE_DISTRIBUTION_P12_BASE64` when you renew the certificate. Replace `APP_STORE_CONNECT_PROFILE_BASE64` when you renew the profile or change App ID capabilities. The profile must contain the certificate stored in the p12.

## GitHub secrets

Repository secrets, not environment files:

| Secret | Value |
| --- | --- |
| `APPLE_TEAM_ID` | 10 character Team ID |
| `APPLE_DISTRIBUTION_P12_BASE64` | Base64 of the distribution `.p12` |
| `APPLE_DISTRIBUTION_P12_PASSWORD` | Password set when exporting the `.p12` |
| `APP_STORE_CONNECT_PROFILE_BASE64` | Base64 of the App Store Connect `.mobileprovision` |
| `APP_STORE_CONNECT_KEY_ID` | API key ID |
| `APP_STORE_CONNECT_ISSUER_ID` | Issuer UUID |
| `APP_STORE_CONNECT_API_KEY` | API private key, as `.p8` PEM text or Base64 of that file |

Encode the binary secrets on the Mac that created them:

```bash
base64 -i distribution.p12 | pbcopy
base64 -i profile.mobileprovision | pbcopy
base64 -i AuthKey_XXXXXXXXXX.p8 | pbcopy
```

Prefer Base64 for `APP_STORE_CONNECT_API_KEY`. The GitHub secret field often damages raw PEM line breaks. The script also accepts PEM that still contains `-----BEGIN PRIVATE KEY-----`.

Sign-only runs do not need the three API secrets. An upload run needs all seven.

## Version

`update/manifest.json` supplies both platforms:

- `versionName` becomes `CFBundleShortVersionString`
- `versionCode` becomes `CFBundleVersion`

The workflow runs `syncStableManifest validateUpdateManifest` before signing. `composeApp/build.gradle.kts`, `update/manifest.json`, `update/stable.json`, and `update/changelogs/{versionCode}.changelog` must already agree, and the source manifest must stay `isReady=false`.

App Store Connect accepts many builds for one `versionName`, but each `versionCode` can be uploaded once. Increase `versionCode` for every new upload, including a retry after Apple has already accepted the build. This workflow does not create a git tag and does not publish the Android update feed.

## Run

1. Push the commit that contains the version you want testers to install.
2. Open GitHub Actions and select `TestFlight`.
3. Run the workflow on that branch. Leave `Upload the signed IPA to App Store Connect` enabled.
4. Turn the upload input off only to archive and sign without calling App Store Connect.

The job runs on `macos-26` and selects Xcode 26.6. Apple requires an iOS build uploaded for TestFlight to be built with Xcode 26 or later. If that Xcode is removed from the runner image, the job fails before compilation and lists the Xcode apps present on the runner.

## What the job does

```text
check secrets
→ validate manifest
→ import the distribution p12 into a temporary keychain
→ install the App Store Connect profile
→ xcodebuild archive (manual signing, Release, generic iOS device)
→ export an App Store Connect IPA
→ reject the IPA unless it is Apple Distribution signed with that profile
→ altool --validate-app
→ altool --upload-app
```

The script checks the profile before compilation. It must match `APPLE_TEAM_ID` and `com.yamibo.bbs`, include the p12 certificate, allow TestFlight (`beta-reports-active`), and not be a development, Ad Hoc, or enterprise profile.

Signing uses the checked-out project as manual signing for this job only. The repository keeps `CODE_SIGN_STYLE = Automatic` and `CODE_SIGN_IDENTITY = Apple Development` for local builds. CI writes `TEAM_ID` into the gitignored `iosApp/Configuration/Config.local.xcconfig` and deletes or restores that file when the job ends.

The signed IPA is uploaded as the private Actions artifact `testflight-ipa` and kept for 7 days. Use it to inspect a failed upload. Do not publish it. If upload fails before App Store Connect accepts the build, rerun the workflow. If Apple already accepted the build number, increase `versionCode` first.

## After Apple accepts the upload

The workflow finishes when the binary is delivered. TestFlight can install it only after App Store Connect finishes processing.

- Internal testers can install after processing.
- External testers also need the TestFlight test information and Beta App Review.
- The first build usually stays in Missing Compliance until someone answers the export-compliance questions in App Store Connect. This workflow does not submit the app for App Store review.

The in-app updater does not install a TestFlight build. It opens `releaseUrl`. Put a public TestFlight link there only after external testing is approved. Until then, iOS users still open the GitHub release page and install the unsigned IPA themselves.
