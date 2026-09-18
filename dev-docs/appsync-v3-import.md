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

舊式 `legacy-ambiguous|...` discriminator 包含歷史標題／摘要，尚需穩定摘要表示與相容性轉接。因此目前明確回傳 `NonReconstructibleEventIdentity` 並保留來源，不以丟棄或改變事件 ID 解決。此限制不是整份規格的完成狀態。

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

輸出保留 legacy source/index fingerprint 供後續發布意圖綁定，但仍是未發布的 candidate；正式 migration 必須合併完整 cloud journal 與當下 pending，再建立新 v3 identity、發布及回讀驗證，才能套用／清理。Snapshot-only 舊資料沒有足夠 resolved provenance 時會拒絕，不會憑 snapshot 內容假造勝出來源；此相容路徑及正式 bootstrap 接線仍待完成。

正式 `YamiboAppSyncJournalRemote` 載入結果現在另附 `verifiedLegacyCheckpoints`。只有同一次載入實際取得的 checkpoint 邏輯 envelope（包含分段重組結果）與已讀回 index，經帳號、實體 blog ID、checkpoint ID 及 fingerprint 綁定成功後才加入。全量 discovery 中未被 index 引用或指紋不符的 checkpoint 仍可供 legacy reader 使用，但沒有遷移證據；記憶體解析快取命中也不重建此證據。此欄位不觸發 canonical processing，也不授權清理。正式 bootstrap 的選擇、合併及發布仍待接線。

## 首次遷移的雲端與 pending 合併

`AppSyncLegacyCloudMigration` 為純規劃器，要求完整 authoritative discovery、同一 index 的 legacy checkpoint 證據、完整 indexed journal、帳號及 writer nonce 一致，並限制 checkpoint／journal／來源操作總數。它核對所有已讀 checkpoint coverage，以及 journal published、observed、acknowledged 與 causal history；各可完成的 checkpoint 經 journal 補齊後必須產生相同 canonical 狀態。只有雲端完整性成立後才合併目前 installation 的 pending，避免 pending 掩蓋雲端缺漏。

輸出新 identity 的未發布候選、原始 legacy 證據及已涵蓋 pending IDs；不產生 canonical verification，不寫 DB、不確認來源、不清除資料。正式呼叫端仍須在凍結發布的交易內取得 pending，接入原有 native recovery 的發布／index 回讀／activation，並處理 snapshot-only 舊 checkpoint 與無 checkpoint 帳號。reader/writer rollout gate 仍維持關閉。

`AppSyncLegacyMigrationStarter` 已提供交易式凍結入口：交易內重讀 installation 與 pending、執行上述完整合併、依候選 canonical bytes 產生新 v3 checkpoint identity，再一併建立 native recovery session、凍結 envelope 與 legacy source binding。它拒絕覆寫未完成工作及既有 canonical head，不啟用本機狀態、不確認 pending。凍結後新增的編輯留待後續 activation 合併。

資料庫 migration 57（schema 58）在 recovery payload 新增 legacy blog ID、checkpoint ID、payload fingerprint 與 index fingerprint，原有工作預設全為 null。`freezeLegacyMigrationSource` 只接受同帳號的來源證據及最初 Classifying 階段；重複相同綁定保持冪等，部分缺失或不同綁定拒絕。來源欄位隨 payload 清理，不增加獨立保留期限。正式 engine／continuation 路由與 index commit 的 legacy base 核對仍待接線，writer gate 仍關閉。
