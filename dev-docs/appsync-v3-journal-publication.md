# Canonical journal 發布準備

`AppSyncCanonicalJournalPreparation` 從 installation、已套用的 canonical checkpoint、本機來源與既有 v3 journal 建立一次發布所需的文件、封套、canonical 摘要及來源 ID 集合。此步驟不寫 DB、不送出網路請求、不標記 acknowledged，也不開啟 writer rollout。

準備器驗證帳號、device/epoch/writer nonce、來源序號是否已配置、既有文件 metadata 與重新編碼內容是否一致。相同操作序號只接受相同 canonical 內容；共享 proof ID 只接受相同證據。所有既有操作保留，新增來源排序後必須連續。不存在前次 journal 時必須從 1 開始；已壓縮成 metadata-only 的既有 journal 可從既有 published-through 的下一個序號追加。

舊來源若正規化成 Excluded／NoOp 或無法匯入，準備器明確拒絕，不能把該序號悄悄丟掉而發布有洞的 stream。此路徑尚需與 legacy 遷移／checkpoint 發布政策整合。現在也不執行 operation retention 或清理。

published-through 必須與 canonical 本機 coverage 的自身 replica 一致，observed 不可倒退。checkpoint acknowledgement 需要 index-bound 證據、摘要重新驗證與本機 coverage 支配；保留既有 acknowledgement 並拒絕 ID 衝突。最終經正式 document codec 編碼及重新讀取，回傳可供遠端 readback 比對的 canonical SHA。

尚待完成：持久化 reader-cohort gate、native v3 遠端 compare/write/readback、分段、模糊寫入重試、ack transaction、sanitized v2 fallback 及完整 engine 接線。此類別存在不表示已啟用 v3 writer。

補充邊界：若既有文件沒有明確 published-through，沿用已發布文件的自身 observed／retained range 下限。已被 metadata-only／裁切前綴取代的序號不能再從待送來源插回；本機 nextSequence 若落後於既有發布進度則拒絕。出站操作也經 canonical reducer 做語意驗證，批次刪除 proof 在 operation block 只保存一次。測試涵蓋追加與重放、metadata 竄改、writer／operation／proof 衝突、缺號、coverage、ack 授權、預算與 metadata-only journal。

`AppSyncCanonicalJournalPublisher` 現提供 inline compare／write／readback。預設 canWrite 回傳 false；未來 caller 必須注入新鮮的持久化 rollout/cohort 判定、已解析的 class 與持久化 attempt／discovery 選擇。目前尚未接到 production service。寫入前及 preflight 後各檢查一次 gate，避免等待讀取期間 rollback 卻仍送出。

更新既有目標時，重新 GET 核對 blog ID、完整標題、account、replica、writer nonce 與 expected canonical fingerprint；若上一輪模糊寫入已留下相同文件，直接回報回讀確認，不再 POST。任何 submit 成功回應都不足以回報 Verified，必須再次 GET 並核對完整 decoded document 與 metadata。已知目標更新逾時可透過相同回讀完成確認；create 回應沒有唯一候選時回傳 Unknown 及候選 IDs，不自行挑選或重建另一篇。

發布器沒有改寫 index、outbox、ack lifecycle 或清理資料；不會把 root 標題當成可原地更新的 inline journal。超過 inline 預算回傳 StoragePressure，分段路徑待接線。Fake provider 回歸涵蓋 gate、模糊 acknowledgement、假成功、候選歧義、更新逾時回讀、相同 body 重試、摘要衝突與 gate 在 preflight 期間關閉。這不構成完整 durable publish／retry 或 cohort gate 驗收。

回讀過程的 NotLoggedIn／FormExpired 會直接回報 FormExpired，包括 preflight 與 submit 後 reader；不將明確的登入失效混入一般未知重試。重放準備時也改用已建立的 sequence map 查找既有序號，避免每個重放來源再掃描完整 journal。

既有 best-effort index 發布路徑現在排除 `candidate:` journal 與 `checkpoint-candidate:` checkpoint 實體快取別名。這些列仍保留供 discovery／衝突判定使用，不能作為 replica key 或 checkpoint ID 發布。回歸測試同時涵蓋一般 checkpoint 與 checkpoint root，並確認發布後實體證據仍在本機。

Production engine 現在把完整 cloud planner 通過的 indexed canonical checkpoint、native journal 操作及 legacy 操作交給交易式 activator。套用時重讀本機 outbox，保留尚未發布的編輯，不把本機 overlay 當成遠端 coverage，也不提前 acknowledged。此階段成功仍明確呈現「已套用 canonical 資料，v3 發布尚未啟用」，不宣告 Converged；既有 checkpoint／retirement 維護只在 Converged 執行。已有 canonical head 卻只讀到空白／legacy cloud 時，也不進入舊版 reduction、compaction 或空雲端 force push。

同步前的 snapshot 安全稽核使用 canonical typed 值比較，不以已移除的 legacy provenance 判斷資料缺漏。只補存在於本機的 live rows，不從缺少本機列推導刪除；修復操作與 canonical head 在同一交易中保存。Essential 值無法正規化時，保留資料並回報稽核失敗。

Migration 46 在 canonical head 增加外部設定待重整標記。完整套用設為待重整，local command 保存 head 時保留標記。偏好設定重整失敗或程序中斷後，snapshot 稽核前先從最新 head 重建設定 mirror 並重試；成功才清除標記，避免把未套用的舊偏好值寫成新操作。重整已完成時不重放舊 mirror，以保留後續使用者編輯。
