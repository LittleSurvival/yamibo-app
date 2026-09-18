# Canonical checkpoint 本機狀態

更新日期：2026-09-18。Migration 45 新增 `AppSyncCanonicalState`，單列保存 account binding、checkpoint ID、raw canonical checkpoint BLOB 與 SHA-256。BLOB 沿用 checkpoint 的去重 operation table；不額外產生 Base64、壓縮層或每欄重複操作。這是 checkpoint 替換用的持久狀態，尚非每次本機編輯的增量儲存格式。

`SqlDelightCanonicalCheckpointState.read` 要求帳號吻合、雜湊吻合及完整 canonical decode。損毀不會轉成空狀態；上層需處理錯誤並保留現存資料。

`replace` 在一個 SQLite transaction 內檢查安裝帳號、既存狀態、checkpoint ID 與 coverage，保留本機封面、替換 syncable materialized rows，再寫入 canonical state。相同 ID／相同 canonical bytes 是 no-op，不重套已套用 checkpoint；相同 ID／不同 bytes、跨帳號、coverage 倒退皆拒絕。任一步失敗會還原舊狀態與舊資料列。外部 SettingsStore 的 reconciliation 必須由 engine 在最外層 transaction commit 後執行。

此類別不構成遠端 index 驗證、acknowledgement 或清理授權。正式 engine 接入前，仍須完成本機 pending operations 合併、canonical reducer、遠端 activation evidence、帳號重設與持久化格式的切換流程。舊 journal／resolved evidence 不由此類別刪除，v3 writer 也尚未啟用。

驗證範圍包括 migration 不動既存資料、實體 SQLite 檔案關閉再開啟、相同 checkpoint 重試、後段失敗的 transaction rollback、ID collision、coverage regression、帳號錯配與磁碟內容損毀拒絕。這些不替代裝置端 process death、iOS 或完整 engine acceptance。
