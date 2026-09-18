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
