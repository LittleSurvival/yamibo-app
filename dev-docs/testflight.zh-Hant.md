# TestFlight 發布流程

`.github/workflows/release.yml` 在 `build_ios` 裡簽署 iPhone Release 建置，再由 `publish_app_store` 把這個 IPA 上傳到 App Store Connect。沒有單獨的 TestFlight workflow。

英文版見 [TestFlight](testflight.md)，簡體版見 [TestFlight 发布流程](testflight.zh-Hans.md)。

同一個 iOS job 仍會建置未簽名 IPA。`release` 把它和 Android APK 一起發布到 GitHub、Gitee 與 Gitea。不要把已簽名 IPA 附到這三個 Release。App Store 簽名包只能透過 TestFlight 安裝，不能當作側載檔。

## 一次性 Apple 準備

1. 加入 Apple Developer Program，並簽妥 App Store Connect 協議。
2. 註冊 App ID `com.yamibo.bbs`。這個值來自 `iosApp/Configuration/AppIdentifiers.xcconfig` 的 `YAMIBO_IOS_BUNDLE_ID`。在 App ID 上開啟 Background Modes。App 會註冊 `fetch` 與 `processing` 背景任務。本機通知不需要 Push capability。
3. 在 App Store Connect 用同一個 Bundle ID 建立 iOS App。如果 `com.yamibo.bbs` 已被其他 Team 占用，先換新的 Bundle ID，並在第一次上傳前修改 `YAMIBO_IOS_BUNDLE_ID`。
4. 在這台 Mac 上建立 Apple Distribution 憑證。打開的是 Xcode 應用程式選單裡的設定，不是 macOS「系統設定」，也不是專案裡的 Signing 頁面。選單列選 **Xcode → Settings…**（⌘,），視窗標題應是 Settings。左側點 **Accounts**；這一頁的分組標題是 **Apple Accounts**。側邊欄裡看不到時，把視窗拉寬，或用設定視窗頂端的搜尋框搜 Accounts。沒有登入就點 **Add Apple Account…**，使用已加入 Developer Program 的 Apple ID。點選這個 Apple ID，再點右側的 Team，然後點 **Manage Certificates…**。左下角點 **+**，選擇 **Apple Distribution**。在憑證列表裡按住 Control 點這張憑證，選 **Export Certificate…**，設定密碼，存成 `.p12`。
5. 用 Account Holder 或 Admin 打開 [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/profiles/list)，確認右上角的 Team 是第 4 步那張 Apple Distribution 憑證所屬的 Team。左側點 Profiles，左上角點 **+**。在 Distribution 下選 **App Store Connect**，不要選 iOS App Development、Ad Hoc 或 Enterprise。App ID 選 `com.yamibo.bbs`（必須是這個明確 ID，不能選帶 `*` 的 wildcard）。憑證只選第 4 步匯出的那張 Apple Distribution，然後 Continue。填一個描述檔名稱，點 Generate，再點 Download，得到 `.mobileprovision`。列表裡如果已有同名描述檔，但憑證不是這張 Distribution，就新建一份並選中這張憑證。
6. 建立角色為 Admin 的 App Store Connect API Key，並下載一次 `.p8`。Admin 可以上傳建置。憑證與描述檔已經存在之後，App Manager 也可以上傳，但不能建立發行憑證。

`.p12`、它的密碼、描述檔與 `.p8` 都不要提交進版本庫。CI 每次執行時把憑證匯入臨時鑰匙圈。不要讓 CI 新建 Apple Distribution 憑證：私鑰會隨 runner 消失，Apple 帳號裡則會累積無法再用來簽名的憑證。

換發憑證時更新 `APPLE_DISTRIBUTION_P12_BASE64`。更新描述檔，或變更 App ID capability 時，更新 `APP_STORE_CONNECT_PROFILE_BASE64`。描述檔必須包含 p12 裡的那張憑證。

## GitHub secrets

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

七個 secret 都要。缺任何一個，`check` 都不會開始發版。

## 版本

`update/manifest.json` 同時提供兩個平台的版本：

- `versionName` 成為 `CFBundleShortVersionString`
- `versionCode` 成為 `CFBundleVersion`

`check` 會在兩個構建之前執行 `syncStableManifest validateUpdateManifest`。`composeApp/build.gradle.kts`、`update/manifest.json`、`update/stable.json` 與 `update/changelogs/{versionCode}.changelog` 必須已經一致，source manifest 也必須維持 `isReady=false`。

同一個 `versionName` 可以有多個 build，但每個 `versionCode` 只能上傳一次。每次發版都要提高 `versionCode`，包括 Apple 已經接受該 build 之後的重試。`release` 會建立 git tag，並發布 Android 更新源。`publish_app_store` 不會。

## 執行

1. 推送要發布的那個版本所在的 commit。
2. 開啟 GitHub Actions，選擇 `Release`。
3. 在該分支上執行 workflow。

只驗證 Apple 發布時，Run workflow 裡把 `scope` 選成 `app-store`。這時只跑 `build_ios` 的簽名包和 `publish_app_store`，不打 tag，也不發布 Android。App Store Connect 拒絕已經接受過的 `versionCode`；要驗證上傳，先提高它。

`build_ios` 和 `publish_app_store` 跑在 `macos-26`，並選用 Xcode 26.6。Apple 要求上傳到 TestFlight 的 iOS 建置必須使用 Xcode 26 或更新版本。如果 runner image 移除了這個 Xcode，job 會在編譯前失敗，並列出 runner 上現有的 Xcode。

## Job 做什麼

```text
check
→ build_ios
    未簽名 IPA
    匯入發行用 p12
    安裝 App Store Connect 描述檔
    xcodebuild archive（手動簽名、Release、generic iOS device）
    匯出已簽名 IPA
→ publish_app_store
    altool --validate-app
    altool --upload-app
```

`build_ios` 會在編譯前檢查描述檔。它必須符合 `APPLE_TEAM_ID` 與 `com.yamibo.bbs`，包含 p12 裡的憑證，允許 TestFlight（`beta-reports-active`），而且不能是 development、Ad Hoc 或 enterprise 描述檔。

簽名只在這個 job 裡把 checkout 出來的專案改成手動簽名。版本庫仍保留本機建置用的 `CODE_SIGN_STYLE = Automatic` 與 `CODE_SIGN_IDENTITY = Apple Development`。CI 會把 `TEAM_ID` 寫進已被 gitignore 的 `iosApp/Configuration/Config.local.xcconfig`，並在 job 結束時刪除或還原該檔。

已簽名 IPA 會成為私有 Actions artifact `signed-ios-ipa`，保留 14 天。它只用來檢查失敗的上傳，不要公開發布。`publish_app_store` 不會擋住 `release`，所以上傳失敗時 Android 發版仍然保留。如果上傳在 App Store Connect 接受 build 之前失敗，重跑 `publish_app_store`。如果 Apple 已經接受該 build number，先提高 `versionCode`。

## Apple 接受上傳之後

Workflow 在二進位檔送達時就結束。TestFlight 要等 App Store Connect 處理完成才能安裝。

- 內部測試者在處理完成後可以安裝。
- 外部測試者還需要 TestFlight 測試資訊，並通過 Beta App Review。
- 第一個 build 通常會停在 Missing Compliance，直到有人在 App Store Connect 回答出口合規問題。這個 workflow 不會把 App 送交 App Store 審核。

App 內更新不會安裝 TestFlight build，它只會開啟 `releaseUrl`。外部測試通過之後，才能把公開的 TestFlight 連結放到那裡。在那之前，iOS 使用者仍會開啟 GitHub release 頁面，自行安裝未簽名 IPA。
