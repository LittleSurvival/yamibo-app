# Canonical 雲端資料選擇與驗證

更新日期：2026-09-18。

`AppSyncCanonicalCloudPlanner` 在任何啟用、ack 或清理前驗證載入結果。它要求同一份 index 的已驗證 checkpoint，重新核對 canonical 內容摘要，並拒絕未解決的讀取錯誤、帳號錯配、artifact 身份衝突、索引中缺少的 journal、operation／proof 衝突及 writer nonce 衝突。資料量上限為 1,024 個載入文件、100,000 個操作，以及本階段合計 64 MiB 的 canonical 候選／文件宣告內容；這些上限不等於裝置效能驗收。

選擇依據是逐 replica 的 coverage，不計算 sequence 總和。每份候選 checkpoint 都與後續 native／legacy journal 合併，再要求結果覆蓋所有已載入 checkpoint 的 coverage 及各 journal 的 published-through／自身 observed／retained range。互不包含的 checkpoint 可以由後續 journal 補齊；不能補齊時不會挑一份看似最大的資料冒充完整狀態。

若多份候選都能產生完整 coverage，會比較排除 checkpoint ID／建立時間後的 canonical 內容，要求收斂一致，再按建立時間與 ID 決定基底。相同 coverage 卻不同內容的候選不會因時間較新而直接覆蓋。缺號、缺失的 published coverage 或勝出操作的身份衝突都有獨立失敗原因。

成功計畫只攜帶基底、canonical 操作區塊與 legacy 操作，不寫資料。Activation 必須在自己的 SQLite transaction 重新讀取 pending 操作並合併。Planner 不把本機 pending 當成雲端已發布證據，也不建立清理權限。

正式 `OperationSyncEngine` 已呼叫此驗證器。自己的 device／epoch 出現不同 writer nonce 時，沿用 restored-installation 處理，旋轉 epoch 並要求重新 bootstrap；其他錯誤保留原因並暫停。有效計畫目前仍等待 canonical activation 與本機 recorder 完整接線，沒有宣告 Converged 或開啟 v3 writer。只有 journal、沒有 indexed canonical checkpoint 的 bootstrap 路徑仍待實作。
