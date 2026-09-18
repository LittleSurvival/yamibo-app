# AppSync v3 canonical checkpoint

更新日期：2026-09-18。`AppSyncCanonicalCheckpointCodec` 實作 canonical checkpoint 根節點與操作表去重。正式 checkpoint coordinator／materializer 尚未切換到此格式。

## 表示法

根節點依序保存 `YCP3`、revision 1、checkpoint ID、建立時間、coverage、未壓縮的操作區塊及 projection 表。帳號只存在於操作區塊，解碼呼叫端必須提供預期帳號與 checkpoint ID。根節點沒有 JSON key、內層 gzip、Base64 或重複備份 snapshot。

操作區塊採用[revision 2 格式](appsync-v3-operation-block.md)，將所有勝出操作按裝置／epoch／sequence 去重並排序。相同操作識別若有不同內容，拒絕整個 checkpoint；不任意挑選一份。只保存實際被 projection 引用的操作與刪除 proof。

每個 projection 是 arity 4 的 tuple：

1. owner operation index：引用本實體使用的最小操作索引，從該操作取得 domain、結構化 entity key 與 generation。
2. 依 field ID 排序的 `(field ID, operation index)` pairs。欄位值直接取自該操作，不另存副本。
3. relation operation index + 1，0 代表無關聯狀態；是否存在由 RelationAdd／RelationRemove 判定。
4. tombstone operation index + 1，0 代表無刪除標記。

數量、索引與非負整數採最短 unsigned LEB128；建立時間採 zig-zag。coverage 依 replica key 排序。projection 依 domain、legacy identity 的確定性字串順序、generation 排序。

## 驗證

預設根節點上限 16 MiB、100,000 個實體與 250,000 個 provenance references；操作區塊仍套用自己的位元組、展開與集合界限。所有 count 在配置前檢查剩餘輸入與上限。

解碼／編碼均要求：

- 所有操作索引有效，欄位已宣告為 portable，且對應操作確實包含該欄位。
- 每個 reference 的 domain、entity identity 與 generation 均符合 projection。
- coverage 涵蓋每個勝出操作的 sequence；不把寫入成功當作涵蓋證明。
- tombstone 是 Delete，且與 live fields／relation 互斥。
- relation 只出現在關聯 domain，且引用 RelationAdd／RelationRemove。
- 操作表沒有衝突的重複識別、未使用操作或不必要的替代表示。

完整解碼後重新編碼比對；失敗只回傳固定原因訊息，不傳回部分 projection。這項 canonical 檢查會增加 CPU／記憶體成本，裝置 benchmark 仍需實測。

## 證據與整合界限

測試包含固定空根節點向量、同一操作勝出多個欄位時的共同參照、map／entity 亂序、後續並行與單調值 reduction、刪除與關聯移除、共享刪除 proof、錯誤／跨實體參照、coverage 缺口、逐位元組截斷，以及完整封套往返。合成 checkpoint 案例涵蓋所有 19 個 domain。

純 operation importer 已加入自訂 favorite-update discriminator 保留與預設 detail discriminator 省略；舊 projection 轉 canonical 的正式 adapter、父層資料 join、ambiguous discriminator 摘要遷移，以及 canonical materializer 仍需實作。正式發布仍採原有格式，v3 capability 與 writer gate 未開啟。跨平台 golden、壓縮選型、裝置效能與實際回收驗收仍屬獨立待完成項目。
