# Yamibo App 架构与开发指南

> 本文档是项目的架构总览与开发约定。针对特定主题的细节见同目录下的
> `app-sync-cloud-data-model.md`、`app-updater-release.md`、`baidu-nox-waf-recovery.md`、
> `ios-opencc-bridge.md`、`branch-naming.md`。

## 1. 项目概览

Yamibo App 是百合会论坛（Yamibo）的第三方客户端，支持首頁版塊、論壇列表、帖子閱讀、
漫畫/小說閱讀、收藏、閱讀紀錄、消息、UserSpace、日志、私訊、簽到與 app 更新等功能。

论坛 API 与 HTML 解析主要依赖外部库 [`yamibo-api`](https://github.com/LittleSurvival/yamibo-api)
（`io.github.littlesurvival:yamibo-api`），本项目通过 `YamiboClient` 与其交互。

本仓库（`lmc2007/yamibo-app`）是上游 [`LittleSurvival/yamibo-app`](https://github.com/LittleSurvival/yamibo-app)
的 fork，在保持上游功能的基础上增加了**本地小说/漫画阅读**等独有功能（见第 7 节）。

## 2. 技术栈

版本集中在 `gradle/libs.versions.toml`：

| 技术 | 版本 | 用途 |
|---|---|---|
| Kotlin | 2.4.10 | 语言 |
| Compose Multiplatform | 1.11.1 | 跨平台 UI（Android + iOS） |
| Android Gradle Plugin | 8.11.2 | Android 构建 |
| Gradle Wrapper | 8.14.3 | 构建系统 |
| Ktor | 3.5.2 | HTTP 客户端 |
| SQLDelight | 2.3.2 | 跨平台数据库 |
| Coil 3 | 3.5.0 | 图片加载（含 SVG/GIF） |
| yamibo-api | 1.1.27 | 论坛 API / HTML 解析 |
| kotlinx-coroutines | 1.11.0 | 协程 |
| kotlinx-serialization | 1.11.0 | JSON 序列化 |
| AndroidX WorkManager | 2.11.2 | Android 后台任务 |
| OpenCC4J | 1.14.0 | 简繁转换 |
| Ksoup | 0.2.6 | HTML 解析 |
| Okio | 3.18.1 | I/O |
| AndroidX Security Crypto | 1.1.0 | 加密存储 |

Android：compile/target SDK 36，min SDK 24。iOS 需要 macOS + Xcode，从 `iosApp` 打开。

## 3. 模块与目录结构

Gradle 模块（`settings.gradle.kts` 启用 `TYPESAFE_PROJECT_ACCESSORS`）：

```
rootProject "yamibo_app"
├── composeApp/   # UI 与应用入口
├── shared/       # 跨平台数据层
├── iosApp/       # iOS 壳（SwiftUI + Compose Multiplatform）
└── buildSrc/     # 本地 Gradle 任务/插件
```

### 3.1 `composeApp`（UI 层）

`composeApp/src/` 的 source set：

- `commonMain`：共享 UI（占绝大多数）
- `androidMain`：Android 平台实现（`MainActivity`、通知、后台任务、文件选择器等）
- `iosMain`：iOS 平台实现（`MainViewController` 等）
- `debug` / `androidDebug`：调试专用（如 WAF 模拟器）
- `commonTest` / `androidUnitTest`：测试

`commonMain` 包 `me.thenano.yamibo.yamibo_app` 下的页面模块：

| 目录 | 职责 |
|---|---|
| `App.kt` | 应用入口（Coil 配置、主题、反馈、事件总线、导航、签到 WebView 等全局逻辑） |
| `MainScreen.kt` | 主界面与底部导航（`MainTab`：Home / History / Message / Favorite / Profile） |
| `AppRepository.kt` | 依赖注入容器（约 40 个 `compositionLocalOf`） |
| `navigation/` | 导航（`ComposableNavigator`、`LocalNavigator`、`NavAction`、`NavigationRestore`） |
| `home/` | 首頁、版塊列表、swiper image |
| `forum/` | ForumPage、ThreadCard、搜索 |
| `thread/` | 帖子阅读、阅读器（最大的模块，见第 6 节） |
| `favorite/` | 收藏、收藏同步、收藏更新侦测 |
| `history/` | 阅读紀錄 |
| `message/` | 消息中心（更新 tab、私訊、提醒） |
| `profile/` | 我的頁、設定、關於、簽到、統計、備份 |
| `userspace/` | UserSpace、BlogReader、好友、主題、回覆 |
| `localnovel/` | 本地小说（書架 + 阅读器，本地独有功能） |
| `updates/` | app 更新（检查、下载、提示） |
| `components/` | 跨页共用 UI 元件（theme、font 等） |
| `webview/` | WebView 封装（`PlatformWebView` expect/actual） |
| `i18n/` | 语言切换（`AppLocaleProvider`、`appString`） |

### 3.2 `shared`（数据层）

`shared/src/` 的 source set：

- `commonMain`：共享数据层
- `nativeMain`：native（iOS/macOS）共享
- `androidMain` / `iosMain`：平台实现
- `commonTest` / `androidUnitTest` / `iosTest`

`commonMain` 包结构：

| 目录 | 职责 |
|---|---|
| `repository/` | 约 30 个 repository 接口 + 部分 commonMain 实现（`*Impl`） |
| `repository/appsync/` | AppSync 云同步（见 5.6 节） |
| `repository/settings/` | 设置 repository（core 抽象 + 各 reader 设置） |
| `store/` | SQLDelight 数据存储封装（`auth/`、`settings/`、`forum/`、`sign/`、`appsync/`） |
| `factory/` | `HttpClientFactory`（expect object）、`plugin/WafCookieHandshakePlugin` |
| `db/` | `DatabaseFactory`（SQLDelight 数据库） |
| `core/cache/` | 磁盘缓存（`DiskCacheFactory`） |
| `task/` | 后台任务抽象（`AppTaskManager`、`AppTaskKey`、`AppTaskState`） |
| `event/` | 事件总线（`AppEventBus` + `events/`） |
| `i18n/` | shared 层 i18n（仅 `{}` 占位符替换） |
| `util/` | 工具（`auth/CookieParser`、`time/` 等） |

### 3.3 `buildSrc`（本地 Gradle 任务）

`buildSrc/src/main/kotlin/`：

| 文件 | 职责 |
|---|---|
| `GenerateI18nResourcesTask.kt` + `I18nAutoMergePlugin.kt` + `I18nAutoMergeExtension.kt` | i18n 扫描与资源生成（见 5.4 节） |
| `GenerateAppVersionTask.kt` | 生成 `AppVersion.kt` |
| `GenerateRestorableScreenRegistryTask.kt` | 生成导航 registry（可恢复屏幕） |
| `GenerateYamiboIconsTask.kt` | 生成 `YamiboIcons.kt`（图标） |
| `SyncStableManifestTask.kt` | 从 `manifest.json` 生成 `stable.json` |
| `ValidateUpdateManifestTask.kt` / `ValidatePublishedUpdateManifestTask.kt` | 校验 update manifest |

## 4. 架构分层

三层结构，遵循「Screen + content/components + repository/cache」模式：

```
┌─────────────────────────────────────────────────┐
│  composeApp (commonMain UI)                      │
│  Screen / content / components                   │
│  └─ 通过 LocalXxxRepository.current 获取数据     │
├─────────────────────────────────────────────────┤
│  shared (commonMain 数据层)                      │
│  repository 接口 + Impl → store (SQLDelight)     │
│  factory (HttpClient) / db / cache / task / event│
├─────────────────────────────────────────────────┤
│  platform (androidMain / iosMain)                │
│  repository 平台实现、通知、后台任务、文件选择器 │
│  HttpClientFactory actual、SQLDelight driver     │
└─────────────────────────────────────────────────┘
```

### 4.1 依赖注入：CompositionLocal

`composeApp/AppRepository.kt` 用 `compositionLocalOf` 为每个 repository 提供 CompositionLocal：

```kotlin
val LocalThreadRepository =
    compositionLocalOf<ThreadRepository> { error("LocalThreadRepository not provided") }
```

UI 内用 `LocalThreadRepository.current` 获取实例。真正的实例在平台入口
（`MainActivity` / `MainViewController`）创建并 `CompositionLocalProvider` 提供。

### 4.2 数据流：Repository → Store → SQLDelight

- repository **接口**定义在 `shared/commonMain`（如 `LocalNovelRepository`），数据模型是普通 data class。
- repository **实现**（`*Impl`）在 `shared/commonMain` 或平台层，内部调用 `store`。
- `store`（如 `store/auth/CookieStore.kt`、`store/settings/SettingsStore.kt`）封装 SQLDelight 生成的 query。
- SQLDelight schema 在 `shared/src/commonMain/sqldelight/.../*.sq`（约 34 个 `.sq` 文件），
  `db/DatabaseFactory` 提供 `Database` 实例，各平台用不同 driver（Android 用
  `sqldelight-android-driver`，iOS 用 `sqldelight-native-driver`）。

### 4.3 expect/actual 平台实现

跨平台能力用 `expect`/`actual` 表达，典型：

- `factory/HttpClientFactory`（expect object）：Android 用 `ktor-client-okhttp`，iOS 用 `ktor-client-darwin`。
- `Platform.kt`：`expect fun getPlatform()`。
- `repository/localnovel/PlatformFileOperations`：本地文件操作（Android/iOS 各自实现）。
- `composeApp/webview/PlatformWebView`：WebView 封装。

## 5. 关键机制

### 5.1 HTTP 客户端与 WAF

- `HttpClientFactory.create(...)` 创建共享配置的 `HttpClient`，配置默认 header。
- `WafCookieHandshakePlugin`（`shared/.../factory/plugin/`）处理百合会 HTTP 405 / WAF cookie 握手，
  详见 `dev-docs/baidu-nox-waf-recovery.md`。
- 论坛访问统一通过 `YamiboClient`（yamibo-api），Android 侧由 `network/AndroidYamiboClientProvider`
  提供 WAF-enabled 客户端（iOS 侧为 `IOSYamiboClientProvider`），供 AppSync / 收藏同步 / 签到等服务共享。
- Cookie 解析与组装见 `util/auth/CookieParser.kt`。

### 5.2 事件总线与任务管理

- `event/AppEventBus`：跨模块事件（如 `SignStatusChangedEvent`），用于签到状态等广播。
- `task/AppTaskManager`：后台任务抽象（`AppTaskKey`/`AppTaskState`/`AppTaskResult`），
  平台层映射到 WorkManager（Android）或 iOS 后台能力。

### 5.3 反馈与确认

- `feedback/AppFeedbackController`：全局提示（snackbar / feedback）。
- `confirmation/AppConfirmationController`：全局确认对话框。
两者都通过 CompositionLocal 提供给 UI。

### 5.4 i18n（编译时扫描生成）

新增 UI 文字直接写：

```kotlin
i18n("繁體中文 source text")
```

机制：

1. `buildSrc` 的 `GenerateI18nResourcesTask` 扫描 `composeApp/src` 与 `shared/src` 中所有 `i18n(...)` 调用。
2. 结合 `i18n/glossary.csv`（术语表）与 `i18n/base.csv`（基础翻译），生成各语言 Compose 资源
   （输出到 `composeApp/build/generated/i18n/composeResources`）与 runtime 映射（`outputKotlin`）。
3. `i18n/i18n.properties` 是配置：`defaultLanguage=zh-tw`、`fallbackToSource=true`
   （无翻译时回退到 source 文本）、`failOnMissingTerm=true`、`apiFunctionName=i18n`。
4. 运行时由 `composeApp/i18n/AppLocale.kt` 提供语言切换与结果缓存。

需要稳定翻译时补到 `i18n/glossary.csv`。任务：`generateI18nResources`、`mergeI18nResources`、`checkI18n`。

> shared 层的 `i18n/SharedI18n.kt` 只是 `{}` 占位符替换，真正的翻译在 composeApp 层生成。

### 5.5 App 更新（update manifest）

- `update/manifest.json` 是**唯一手动维护**的 source manifest。
- `update/stable.json` 由 `./gradlew syncStableManifest` 生成，不手动改。
- source repo 内两者必须保持 `{"isReady": false, "assets": []}`，不得手写 `releaseUrl` 或 APK asset。
- 正式可更新 manifest 由 GitHub Actions 在 APK build/sign/upload 后生成，推送到 `update-release` branch。
- changelog 在 `update/changelogs/{versionCode}.changelog`。

详见 `dev-docs/app-updater-release.md` 与 `update/README.md`。

### 5.6 AppSync 云同步

AppSync 把收藏/设置/阅读记录同步到云端，`shared/.../repository/appsync/` 结构：

- `AppSyncService.kt`：同步服务入口
- `operation/`：同步操作定义
- `engine/`：同步引擎（数据差异合并、迁移规划）
- `domain/`：领域模型
- `model/`：数据模型
- `remote/`：远端通信
- `rollout/`：灰度/回滚
- `AppSyncMutationRecorder` / `AppSyncPayloadSanitizer`：变更记录与 payload 过滤

数据模型细节见 `dev-docs/app-sync-cloud-data-model.md`。

## 6. 阅读器（Reader）

`composeApp/.../thread/reader/` 是核心阅读器（thread 模块共 61 个文件）：

- `ThreadReaderScreen.kt`：小说/帖子阅读主入口（含收藏刷新、复制本章、阅读进度持久化）
- `ImagesReaderScreen.kt`：漫画/图片阅读
- `CommentReaderScreen.kt`：评论阅读
- `components/`：目录面板、悬浮菜单、阅读设置、标签等子组件
- `debug/`：性能调试（recompose probe、perf log）

阅读进度通过 `ChapterStateRepository` + `ReadHistoryRepository` 持久化，
由 `SynchronousReaderPersistence` 统一落库。漫画阅读走图片 reader，
小说阅读走分页/滚动 reader，支持简繁转换（`ChineseConversionRepository`）。

## 7. 本地独有功能（fork 扩展）

相对上游，本 fork 新增的能力（对应 `git log upstream/main..main` 中的本地提交）：

### 7.1 本地小说阅读（localnovel）

- UI：`composeApp/.../localnovel/` 的 `LocalNovelBookshelfScreen`（書架）与 `LocalNovelReaderScreen`（阅读器）。
- 数据层：`shared/.../repository/localnovel/`：
  - `LocalNovelRepository`（接口）+ `LocalNovelRepositoryImpl`
  - `TxtFileParser`（TXT 解析）/ `EpubFileParser`（EPUB 解析）
  - `PlatformFileOperations`（expect/actual，文件选择/读取）
- 数据库：SQLDelight 表 `LocalNovel`、`LocalNovelChapter`、`LocalNovelProgress`（`.sq` 文件）。
- 能力：TXT/EPUB 导入、自动分章、断点续读（按章 + 章内字符偏移）。

### 7.2 下载系统

- `shared/.../repository/download/DownloadRepository.kt`。
- 测试矩阵见 `docs/download-system-test-matrix.md`。

### 7.3 其他

- 复制本章内容（`ThreadReaderScreen` 的 `copyChapter`，复制当前章 HTML → 纯文本）。
- 本地阅读分章、断点续读的 UI 重构（本地提交 `dfd09be` 等）。

### 7.4 网盘云端备份（pancloud）

以第三方网盘（Cloud Nine，见仓库根 `API.md`）作为云端备份的唯一方式，替代暂不可用的
AppSync 云端同步。语义是**快照式整档备份**（上传/下载 + Merge/Overwrite 还原），不是多装置增量同步。

- 设计文档：`dev-docs/pan-cloud-backup-plan.md`。
- 数据层 `shared/.../repository/pancloud/`：
  - `PanCloudApiClient`：网盘 REST 客户端（Ktor，`HttpClientFactory`），统一解析
    `{success, data, message, error}`，401 自动刷新重试；上传按 ≤10MB 单档直传 / >10MB 分块。
  - `PanCloudAccountRepository`：注册/登录/登出/会话恢复 + `yamibo` 文件夹幂等绑定。
  - `PanCloudBackupStorageProvider`：实现 `BackupStorageProvider`，把 `.yamibobak` gzip 后
    上传到网盘 `yamibo` 文件夹，下载时 gunzip；复用 `BackupRepositoryImpl` 的生成/还原逻辑。
- 凭证：`AppSettingsRepository` 的 `pan_cloud_*` 设置；refresh_token 不进备份
  （`BackupRepositoryImpl.shouldSkipSetting` 黑名单）。
- UI：`composeApp/.../profile/settings/backup/PanCloudBackupScreen.kt`（登录/注册/手动备份/恢复/登出/自动备份开关）。
- 自动备份：`PanCloudBackupWorker` + `AndroidPanCloudBackupScheduler`（WorkManager）；
  iOS 为 stub（`IOSPanCloudBackupScheduler`）。

## 8. 平台实现要点

### 8.1 Android

- 入口 `composeApp/src/androidMain/.../MainActivity.kt`：创建各 repository 实例、
  YamiboClient、通知渠道，`setContent { App(...) }`。
- 后台任务用 WorkManager（`AppSyncWorker`、`SignReminderWorker`）。
- 文件选择器 `localnovel/LocalNovelFilePicker.android.kt`。

### 8.2 iOS

- 入口 `composeApp/src/iosMain/.../MainViewController.kt` + `iosApp/` SwiftUI 壳。
- OpenCC 桥接见 `dev-docs/ios-opencc-bridge.md`。

## 9. 构建与测试

环境：JDK 21、Android SDK 36、`ANDROID_HOME` 指向 SDK。Windows 建议 PowerShell 先设
`$OutputEncoding=[System.Text.Encoding]::UTF8`。

```powershell
.\gradlew --version                                   # 检查 Gradle
.\gradlew :composeApp:compileDebugKotlinAndroid --console=plain   # Android debug 编译
.\gradlew :composeApp:assembleDebug --console=plain   # Debug APK
.\gradlew :composeApp:assembleRelease --console=plain # Release APK
.\gradlew build --console=plain                       # 完整构建
.\gradlew syncStableManifest validateUpdateManifest --console=plain  # 同步并校验 update manifest
```

i18n 相关任务：`generateI18nResources`、`mergeI18nResources`、`checkI18n`。

## 10. 开发约定与注意事项

- **页面模式**：优先沿用 `Screen` + content/components + repository/cache。
- **UI 文字**：一律走 `i18n(...)`，稳定翻译补到 `i18n/glossary.csv`。
- **API 改动**：先确认本地 `kotlin-libs/yamibo-api` 或已发布的 yamibo-api 版本。
- **不提交** `build/` 内 generated source；`composeApp/src` 下的 i18n 生成副本也不提交。
- **update manifest**：不手动把 source manifest 改成 `isReady=true`，不手写 `releaseUrl`/asset。
- **OpenSpec 隔离**：`openspec/` 下任何文件/改动不得合并进 `main`；合并前
  `git diff --name-only main...HEAD -- openspec/` 必须为空。
- **分支命名**：建分支前读 `dev-docs/branch-naming.md`。
- **Git 身份**：每次提交前运行 `python .github/scripts/validate_git_name.py`，仅当其成功退出才提交。

## 11. 相关文档

- `dev-docs/app-sync-cloud-data-model.md`：AppSync 云同步数据模型
- `dev-docs/app-updater-release.md`：更新与发版流程
- `dev-docs/baidu-nox-waf-recovery.md`：WAF 恢复机制
- `dev-docs/ios-opencc-bridge.md`：iOS OpenCC 桥接
- `dev-docs/branch-naming.md`：分支命名规则
- `update/README.md`：update manifest 工作流
