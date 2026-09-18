# AppSync legacy → canonical 轉接

更新日期：2026-09-18。`AppSyncCanonicalOperationImporter` 是純資料轉換元件，尚未接入正式 journal/recovery 發布；不修改原始 operation 或資料庫。

轉接先比對帳號與結構化 entity key，再核對原始 identity fields 是否與 key 相同。可解析的數字採型別等價，避免把 `0001` 當成另一個 ID；但 entity key 自身仍要求正規表示。非刪除操作先通過既有 domain contract，接著經 canonical normalizer，最後使用操作區塊 codec 驗證型別、空主體、kind、證據及大小。

呼叫端必須處理四種結果：

- `Accepted`：canonical operation、可選的共享刪除 proof 與去識別化排除原因。
- `Excluded`：未知 domain 或 setting 不進入同步資料。
- `NoOp`：Patch 僅含被排除欄位，不建立空操作。
- `NeedsAttention`：身分不一致、不可重建來源、契約失敗、必要值超出預算或刪除證據缺失；不傳回部分操作。

Excluded/NoOp 仍有來源 sequence，未來 journal／recovery adapter 必須保存其 coverage，不可直接過濾清單後假裝序號連續。Accepted 的 operation 保留裝置、epoch、sequence、generation、timestamp、origin 與 causal context；普通 Delete/RelationRemove 的舊主體清空，授權 Delete 將 scope/count/expiry 提取至 proof。沒有 ID 的 proof、缺欄位或過期證據均拒絕。

## 收藏更新事件的 identity 證據

固定 field ID 64 `sourceDiscriminator` 是條件式可省略的必要資料，不可一律分類為 Derived。正式產生端的 post/update/scan discriminator 無法從 event fingerprint 反推，必須以原本的有限大小 scalar 保留。只有與 `details:<排序且去重的 immutable detail IDs>` 完全相同的預設值，才由 normalizer 省略。`sourceFingerprint` 仍由 event key 重建。

舊式 `legacy-ambiguous|...` discriminator 包含歷史標題／摘要。Normalizer 現在先核對原事件 ID，再將它轉成下述版本化識別，移除重複的顯示文字；不改變原事件 ID 或來源操作。

測試涵蓋全部 19 個 domain 的正式合成來源、序號與衝突證據保留、來源不可變、unknown/no-op 結果、identity mismatch、超長標題省略、超長筆記拒絕、共享刪除 proof、自訂事件 discriminator 保留及 ambiguous 來源保護。

## 還原為本機欄位

`AppSyncCanonicalMaterializedFields` 接受 resolved projection，核對每個欄位的勝出操作是否屬於相同 domain、entity、generation，再經 field codec 驗證 portable values。它從 key 重建 identity；RSS history 必須提供可匹配 subscription key 的父層，標籤使用父層當前資料。缺少顯示標題或非 nullable 快取標題時，僅在輸出本機欄位中補空字串，不新增 canonical field 或 provenance。封面不補入。

事件重建省略的預設 detail discriminator，並重新計算 event identity；無法重建或不吻合時拒絕。已刪除或移除的 projection 不可走值還原流程。四項測試覆蓋 19 個 domain 往返、父層缺失／錯配／更新、標題補值不污染來源，以及事件與 provenance 錯配。

`DatabaseSyncDomainMaterializer.applyCanonicalProjections` 現在提供交易式資料庫套用入口：先比對帳號並使用 checkpoint codec 驗證 coverage／provenance，依既有 domain 父子順序套用；同一實體若出現多個 generation，要求呼叫端先完成解析。RSS 標籤來自已套用父層，新增關聯若缺少 item／category／collection 就失敗，避免默默漏寫。後段缺必填值時，前段寫入也會回滾。

materializer 的內部輸入將本機值與勝出操作 ID／時間分開；還原補值不會製造假的操作。原有 legacy apply 也轉為同一內部表示。canonical 空主體刪除與關聯移除由 key 定位；重複套用保持冪等，更新既有項目保留本機封面。

此入口只套用資料庫 projection，不代表 checkpoint activation。`SqlDelightCanonicalCheckpointState` 已提供 canonical provenance 與 projection 的交易式持久化替換，詳見 [本機狀態](appsync-v3-state.md)。外部 settings reconciliation、正式 engine／reader 呼叫、完整 resolved-state 預檢、本機 pending operations 合併、序號 coverage 整合，以及上述 ambiguous identity 支援仍未完成。沒有因此清除舊 journal 或啟用 v3 writer；tasks 2.5／2.7 保持未完成。

## Legacy resolved projection 轉換

`AppSyncCanonicalProjectionImporter` 提供純資料轉換，逐欄保留 legacy resolved projection 的勝出關係；不把欄位來源中的完整舊 Put 重新重播，避免舊值重新勝出。它核對 account、entity/generation、operation ID、欄位值與来源值、coverage 及 causal dependencies；重複實體、多 generation、同操作 ID 的不同內容或無法匯入的必要值會回傳不含使用者內容的 NeedsAttention 分類。

來源經既有 operation importer 與 canonical registry 正規化。未知 domain／setting 明確排除但仍核對其 account 與 coverage；derived/cache 欄位不進入 canonical field references。刪除維持 tombstone，關聯移除不攜帶舊欄位主體，刪除 proof 依身分合併並只保留仍被引用的證據。最後經 checkpoint codec 往返與共用 canonical reducer 的空輸入驗證，輸出確定性 canonical projection。

限制為 100,000 個實體／不同來源、250,000 個 provenance references、64 MiB 來源欄位資料，另受既有 canonical codec 預算約束。此轉換器本身不宣告 legacy checkpoint 的遠端可信度，不比對 encoded snapshot，也不授權發布、ack 或清理；legacy index 綁定與 snapshot 一致性由下述驗證器處理，正式 migration 接線仍須完成。既有 canonical cloud 的 activation／pending merge／外部設定重整已另行接入 service，不代表純 legacy migration 已驗收。

`AppSyncVerifiedLegacyCheckpoint` 現提供獨立的 legacy index 綁定：驗證 account、實體 Blog ID、唯一 checkpoint ID 與 legacy payload fingerprint。它保存原始不可變封套，每次讀取重新解析，避免呼叫端修改已解析集合後仍沿用舊 index 證據。此型別不等同 `AppSyncVerifiedCanonicalCheckpoint`，不能傳入 canonical 清理入口。

`AppSyncLegacyCheckpointMigration` 從此 legacy 證據建立候選資料，先轉換 resolved field winners，再將 encoded snapshot 經既有 snapshot planner 與 canonical normalizer 轉換，雙向核對可攜 live entity 集合與欄位值。只容許 schema 明定 nullable 的缺值/null 等價，以及省略的顯示文字與空字串等價；其他不一致、重複實體、孤立 RSS 歷史及沒有對應 resolved 刪除來源的 tombstone 均回報 NeedsAttention。已刪除實體及移除關聯不要求出現在 live snapshot。

輸出保留 legacy source/index fingerprint 供後續發布意圖綁定，但仍是未發布的 candidate；正式 migration 必須合併完整 cloud journal 與當下 pending，再建立新 v3 identity、發布及回讀驗證，才能套用／清理。Snapshot-only 舊資料可由下述完整 journal 重建路徑處理；來源不足時拒絕，不會憑 snapshot 內容假造勝出來源。

正式 `YamiboAppSyncJournalRemote` 載入結果現在另附 `verifiedLegacyCheckpoints`。只有同一次載入實際取得的 checkpoint 邏輯 envelope（包含分段重組結果）與已讀回 index，經帳號、實體 blog ID、checkpoint ID 及 fingerprint 綁定成功後才加入。全量 discovery 中未被 index 引用或指紋不符的 checkpoint 仍可供 legacy reader 使用，但沒有遷移證據；記憶體解析快取命中也不重建此證據。此欄位不觸發 canonical processing，也不授權清理。正式 bootstrap 的選擇、合併及發布仍待接線。

## 首次遷移的雲端與 pending 合併

`AppSyncLegacyCloudMigration` 為純規劃器，要求完整 authoritative discovery、同一 index 的 legacy checkpoint 證據、完整 indexed journal、帳號及 writer nonce 一致，並限制 checkpoint／journal／來源操作總數。它核對所有已讀 checkpoint coverage，以及 journal published、observed、acknowledged 與 causal history；各可完成的 checkpoint 經 journal 補齊後必須產生相同 canonical 狀態。只有雲端完整性成立後才合併目前 installation 的 pending，避免 pending 掩蓋雲端缺漏。

輸出新 identity 的未發布候選、原始 legacy 證據及已涵蓋 pending IDs；不產生 canonical verification，不寫 DB、不確認來源、不清除資料。正式呼叫端仍須在凍結發布的交易內取得 pending，接入原有 native recovery 的發布／index 回讀／activation，並處理 snapshot-only 舊 checkpoint 與無 checkpoint 帳號。reader/writer rollout gate 仍維持關閉。

`AppSyncLegacyMigrationStarter` 已提供交易式凍結入口：交易內重讀 installation 與 pending、執行上述完整合併、依候選 canonical bytes 產生新 v3 checkpoint identity，再一併建立 native recovery session、凍結 envelope 與 legacy source binding。它拒絕覆寫未完成工作及既有 canonical head，不啟用本機狀態、不確認 pending。凍結後新增的編輯留待後續 activation 合併。

資料庫 migration 57（schema 58）在 recovery payload 新增 legacy blog ID、checkpoint ID、payload fingerprint 與 index fingerprint，原有工作預設全為 null。`freezeLegacyMigrationSource` 只接受同帳號的來源證據及最初 Classifying 階段；重複相同綁定保持冪等，部分缺失或不同綁定拒絕。來源欄位隨 payload 清理，不增加獨立保留期限。正式 engine／continuation 路由與 index commit 的 legacy base 核對仍待接線，writer gate 仍關閉。

Native index committer 已接入凍結的 legacy source：分段發布前先完整掃描及讀回 index，要求來源 blog／checkpoint／fingerprint 仍被引用，且整份 index fingerprint 與規劃時相同；分段發布後再次核對。若上次 index POST 已成功但未確認，只有實體 index ID 與整份凍結意圖 SHA 完全一致，才允許跨過舊 index fingerprint 的差異。既有提交前 base 重讀仍保留，provider 沒有 CAS 的競爭限制也不變。

本機 `markNativeIndexCommitted` 同時要求回讀 index 保留凍結的 legacy checkpoint 引用，避免只驗證新產物便提前承認遷移。偽 provider／SQLite 回歸涵蓋缺 index、移除來源、改 blog／fingerprint／index 時間、發布中競爭，以及 lost response 後重建 committer 不重複 POST；全程 pending 保持未確認。正式 engine／continuation 的 legacy 路由仍待完成。

正式 service 已建立 legacy starter，engine 在既有 process mutex／run lease 內、cohort observation 後呼叫 `resumeLegacy`。三個 rollout flags 全開時強制完整 discovery，取得新鮮 cohort 及 migration source；真正建立工作與每次遠端寫入仍要求 cohort gate 成立。已凍結遷移若尚未被 index 引用，可接受與 frozen checkpoint 完全相同的未索引分段產物；其他 native 內容不會轉作 legacy 資料。

每次恢復都重新核對凍結 source binding、目前 legacy cloud 的完整性與 coverage，拒絕凍結後雲端新增而未被候選涵蓋的歷史。通過後沿用 native coordinator 完成發布、index、activation、settings reconciliation 與分批清理；較晚的本機編輯在 activation 合併並保持 pending。若已有 indexed native checkpoint，則回到原 canonical planner／recovery 路徑。預設 flags 仍關閉，snapshot-only 舊 checkpoint、無 checkpoint 帳號、manual force/reset、reader capability rollout 與裝置驗收仍未完成。

Native checkpoint activation 在 settings reconciliation 成功後，於同一交易再次讀取發布證據，只將凍結 checkpoint coverage 涵蓋的同帳號／同來源裝置及 epoch pending 標記已確認，再轉入 Cleaning。設定套用失敗時不提前確認，較晚 sequence 不受影響；清理仍只依遠端已驗證 coverage 執行。

## Snapshot-only checkpoint 的真實歷史重建

若舊 checkpoint 的 resolvedEntities 為空而 coverage 非空，`AppSyncLegacyCheckpointMigration` 可使用已載入 journal 中、被該 coverage 涵蓋的操作重建。它要求每個 replica 從第一個序號起完整連續、無身分衝突、帳號相同、操作 causal context 不超過原 checkpoint，且重建後 coverage 完全吻合。晚於 checkpoint 的歷史不參與此步，仍由雲端規劃器在後續合併。

來源經 canonical importer／reducer 保留原本 operation ID、generation、timestamp、刪除 proof 與欄位 winner，再與 snapshot 的可攜內容雙向核對；不建立虛構的 migration writer 或把 snapshot 時間當成每個欄位的來源時間。既有非空 resolved provenance 仍走逐欄轉換，不會被完整重播覆蓋。history 不足、衝突或 snapshot 不一致時保留原來源並回報 NeedsAttention。正式 cloud planner 已傳入完整 journal 集合；失去原始 journal 且無 resolved provenance 的帳號仍需要另行明確的 rebootstrap 決策，不能自動宣稱無損轉換。

測試語料使用符合現行 legacy reader 規則、未包含 FavoriteUpdate 資料的 snapshot-only checkpoint；FavoriteUpdate snapshot 與 resolved projection 必須一致的既有檢查未放寬。包含這類資料卻遺失相應 projection 的損毀文件仍在 reader 階段拒絕。

## Ambiguous event 的有限長度識別

`legacy-identity-v1|<scope SHA-256>|<original event fingerprint>` 保存原 16 位 hexadecimal event fingerprint，scope 綁定原 identity 規則中的 target type、target ID、author ID（null 等同 0）及 mode。這是識別／損毀檢查資料，不是認證或簽章。原始 discriminator 的摘要／標題不再重複放入必要欄位；summary 與允許的有限顯示標題仍依各自 schema 規則處理。

只有 ambiguous、沒有 immutable detail IDs、來源 ID 核對成功的 legacy discriminator 可轉換。新版 identity helper 辨識此表示、驗證 scope 及格式後保留原事件 ID；canonical reducer／materializer 因而可還原且再匯入。普通 detail/custom discriminator 與未轉換的本機生成規則保持原行為。原 legacy 操作仍保留原文，轉換不就地修改來源。

舊版 v2 reader 不理解此 discriminator 表示；v3 writer 仍必須等所有相關 reader 能力與 rollout gate 通過。停用 v3 writer 後的 sanitized v2 回退需要另外核對可讀能力，不能把此表示宣稱為任意舊客戶端都可讀。完整回退流程與能力公告仍待完成。

Legacy publication 現在有共用相容性檢查：journal 操作、checkpoint 的各欄位 provenance／relation／tombstone 來源及 snapshot event 若包含新版 portable discriminator，v1/v2 codec 拒絕編碼；正式單篇／分段 journal、checkpoint 與 legacy shadow recovery 在 provider 請求或工作建立前回報明確相容性原因。讀取驗證沒有改成拒絕新版識別。這是避免錯誤降版的保護，並不代表 sanitized v2 回退轉換已完成；後續 adapter 必須取得可驗證原始 legacy 證據，或使用另行核對的新 reader 相容策略。

## Sanitized v2 操作轉換邊界

`AppSyncSanitizedV2OperationExporter` 接受完整 canonical operation block，先驗證 schema、大小、操作身分及共用刪除授權，再將 typed portable 欄位轉回 v2 字串值。舊格式契約需要的實體識別由 structured key 還原；Patch 只補契約必要的識別欄位，Delete 只有原刪除授權證明，不附帶陳舊實體內容。預設 detail event discriminator 可由 immutable detail IDs 還原；portable ambiguous discriminator 則明確回報 reader compatibility 限制，不嘗試重新塞入舊標題／摘要。

每筆輸出須通過現行 legacy domain 契約，並重新匯入為與來源完全相同的 canonical operation 與 proof。任何一筆不符即拒絕整批；診斷只有固定原因，不包含使用者資料。快取、父實體標籤和本機欄位不從 materialized projection 補回。

新版 legacy domain 契約已允許省略 canonical registry 判定為 Cache、ParentJoinable、DeviceLocal 或 BoundedPresentation 的欄位；Essential 與必要 Derived 識別仍須存在。使用者命名的 RSS title 仍屬 Essential 且不得省略；event 以已驗證 discriminator 還原識別，不要求重複顯示標題。本機 materializer 僅為缺少的非 nullable cache／顯示欄位提供空字串，RSS 歷程從現有 parent 取得省略的 title／query，不改寫 remote winners。這讓 19-domain 語料的 canonical operations 可經 sanitized v2 編碼、讀取、reduce 並套用資料庫。這項行為需要已更新的 reader；不能據此推論已發佈舊版客戶端也接受缺少欄位。此 adapter 尚未接入正式 fallback dispatch，不代表任意舊 reader 均可讀、來源已確認、v3 root 已替換或完整回退已完成；正式發布仍須 reader capability、durable intent、index readback 與原 v3 root 保護。

## 回退 journal 與 reader gate

`canWriteSanitizedV2` 與 v3 writer／benchmark 開關分離，但仍要求本機 reader ready、Active installation、相符 account／writer nonce、完整且未過期的 authoritative cohort，以及所有有效 reader 的 read version >= 3。證據缺失、cached scan、時間倒退或任何有效舊 reader 都不能通過。v3 `canWrite` 在此共同 reader 條件之外，仍保留原 writer 與 benchmark 開關。

`AppSyncSanitizedV2JournalPreparation` 預設禁止準備，呼叫方必須提供上述能力檢查。它核對 installation writer 與 next sequence、驗證完整 canonical journal、轉換 portable operations，保留 causal／observed／published watermark、checkpoint acknowledgements、heartbeat 和 app version，僅將 write protocol 改為 2。v2 envelope 編碼後須重新解碼核對，並再次檢查 gate，才回傳固定 envelope 與 fingerprint。此結果不是遠端確認，不修改來源／outbox／索引。正式發布還需要每次網路寫入前重查 gate、durable intent、readback 與保護原 native root；這些接線仍待完成。

## 回退 session 的固定 payload

Migration 58→59 新增 `AppSyncV2FallbackPayload`，以 recovery session 為外鍵保存 v2 envelope、其 SHA-256 與原 canonical envelope 的 SHA-256；既有 session 不會憑空取得回退證據。`AppSyncNativeJournalStarter` 的顯式 `sanitizedV2Fallback` 選項，將 canonical journal、來源集合和 v2 bytes 放在同一個 DB transaction 內建立；此模式暫不觸發 native checkpoint cadence，避免回退時發出新的 v3 checkpoint。

重新載入核對兩份固定 bytes、當前 writer 與 canonical→v2 轉換結果。後續本機操作不加入已固定的 journal；交易失敗會一起回復，pre-commit rollback 會清除附屬 payload，保留 pending sources。開始發布後不得把普通 native session 改成回退 session。v3 segment／index publisher 在回退綁定存在時拒絕執行，避免重新打開 v3 flag 後誤發錯誤格式。

目前這個 starter 選項尚未由正式 service 啟用；v2 專用發布、索引確認與 canonical activation 還需接線並驗證中斷重試。因此 3.9 與完整回退驗收仍未完成。

## Sanitized v2 分段發布

`AppSyncSanitizedV2SegmentPublisher` 僅接受有固定 v2 companion 的 canonical session，輸出現有 v2 segment／root 格式。分段設定沿用 session 內固定的 configuration，開始時先核對整份已持久化分段計畫，再由尾段向前建立所有文件。每次 POST 前重查 reader gate；POST 一律使用新文件，不更新原 native root。

每段以 SHA-256 綁定 durable intent，已確認的段重試時仍必須實際讀回，比對 Blog ID、title 及完整 body。回應遺失或候選不明時使用完整分頁探索；Unknown、授權失效與衝突均不得視為不存在，也不在同一次呼叫重送 POST。Root 的持久化 intent 依 v2 格式保留 16 位 wire fingerprint，但探索另外使用 SHA-256 並核對完整內容；僅有 v2 companion 的 session 可以使用此規則，普通 v3 root 仍要求 64 位 SHA-256。

成功僅進入 CommittingIndex，不提交索引、不確認 outbox、不啟動清理。正式 index readback、canonical activation 與 service dispatch 仍待接線；原 v3 文件保持原狀。

共用 reader-text 還原也保留 v2 segment／root 的換行，避免 Discuz `<br>` 呈現使完整 body 核對或 SHA-256 探索誤判。既有 v1/v2 journal 的讀取方式沒有因此改成 v3 解碼。

## 回退索引確認

`AppSyncSanitizedV2IndexCommitter` 必須收到已驗證的 canonical checkpoint，且該 checkpoint ID／coverage 必須已存在於固定 journal 的 acknowledgements。發布分段前及取得 root 後，都重新探索索引並核對該 checkpoint 的 Blog ID／fingerprint；缺少原始基底時不開始新的分段寫入。索引保留目前所有 checkpoint 與其他 replica journal，只替換自己的 journal reference。

索引 body、原索引 SHA-256 及目標 Blog ID 在 POST 前固定，再次探索確認基底未變及 reader gate 通過才送出。回應遺失以完整索引探索與實際讀回確認，已出現相同固定 body 時不重送。Provider 沒有 compare-and-swap，最後讀取與 POST 間的競態限制仍存在，不能宣稱伺服器端原子交換。

`markSanitizedV2IndexCommitted` 與 native 入口分開，核對完整固定 intent、實體 root ID、v2 root fingerprint、canonical→v2 payload 及至少一個已確認 checkpoint 後，才原子記錄 index evidence 並進入 ActivatingLocal。Native 入口拒絕帶 v2 companion 的 session；以 canonical document fingerprint 冒充 v2 root fingerprint 或移除已確認 checkpoint 均拒絕。這一步不確認 pending operations、不改寫本機投影、不執行清理；後續 canonical activation 與 service dispatch 仍待完成。


## 回退日誌本機啟用與目前 session 回收

已確認回退索引的 session 可使用 `activateJournalRecovery` 合併目前雲端與固定 canonical journal。
啟用前及資料庫交易內重新檢查 v2 companion、來源操作、writer 與固定索引，沿用完整歷史 coverage
檢查。外部設定寫入失敗時維持 ActivatingLocal，不確認來源；重啟後重新合併後續 pending 操作，
只確認原 session 的 source IDs。Completed 重複呼叫不重播設定。遠端 root 紀錄保存 v2 root
fingerprint，避免誤記 canonical document fingerprint。

目前 Completed session 必須等到後續已驗證 checkpoint 涵蓋整份 journal 的 own、observed、
acknowledged 與 causal history，才在同一交易中刪除 canonical／v2 本文並保存 payload-free receipt。
回收 byte 計數包含 v2 companion 的 UTF-8 bytes；未覆蓋、證據不符或 pending source 均不能提前清理。

Migration 59→60 在 retained-journal 增加可空的 fallback envelope／SHA-256；普通 native 歷史維持
null。建立下一個 session 前，重新檢查固定索引與 companion，將兩份本文和確認證據一併存入
retained history，核對完整保存內容後才刪除舊 session。交易中斷會一起回復舊 session 與 companion。

Retained cleanup 不依賴當前 writer：它從已保存的 canonical journal 重新轉換 sanitized v2，核對
完整 bytes、SHA-256、acknowledged checkpoint 與 v2 root index reference，再套用既有完整歷史
coverage 規則。純轉換入口 encodeFrozen 只用於資料驗證，不提供發布權限；新 session 仍經過
installation／reader gate。每批最多 8 份 retained journals，未涵蓋的日誌保留 checkpoint cursor，
新 checkpoint 會重新檢查；成功回收將 companion UTF-8 bytes 加入既有 audit。

正式 coordinator／service dispatch、多段與裝置中斷驗收仍待完成，不能宣告完整回退流程可上線。
