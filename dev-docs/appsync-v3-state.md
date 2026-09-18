# Canonical checkpoint 本機狀態

更新日期：2026-09-18。Migration 45 新增 `AppSyncCanonicalState`，單列保存 account binding、checkpoint ID、raw canonical checkpoint BLOB 與 SHA-256。BLOB 沿用 checkpoint 的去重 operation table；不額外產生 Base64、壓縮層或每欄重複操作。

`SqlDelightCanonicalCheckpointState.read` 要求帳號吻合、雜湊吻合及完整 canonical decode。損毀不會轉成空狀態；上層需處理錯誤並保留現存資料。

`replace` 在一個 SQLite transaction 內檢查安裝帳號、既存狀態、checkpoint ID 與 coverage，保留本機封面、替換 syncable materialized rows，再寫入 canonical state。相同 ID／相同 canonical bytes 是 no-op，不重套已套用 checkpoint；相同 ID／不同 bytes、跨帳號、coverage 倒退皆拒絕。任一步失敗會還原舊狀態與舊資料列。外部 SettingsStore 的 reconciliation 必須由 engine 在最外層 transaction commit 後執行。

此類別不構成遠端 index 驗證、acknowledgement 或清理授權。正式 engine 接入前，仍須完成本機 pending operations 合併、canonical reducer、遠端 activation evidence、帳號重設與持久化格式的切換流程。舊 journal／resolved evidence 不由此類別刪除，v3 writer 也尚未啟用。

`recordLocalBatch` 供已完成本機資料修改的 command 在同一個 outbox transaction 內呼叫一次。它透過 canonical pending merge 更新 provenance 與連續 coverage，完全不重套 materialized rows、不 reconcile 外部設定，也不更新遠端 checkpoint 證據。相同來源重送不重寫；新 identity 從固定 placeholder 與內容計算，因此同一批來源一次或分次記錄會收斂至相同 bytes。

未啟用 canonical state 時回傳 `NotActivated`；匯入、序號或語意失敗回傳 `NeedsAttention`，保留上一份完整 canonical state。呼叫者必須保留原始 outbox 與本機修改，並持久化待處理狀態；不能將此結果當作成功。正式 recorder 的錯誤處理與路由尚待接入，不能僅因這個 API 存在就啟用 canonical runtime。

目前每個成功 command 仍重寫一份 canonical BLOB，不是 per-entity 寫入。避免重套整份本機資料與批次內重複寫入已完成；規格要求的裝置 write-amplification gate 與正規化本機儲存仍待驗證／改善。

驗證範圍包括 migration 不動既存資料、實體 SQLite 檔案關閉再開啟、相同 checkpoint 重試、後段失敗的 transaction rollback、ID collision、coverage regression、帳號錯配與磁碟內容損毀拒絕。這些不替代裝置端 process death、iOS 或完整 engine acceptance。
