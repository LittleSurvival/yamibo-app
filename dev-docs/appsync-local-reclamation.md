# Canonical 本機 payload 清理

`AppSyncCanonicalLocalPruner` 由 canonical activator 在設定重整成功後呼叫。每次最多處理 128 筆 outbox，僅限 ACKNOWLEDGED、COMPACTED 或 SUPERSEDED_BY_RECOVERY，且原 replica／sequence 必須在本次實際讀回並通過 index binding 的 canonical checkpoint coverage 內。Pending、published-unverified、未覆蓋及其他帳號資料不刪除；仍有未完成 recovery 的帳號全部保護，因為 activation 可能還需要精確比對來源。

清理前重新驗證 checkpoint canonical digest、已保存 checkpoint ID／Blog ID／fingerprint／VERIFIED 狀態、installation 帳號、canonical head 完整性／coverage 與 settingsReconciliationPending。只存在本機 overlay 的 coverage 不能授權刪除。沒有可讀的 index-verified checkpoint、projection 尚未安裝或設定失敗，都保留來源。清理錯誤回報可重試的 NeedsAttention，不把已完成的 projection 套用宣稱為回滾。

刪除與統計在同一 SQLite 交易中完成；交易中斷兩者一起回滾。成功刪除後再呼叫會跳過已不存在的列，因此不重複計數。`removedLocalRows`、`removedLocalPayloadBytes` 由 activation result 回傳。bytes 使用 SQLite `length(CAST(... AS BLOB))` 計算 fieldsJson 與 causalContextJson 的 UTF-8 位元組，不載入主體到 Kotlin，也不把此數值當作資料庫檔案已縮小。

Migration 53（schema version 54）新增裝置端 `AppSyncLocalPruneAudit`，只保存 account binding、checkpoint fingerprint、累計列數／payload bytes 及建立／更新時間，不保存 entity ID、operation ID 或 payload。此 domain 排除於 AppSync 與可攜備份。下一次符合證據要求的清理會移除已建立 30 天的 audit；持續追加計數不延長舊 audit 的保存期限。獨立的到期排程仍待接線。

此階段不刪除 recovery payload／shadow、segment intents 或 applied receipts，不執行 SQLite VACUUM，也不刪除雲端文件。Native checkpoint 的批次接續如下；journal recovery、一般 reader 清理的全部批次排程、UI 累計統計、30 天到期維護及實體頁面回收仍待完成。不能將這個有上限的邏輯主體清理，視為完整 storage-reclamation 規格或整體 recovery 已完成。

測試涵蓋合成大型 cache 主體在 canonical rebuild 後刪除、仍 pending 的後續編輯保留、checkpoint／設定證據不足、active recovery 保護、每批上限、交易回滾與重新執行、損壞的 canonical head、錯誤 checkpoint、audit 到期與 migration 初始狀態。

2026-09-18：752 項 shared 與 15 項 CloudSyncUiState 回歸全數通過，零失敗、錯誤或略過；strict OpenSpec validation 通過。

## Native checkpoint 的 Cleaning 接續

Canonical checkpoint recovery 在 projection 與設定都啟用後，先保存 Cleaning 階段，不立即完成 session。專用清理入口再次檢查 native frozen payload、root／index intent 與提交證據，只對同一 Cleaning session 開放清理；一般清理入口仍保護未完成 recovery。這亦避免雲端與 frozen checkpoint 使用相同 canonical identity、不同實體 Blog ID 時，一般清理誤判已保存證據。

每批 SQL 刪除、audit 與最後的 Completed 轉換在同一交易內。尚有符合條件的主體時回傳 cleanupPending，coordinator 在批次間 yield，再繼續下一批，不把正常批次切換當作網路失敗或等待重試。取消／程序中斷後保持 Cleaning，下次直接檢查 frozen 證據並接續刪除，不重新套用 projection／偏好設定，也不重新發布 root/index。批次間新增的 pending 操作及已更新的本機設定保留。

Completed 在此僅表示這條 native checkpoint 路徑已完成目前支援的 covered-outbox 清理；其他 recovery payload、SQLite 空間與雲端退休工作仍須補齊，才滿足完整規格的完成判定。

成功批次在同一交易內清除先前 retry count／identity／deadline；取消於批次間傳播，不額外計為失敗。2026-09-19：新增 260 筆分批重啟、並行後續編輯及 coordinator 取消後完整接續驗證；754 項 shared 與 15 項 CloudSyncUiState 測試全部通過。
