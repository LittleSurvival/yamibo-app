# Canonical journal 發布準備

`AppSyncCanonicalJournalPreparation` 從 installation、已套用的 canonical checkpoint、本機來源與既有 v3 journal 建立一次發布所需的文件、封套、canonical 摘要及來源 ID 集合。此步驟不寫 DB、不送出網路請求、不標記 acknowledged，也不開啟 writer rollout。

準備器驗證帳號、device/epoch/writer nonce、來源序號是否已配置、既有文件 metadata 與重新編碼內容是否一致。相同操作序號只接受相同 canonical 內容；共享 proof ID 只接受相同證據。所有既有操作保留，新增來源排序後必須連續。不存在前次 journal 時必須從 1 開始；已壓縮成 metadata-only 的既有 journal 可從既有 published-through 的下一個序號追加。

舊來源若正規化成 Excluded／NoOp 或無法匯入，準備器明確拒絕，不能把該序號悄悄丟掉而發布有洞的 stream。此路徑尚需與 legacy 遷移／checkpoint 發布政策整合。現在也不執行 operation retention 或清理。

published-through 必須與 canonical 本機 coverage 的自身 replica 一致，observed 不可倒退。checkpoint acknowledgement 需要 index-bound 證據、摘要重新驗證與本機 coverage 支配；保留既有 acknowledgement 並拒絕 ID 衝突。最終經正式 document codec 編碼及重新讀取，回傳可供遠端 readback 比對的 canonical SHA。

尚待完成：持久化 reader-cohort gate、native v3 遠端 compare/write/readback、分段、模糊寫入重試、ack transaction、sanitized v2 fallback 及完整 engine 接線。此類別存在不表示已啟用 v3 writer。

補充邊界：若既有文件沒有明確 published-through，沿用已發布文件的自身 observed／retained range 下限。已被 metadata-only／裁切前綴取代的序號不能再從待送來源插回；本機 nextSequence 若落後於既有發布進度則拒絕。出站操作也經 canonical reducer 做語意驗證，批次刪除 proof 在 operation block 只保存一次。測試涵蓋追加與重放、metadata 竄改、writer／operation／proof 衝突、缺號、coverage、ack 授權、預算與 metadata-only journal。
