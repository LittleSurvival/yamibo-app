# 網盤憑證加密落盤 + 自動重登 開發計劃

> 狀態：已確認決策，實作中。
> 決策：存加密密碼，refresh_token 過期後自動重新登入；iOS 暫不動（維持 NSUserDefaults）。

## 0. 現況（安全發現）

- `refresh_token` 目前**明文**存於：
  - Android：`AndroidSettingsStore` 用普通 `SharedPreferences`。
  - iOS：`IOSSettingsStore` 用 `NSUserDefaults`。
- Android 已有加密元件 `AndroidEncryptedPreferences.kt`（`EncryptedSharedPreferences` + `MasterKey`，AES256-GCM，Keystore），但 `AndroidSettingsStore` 未使用；iOS 無 Keychain 封裝。

## 1. 方案

1. **加密 store（Android）**：新增 `EncryptedSettingsStore`（實現 `SettingsStore`，底層用 `encryptedSharedPreferences`）；iOS 暫用 `IOSSettingsStore`。
2. **依賴改造**：`PanCloudAccountRepository` 改為接收 `SettingsStore`（內部自建 `AppSettingsRepository`），讓呼叫方可注入加密 store。
3. **密碼欄位**：`AppSettingsRepository` 新增 `panCloudPassword`；`login/register` 成功後保存，`logout` 清除。
4. **自動重登**：`restoreSession`/`refreshAccessToken` 遇到 refresh 401 → 讀取密碼 → 自動 `login` 換新 token；`login` 也 401（密碼失效）→ 標記 `Expired` 提示手動登入。
5. **裝配**：Android（`MainActivity`/`AppSyncWorker`/`AndroidPanCloudBackupSupport`）傳入加密 store；iOS 傳 `IOSSettingsStore`。

## 2. 安全要點

- `panCloudPassword` / `panCloudRefreshToken` 加入 `BackupRepositoryImpl.shouldSkipSetting` 黑名單，**絕不進 `.yamibobak`**。
- Android 密鑰存 Keystore，不隨 `adb`/雲備份走；加密值被備份也無法解。
- 密碼是**可逆加密存儲**（密鑰同設備），防明文外洩，但不防 root/越獄/物理獲取後解密——屬可接受的緩解。

## 3. 階段

| 階段 | 內容 | 驗收 |
|---|---|---|
| D0 | `EncryptedSettingsStore`（Android）+ `PanCloudAccountRepository` 依賴改 `SettingsStore` | shared 編譯通過 |
| D1 | `panCloudPassword` 設置 + 黑名單 + login/register 存密碼 | 單測：login 後密碼已存 |
| D2 | 自動重登（refresh 401 → 密碼 login → 新 token；login 401 → Expired） | 單測：過期自動重登成功 / 密碼失效標記 Expired |
| D3 | 裝配（Android 加密 store）+ 測試收尾 | Android 編譯 + 單測通過 |

## 4. 附：關鍵檔案

- `shared/src/androidMain/.../store/settings/EncryptedSettingsStore.kt`（新增）
- `shared/src/androidMain/.../store/AndroidEncryptedPreferences.kt`（現有，`encryptedSharedPreferences`）
- `shared/.../repository/pancloud/PanCloudAccountRepository.kt`
- `shared/.../repository/settings/AppSettingsRepository.kt`
- `shared/.../repository/backup/BackupRepositoryImpl.kt`（`shouldSkipSetting`）
