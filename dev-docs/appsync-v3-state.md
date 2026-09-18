# Canonical checkpoint 本機狀態

更新日期：2026-09-18。Migration 45 新增 `AppSyncCanonicalState`，單列保存 account binding、checkpoint ID、raw canonical checkpoint BLOB 與 SHA-256。BLOB 沿用 checkpoint 的去重 operation table；不額外產生 Base64、壓縮層或每欄重複操作。

`SqlDelightCanonicalCheckpointState.read` 要求帳號吻合、雜湊吻合及完整 canonical decode。損毀不會轉成空狀態；上層需處理錯誤並保留現存資料。

`replace` 在一個 SQLite transaction 內檢查安裝帳號、既存狀態、checkpoint ID 與 coverage，保留本機封面、替換 syncable materialized rows，再寫入 canonical state。相同 ID／相同 canonical bytes 是 no-op，不重套已套用 checkpoint；相同 ID／不同 bytes、跨帳號、coverage 倒退皆拒絕。任一步失敗會還原舊狀態與舊資料列。外部 SettingsStore 的 reconciliation 必須由 engine 在最外層 transaction commit 後執行。

此類別不構成遠端 index 驗證、acknowledgement 或清理授權。正式 engine 接入前，仍須完成本機 pending operations 合併、canonical reducer、遠端 activation evidence、帳號重設與持久化格式的切換流程。舊 journal／resolved evidence 不由此類別刪除，v3 writer 也尚未啟用。

`recordLocalBatch` 供已完成本機資料修改的 command 在同一個 outbox transaction 內呼叫一次。它透過 canonical pending merge 更新 provenance 與連續 coverage，完全不重套 materialized rows、不 reconcile 外部設定，也不更新遠端 checkpoint 證據。相同來源重送不重寫；新 identity 從固定 placeholder 與內容計算，因此同一批來源一次或分次記錄會收斂至相同 bytes。

未啟用 canonical state 時回傳 `NotActivated`；匯入、序號或語意失敗回傳 `NeedsAttention`，保留上一份完整 canonical state。呼叫者必須保留原始 outbox 與本機修改，並持久化待處理狀態；不能將此結果當作成功。正式 recorder 現已接入：存在 canonical state 時，command 完成後呼叫此 API；NeedsAttention 會在同一筆交易將 installation 標為 Quarantined，保留來源與本機修改。尚未提供欄位層級錯誤 UI 或接通完整 canonical engine。

目前每個成功 command 仍重寫一份 canonical BLOB，不是 per-entity 寫入。避免重套整份本機資料與批次內重複寫入已完成；規格要求的裝置 write-amplification gate 與正規化本機儲存仍待驗證／改善。

驗證範圍包括 migration 不動既存資料、實體 SQLite 檔案關閉再開啟、相同 checkpoint 重試、後段失敗的 transaction rollback、ID collision、coverage regression、帳號錯配與磁碟內容損毀拒絕。這些不替代裝置端 process death、iOS 或完整 engine acceptance。

`AppSyncCanonicalMutationPreparation` 在每個 command 內比較 typed canonical 欄位，忽略 derived／cache 欄位造成的假變更，並用共享 reducer 處理同一 command 的先後修改。相同 portable patch 不配置 sequence；刪除後的 generation 也讀 canonical tombstone。準備階段的狀態只存在記憶體，正式 provenance 每個 command 寫一次，不更新 legacy resolved projection。

目前 outbox 仍保留 v2 contract 必填欄位及批次 proof，以維持尚未切換的來源／相容性流程，因此尚未宣稱完成 native canonical outbox 或最小 wire patch。全量 BLOB 比較、損毀狀態的使用者復原、帳號重設、完整 engine 啟用與詳細待處理提示仍需完成。

Canonical comparison snapshots are loaded lazily inside the outbox transaction. Import or
aggregate preparation failures retain source fields for the durable merge to report; oversized
essential note content is never truncated. Recorder regressions cover typed no-op sequence
preservation, repeated-target batches, invalid source retention, oversized notes, callback
rollback, generation after deletion, and absence of legacy provenance writes. This does not
complete normalized storage, all producer acceptance, native writing or field-specific UI.

舊版完整狀態替換的生命週期已接線：`completeBootstrap` 與 `replaceWithVerifiedCloudState` 在 materialization 成功後，於同一筆 transaction 移除被取代的 canonical head。成功換帳號 bootstrap 後，recorder 因沒有 canonical head 而讀取新 legacy projection，不會再因舊帳號 binding 拒絕操作。單純 `prepareForCloudReset`、writer epoch rotation 或失敗的 bootstrap 不清除 canonical state；外層 transaction 失敗時會一起還原 canonical bytes、outbox lifecycle、installation 及 materialized rows。

此清除只適用於上述既有完整 legacy replacement API，不放在一般 canonical activation、遠端 receipt 記錄或本機修改路徑。Canonical 到 canonical 的跨帳號 bootstrap、完整 v3 reset／force-pull UI 與 engine 路由仍需實作；本次不擴大其完成宣稱。
