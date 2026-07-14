# Yamibo App

> 拜託打顆星星吧 :star: ~

百合會論壇的第三方App.

## App 展示與說明

Yamibo App 在基於爭取完整保留網頁版論壇的功能前提，增加完整的小說/漫畫閱讀器屬性：首頁找版塊與搜尋內容，論壇頁瀏覽帖子，閱讀器接續小說/漫畫進度，收藏頁分類整理追蹤項目，消息頁集中查看收藏更新、私訊與提醒，我的頁則提供簽到、設定、閱讀統計與備份入口。

產品說明站包含主要畫面截圖與功能介紹：

- [App 說明首頁](https://littlesurvival.github.io/yamibo-app/)
- [下載最新版本](https://github.com/LittleSurvival/yamibo-app/releases)

## 社群

- [Discord 社群](https://discord.gg/3nhKpxM7Hc)
- [QQ 群聊](https://qm.qq.com/q/13i8doGGV0)
- [GitHub 專案](https://github.com/LittleSurvival/yamibo-app)

## -- 協助開法指南 --

## 概述

Yamibo App 是一個百合會論壇第三方客戶端，支援首頁版塊、論壇列表、帖子閱讀、漫畫/小說閱讀、收藏、閱讀紀錄、消息、UserSpace、日志、私訊、簽到與 app 更新等功能。

目前希望能有一位熟悉 iOS / Compose Multiplatform iOS 端整合的合作者，一起協助 iOS 版本的驗證、平台功能補齊與發佈流程。若有興趣參與，請加入 [Discord 社群](https://discord.gg/3nhKpxM7Hc) 聯絡。

專案主要分成兩層：

- `composeApp`: UI 與 app 入口，包含畫面、導航、theme、reader、WebView、設定頁。
- `shared`: 跨平台資料層，包含 repository、settings、cache、database、auth/cookie store 與 platform implementation。

論壇 API / HTML 解析主要依賴 `yamibo-api`。

## 目錄結構

- `composeApp/src/commonMain/kotlin/.../home`: 首頁、版塊列表、swiper image。
- `forum`: ForumPage、ThreadCard、搜尋。
- `thread`: ThreadReader、CommentReader、小說/漫畫 reader、tag detail。
- `favorite`: 本地收藏、收藏同步、收藏更新偵測。
- `history`: 閱讀紀錄。
- `message`: 更新 tab、我的消息、提醒、私訊。
- `profile`: 我的頁、設定、關於、簽到、統計。
- `userspace`: UserSpace、BlogReader、好友、主題、回覆。
- `components`: 跨頁共用 UI 元件。
- `shared/src/commonMain`: repository interface、SQLDelight schema、settings/cache/util。
- `buildSrc`: 專案本地 Gradle task，例如 i18n、版本資訊、navigation registry、update manifest 驗證。
- `i18n`: glossary/base 翻譯資料。
- `update`: app update manifest、changelog、workflow 說明。

## 主要技術

版本集中在 `gradle/libs.versions.toml`。

- Kotlin `2.3.20`
- Android Gradle Plugin `8.11.2`
- Gradle Wrapper `8.14.3`
- Compose Multiplatform `1.10.3`
- Android compile/target SDK `36`, min SDK `24`
- [yamibo-api](https://github.com/LittleSurvival/yamibo-api) `1.1.10`
- Ktor `3.4.2`
- Coil 3 `3.4.0`
- SQLDelight `2.2.1`
- WorkManager `2.11.0`
- kotlinx.coroutines / kotlinx.serialization
- AndroidX Lifecycle / Security Crypto
- OpenCC4J、Ksoup、Okio

## 環境配置

1. 安裝 JDK 21。
2. 安裝 Android Studio 與 Android SDK 36。
3. 確認 `ANDROID_HOME` 指向 Android SDK。
4. 使用 repo 內的 Gradle wrapper：

```powershell
.\gradlew --version
```

Windows / PowerShell 讀取中文內容時建議先設定 UTF-8：

```powershell
$OutputEncoding=[System.Text.Encoding]::UTF8
```

iOS 需要 macOS + Xcode，從 `iosApp` 開啟。

## 常用命令

```powershell
# Android debug compile
.\gradlew :composeApp:compileDebugKotlinAndroid --console=plain

# Full build
.\gradlew build --console=plain

# Debug APK
.\gradlew :composeApp:assembleDebug --console=plain

# Release APK
.\gradlew :composeApp:assembleRelease --console=plain

# 同步並驗證 source update manifest
.\gradlew syncStableManifest validateUpdateManifest --console=plain
```

## i18n

新增 UI 文字時直接使用：

```kotlin
i18n("繁體中文 source text")
```

需要穩定翻譯時補到 `i18n/glossary.csv`。Build 會自動生成 Compose resources 與 runtime。

## 更新與發版

`update/manifest.json` 是唯一手動維護的 source manifest。`update/stable.json` 由 `syncStableManifest` 生成。

source repo 內必須保持：

```json
{
  "isReady": false,
  "assets": []
}
```

不要手動加入 `releaseUrl` 或 APK asset。正式可更新的 manifest 由 GitHub Actions 在 APK build/sign/upload 完成後生成，並推送到 GitHub `update-release` branch 與 Gitee / Gitea mirror。

Gitee / Gitea mirror 的 ready manifest 必須指向各自 mirror release 的 APK asset，不可回指 GitHub release APK。

更多細節見：

- `update/README.md`
- `dev-docs/app-updater-release.md`

## Support This Project

如果這個 app 對你有幫助，可以考慮支持開發與維護，或以以下管道支持。

<a href="https://ko-fi.com/thenano">
    <img height="36" style="border:0px;height:36px;" src="https://storage.ko-fi.com/cdn/kofi2.png?v=3" border="0" alt="Buy Me a Coffee at ko-fi.com" />
</a>
<a href="https://afdian.com/a/littlesurvival0001">
    <img height="36" style="border-radius:12px;height:36px;" src="https://pic1.afdiancdn.com/static/img/welcome/button-sponsorme.jpg" alt="在愛發電支持我" />
</a>

## 開發注意事項

- 優先沿用現有頁面模式：`Screen` + content/components + repository/cache。
- UI 文字走 `i18n(...)`。
- API 相關改動先確認本地 `kotlin-libs/yamibo-api`。
- 不提交 `build/` 內 generated source。
- 不手動把 source manifest 改成 `isReady=true`。

## License

見 `LICENSE`。

## Star History

<a href="https://www.star-history.com/?repos=copymanga-copy20%2Fcopymanga-copy20&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=copymanga-copy20/copymanga-copy20&type=date&theme=dark&legend=top-left&sealed_token=WEdFbEITJox-W_HaOBIm5FXB0wwesn2uEgQFrpmk0mN70-cZl2prLizfq3kV4Cozynk_dY0FMg4HDjoyiUxhAnhgMunlxxXpt8tYlazt1cAgDWSZOZ5kLQ" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=copymanga-copy20/copymanga-copy20&type=date&legend=top-left&sealed_token=WEdFbEITJox-W_HaOBIm5FXB0wwesn2uEgQFrpmk0mN70-cZl2prLizfq3kV4Cozynk_dY0FMg4HDjoyiUxhAnhgMunlxxXpt8tYlazt1cAgDWSZOZ5kLQ" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=copymanga-copy20/copymanga-copy20&type=date&legend=top-left&sealed_token=WEdFbEITJox-W_HaOBIm5FXB0wwesn2uEgQFrpmk0mN70-cZl2prLizfq3kV4Cozynk_dY0FMg4HDjoyiUxhAnhgMunlxxXpt8tYlazt1cAgDWSZOZ5kLQ" />
 </picture>
</a>
