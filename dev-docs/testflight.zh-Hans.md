# TestFlight 发布流程

`.github/workflows/release.yml` 在 `build_ios` 里签名 iPhone Release 构建，再由 `publish_app_store` 把这个 IPA 上传到 App Store Connect。没有单独的 TestFlight workflow。

英文版见 [TestFlight](testflight.md)，繁体版见 [TestFlight 發布流程](testflight.zh-Hant.md)。

同一个 iOS job 仍会构建未签名 IPA。`release` 把它和 Android APK 一起发布到 GitHub、Gitee 和 Gitea。不要把已签名 IPA 附到这三个 Release。App Store 签名包只能通过 TestFlight 安装，不能当作侧载文件。

## 一次性 Apple 准备

1. 加入 Apple Developer Program，并签妥 App Store Connect 协议。
2. 注册 App ID `com.yamibo.bbs`。这个值来自 `iosApp/Configuration/AppIdentifiers.xcconfig` 的 `YAMIBO_IOS_BUNDLE_ID`。在 App ID 上开启 Background Modes。App 会注册 `fetch` 和 `processing` 后台任务。本地通知不需要 Push capability。
3. 在 App Store Connect 用同一个 Bundle ID 创建 iOS App。如果 `com.yamibo.bbs` 已被其他 Team 占用，先换新的 Bundle ID，并在第一次上传前修改 `YAMIBO_IOS_BUNDLE_ID`。
4. 在这台 Mac 上创建 Apple Distribution 证书。打开的是 Xcode 应用菜单里的设置，不是 macOS「系统设置」，也不是工程里的 Signing 页面。菜单栏选 **Xcode → Settings…**（⌘,），窗口标题应是 Settings。左侧点 **Accounts**；这一页的分组标题是 **Apple Accounts**。侧边栏里看不到时，把窗口拉宽，或用设置窗口顶部的搜索框搜 Accounts。没有登录就点 **Add Apple Account…**，使用已加入 Developer Program 的 Apple ID。点选这个 Apple ID，再点右侧的 Team，然后点 **Manage Certificates…**。左下角点 **+**，选择 **Apple Distribution**。在证书列表里按住 Control 点这张证书，选 **Export Certificate…**，设置密码，存成 `.p12`。
5. 用 Account Holder 或 Admin 打开 [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/profiles/list)，确认右上角的 Team 是第 4 步那张 Apple Distribution 证书所属的 Team。左侧点 Profiles，左上角点 **+**。在 Distribution 下选 **App Store Connect**，不要选 iOS App Development、Ad Hoc 或 Enterprise。App ID 选 `com.yamibo.bbs`（必须是这个明确 ID，不能选带 `*` 的 wildcard）。证书只选第 4 步导出的那张 Apple Distribution，然后 Continue。填一个配置文件名称，点 Generate，再点 Download，得到 `.mobileprovision`。列表里如果已有同名配置文件，但证书不是这张 Distribution，就新建一份并选中这张证书。
6. 创建角色为 Admin 的 App Store Connect API Key，并下载一次 `.p8`。Admin 可以上传构建。证书和描述文件已经存在之后，App Manager 也可以上传，但不能创建发布证书。

`.p12`、它的密码、描述文件和 `.p8` 都不要提交进版本库。CI 每次运行时把证书导入临时钥匙串。不要让 CI 新建 Apple Distribution 证书：私钥会随 runner 消失，Apple 账号里则会累积无法再用来签名的证书。

换发证书时更新 `APPLE_DISTRIBUTION_P12_BASE64`。更新描述文件，或变更 App ID capability 时，更新 `APP_STORE_CONNECT_PROFILE_BASE64`。描述文件必须包含 p12 里的那张证书。

## GitHub secrets

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

七个 secret 都要。缺任何一个，`check` 都不会开始发版。

## 版本

`update/manifest.json` 同时提供两个平台的版本：

- `versionName` 成为 `CFBundleShortVersionString`
- `versionCode` 成为 `CFBundleVersion`

`check` 会在两个构建之前执行 `syncStableManifest validateUpdateManifest`。`composeApp/build.gradle.kts`、`update/manifest.json`、`update/stable.json` 和 `update/changelogs/{versionCode}.changelog` 必须已经一致，source manifest 也必须保持 `isReady=false`。

同一个 `versionName` 可以有多个 build，但每个 `versionCode` 只能上传一次。每次发版都要提高 `versionCode`，包括 Apple 已经接受该 build 之后的重试。`release` 会创建 git tag，并发布 Android 更新源。`publish_app_store` 不会。

## 执行

1. 推送要发布的那个版本所在的 commit。
2. 打开 GitHub Actions，选择 `Release`。
3. 在该分支上运行 workflow。

只验证 Apple 发布时，Run workflow 里把 `scope` 选成 `app-store`。这时只跑 `build_ios` 的签名包和 `publish_app_store`，不打 tag，也不发布 Android。App Store Connect 拒绝已经接受过的 `versionCode`；要验证上传，先提高它。

`build_ios` 和 `publish_app_store` 跑在 `macos-26`，并选用 Xcode 26.6。Apple 要求上传到 TestFlight 的 iOS 构建必须使用 Xcode 26 或更新版本。如果 runner image 移除了这个 Xcode，job 会在编译前失败，并列出 runner 上现有的 Xcode。

## Job 做什么

```text
check
→ build_ios
    未签名 IPA
    导入发布用 p12
    安装 App Store Connect 描述文件
    xcodebuild archive（手动签名、Release、generic iOS device）
    导出已签名 IPA
→ publish_app_store
    altool --validate-app
    altool --upload-app
```

`build_ios` 会在编译前检查描述文件。它必须符合 `APPLE_TEAM_ID` 和 `com.yamibo.bbs`，包含 p12 里的证书，允许 TestFlight（`beta-reports-active`），而且不能是 development、Ad Hoc 或 enterprise 描述文件。

签名只在这个 job 里把 checkout 出来的项目改成手动签名。版本库仍保留本地构建用的 `CODE_SIGN_STYLE = Automatic` 和 `CODE_SIGN_IDENTITY = Apple Development`。CI 会把 `TEAM_ID` 写进已被 gitignore 的 `iosApp/Configuration/Config.local.xcconfig`，并在 job 结束时删除或还原该文件。

已签名 IPA 会成为私有 Actions artifact `signed-ios-ipa`，保留 14 天。它只用来检查失败的上传，不要公开发布。`publish_app_store` 不会挡住 `release`，所以上传失败时 Android 发版仍然保留。如果上传在 App Store Connect 接受 build 之前失败，重跑 `publish_app_store`。如果 Apple 已经接受该 build number，先提高 `versionCode`。

## Apple 接受上传之后

Workflow 在二进制文件送达时就结束。TestFlight 要等 App Store Connect 处理完成才能安装。

- 内部测试者在处理完成后可以安装。
- 外部测试者还需要 TestFlight 测试信息，并通过 Beta App Review。
- 第一个 build 通常会停在 Missing Compliance，直到有人在 App Store Connect 回答出口合规问题。这个 workflow 不会把 App 送交 App Store 审核。

App 内更新不会安装 TestFlight build，它只会打开 `releaseUrl`。外部测试通过之后，才能把公开的 TestFlight 链接放到那里。在那之前，iOS 用户仍会打开 GitHub release 页面，自行安装未签名 IPA。
