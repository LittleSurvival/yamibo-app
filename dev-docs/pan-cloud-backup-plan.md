# 網盤雲端備份開發計劃

> 狀態：決策已確認，待實作。
> 網盤 Base URL：`https://pan.muleng.dpdns.org/api`
> 介面文件：`API.md`（倉庫根目錄，Cloud Nine 網盤介面呼叫文件）

## 0. 結論速覽

**可行。** 網盤 API 已涵蓋「認證 / 建資料夾 / 上傳（單檔 + 分塊）/ 下載 / 列表 / 刪除 /
儲存統計」全部需求，足以承載快照式雲端備份。

核心思路：

> 複用現有 `BackupRepository` 的快照生成與還原邏輯，新增一個
> **`PanCloudBackupStorageProvider` 實作 `BackupStorageProvider` 介面**，把備份檔案上傳到
> 網盤 `yamibo` 資料夾；還原時從網盤下載。`BackupRepositoryImpl` 幾乎零改動。

一句話定位：**網盤方案是「快照式雲端備份」（整檔上傳/下載 + Merge/Overwrite 還原），
不是 AppSync 那種「多裝置增量即時同步」。**

## 1. 背景與目標

現有雲端同步 AppSync 走 Yamibo 論壇 Blog API（Index / Journal / Checkpoint 三種 Blog），
目前暫不可用。本計劃以使用者的網盤作為雲端備份的唯一方式：

- APP 引導使用者註冊/登入網盤帳戶。
- 在網盤建立 `yamibo` 資料夾。
- 備份時，把按原有雲端同步方案（`BackupRepository` 快照）生成的檔案上傳到網盤 `yamibo` 資料夾。
- 還原時，從網盤拉取檔案並套用 Merge/Overwrite。

## 2. 現況梳理

### 2.1 AppSync（暫不可用的雲端同步）

- 位置：`shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/appsync/`
- 架構：local-first **operation-log**，透過 Yamibo 論壇 Blog API 同步三種 Blog
  （Index / Journal / Checkpoint），詳見 `dev-docs/app-sync-cloud-data-model.md`。
- 暫不可用的根源：依賴論壇帳號的 Blog 寫讀能力，而非獨立儲存後端——這是本計劃要取代的部分。

### 2.2 本地備份 BackupRepository（要複用的部分）

| 元件 | 路徑 | 作用 |
|---|---|---|
| `BackupRepository` 介面 | `shared/.../repository/BackupRepository.kt` | `createBackup` / `restoreBackup` / `listBackupFiles` / `cleanupAutoBackups` 等 |
| `BackupRepositoryImpl` | `shared/.../repository/backup/BackupRepositoryImpl.kt` | 生成快照、序列化、Merge/Overwrite 還原 |
| `BackupModels.kt` | `shared/.../repository/backup/BackupModels.kt` | `YamiboBackupFile`（收藏/設定/筆記/書籤/閱讀歷史/更新紀錄） |
| `CloudBackupPayloadCodec` | `shared/.../repository/backup/CloudBackupPayloadCodec.kt` | gzip+base64 信封（`yamibo-app-sync:gzip-base64:1:` 前綴），AppSync 傳輸編碼 |
| `PortableDomainManifest` | `shared/.../repository/backup/PortableDomainManifest.kt` | 域清單（哪些進 local backup / appsync） |
| `BackupStorageProvider` 介面 | `shared/.../repository/backup/BackupStorageProvider.kt` | 儲存後端抽象（寫/讀/列/刪） |
| 平台實作 | `shared/src/androidMain/.../AndroidBackupStorageProvider.kt`、`shared/src/iosMain/.../IOSBackupStorageProvider.kt` | 本地 SAF / iOS 文件目錄 |

關鍵事實（已讀原始碼確認）：

- `BackupRepositoryImpl.createSnapshot(now, scope)` 是唯一快照來源；`createAppSyncSnapshot()`
  就是用它在 `PortableSnapshotScope.AppSync` 下生成 `YamiboBackupFile`。
- `createBackup()` = `createSnapshot(LocalBackup)` → JSON → `storageProvider.writeBackupFile(name, bytes)`。
- `restoreBackup(uri, mode)` = `storageProvider.readBackupFile(uri)` → 解碼 → `migrate` → `restoreSnapshot`。
- 因此 `BackupRepositoryImpl` 對「儲存在哪」完全無感——只要換一個 `BackupStorageProvider`
  就能把備份存到網盤，這是本方案的基石。
- `CloudBackupPayloadCodec` / `createAppSyncSnapshot` 為 `internal`，網盤 provider 放在
  `shared` 模組內即可直接呼叫。

### 2.3 DI 與 UI 接入點（要擴充的部分）

- DI：`composeApp/.../AppRepository.kt` 的 `LocalBackupRepository`；裝配在 `MainActivity.kt`
  （約 283–292 行）與 `MainViewController.kt`（約 198–200 行）。
- UI：`composeApp/.../profile/settings/backup/BackupSettingsScreen.kt`（本地備份頁）、
  `BackupScheduler.kt` + `AndroidBackupScheduler.kt` + `BackupWorker.kt`（自動備份）。
- 設定：`AppSettingsRepository` 已有 `backup_interval` / `backup_max_auto_files` /
  `backup_folder_uri` / `backup_last_auto_backup_at`。

## 3. 可行性評估（網盤 API ↔ 需求對照）

| 需求 | 網盤介面 | 結論 |
|---|---|---|
| 註冊/登入/續期 | `POST /api/auth/register` / `login` / `refresh`（access 15min，refresh 7d） | ✅ 需自行實作自動刷新+重試 |
| 確保 `yamibo` 資料夾存在 | `GET /api/files` + `POST /api/files/folder`（409 同名衝突） | ✅ 需冪等處理 |
| 上傳備份（可能 >10MB） | `POST /api/files/upload`（≤10MB 直傳）；`upload/chunk` + `upload/complete`（>10MB 分塊，chunk 10MB） | ✅ 需按體積分檔 |
| 下載備份 | `GET /api/files/:id/download`（回傳二進位串流） | ✅ |
| 列出/清理舊備份 | `GET /api/files?parent_id=`、`DELETE /api/files/:id` | ✅ |
| 儲存統計 | `GET /api/user/storage` | ✅ 可選展示 |
| 連通性 | 實測 `GET /api/auth/me` → `401 {"success":false,"error":"Unauthorized: missing token"}` | ✅ 服務可達、格式符合文件 |

唯一工程複雜度點：大備份的分塊上傳。備份檔案體量：`CloudBackupPayloadCodec` 上限是
壓縮後 ≤12MB / 解壓 ≤64MB，因此「單檔直傳」經常不夠，**必須實作 chunk + complete 分檔上傳**。
網盤分塊介面**無斷點續傳**（失敗需整體重傳），列入風險。

## 4. 總體方案

### 4.1 複用 / 新增

**複用（不改或極少改）**：

- `YamiboBackupFile` 快照模型 + `BackupRepositoryImpl` 的生成/還原/Merge/Overwrite/清理邏輯。
- `BackupStorageProvider` 介面契約、`BackupScheduler`/`BackupWorker` 自動備份機制、`i18n`、
  `HttpClientFactory`（Ktor 客戶端，Android okhttp / iOS darwin）。

**新增（都在 `shared/commonMain`，跨平台一次實作）**：

1. `repository/pancloud/PanCloudApiClient.kt` — 網盤 REST 客戶端（Ktor + `HttpClientFactory`）。
2. `repository/pancloud/PanCloudModels.kt` — `@Serializable` DTO（auth、file、upload）。
3. `repository/pancloud/PanCloudAuthStore.kt` — access/refresh token 持久化（**不進備份**）。
4. `repository/pancloud/PanCloudAccountRepository.kt` — 註冊/登入/登出/狀態、自動刷新。
5. `repository/pancloud/PanCloudBackupStorageProvider.kt` — `BackupStorageProvider` 實作
   （上傳/下載/列/刪，含分塊）。
6. `AppSettingsRepository` 增加 `pan_cloud_*` 設定（token、folder_id、username、自動備份開關等）。
7. UI：新增 `PanCloudBackupScreen`（登入/綁定/狀態/手動備份/還原）。
8. DI：`AppRepository.kt` 新增 `LocalPanCloudBackupRepository`；`MainActivity`/`MainViewController` 裝配。

### 4.2 資料流

```
【註冊/綁定】
  UI 輸入使用者名稱+密碼
    → POST /api/auth/register（409 則轉 login）
    → 保存 refresh_token（本地，Secret）
    → GET /api/files?parent_id=null 找 name=yamibo
    → 不存在則 POST /api/files/folder{name:"yamibo"}（409 則複用）
    → 保存 folder_id 到設定

【備份】
  createBackup() → createSnapshot(LocalBackup) → JSON bytes
    → PanCloudBackupStorageProvider.writeBackupFile
       → gzip（okio GzipSink）
       → 若 size≤10MB：POST /api/files/upload（multipart，parent_id=yamibo_folder）
       → 若 size>10MB：upload/chunk × N → upload/complete
    → 記 file_id + name（用於 list/restore）

【還原】
  UI 列出 yamibo 資料夾內的 *.yamibobak（GET /api/files?parent_id=）
    → 選檔 → GET /api/files/:id/download → bytes → gunzip
    → BackupRepositoryImpl.restoreBackup 走原邏輯（Merge/Overwrite）

【自動備份】
  複用 BackupScheduler/Worker，間隔設定沿用 backup_interval
```

## 5. 已確認決策

| # | 決策點 | 結論 |
|---|---|---|
| D1 | 後端形態 | **實作 `BackupStorageProvider`**，複用 `BackupRepositoryImpl` 全部邏輯，零侵入 |
| D2 | 傳輸格式 | **gzip 二進位（okio `GzipSink`/`GzipSource`），不做 base64 信封**（理由見下） |
| D3 | 快照 scope | **`PortableSnapshotScope.LocalBackup`（全量，含 `LocalChapterState`）**，網盤定位是完整備份 |
| D4 | 帳號模型 | **手動註冊/登入，支援既有網盤帳戶**（跨裝置還原需同一帳戶登入） |
| D5 | 憑證安全 | refresh_token 存本地、**不進備份**（`PortableDomainManifest` 已排除 `authentication`）、不寫日誌、HTTPS |
| D6 | 與 AppSync 關係 | **並存，不動 AppSync**；網盤作為新增的雲端備份入口 |
| D7 | 本地檔案備份 | **保留**（SAF/iOS 匯出），網盤為雲端備份通道 |
| D8 | base URL | 常數 `https://pan.muleng.dpdns.org/api` + debug 可覆蓋 |

### D2 為何選 gzip 二進位（最不易出錯）

1. **介面只拿 bytes**：`BackupStorageProvider.writeBackupFile(name, bytes)` 收到的是 JSON bytes，
   拿不到 `YamiboBackupFile` 物件。若用 `CloudBackupPayloadCodec`（base64 信封），其 `encode`
   輸入是 `YamiboBackupFile`，勢必改 `BackupRepositoryImpl` 或讓 provider 反序列化——增加耦合與出錯面。
2. **gzip 用現成 API**：okio `GzipSink`/`GzipSource` 十幾行即可，且 `CloudBackupPayloadCodec`
   已提供可參考的 gzip 實作，`BackupRepositoryImpl` 零改動。
3. **體積更小**：無 base64 約 33% 膨脹；網盤是二進位介質，無文字信封必要。
4. **還原閉環風險最低**：gunzip 後得到原 JSON，`restoreBackup` 的 `json.decodeFromString` 直接可用。

## 6. 分階段實施計劃

每階段可獨立提交/合入，均有明確驗收標準。

### Phase 0 — 網盤 API 客戶端（純資料層，可單測）

- **任務**：新增 `PanCloudModels.kt`（DTO）+ `PanCloudApiClient.kt`，用 `HttpClientFactory.create`
  建 Ktor 客戶端，實作 `register / login / refresh / me / listFiles / createFolder / upload /
  uploadChunk / completeUpload / download / delete`；封裝統一回應
  `{success, data, message, error}` 解析 + 自動刷新 + 401 重試一次。
- **檔案**：`shared/src/commonMain/.../repository/pancloud/{PanCloudApiClient,PanCloudModels}.kt`
- **驗收**：`commonTest` 用 `MockEngine` 打樁覆蓋：註冊/登入成功、409 衝突、401→refresh→重試、
  分塊 complete 的 index 校驗錯誤。

### Phase 1 — 憑證與帳號綁定

- **任務**：`PanCloudAuthStore`（refresh_token 持久化，用 `SettingsStore`，key 加進
  `shouldSkipSetting` 黑名單確保不進備份）；`PanCloudAccountRepository`
  （register→409 轉 login、logout、`currentUser()`、資料夾冪等綁定：
  list→createFolder→存 folder_id）；`AppSettingsRepository` 增加
  `pan_cloud_refresh_token / pan_cloud_folder_id / pan_cloud_username / pan_cloud_auto_backup_enabled`。
- **檔案**：上述三個檔案 + `AppSettingsRepository.kt`
- **驗收**：單測覆蓋 409 轉 login、資料夾冪等（已存在不重複建）；`BackupRepositoryImpl`
  備份產出中**不含** `pan_cloud_*` 設定。

### Phase 2 — 網盤儲存後端（核心）

- **任務**：`PanCloudBackupStorageProvider` 實作 `BackupStorageProvider`：
  - `writeBackupFile(name, bytes)`：gzip（okio `GzipSink`）→ 按體積分檔 → ≤10MB 直傳 /
    >10MB 分塊（10MB/chunk）→ 回傳 `BackupFileInfo(uri = file_id)`。
  - `readBackupFile(uri)`：`GET /api/files/:id/download` → gunzip → 原 bytes。
  - `listBackupFiles`：`GET /api/files?parent_id=<yamibo>` 過濾 `.yamibobak`。
  - `deleteBackupFile`：`DELETE /api/files/:id`。
  - `getSelectedFolderLabel` / `setSelectedFolder`：回傳網盤帳戶 + `yamibo` 資料夾識別。
- **檔案**：`shared/src/commonMain/.../repository/pancloud/PanCloudBackupStorageProvider.kt`
- **驗收**：單測用 `MockEngine` 覆蓋直傳/分塊兩檔、分塊 complete 的 `file_ids` 按 index 升序
  與 `total_size` 校驗；整合測試驗證 `BackupRepositoryImpl(panCloudStorageProvider)` 的
  create→list→restore 全鏈路。

### Phase 3 — DI 裝配

- **任務**：`AppRepository.kt` 新增 `LocalPanCloudBackupRepository`
  （`compositionLocalOf<BackupRepository>`）；`MainActivity.kt` / `MainViewController.kt`
  建立網盤 `BackupRepositoryImpl`（+ `PanCloudBackupStorageProvider`）並 provide。
- **檔案**：`AppRepository.kt`、`MainActivity.kt`、`MainViewController.kt`
- **驗收**：`./gradlew :composeApp:compileDebugKotlinAndroid --console=plain` 通過；
  Android/iOS 均能編譯。

### Phase 4 — UI

- **任務**：新增 `PanCloudBackupScreen`（未登入→登入/註冊表單；已登入→顯示帳戶/資料夾/儲存用量、
  手動備份、備份列表、還原 Merge/Overwrite 選擇、登出）。複用 `BackupSettingsScreen` 的
  `BackupInfoCard`/`BackupFileListCard`/`RestoreModeDialog` 等子元件。所有文案走 `i18n(...)`。
- **檔案**：`composeApp/.../profile/settings/backup/PanCloudBackupScreen.kt`（+ 新 entry 註冊導航）
- **驗收**：`generateI18nResources` 通過；手工走查登入→備份→登出→登入→還原全流程。

### Phase 5 — 自動備份調度

- **任務**：複用 `BackupScheduler`/`AndroidBackupScheduler`/`BackupWorker`，當
  `pan_cloud_auto_backup_enabled` 為真時，定時呼叫網盤 `createBackup(automatic=true)`；
  沿用 `backup_interval` / `backup_max_auto_files` 做輪換清理。
- **檔案**：`AndroidBackupScheduler.kt`、`BackupWorker.kt`（增量改造）
- **驗收**：WorkManager 後台備份成功落網盤；自動備份數量受 `backup_max_auto_files` 約束。

### Phase 6 — 測試 + i18n + 文件

- **任務**：`generateI18nResources`/`mergeI18nResources`；新增
  `PanCloudBackupStorageProviderTest`、`PanCloudApiClientTest`；補
  `docs/pan-cloud-backup-test-matrix.md`（註冊/登入/建夾/直傳/分塊/還原/清理/斷網重試矩陣）；
  更新 `dev-docs/architecture.md` 第 7 節補充網盤備份。
- **驗收**：`./gradlew :shared:testDebugUnitTest --console=plain`（或對應 Android unit test 任務）綠；
  `checkI18n` 通過。

## 7. 風險與限制

1. **快照語義 ≠ 增量同步**：網盤方案是「整份上傳/下載」，多裝置同時改同一份資料時是
   **最後寫入覆蓋**（無 AppSync 的 deterministic merge）。
2. **分塊上傳無斷點續傳**：>10MB 備份若中途失敗需整體重傳；大帳號（收藏/歷史很多）備份
   體積可能到數十 MB。
3. **refresh_token 7 天過期**：過期後需重新登入；`refresh` 回傳 401 要引導使用者重登。
4. **憑證遺失 = 無法還原**：還原依賴登入同一網盤帳戶，密碼由使用者保管，App 無法找回。
5. **網盤 API 無 ETag/CAS**：併發寫/刪無樂觀鎖，靠檔名 + 時間戳輪換規避。
6. **`pan.muleng.dpdns.org` 是動態 DNS 域名**：穩定性/速率限制需長期觀察，建議客戶端加
   指數退避重試。
7. **安全**：備份內容（收藏/筆記/歷史）以 gzip 明文存於網盤，非端對端加密；若敏感需後續
   加 AES（Android 可複用 `AndroidX Security Crypto`，iOS 需另選）。

## 8. 關鍵檔案清單

- 介面文件：`API.md`（倉庫根目錄）
- 既有複用：
  - `shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/backup/`（Backup 系統）
  - `shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/factory/HttpClientFactory.kt`
  - `composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/profile/settings/backup/`（UI）
- 新增：
  - `shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/pancloud/`（API 客戶端 + 儲存後端 + 帳號）
  - `composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/profile/settings/backup/PanCloudBackupScreen.kt`
