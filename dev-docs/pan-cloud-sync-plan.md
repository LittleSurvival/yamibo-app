# 网盘备份并入本地备份 + 网盘云端同步开发计划

> 状态：待评审。本文在 `dev-docs/pan-cloud-backup-plan.md` 已完成的快照式网盘备份基础上，
> 规划两项收敛工作：
> - **Part A**：把快照式网盘备份并入「本地資料備份」功能（统一 `.yamibobak` 文件，自动备份自动上云盘）。
> - **Part B**：实现网盘 AppSync，功能名「網盤雲端同步」（多设备增量合并）。

## 0. 目标总览

| 项目 | 现状 | 目标 |
|---|---|---|
| 快照式网盘备份 | 独立入口 `PanCloudBackupScreen`，与本地备份分离 | 并入 `BackupSettingsScreen`，作为「备份目标」之一（本地 / 网盘，可并存），自动备份自动上传网盘 |
| 云端同步 | AppSync 引擎已解耦，但 remote 硬编码论坛 Blog（暂不可用） | 云端同步内增加后端选择（论坛 Blog / 云盘，**二选一**），新增 `PanCloudJournalRemote` 复用 AppSync 引擎实现「網盤雲端同步」 |

---

## 1. Part A：快照式网盘备份并入「本地資料備份」

### 1.1 现状

- `BackupSettingsScreen`（`composeApp/.../profile/settings/backup/BackupSettingsScreen.kt`）只处理本地
  `LocalBackupRepository`（storageProvider = `AndroidBackupStorageProvider` / `IOSBackupStorageProvider`）。
- 网盘备份是独立页面 `PanCloudBackupScreen` + 独立 `LocalPanCloudBackupRepository`
  （storageProvider = `PanCloudBackupStorageProvider`，已在上一轮实现）。
- 两者都通过 `BackupRepositoryImpl` 生成**同一个** `YamiboBackupFile` → `.yamibobak` 文件，只是存储后端不同
  （本地写 JSON；网盘 gzip 传输）。

### 1.2 目标

- 只保留「本地資料備份」一个入口；网盘作为**备份目标**之一。
- 备份目标：本地文件夹（默认开启）+ 网盘 `yamibo` 文件夹（登录网盘后可选开启，两者可并存）。
- 手动/自动备份：按开启的目标分别写入本地与网盘。
- 恢复：从「本地备份文件」或「网盘备份文件」列表分别选择恢复。

### 1.3 方案（UI 聚合，不重构 repository）

**决策 A1**：不改造 `BackupRepositoryImpl`（保持单一 storageProvider），在 UI/调度层聚合两个
`BackupRepository` 实例（本地 + 网盘）。

理由：
- `BackupRepositoryImpl` 已稳定、有大量测试，改动风险大。
- 两个 repository 的差异只在 storageProvider，聚合层逻辑简单（备份时分别调 `createBackup`，恢复时按来源选 repository）。

### 1.4 改动点

| 层 | 文件 | 改动 |
|---|---|---|
| UI | `BackupSettingsScreen.kt` | 合并网盘功能：`BackupInfoCard` 显示本地文件夹 + 网盘账号状态；新增「备份目标」区块（本地开关 + 网盘登录/绑定 + 开关）；文件列表分「本地 / 网盘」两个来源（标记 + 恢复回调） |
| UI | `PanCloudBackupScreen.kt` / `IPanCloudBackupScreen.kt` | 删除（其登录表单、账户卡片、网盘文件列表逻辑迁入 `BackupSettingsScreen`） |
| UI | `SettingsScreen.kt` | 移除「網盤雲端備份」入口（恢复为单一「本地資料備份」入口） |
| 调度 | `BackupWorker.kt`（Android） | 在本地备份后，若网盘已登录且开关开启，调用网盘 `createBackup(automatic=true)` 上传 |
| 调度 | `AndroidBackupSupport.kt` / `AndroidPanCloudBackupSupport.kt` | 复用现有 support，worker 内聚合 |
| i18n | `i18n/glossary.csv` | 复用/补充「备份目标」「网盘」等术语 |

### 1.5 Part A 阶段

- **A0**：`BackupSettingsScreen` 增加网盘登录/绑定区块（复用 `PanCloudBackupScreen` 的登录表单逻辑）。
- **A1**：备份目标开关 + 手动备份聚合（本地 + 网盘）。
- **A2**：文件列表分来源 + 恢复路由（本地 uri / 网盘 fileId）。
- **A3**：`BackupWorker` 自动备份聚合（本地 + 网盘）。
- **A4**：删除 `PanCloudBackupScreen`/`IPanCloudBackupScreen`，移除设置页独立入口，i18n 收敛。

---

## 2. Part B：网盘云端同步（AppSync 增量合并）

### 2.1 现状（已确认引擎解耦）

- AppSync 的合并引擎全部在 `engine/`（`OperationReducer`、`OperationSyncEngine`、`BootstrapCoordinator`、
  `CheckpointCoordinator`、`CompactionCoordinator`、`JournalRetirementCoordinator`）与 `operation/`，只依赖
  **抽象接口** `AppSyncJournalRemote`（`engine/OperationSyncEngine.kt:97`）。
- 唯一绑定论坛的地方：`AppSyncService` 构造函数**硬编码**创建 `YamiboAppSyncJournalRemote`
  （`AppSyncService.kt:306`），其依赖 `AppSyncBlogProvider`（论坛 Blog 读写）。
- 三个 envelope codec（`AppSyncJournalEnvelopeCodec` / `IndexEnvelopeCodec` / `CheckpointEnvelopeCodec`）
  是「对象 ↔ 字符串」的纯编解码，**与传输介质无关**，可直接用于网盘文件内容。

### 2.2 映射设计（论坛 Blog → 网盘文件）

网盘 `yamibo` 文件夹内：

| AppSync 概念 | 论坛 Blog | 网盘文件 |
|---|---|---|
| Index | `Yamibo App Sync Index ... v1` | `index.json` |
| Journal（每设备一篇） | `Yamibo App Sync Journal ... <replica>` | `journal-<replicaKey>.json` |
| Checkpoint（≤3） | `Yamibo App Sync Checkpoint ... <id>` | `checkpoint-<id>.json` |

文件内容 = 对应 codec 的 `encode(...)` 输出（字符串，可 gzip 后上传）。

### 2.3 方案（PanCloudJournalRemote + 注入改造）

**决策 B1**：新增 `PanCloudJournalRemote` 实现 `AppSyncJournalRemote`，内部复用已实现的
`PanCloudApiClient`（上传/下载/列表/删除 + 分块），把 5 个方法映射为网盘文件操作：

| 接口方法 | 网盘实现 |
|---|---|
| `loadJournals(accountBinding, forceDiscovery)` | `listFiles(parent_id=yamibo)` 列出 `index.json` + `journal-*` + `checkpoint-*`，下载并用对应 codec 解析验证 |
| `publishOwnJournal(payload, expectedFingerprint, formHash)` | `journalCodec.encode(payload)` → gzip → 上传 `journal-<replicaKey>.json`（single-writer，只写自己的文件） |
| `publishCheckpoint(payload, formHash)` | `checkpointCodec.encode(...)` → 上传 `checkpoint-<id>.json` |
| `enforceCheckpointRetention(...)` | 列出 checkpoint 文件，按保留上限删除旧文件 |
| `publishRetirementIndex(...)` / `deleteRetiredJournal(...)` | 更新 `index.json` / 删除对应 `journal-*` 文件 |

**决策 B2**：`AppSyncService` 通过一个可切换的 `SwitchableAppSyncJournalRemote` 获取 remote，依据设置
`appsync.backend`（`forum` / `pancloud`）委托给 `YamiboAppSyncJournalRemote`（论坛）或
`PanCloudJournalRemote`（网盘）。切换后端需重置 installation 状态为 Unbound，重新走 Seed/Join
（两个后端是独立云存储，数据不互通）。

**决策 B3**：网盘账号复用。`PanCloudJournalRemote` 复用 `PanCloudApiClient` + `PanCloudAccountRepository`
（同一网盘账户/凭证），不另建账号体系。

**决策 B4**：绕过 `AppSyncRemoteBlogStore`（它强依赖论坛 `BlogId/ClassId`）。网盘实现用「文件名 + 内存缓存」
做 discovery 索引，fingerprint 用文件内容校验，不改 store。

### 2.4 关键改动点

| 文件 | 改动 |
|---|---|
| `shared/.../appsync/remote/PanCloudJournalRemote.kt`（新增） | 实现 `AppSyncJournalRemote`，网盘文件读写 + codec 复用 |
| `shared/.../appsync/AppSyncService.kt` | 改用可切换 remote（`SwitchableAppSyncJournalRemote`，按 `appsync.backend` 设置委托） |
| `composeApp/.../MainActivity.kt`、`MainViewController.kt` | 装配 `PanCloudJournalRemote`（复用 `PanCloudApiClient`/`PanCloudAccountRepository`）传给 `AppSyncService` |
| `AppSyncWorker.kt`、`AndroidFavoriteSyncSupport.kt`、`AndroidFavoriteUpdateSupport.kt`、`IOSAppSyncBackground.kt` | 同步更新后台路径（网盘 remote + 凭证恢复） |
| `composeApp/.../cloud/AppSyncSettingsScreen.kt` | 增加「同步后端」选择（论坛 Blog / 云盘，二选一）；选择云盘时标题/文案体现网盘后端 |
| `SettingsScreen.kt` | 「雲端同步」入口保持不变（后端在同步页内二选一） |

### 2.5 Part B 阶段

- **B0**：`PanCloudJournalRemote` 骨架 + 文件名约定 + `loadJournals`（list + 下载 + codec 解码验证）。
- **B1**：`publishOwnJournal`（single-writer 上传）+ `AppSyncService` 注入改造。
- **B2**：`publishCheckpoint` / `enforceCheckpointRetention` / `publishRetirementIndex` / `deleteRetiredJournal`。
- **B3**：平台装配（MainActivity/MainViewController + 后台 4 处）+ 网盘凭证恢复。
- **B4**：UI（同步后端二选一 + 切换后重置 installation + 状态展示）+ i18n + 端到端验证。

---

## 3. 分阶段实施总览（Part A 与 Part B 可并行）

```
A0 网盘登录/绑定区块 → A1 备份目标开关+手动聚合 → A2 文件列表分来源+恢复路由 → A3 自动备份聚合 → A4 清理独立入口
B0 PanCloudJournalRemote.loadJournals → B1 publishOwnJournal+注入 → B2 checkpoint/retirement → B3 平台装配 → B4 UI
```

每阶段独立可提交，均有编译 + 单测验收。

## 4. 风险与限制

1. **并发写（网盘无 CAS）**：Index 是 shared write，靠 AppSync 既有「advisory cache」语义容忍后写覆盖；
   Journal 靠 single-writer（每设备只写自己的文件）规避。
2. **`formHash: FormHash` 是论坛遗留参数**：网盘实现忽略（传占位值），不改接口签名。
3. **AppSyncService 有 6 处创建点**（UI + 后台）：注入改造需逐一覆盖，后台路径要额外做网盘凭证恢复（refresh_token → access_token）。
4. **`AppSyncRemoteBlogStore` 依赖论坛 BlogId**：网盘实现绕过它，用文件名索引；需确认引擎层不强制依赖 store 的 BlogId。
5. **网盘分块上传无断点续传**：超大 Journal/Checkpoint 上传失败需整体重传。
6. **快照备份（Part A）仍是整档覆盖语义**：与增量同步（Part B）并存，二者各司其职——备份负责整档兜底，同步负责多设备实时合并。
7. **iOS 后台**：`IOSAppSyncBackground` 与 `IOSPanCloudBackupScheduler` 均需 macOS 编译验证；自动同步在 iOS 受 BGTaskScheduler 限制。

## 5. 已确认决策

1. **Part A 备份目标**：本地默认开启 + 网盘可选开启，两者**并存**。
2. **Part B 后端选择**：直接在「雲端同步」内提供后端选择——论坛 Blog / 云盘，**二选一**（不新增并列入口）。
3. **论坛 AppSync 保留**：论坛实现代码保留，作为可选后端之一，UI 内二选一切换。
4. **网盘账号**：复用与 Part A 相同的网盘账户/凭证，无需额外登录。

---

## 附：关键文件索引

- 备份/网盘存储：`shared/.../repository/backup/`、`shared/.../repository/pancloud/`
- AppSync 引擎：`shared/.../repository/appsync/engine/`、`operation/`
- AppSync remote（论坛）：`shared/.../repository/appsync/remote/`
- UI：`composeApp/.../profile/settings/backup/`、`composeApp/.../profile/settings/cloud/`
- 调度：`composeApp/src/androidMain/.../profile/settings/backup/`（`BackupWorker.kt` 等）
