# 網盤登入檢測與雲端同步帳號 開發計劃

> 狀態：待實作。在既有網盤備份/同步的基礎上，補齊兩項：
> 1. 網盤登入狀態檢測 + 失效自動重新登入（refresh）。
> 2. 雲端同步頁（選網盤後端時）提供網盤帳號註冊/登入。

## 0. 目標

| # | 需求 | 說明 |
|---|---|---|
| 1 | 登入檢測 + 自動重新登入 | access_token 失效 → 自動用 refresh_token 刷新（401 自動重試）；refresh_token 也過期 → 標記「已失效」，UI 提示重新登入 |
| 2 | 雲端同步頁帳號 | 「雲端同步」頁選「網盤」後端時，提供網盤帳號登入/註冊/登出，與備份頁共用同一登入元件 |

## 1. 現況

- `PanCloudAccountRepository`（`shared/.../repository/pancloud/`）：
  - `AccountStatus(loggedIn, username, folderId)`，`loggedIn` 僅表示 refresh_token 非空，**不區分有效/過期**。
  - `restoreSession()` 用 refresh_token 換新 access_token；失敗只回傳 `Result.failure`，**不清憑證、不標記失效**。
  - `refreshAccessToken()`（`onUnauthorized` 401 鉤子）失敗回傳 false，同樣不標記。
  - `login/register/logout` 已齊全。
- 網盤登入/註冊 UI 目前在 `BackupSettingsScreen.kt` 的 `CloudBackupCard` + `CloudLoginDialog`（`private`）。
- `AppSyncSettingsScreen`（`profile/settings/cloud/`）的 `BackendSelectionSection` 只有「同步後端」二選一（論壇 Blog / 網盤），**選網盤後無登入入口**。
- 啟動時（`MainActivity`/`MainViewController`/`AppSyncWorker`/`IOSAppSyncBackground`）已呼叫 `restoreSession()`。

## 2. 方案

### 2.1 會話狀態模型（需求 1）

在 `PanCloudAccountRepository` 增加會話狀態：

```kotlin
enum class PanCloudSessionState {
    Active,      // access_token 有效（剛登入/刷新成功）
    LoggedOut,   // 無 refresh_token
    Expired,     // refresh_token 過期（refresh 401）
    Unknown,     // 尚未檢測（啟動初始）
}

data class AccountStatus(
    val state: PanCloudSessionState,
    val username: String?,
    val folderId: String?,
)
```

- 以 `MutableStateFlow<AccountStatus>` 暴露 `sessionState`，UI 用 `collectAsState()` 觀察。
- 變更點：
  - `login/register/adoptAuth` 成功 → `Active`。
  - `logout/clearAuth` → `LoggedOut`。
  - `restoreSession()`：refresh 401 → 清憑證 + `Expired`；refresh 網路失敗 → 保留憑證 + 回傳 failure（可重試）；refresh 成功 → `Active`。
  - `refreshAccessToken()`（401 鉤子）：refresh 401 → 清憑證 + `Expired`；網路失敗 → 保留憑證回傳 false。

### 2.2 自動重新登入（需求 1）

- **access_token 失效**：沿用既有 `onUnauthorized` 自動 refresh + 重試（已實現），確保多數情況無感。
- **refresh_token 過期**：無法自動重登（密碼不落盤），改為「檢測 → 標記 Expired → UI 提示重新登入」。
- **進入頁面時主動檢測**：雲端同步頁 / 備份頁在 `LaunchedEffect` 呼叫 `restoreSession()`，刷新會話狀態；`Expired` 時以 feedback/對話框提示。

### 2.3 雲端同步頁帳號（需求 2）

- 抽取 `CloudLoginDialog` 為共用 `internal` composable（從 `BackupSettingsScreen.kt` 移到 `profile/settings/cloud/` 或 `components/`），`BackupSettingsScreen` 與 `AppSyncSettingsScreen` 共用。
- `AppSyncSettingsScreen` 的 `BackendSelectionSection`：當選「網盤」後端時，額外顯示網盤帳號區塊：
  - `Active`：顯示 username + 「登出」。
  - `LoggedOut` / `Unknown`：顯示「登入網盤」按鈕（開啟共用登入對話框，含註冊切換）。
  - `Expired`：顯示「登入已失效，請重新登入」+ 登入按鈕。
- 登入成功後自動 `restoreSession()`/刷新，並可提示「已登入網盤」。

## 3. 分階段實施

| 階段 | 內容 | 驗收 |
|---|---|---|
| C0 | `PanCloudSessionState` + `AccountStatus.state` + `sessionState` StateFlow + `restoreSession`/`refreshAccessToken` 的 401/網路失敗分流 | shared 單測：401→Expired+清憑證；網路失敗→保留憑證 |
| C1 | 抽取共用 `CloudLoginDialog`；`BackupSettingsScreen` 改為引用共用元件 | Android 編譯通過；備份頁登入功能不變 |
| C2 | `AppSyncSettingsScreen` 的 `BackendSelectionSection` 增加網盤帳號區塊（狀態 + 登入/註冊/登出） | 編譯通過；切換到「網盤」後端時可見帳號區 |
| C3 | 頁面進入時 `restoreSession()` 主動檢測；`Expired` 提示重新登入 | 手工走查：過期 token → 顯示失效提示 |
| C4 | i18n 術語補充 + `checkI18n` + 測試收尾 | checkI18n 通過 |

## 4. 風險與邊界

1. **refresh_token 過期無法自動重登**：密碼不落盤，只能提示使用者手動登入（這是「自動重新登入」的邊界，需在 UI 明確）。
2. **`onUnauthorized` 與 `restoreSession` 並發**：多個請求同時 401 觸發多次 refresh，需以 Mutex 串列化，避免重複刷新/重登。
3. **網盤後端選中但未登入**：同步會失敗（401）；需在 UI 明確引導「先登入網盤再同步」。
4. **i18n**：新增「登入已失效」「登入網盤」等術語需進 `glossary.csv`（`failOnMissingTerm=true`）。
5. **iOS**：`MainViewController`/`IOSAppSyncBackground` 的改動需 macOS 編譯驗證。

## 5. 已確認決策

1. 網盤備份（Part A）與網盤同步（Part B）**共用同一網盤帳戶**（已有 `PanCloudAccountRepository`）。
2. 雲端同步頁選「網盤」後端時，就地提供登入/註冊（不另開頁面）。
3. 自動重新登入＝access_token 自動 refresh；refresh_token 過期改提示手動登入。

## 附：關鍵檔案

- `shared/.../repository/pancloud/PanCloudAccountRepository.kt`（會話狀態）
- `composeApp/.../profile/settings/backup/BackupSettingsScreen.kt`（既有 `CloudLoginDialog`）
- `composeApp/.../profile/settings/cloud/AppSyncSettingsScreen.kt`（`BackendSelectionSection`）
