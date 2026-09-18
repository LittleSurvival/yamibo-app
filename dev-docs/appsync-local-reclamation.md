# Canonical 本機 payload 清理

`AppSyncCanonicalLocalPruner` 由 canonical activator 在設定重整成功後呼叫。每次最多處理 128 筆 outbox，僅限 ACKNOWLEDGED、COMPACTED 或 SUPERSEDED_BY_RECOVERY，且原 replica／sequence 必須在本次實際讀回並通過 index binding 的 canonical checkpoint coverage 內。Pending、published-unverified、未覆蓋及其他帳號資料不刪除；仍有未完成 recovery 的帳號全部保護，因為 activation 可能還需要精確比對來源。

清理前重新驗證 checkpoint canonical digest、已保存 checkpoint ID／Blog ID／fingerprint／VERIFIED 狀態、installation 帳號、canonical head 完整性／coverage 與 settingsReconciliationPending。只存在本機 overlay 的 coverage 不能授權刪除。沒有可讀的 index-verified checkpoint、projection 尚未安裝或設定失敗，都保留來源。清理錯誤回報可重試的 NeedsAttention，不把已完成的 projection 套用宣稱為回滾。

刪除與統計在同一 SQLite 交易中完成；交易中斷兩者一起回滾。成功刪除後再呼叫會跳過已不存在的列，因此不重複計數。`removedLocalRows`、`removedLocalPayloadBytes` 由 activation result 回傳。bytes 使用 SQLite `length(CAST(... AS BLOB))` 計算 fieldsJson 與 causalContextJson 的 UTF-8 位元組，不載入主體到 Kotlin，也不把此數值當作資料庫檔案已縮小。

Migration 53（schema version 54）新增裝置端 `AppSyncLocalPruneAudit`，只保存 account binding、checkpoint fingerprint、累計列數／payload bytes 及建立／更新時間，不保存 entity ID、operation ID 或 payload。此 domain 排除於 AppSync 與可攜備份。下一次符合證據要求的清理會移除已建立 30 天的 audit；持續追加計數不延長舊 audit 的保存期限。同步入口現在也會清除已到期 audit；未執行同步時的獨立到期排程仍待接線。

Native checkpoint 與已有後續 checkpoint 完整覆蓋的 native journal，現依下述完成收據流程移除凍結 payload 與 segment intents；legacy 的相關主體與 applied receipts 仍保留，不執行 SQLite VACUUM，也不刪除雲端文件。一般 reader 清理的全部批次排程、UI 累計統計、獨立到期維護及實體頁面回收仍待完成。不能將這個有上限的邏輯主體清理，視為完整 storage-reclamation 規格或整體 recovery 已完成。

測試涵蓋合成大型 cache 主體在 canonical rebuild 後刪除、仍 pending 的後續編輯保留、checkpoint／設定證據不足、active recovery 保護、每批上限、交易回滾與重新執行、損壞的 canonical head、錯誤 checkpoint、audit 到期與 migration 初始狀態。

2026-09-18：752 項 shared 與 15 項 CloudSyncUiState 回歸全數通過，零失敗、錯誤或略過；strict OpenSpec validation 通過。

## Native checkpoint 的 Cleaning 接續

Canonical checkpoint recovery 在 projection 與設定都啟用後，先保存 Cleaning 階段，不立即完成 session。專用清理入口再次檢查 native frozen payload、root／index intent 與提交證據，只對同一 Cleaning session 開放清理；一般清理入口仍保護未完成 recovery。這亦避免雲端與 frozen checkpoint 使用相同 canonical identity、不同實體 Blog ID 時，一般清理誤判已保存證據。

每批 SQL 刪除、audit 與最後的 Completed 轉換在同一交易內。尚有符合條件的主體時回傳 cleanupPending，coordinator 在批次間 yield，再繼續下一批，不把正常批次切換當作網路失敗或等待重試。取消／程序中斷後保持 Cleaning，下次直接檢查 frozen 證據並接續刪除，不重新套用 projection／偏好設定，也不重新發布 root/index。批次間新增的 pending 操作及已更新的本機設定保留。

Completed 在此表示這條 native checkpoint 路徑已完成 covered-outbox 與凍結 recovery payload 清理；legacy／journal 清理、SQLite 空間與雲端退休工作仍須補齊，才滿足完整規格的完成判定。

成功批次在同一交易內清除先前 retry count／identity／deadline；取消於批次間傳播，不額外計為失敗。2026-09-19：新增 260 筆分批重啟、並行後續編輯及 coordinator 取消後完整接續驗證；754 項 shared 與 15 項 CloudSyncUiState 測試全部通過。


## 移除凍結主體與完成收據

Migration 54（schema version 55）新增 `AppSyncNativeCompletion`。最後一批 checkpoint 清理再次核對凍結 checkpoint／index 證據、canonical head 的摘要與 coverage、已重整設定，並確認沒有仍符合刪除條件的 outbox。Native checkpoint 若含 shadow operation 會拒絕完成，不把未驗證內容當作可丟棄副本。

完成收據保存 session／account／generation／checkpoint 身分、checkpoint／root／index 摘要與實體 Blog ID、移除的 payload bytes、segment rows 及完成時間，不保存封套、index body 或操作內容。收據寫入、凍結封套與 index body 刪除、segment intent 刪除、work ledger 刪除及 Completed 轉換都在同一交易中；中斷會完整回滾。最後一批的 removedLocalPayloadBytes 額外包含凍結封套與 index body 的 UTF-8 bytes，outbox 列數仍單獨計算。

已清理的 native session 透過收據辨識 transport 3，重播直接保留完成結果；`pinPayload` 拒絕重建已丟棄主體。一般同步入口執行到期維護，每次最多移除 128 筆已完成且主體早已清掉的 30 天收據及 session，並清除到期的本機清理統計。新 recovery 取代舊 completed session 時也一併移除收據。維護不刪除當前 canonical head、普通本機資料、pending 操作或雲端文件。

尚未執行同步的裝置不會準時觸發到期維護；獨立排程與 legacy 主體的相同生命週期仍待完成。這些剩餘事項不因 native checkpoint 收據流程通過測試而視為已驗收。

2026-09-19：完成主體移除、完成重播、交易回滾、到期邊界與 migration 回歸通過；758 項 shared 與 15 項 CloudSyncUiState 全數通過，零失敗、錯誤或略過。

## 完整 checkpoint 覆蓋後清理 native journal 凍結副本

一般 canonical 清理會檢查帳號目前的 completed native journal session。成功發布本身不授權此清理；後續 index-verified checkpoint 必須涵蓋 journal 的完整 published-through、observed、acknowledgement coverage 與操作因果相依。只覆蓋自身 writer、尚未覆蓋其他 replica 相依時仍保留凍結副本。

刪除前重驗 checkpoint 身分與保存狀態、canonical head／設定，以及原 journal 的凍結封套、root 與 index intent/readback 證據。來源若仍為 pending、存在 shadow operation 或證據不一致，拒絕清理。原 index 的重驗在 Completed 階段只比對已保存證據，不重新發布或倒退階段。

移除封套、index body、segment intents 與 work ledger，連同完成收據寫入與同批 covered-outbox 刪除都在同一 SQL 交易內。收據沿用原 session 完成時間，checkpoint 欄位記錄授權清理的替代 checkpoint，root/index 欄位記錄原 journal 的已驗證發布；無須新增 schema。已清理 session 可重播辨識 transport 3，收據和 session 於既有 30 天到期流程移除。移除位元組包含凍結封套與 index body，仍屬邏輯量測，不代表實體資料庫縮小。

## 新 session 取代舊 session 時保存未覆蓋 journal

Migration 55（schema version 56）新增裝置端 `AppSyncRetainedJournal`。原 recovery session 每個帳號只有一個位置；過去開始下一個 session 時會直接刪除 completed session 的 payload。現在尚未取得清理收據的 completed native journal，會先重驗 frozen index readback，再於移除舊 session 的同一交易保存 journal 封套、index intent、摘要、root/index Blog ID 與完成時間。此資料表不以舊 session 為外鍵，因此取代 session 不會連帶刪除尚未覆蓋的主體。

若同 session ID 已有保留資料，所有保存的身分、主體與證據都必須一致；不接受覆寫。交易中斷會同時還原舊 session 與保留副本變更。證據損壞時拒絕取代，保留原 session 與 payload。已經取得完成收據、主體已合法清理的 session 不會重建副本。

此資料排除於 AppSync 與可攜備份，也不參與單靠時間的 30 天收據到期刪除。只有以下完整 checkpoint coverage 驗證與有界清理能移除保留主體，不能僅因保存時間較久就丟棄副本。

Migration 56（schema version 57）新增每列最後檢查的 checkpoint fingerprint，並在既有 payload-free prune audit 加入 removedRetainedJournals。一般 canonical 清理與 native checkpoint Cleaning 每批最多檢查 8 份保留 journal，逐份載入及解碼；同一 checkpoint 下，未覆蓋列保存檢查進度，後續批次繼續後面的列。新 checkpoint fingerprint 會使未刪除列重新符合檢查條件。

清理沿用外層交易的 checkpoint digest／indexed evidence、installation account、canonical head 及設定重整檢查；每份保留資料另驗 journal envelope fingerprint、index SHA／fingerprint、account／replica 與 root reference，要求 checkpoint 覆蓋全部 published、observed、acknowledgement 與 causal dependencies。資料刪除、檢查進度和累計位元組／保留 journal 筆數同一交易提交；例外全部回滾，再次呼叫不重複計數。hasMore 包含尚未檢查的保留資料，native Cleaning 會接續批次；一般 reader 的獨立背景批次排程仍待補齊。audit 沿用建立日起 30 天到期，不保存 journal/index 主體。
