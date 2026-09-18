# v3 原生分段傳輸

`AppSyncV3SegmentCodec` 先以正式 document reader 驗證 v3 journal／checkpoint，再切分既有封套文字。封套內 canonical bytes 已經 gzip 一次並 Base64 一次；分段只加入 JSON framing，不再壓縮或 Base64 包裝。

分段與 root 分別使用 `YAMIBO_APP_SYNC_SEGMENT:v3`、`YAMIBO_APP_SYNC_ROOT:v3` 四行 frame。第二行為 canonical JSON 的 SHA-256，第三行為 JSON payload。解碼要求完整 frame、固定欄位、摘要相符及重新編碼後逐字相等；不接受前後插入文字或重複 framing。

分段包含 account、kind、identity、generation、index、count、chunk 與下一段的 Blog ID／SHA-256。generation 為完整原始封套的 SHA-256。tail 的 next 為 null；其餘段需要下一段實體文件的參照，因此發布次序由尾到頭。`reference` 僅驗證傳入文件格式，不構成遠端提交或回讀成功的證明。

root 保存原始 v3 metadata、generation、封套 SHA-256／字元數、段數與 head 參照。每個下一段摘要涵蓋下一段的完整文件及後續鏈結；root 因此承諾完整有序鏈。重組逐段核對帳號、kind、identity、generation、index／count、完整文件摘要，拒絕遺失、循環、提前或延後終止，並在累積內容前檢查宣告長度。完成後重新驗證封套、canonical document 及 root metadata 一致性。

規劃使用二分搜尋量測最終文件，預留最大 Blog ID、摘要與段數所需空間，並在開始前檢查 root 的最壞大小。每段同時符合設定的字元與 UTF-8 byte 預算；Unicode surrogate pair 不會被切開。預設上限為 4,096 段及 16 MiB + 4 KiB 的完整封套。無法驗證、超過語意／總量限制或 metadata 無法容納時，不回傳可發布計畫。

正式 `YamiboAppSyncJournalRemote` 已能辨識並讀取原生 v3 root，逐段 GET 核對 Blog ID 與完整分段標題，再提供既有 canonical cloud planner／index-bound checkpoint 驗證。root 使用既有 journal／checkpoint 主文件標題供 discovery 尋找；分段使用 v3 segment 標題。HTML reader 保留 v3 root／segment 的換行。缺段會回報可重試；損壞 root／chain 保留 canonical 讀取問題，不能被視為空雲端，也不能交給 legacy writer 原地覆寫。

目前完成規劃、codec、重組及 production reader，另提供預設停用的 durable 分段發布元件。正式服務的 attempt／reconciliation／index commit、reader capability 提升與 writer rollout 尚待接線；此文件不表示已啟用 v3 發布。測試涵蓋兩種文件、大型封套、確切預算邊界、Unicode、無效來源、段數與累積大小限制、遺失／竄改／順序／循環／跨帳號、真實 fake-provider reader 與 checkpoint index 證據。

Migration 48 為 frozen recovery payload 增加 transportVersion 與 native root intent fingerprint。既有資料預設 transport 2，維持舊分段流程。相同 session 不得在 2／3 之間切換；native 3 第一次保存前驗證完整 canonical 文件、帳號、identity，journal 另核對 session 的 device／epoch／writer nonce。native payload 不接受 LegacyShadow session。重試沿用第一次保存的封套，不執行新的 payload builder。

`pinNativeRootIntent` 只在 native payload 已保存、完整 segment chain 已確認且進入 PublishingRoot 時首次建立不可變 root 摘要。回傳 true 表示首次 intent；回傳 false 表示之前的 POST 可能已經發生，發布器必須先做 authoritative reconciliation。不同摘要不能覆蓋同一 intent；`markRootVerified` 也必須符合這份 intent 才能推進 CommittingIndex。

`AppSyncV3SegmentPublisher` 在 session lease 內使用上述 store，先凍結封套、核對所有已保存的分段計畫，再由尾到頭保存 intent、POST 及逐段 GET。每次 GET 都核對實體 ID、完整標題及 reader 解出的完整內容；重新啟動也重新讀取已確認分段。舊進度的回讀不重設後續失敗的重試次數。完整鏈確認後才保存 root intent、POST／GET root，回傳 `ReadyToCommitIndex`；此結果不代表 index 已提交，也不確認 outbox、不新增 checkpoint coverage、不刪除任何文件。

尚未確認的舊 intent 必須先呼叫注入的 authoritative discovery。找到原文件就回讀；只有完整掃描確認不存在才允許新 POST。未知結果、掃描失敗或歧義不能推定不存在。單次呼叫內，即使 POST 逾時後掃描回報不存在，也只回報可重試，不立即重送。驗證失敗、登入失效與 rollout gate 關閉分別保留可辨識結果；每次 POST 前重新檢查 gate。

呼叫端仍須提供帳號/session lease、正式完整掃描及新鮮 reader-cohort gate。傳輸預算尚未另存於 session；不同設定造成的計畫變更會在任何 POST 前拒絕，而非改寫既有 generation。正式啟用前仍需完成固定傳輸設定、index 回讀提交、worker 重試及完整故障驗收。五項 SQLite／fake-provider 回歸涵蓋 frozen bytes、重啟、分段與 root 遺失回應、完整不存在證據、gate／登入／回讀錯誤、計畫變更及 pending 保留。

`AppSyncV3ArtifactReconciler` 提供基於 provider 的完整分類掃描，限制最多 100 頁、10,000 個實體 ID，使用正式同步分類名稱核對每頁分類。分頁必須連續、current／total 一致，不接受重複 ID、無法解析的 next URL 或超限；只有全部頁面完成後才讀取所有同名候選。逐一核對實體 ID、標題與 reader 文字 SHA-256；唯一精確符合回傳 Found、多份符合回傳 Conflict，完整掃描無符合才回傳 Absent。同名舊 generation 不算本次寫入成功。任何未完成掃描、候選遺失／無法讀取、分類或分頁異常均回傳 Unknown；登入中斷另回傳 FormExpired，取消則向呼叫端傳播。

這是沒有伺服器 snapshot token 的分頁讀取；無法保證掃描期間其他裝置不新增文件，呼叫端仍須持有本機帳號/session lease，並遵守 writer identity／cohort gate。元件不做遠端修改，也不以掃描結果授權清理。正式服務接線與 durable index commit 仍待完成。

分段標題含 generation，因此同名分段只要摘要不同即回報 Conflict，不能視為不存在後再建立。root 主文件標題允許不同 generation，完整讀取的舊 root 可略過。本次測試將發布器的預設 fixture discovery 換成此元件，確保 readback mismatch 仍保留原 intent 且不重送；另覆蓋多頁完整掃描、缺頁、重複頁、上限、登入中斷、候選遺失、重複符合、同名舊 root 與分類不符。

Index 的讀寫 codec 已共用參照衝突檢查：同一 journal replica、checkpoint identity 或 retirement replica 對應不同內容，以及同一類型的實體 Blog ID 對應不同身分，均拒絕；journal 與 checkpoint 不能共用一個 Blog ID。完全相同的重複項目仍相容，編碼時折疊；journal 的舊版 null fingerprint 仍保留。空身分、非正整數 ID 與空白 fingerprint 不可成為新 index 證據。這避免原先 distinctBy 在發布時靜默選第一筆衝突參照，也拒絕外層 checksum 正確的衝突輸入。此驗證不等於 durable index 提交已完成。

Migration 49 在 recovery payload 保存 verifiedIndexBlogId、verifiedIndexFingerprint、indexVerifiedAtEpochMillis，既有列均為 null，不臆造成功證據。舊 `markIndexCommitted` 拒絕 transport 3；專用 `markNativeIndexCommitted` 重新驗證凍結封套、帳號、root intent／已確認 root 與 index 內的 canonical 摘要。journal 另核對 session writer／replica，checkpoint 核對 identity。index 證據與 ActivatingLocal 階段在同一 SQLite 交易寫入；相同回讀證據重試不改寫第一次時間，不同 index 不能覆蓋既有成功證據。此 API 假設呼叫端已 GET 並核對 index 實體 ID／標題，由下述 native committer 提供；不會確認 outbox、建立可清理 coverage 或進行刪除。

舊版 `AppSyncSegmentIndexCommitter` 在任何 discovery／POST 前拒絕 native transport，避免先寫入舊摘要才於本機提交時失敗。SQLite／fake-provider 測試涵蓋 journal 與 checkpoint 的 canonical 參照、帳號／identity／root 不符、封套摘要誤用、外層交易回滾、重建 store 後重試、第一次時間保留、不同 index 拒絕、舊入口無遠端寫入，以及 pending／coverage 不變。

Migration 50 凍結 native index 的完整 body／SHA-256、更新目標 ID 與更新前正規化 index SHA-256。重試不可替換意圖；提交證據必須與完整凍結內容相符。`AppSyncV3IndexCommitter` 預設停用，使用已知同步分類與呼叫端 lease／gate，先呼叫分段發布器重新回讀 root／chain，再完整掃描 index 候選。多份 index、帳號不符或無法驗證的文件不能作為更新基礎；掃描／回讀中斷不等於不存在。

建立意圖時保留其他 journal、checkpoint 與 retirement 參照，只更新本次 identity。更新前再次完整掃描：遠端等於預期內容就直接確認；遠端仍等於原基礎才允許 POST；其他版本回報衝突。提交後不信任 acknowledgement／candidate ID，一律重新掃描並 GET 核對實體 ID、標題、帳號與完整正規化內容；逾時亦走相同確認。單次呼叫不重複 POST。成功只推進 ActivatingLocal，仍不確認來源或清理。

Provider 不支援 compare-and-swap，因此更新前檢查與 POST 間仍有跨裝置競爭窗口；此元件尚未接入正式服務，也未宣告並行 writer／worker／清理驗收完成。呼叫端仍須完成 writer/cohort 驗證、durable retry 排程及後續本機 activation 協調。缺少固定傳輸設定與完整裝置驗收，v3 rollout 保持關閉。

五項新增 SQLite／fake-provider 測試涵蓋 root 後建立 index、pending 保留、遺失回應後重建 committer 且不重送、保留其他參照、前置版本變動、預設及提交前 gate、完整掃描中斷、虛假成功回應、重複候選與登入中斷。Migration 測試確認新欄位預設為 null；既有 store 提交測試改為先保存 immutable intent。

Native journal 的本機 activation 現在由 `activateCommittedSession` 分流，不能沿用舊版僅根據 source IDs 確認的路徑。交易內重驗 index intent／已保存證據、凍結 journal、安裝帳號／device／epoch／writer／Active 狀態。每筆 session 來源須存在、屬於可確認生命週期，且經 canonical importer 後操作與共享刪除 proof 均完整存在於已發布 journal；Excluded／NoOp／無法轉接或缺少操作時拒絕全部 activation。

通過後只確認該 session 的來源，保存 canonical fingerprint 的 root 連結，更新 heartbeat 並進入 Completed；sequence counter 與後來新增的 pending 操作保持原值。全部 SQL 在同一交易，外層回滾會一起回復；Completed 重試不再改寫時間或提交遠端。不建立 checkpoint coverage，也不刪除來源。Native checkpoint 拒絕 generic activation，改由下述 canonical projection／pending overlay 專用流程處理，不能只標記完成。

來源驗證依 session source ID 使用 outbox 主鍵逐筆查詢，不載入或解碼整個 outbox。這避免 activation 記憶體隨無關歷史資料增加，也避免無關損壞資料阻擋本次已驗證發布。整合測試在 index 提交後新增 pending 列並暫時損壞其 JSON，確認 activation 不讀取、不確認、不改寫該列；本次來源仍通過完整 canonical 比對。

Native checkpoint 已提供專用 `AppSyncCanonicalCheckpointActivator.activateRecovery`。它從持久化 index intent／回讀證據與凍結 checkpoint 重建驗證物件，核對帳號及目前 writer，並在 projection 交易內再次核對 recovery 證據。既有 canonical activator 合併最新 outbox overlay、還原 projection、保存原遠端 coverage；不把 overlay 的 coverage 冒充為遠端已提交內容，也不確認 outbox。

設定仍在 SQLite 交易外還原。設定還原失敗時，projection 保留且 session 維持 ActivatingLocal；重試重新合併最新編輯。只有 canonical head 的設定待處理旗標清除，且 adopted checkpoint 的 ID／Blog ID／摘要與驗證物件一致，才在交易內保存 root 連結並進入 Completed。完成重試不重套舊 projection 或設定。通用舊 activation 入口仍拒絕 native checkpoint，正式 service／worker 協調尚待接線。

`AppSyncV3CommitCoordinator` 提供預設停用的單次執行協調：由 Classifying／Staging 開始，接續分段、index 與本機 activation，只有資料庫確實 Completed 且 indexCommitted 才回傳 Verified。journal 回報本 session 已確認來源；checkpoint 必須注入 canonical activator，且不回報 outbox 確認。取消向上傳播，登入中斷不消耗重試預算；其餘結果沿用 `AppSyncRecoveryAttempts` 保存不可變 retry identity／期限及第三次失敗停止規則。期限未到不執行遠端操作，也不增加失敗計數。

此 coordinator 不建立背景工作；呼叫端必須另外成功排入 durable worker，才可在 UI 宣稱等待已排程的重試。正式 service／worker 接線、重開機驗收仍未完成。測試涵蓋從 staging 到 journal activation 的完整 fake-provider／SQLite 執行，以及重新建立 coordinator 後的期限、第三次停止、來源保留與明確恢復。

v2 segment publisher 與 v2 coordinator 在入口即拒絕 transport 3，包括已經進入 ActivatingLocal 的 session；不得跳過 native coordinator 直接啟用，也不得把錯誤分流計入 native retry budget。回歸測試確認發布前及 index 提交後均保持 session、pending 與遠端寫入數不變。

反向亦相同：native coordinator 若發現 frozen payload 的 transport 不是 3，會在任何 phase／retry 更新前拒絕；尚未凍結的 native session 則可正常起始。測試確認 v2 frozen body、來源及 retry state 原樣保留，沒有 discovery 或 POST。

`OperationSyncEngine.canonicalRecovery` 是可選的 recovery continuation，未提供時保留既有 reader 行為。其 `resume` 在同步 mutex／database lease 內、reader cohort observation 與 canonical cloud planner 驗證成功後執行，且早於一般 canonical activation。writer 衝突與無效 cloud 不會呼叫 resume。非 null 結果直接交回 service；null 則照常啟用 reader projection。測試核對呼叫時 lease 存在、返回後釋放，以及無效 cloud／pending 保留與預設相容性。

Native checkpoint coordinator 現可接收本次驗證完成的 cloud plan。`AppSyncCanonicalRecoveryPlanner` 比較凍結 checkpoint 與最新雲端 base，保留兩者各自的 index 驗證證據，允許 index 版本不同；不把已壓縮的 winner 操作冒充為完整 journal。先檢查帳號、checkpoint ID／摘要、跨 checkpoint 的操作及 proof 衝突，再分別用雲端 journals 補齊候選。只有結果覆蓋凍結 checkpoint 與完整 cloud plan，且所有合格候選產生相同 canonical 內容，才選擇啟用 base。覆蓋不完整或內容矛盾時保留 session 與本機資料。

啟用交易仍合併當下 outbox，並拒絕降低既有 canonical head 的任何 replica coverage。若選用較新的遠端 base，交易另外保存凍結 checkpoint 的原始 verified evidence，讓 recovery completion 可核對真正發布的 root；兩份證據都不擴張為本機 overlay coverage。設定失敗後仍以最新 cloud plan 重試，不重做已完成的遠端發布。此介面尚待 service 傳入最新 plan，writer 預設仍停用；本機 coverage 防退檢查本身不代表完成所有並行一致性或裝置驗收。

Journal recovery 在 coordinator 收到 cloud plan 時，亦透過 canonical activator 執行。它先從持久化 index／frozen payload 重驗 writer 及來源，合併當次 cloud operations 與 frozen journal，拒絕同一操作 ID 或 proof ID 的不同內容，並在啟用交易內再次檢查 recovery 證據。雲端 checkpoint 的原始 coverage 仍單獨保存；本機 overlay 可包含後續編輯。設定尚未還原時不確認任何來源、不完成 session；成功後在另一筆交易重驗來源、canonical head 的完整性／coverage 與設定旗標，才只確認凍結來源並完成。已完成重試不重套設定。未提供 cloud plan 的低階 publication-only 呼叫仍保留既有行為；正式 service 必須傳入已驗證 plan 及 canonical activator，不能把 publication-only 確認當作完整同步。

啟用前的必要 coverage 同時納入 frozen journal 的 observed、published-through、acknowledgement 及操作 causal context，避免只補齊 journal 自身 sequence 就遺漏已觀察的其他裝置歷史。缺少任何必要歷史時，projection、checkpoint evidence 與 outbox 確認均不變。回歸也確認重試間新增、且已觀察到雲端更新的本機編輯依因果順序勝出；它仍保持 pending，不會被先前 journal 的成功確認順帶標記。

正式 `AppSyncService` 現綁定 `AppSyncNativeRecoveryContinuation`，只接續已存在且已凍結的 transport 3 session，傳入完整 cloud plan 與 canonical activator；尚不建立新的 v3 發佈或初次 migration。native session 強制完整 discovery，preflight 在 lease 內檢查 NeedsAttention／持久化重試期限；讀取失敗使用同一 phase／payload 的重試預算，無有效 canonical base 時禁止落入 legacy writer。設定旗標 `appSyncV3WriterEnabled`、`appSyncV3ReaderReady`、`appSyncV3BenchmarksApproved` 全數預設 false；遠端寫入還必須通過當次 reader cohort gate。已確認 index 的本機 activation 可在 writer flag 關閉時繼續，不再 POST。

缺少相容性或無效雲端／凍結證據會留下 NeedsAttention，避免 worker 不斷接續；只有明確手動恢復會重設這些可恢復分類並重新檢查條件。成功後 service 偵測 canonical head，跳過 legacy checkpoint／journal retirement。coordinator 的 persisted deadline 仍須由 Android worker 成功排入下一個工作；排程證據與 UI／重開機驗收尚未完成。native transport budget 持久化、初次 checkpoint 建立及一般新 journal 寫入也仍是獨立待辦。

Service continuation 將本次 cloud plan 的 checkpoint 驗證物件一路傳至 native index committer。凍結 index intent 前，最新權威 index 必須仍保有同一 checkpoint ID、實體 Blog ID 與 canonical 摘要；缺少 index 或 reference 已變更時不凍結 intent、不送出 index POST。之後仍使用兩次 base 比對與精確 readback，保留既有不能原子 CAS 的限制。同步接收計數排除本 installation 的 writer 操作，避免把本機確認同時計成遠端接收。
