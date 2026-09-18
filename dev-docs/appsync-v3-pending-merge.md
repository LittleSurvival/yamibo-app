# Pending operation 與 canonical checkpoint 合併

更新日期：2026-09-18。`AppSyncCanonicalPendingMerge.prepare` 是純資料準備步驟，不修改 outbox、不 ack、不發布。輸入為已驗證的 current canonical checkpoint 與 legacy source operations；輸出完整候選 checkpoint，或不含部分狀態的 `NeedsAttention`。

可選 `canonicalBlock` 與 legacy sources 先合併為同一序號集合，再做連續 coverage 檢查，因此同一 writer 的交錯 v2/v3 序號不會因分開處理而誤報缺號。同 ID 的兩種表示必須正規化成相同 canonical 操作與 proof；任何衝突都拒絕。Canonical block 先驗證 wire/schema，不重新套用 legacy 欄位必填規則。供舊 receipt／bulk-delete API 使用的暫時 operation view 不得拿去發布 v2。

先檢查帳號、來源筆數、相同 operation ID 的內容一致性與 current codec。對 current 已保留的勝出操作，也核對重送來源的 canonical 內容。已被可信 current coverage 覆蓋的其他歷史操作不重放。

每個 replica 的新序號必須從既存 coverage + 1 連續排列；缺號即停止。明確 `Excluded`／`NoOp` 的來源仍計入連續覆蓋，但不建立空 entity。任何匯入失敗、proof 衝突、bulk-delete 隔離、reducer 隔離或候選 codec 失敗，都不傳回部分候選。結果保留 represented source IDs 供未來驗證提交後使用，不能將 prepare 成功當成 ack 授權。

刪除檢查沿用既有 threshold。空資料庫以本次刪除數作分母，避免筆數為零而關閉比例檢查。已驗證 checkpoint 可能涵蓋授權批次的一部分，因此 canonical 路徑允許剩餘刪除數小於原始 proof count；匯入與合併 codec 仍要求 proof 一致、未逾期且引用不超過原數。原始 count 不改寫。舊版 `BulkDeleteGuard.evaluate` 保持精確批次數比對。已覆蓋的勝出操作重送時，proof metadata 也必須保持一致。

遠端載入已有 index 綁定證據，activation adapter 也能在交易內合併操作；正式 engine 的路由、reader rollout 與持久排程仍待接線，不得據此啟用 writer 或刪除來源資料。

Canonical 合併中的多個已授權刪除 command 會先以整個 domain 判斷 threshold，再逐個 authorization ID 核對各自 proof。分組不會繞過 threshold；缺少授權的子集合仍拒絕。原始 count 不縮小，legacy 精確批次路徑保持原本行為。啟用 adapter 已使用此混合合併，但完整 engine／journal 集合選擇仍待接線。
