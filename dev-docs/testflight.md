# TestFlight

[繁體中文](#繁體中文) · [简体中文](#简体中文) · [English](#english)

## 繁體中文

在 GitHub Actions 手動執行現有的 **Release**。GitHub、Gitee 和 Gitea 的同一個 Release 都會附上 Android APK、供自行簽名的 `*-unsigned.ipa`，以及 App Store Connect 簽名的 `*-signed.ipa`。已簽名的建置透過 TestFlight 分發與安裝。

### 一次性 Apple 準備

1. 加入 Apple Developer Program，並簽妥 App Store Connect 協議。
2. 註冊 App ID `com.yamibo.bbs`。這個值來自 `iosApp/Configuration/AppIdentifiers.xcconfig` 的 `YAMIBO_IOS_BUNDLE_ID`。在 App ID 上開啟 Background Modes。App 會註冊 `fetch` 與 `processing` 背景任務。本機通知不需要 Push capability。
3. 在 App Store Connect 用同一個 Bundle ID 建立 iOS App。如果 `com.yamibo.bbs` 已被其他 Team 占用，先換新的 Bundle ID，並在第一次上傳前修改 `YAMIBO_IOS_BUNDLE_ID`。
4. 在這台 Mac 上建立 Apple Distribution 憑證。打開的是 Xcode 應用程式選單裡的設定，不是 macOS「系統設定」，也不是專案裡的 Signing 頁面。選單列選 **Xcode → Settings…**（⌘,），視窗標題應是 Settings。左側點 **Accounts**；這一頁的分組標題是 **Apple Accounts**。側邊欄裡看不到時，把視窗拉寬，或用設定視窗頂端的搜尋框搜 Accounts。沒有登入就點 **Add Apple Account…**，使用已加入 Developer Program 的 Apple ID。點選這個 Apple ID，再點右側的 Team，然後點 **Manage Certificates…**。左下角點 **+**，選擇 **Apple Distribution**。在憑證列表裡按住 Control 點這張憑證，選 **Export Certificate…**，設定密碼，存成 `.p12`。
5. 用 Account Holder 或 Admin 打開 [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/profiles/list)，確認右上角的 Team 是第 4 步那張 Apple Distribution 憑證所屬的 Team。左側點 Profiles，左上角點 **+**。在 Distribution 下選 **App Store Connect**，不要選 iOS App Development、Ad Hoc 或 Enterprise。App ID 選 `com.yamibo.bbs`（必須是這個明確 ID，不能選帶 `*` 的 wildcard）。憑證只選第 4 步匯出的那張 Apple Distribution，然後 Continue。填一個描述檔名稱，點 Generate，再點 Download，得到 `.mobileprovision`。列表裡如果已有同名描述檔，但憑證不是這張 Distribution，就新建一份並選中這張憑證。
6. 建立角色為 Admin 的 App Store Connect API Key，並下載一次 `.p8`。Admin 可以上傳建置。憑證與描述檔已經存在之後，App Manager 也可以上傳，但不能建立發行憑證。

`.p12`、它的密碼、描述檔與 `.p8` 都不要提交進版本庫。CI 每次執行時把憑證匯入臨時鑰匙圈。不要讓 CI 新建 Apple Distribution 憑證：私鑰會隨 runner 消失，Apple 帳號裡則會累積無法再用來簽名的憑證。

換發憑證時更新 `APPLE_DISTRIBUTION_P12_BASE64`。更新描述檔，或變更 App ID capability 時，更新 `APP_STORE_CONNECT_PROFILE_BASE64`。描述檔必須包含 p12 裡的那張憑證。

### GitHub secrets

這些是 repository secrets，不是環境設定檔：

| Secret | 內容 |
| --- | --- |
| `APPLE_TEAM_ID` | 10 個字元的 Team ID |
| `APPLE_DISTRIBUTION_P12_BASE64` | 發行用 `.p12` 的 Base64 |
| `APPLE_DISTRIBUTION_P12_PASSWORD` | 匯出 `.p12` 時設定的密碼 |
| `APP_STORE_CONNECT_PROFILE_BASE64` | App Store Connect `.mobileprovision` 的 Base64 |
| `APP_STORE_CONNECT_KEY_ID` | API Key ID |
| `APP_STORE_CONNECT_ISSUER_ID` | Issuer UUID |
| `APP_STORE_CONNECT_API_KEY` | API 私鑰。可以是 `.p8` PEM 原文，或該檔案的 Base64 |

在產生這些檔案的 Mac 上編碼：

```bash
base64 -i distribution.p12 | pbcopy
base64 -i profile.mobileprovision | pbcopy
base64 -i AuthKey_XXXXXXXXXX.p8 | pbcopy
```

`APP_STORE_CONNECT_API_KEY` 優先使用 Base64。GitHub secret 欄位經常破壞 PEM 的換行。腳本也接受仍含有 `-----BEGIN PRIVATE KEY-----` 的 PEM。

簽名步驟需要四個簽名 secret。App Store Connect 上傳步驟另外需要三個 API secret。

### 版本

`update/manifest.json` 同時提供兩個平台的版本：

- `versionName` 成為 `CFBundleShortVersionString`
- `versionCode` 成為 `CFBundleVersion`

`check` 會在兩個構建之前執行 `syncStableManifest validateUpdateManifest`。`composeApp/build.gradle.kts`、`update/manifest.json`、`update/stable.json` 與 `update/changelogs/{versionCode}.changelog` 必須已經一致，source manifest 也必須維持 `isReady=false`。

同一個 `versionName` 可以有多個 build，但每個 `versionCode` 只能上傳一次。每次發版都要提高 `versionCode`，包括 Apple 已經接受該 build 之後的重試。`release` 會建立 git tag，並發布 Android 更新源。`publish_app_store` 不會。

### 執行

1. 推送要發布的那個版本所在的 commit。
2. 開啟 GitHub Actions，選擇 `Release`。
3. 在該分支上執行 workflow。

`build_ios` 與 `publish_app_store` 使用 `macos-26` 和 Xcode 26.6。

### Job 做什麼

```text
check
→ build_ios
    未簽名 IPA
    匯入發行用 p12
    安裝 App Store Connect 描述檔
    xcodebuild archive（手動簽名、Release、generic iOS device）
    匯出已簽名 IPA
→ release（與 Android APK 一起發布兩種 IPA）
→ publish_app_store
    altool --validate-app
    altool --upload-app
```

`build_ios` 會在編譯前檢查描述檔。它必須符合 `APPLE_TEAM_ID` 與 `com.yamibo.bbs`，包含 p12 裡的憑證，允許 TestFlight（`beta-reports-active`），而且不能是 development、Ad Hoc 或 enterprise 描述檔。

簽名只在這個 job 裡把 checkout 出來的專案改成手動簽名。版本庫仍保留本機建置用的 `CODE_SIGN_STYLE = Automatic` 與 `CODE_SIGN_IDENTITY = Apple Development`。CI 會把 `TEAM_ID` 寫進已被 gitignore 的 `iosApp/Configuration/Config.local.xcconfig`，並在 job 結束時刪除或還原該檔。

`release` 等待 APK 和兩種 IPA 都建置成功後才發布。`publish_app_store` 會讀取同一份 `signed-ios-ipa` Actions 產物並獨立上傳。若 Apple 尚未接受建置，可以單獨重跑上傳 job。

### Apple 接受上傳之後

Workflow 在二進位檔送達時就結束。TestFlight 要等 App Store Connect 處理完成才能安裝。

- 內部測試者在處理完成後可以安裝。
- 外部測試者還需要 TestFlight 測試資訊，並通過 Beta App Review。
- 第一個 build 通常會停在 Missing Compliance，直到有人在 App Store Connect 回答出口合規問題。這個 workflow 不會把 App 送交 App Store 審核。

App 內更新不會安裝 TestFlight build，它只會開啟 `releaseUrl`。外部測試通過之後，才能把公開的 TestFlight 連結放到那裡。在那之前，iOS 使用者仍會開啟 GitHub release 頁面，自行安裝未簽名 IPA。

## 简体中文

在 GitHub Actions 手动运行现有的 **Release**。GitHub、Gitee 和 Gitea 的同一个 Release 都会附上 Android APK、供自行签名的 `*-unsigned.ipa`，以及 App Store Connect 签名的 `*-signed.ipa`。已签名的构建通过 TestFlight 分发和安装。

### 一次性 Apple 准备

1. 加入 Apple Developer Program，并签妥 App Store Connect 协议。
2. 注册 App ID `com.yamibo.bbs`。这个值来自 `iosApp/Configuration/AppIdentifiers.xcconfig` 的 `YAMIBO_IOS_BUNDLE_ID`。在 App ID 上开启 Background Modes。App 会注册 `fetch` 和 `processing` 后台任务。本地通知不需要 Push capability。
3. 在 App Store Connect 用同一个 Bundle ID 创建 iOS App。如果 `com.yamibo.bbs` 已被其他 Team 占用，先换新的 Bundle ID，并在第一次上传前修改 `YAMIBO_IOS_BUNDLE_ID`。
4. 在这台 Mac 上创建 Apple Distribution 证书。打开的是 Xcode 应用菜单里的设置，不是 macOS「系统设置」，也不是工程里的 Signing 页面。菜单栏选 **Xcode → Settings…**（⌘,），窗口标题应是 Settings。左侧点 **Accounts**；这一页的分组标题是 **Apple Accounts**。侧边栏里看不到时，把窗口拉宽，或用设置窗口顶部的搜索框搜 Accounts。没有登录就点 **Add Apple Account…**，使用已加入 Developer Program 的 Apple ID。点选这个 Apple ID，再点右侧的 Team，然后点 **Manage Certificates…**。左下角点 **+**，选择 **Apple Distribution**。在证书列表里按住 Control 点这张证书，选 **Export Certificate…**，设置密码，存成 `.p12`。
5. 用 Account Holder 或 Admin 打开 [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/profiles/list)，确认右上角的 Team 是第 4 步那张 Apple Distribution 证书所属的 Team。左侧点 Profiles，左上角点 **+**。在 Distribution 下选 **App Store Connect**，不要选 iOS App Development、Ad Hoc 或 Enterprise。App ID 选 `com.yamibo.bbs`（必须是这个明确 ID，不能选带 `*` 的 wildcard）。证书只选第 4 步导出的那张 Apple Distribution，然后 Continue。填一个配置文件名称，点 Generate，再点 Download，得到 `.mobileprovision`。列表里如果已有同名配置文件，但证书不是这张 Distribution，就新建一份并选中这张证书。
6. 创建角色为 Admin 的 App Store Connect API Key，并下载一次 `.p8`。Admin 可以上传构建。证书和描述文件已经存在之后，App Manager 也可以上传，但不能创建发布证书。

`.p12`、它的密码、描述文件和 `.p8` 都不要提交进版本库。CI 每次运行时把证书导入临时钥匙串。不要让 CI 新建 Apple Distribution 证书：私钥会随 runner 消失，Apple 账号里则会累积无法再用来签名的证书。

换发证书时更新 `APPLE_DISTRIBUTION_P12_BASE64`。更新描述文件，或变更 App ID capability 时，更新 `APP_STORE_CONNECT_PROFILE_BASE64`。描述文件必须包含 p12 里的那张证书。

### GitHub secrets

这些是 repository secrets，不是环境配置文件：

| Secret | 内容 |
| --- | --- |
| `APPLE_TEAM_ID` | 10 个字符的 Team ID |
| `APPLE_DISTRIBUTION_P12_BASE64` | 发布用 `.p12` 的 Base64 |
| `APPLE_DISTRIBUTION_P12_PASSWORD` | 导出 `.p12` 时设置的密码 |
| `APP_STORE_CONNECT_PROFILE_BASE64` | App Store Connect `.mobileprovision` 的 Base64 |
| `APP_STORE_CONNECT_KEY_ID` | API Key ID |
| `APP_STORE_CONNECT_ISSUER_ID` | Issuer UUID |
| `APP_STORE_CONNECT_API_KEY` | API 私钥。可以是 `.p8` PEM 原文，或该文件的 Base64 |

在生成这些文件的 Mac 上编码：

```bash
base64 -i distribution.p12 | pbcopy
base64 -i profile.mobileprovision | pbcopy
base64 -i AuthKey_XXXXXXXXXX.p8 | pbcopy
```

`APP_STORE_CONNECT_API_KEY` 优先使用 Base64。GitHub secret 字段经常破坏 PEM 的换行。脚本也接受仍含有 `-----BEGIN PRIVATE KEY-----` 的 PEM。

签名步骤需要四个签名 secret。App Store Connect 上传步骤另外需要三个 API secret。

### 版本

`update/manifest.json` 同时提供两个平台的版本：

- `versionName` 成为 `CFBundleShortVersionString`
- `versionCode` 成为 `CFBundleVersion`

`check` 会在两个构建之前执行 `syncStableManifest validateUpdateManifest`。`composeApp/build.gradle.kts`、`update/manifest.json`、`update/stable.json` 和 `update/changelogs/{versionCode}.changelog` 必须已经一致，source manifest 也必须保持 `isReady=false`。

同一个 `versionName` 可以有多个 build，但每个 `versionCode` 只能上传一次。每次发版都要提高 `versionCode`，包括 Apple 已经接受该 build 之后的重试。`release` 会创建 git tag，并发布 Android 更新源。`publish_app_store` 不会。

### 执行

1. 推送要发布的那个版本所在的 commit。
2. 打开 GitHub Actions，选择 `Release`。
3. 在该分支上运行 workflow。

`build_ios` 与 `publish_app_store` 使用 `macos-26` 和 Xcode 26.6。

### Job 做什么

```text
check
→ build_ios
    未签名 IPA
    导入发布用 p12
    安装 App Store Connect 描述文件
    xcodebuild archive（手动签名、Release、generic iOS device）
    导出已签名 IPA
→ release（与 Android APK 一起发布两种 IPA）
→ publish_app_store
    altool --validate-app
    altool --upload-app
```

`build_ios` 会在编译前检查描述文件。它必须符合 `APPLE_TEAM_ID` 和 `com.yamibo.bbs`，包含 p12 里的证书，允许 TestFlight（`beta-reports-active`），而且不能是 development、Ad Hoc 或 enterprise 描述文件。

签名只在这个 job 里把 checkout 出来的项目改成手动签名。版本库仍保留本地构建用的 `CODE_SIGN_STYLE = Automatic` 和 `CODE_SIGN_IDENTITY = Apple Development`。CI 会把 `TEAM_ID` 写进已被 gitignore 的 `iosApp/Configuration/Config.local.xcconfig`，并在 job 结束时删除或还原该文件。

`release` 等待 APK 和两种 IPA 都构建成功后才发布。`publish_app_store` 会读取同一份 `signed-ios-ipa` Actions 产物并独立上传。如果 Apple 尚未接受构建，可以单独重跑上传 job。

### Apple 接受上传之后

Workflow 在二进制文件送达时就结束。TestFlight 要等 App Store Connect 处理完成才能安装。

- 内部测试者在处理完成后可以安装。
- 外部测试者还需要 TestFlight 测试信息，并通过 Beta App Review。
- 第一个 build 通常会停在 Missing Compliance，直到有人在 App Store Connect 回答出口合规问题。这个 workflow 不会把 App 送交 App Store 审核。

App 内更新不会安装 TestFlight build，它只会打开 `releaseUrl`。外部测试通过之后，才能把公开的 TestFlight 链接放到那里。在那之前，iOS 用户仍会打开 GitHub release 页面，自行安装未签名 IPA。

## English

Run the existing **Release** workflow manually in GitHub Actions. Each release on GitHub, Gitee, and Gitea includes the Android APK, `*-unsigned.ipa` for self-signing, and the App Store Connect signed `*-signed.ipa`. Install the distributed signed build through TestFlight.

### One-time Apple setup

1. Join the Apple Developer Program and sign the App Store Connect agreements.
2. Register the App ID `com.yamibo.bbs`. That value comes from `YAMIBO_IOS_BUNDLE_ID` in `iosApp/Configuration/AppIdentifiers.xcconfig`. Enable Background Modes on the App ID. The app registers `fetch` and `processing` background tasks. Local notifications do not need the Push capability.
3. Create the iOS app in App Store Connect with that bundle ID. If another team already owns `com.yamibo.bbs`, choose a new bundle ID and change `YAMIBO_IOS_BUNDLE_ID` before the first upload.
4. On this Mac, create an Apple Distribution certificate from the Xcode app menu, not macOS System Settings and not the project Signing page. Choose **Xcode → Settings…** (⌘,); the window title is Settings. Select **Accounts** in the sidebar. That page is grouped under **Apple Accounts**. If the sidebar item is not visible, widen the window or search for Accounts in the Settings search field. If you are not signed in, click **Add Apple Account…** and use the Apple ID enrolled in the Developer Program. Select that Apple ID, select the team on the right, then click **Manage Certificates…**. Click **+** at the lower left and choose **Apple Distribution**. Control-click that certificate and choose **Export Certificate…**, set a password, and save the `.p12`.
5. As Account Holder or Admin, open [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/profiles/list) and confirm the team in the upper right is the team that owns the Apple Distribution certificate from step 4. Click Profiles in the sidebar, then **+** at the upper left. Under Distribution, select **App Store Connect**. Do not select iOS App Development, Ad Hoc, or Enterprise. Choose the explicit App ID `com.yamibo.bbs`, not a wildcard. Select only the Apple Distribution certificate exported in step 4, continue, enter a profile name, click Generate, then Download. The file is a `.mobileprovision`. If a profile for this App ID already exists but uses a different certificate, create a new one that includes this certificate.
6. Create an App Store Connect API key with the Admin role and download the `.p8` once. Admin can upload builds. App Manager can upload after the certificate and profile already exist, but it cannot create the distribution certificate.

Keep the `.p12`, its password, the profile, and the `.p8` out of the repository. CI imports the certificate into a temporary keychain on each run. Do not let CI create a new Apple Distribution certificate: the private key disappears with the runner, and the Apple account then accumulates certificates that cannot sign anything.

Replace `APPLE_DISTRIBUTION_P12_BASE64` when you renew the certificate. Replace `APP_STORE_CONNECT_PROFILE_BASE64` when you renew the profile or change App ID capabilities. The profile must contain the certificate stored in the p12.

### GitHub secrets

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

The signing step requires four signing secrets. The App Store Connect upload step requires the three API secrets.

### Version

`update/manifest.json` supplies both platforms:

- `versionName` becomes `CFBundleShortVersionString`
- `versionCode` becomes `CFBundleVersion`

`check` runs `syncStableManifest validateUpdateManifest` before either build. `composeApp/build.gradle.kts`, `update/manifest.json`, `update/stable.json`, and `update/changelogs/{versionCode}.changelog` must already agree, and the source manifest must stay `isReady=false`.

App Store Connect accepts many builds for one `versionName`, but each `versionCode` can be uploaded once. Increase `versionCode` for every release, including a retry after Apple has already accepted the build. `release` creates the git tag and publishes the Android update feed. `publish_app_store` does not.

### Run

1. Push the commit that contains the version you want to release.
2. Open GitHub Actions and select `Release`.
3. Run the workflow on that branch.

`build_ios` and `publish_app_store` use `macos-26` and Xcode 26.6.

### What the jobs do

```text
check
→ build_ios
    unsigned IPA
    import the distribution p12
    install the App Store Connect profile
    xcodebuild archive (manual signing, Release, generic iOS device)
    export the signed IPA
→ release (publish both IPAs alongside the Android APK)
→ publish_app_store
    altool --validate-app
    altool --upload-app
```

`build_ios` checks the profile before compilation. It must match `APPLE_TEAM_ID` and `com.yamibo.bbs`, include the p12 certificate, allow TestFlight (`beta-reports-active`), and not be a development, Ad Hoc, or enterprise profile.

Signing uses the checked-out project as manual signing for this job only. The repository keeps `CODE_SIGN_STYLE = Automatic` and `CODE_SIGN_IDENTITY = Apple Development` for local builds. CI writes `TEAM_ID` into the gitignored `iosApp/Configuration/Config.local.xcconfig` and deletes or restores that file when the job ends.

`release` waits for the APK and both IPAs to build successfully. `publish_app_store` reads the same `signed-ios-ipa` Actions artifact and uploads it independently. If Apple has yet to accept the build, rerun the upload job on its own.

### After Apple accepts the upload

The workflow finishes when the binary is delivered. TestFlight can install it only after App Store Connect finishes processing.

- Internal testers can install after processing.
- External testers also need the TestFlight test information and Beta App Review.
- The first build usually stays in Missing Compliance until someone answers the export-compliance questions in App Store Connect. This workflow does not submit the app for App Store review.

The in-app updater does not install a TestFlight build. It opens `releaseUrl`. Put a public TestFlight link there only after external testing is approved. Until then, iOS users still open the GitHub release page and install the unsigned IPA themselves.
