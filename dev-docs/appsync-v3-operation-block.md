# AppSync v3 操作區塊

更新日期：2026-09-18。本階段推進工作 3.2 與 3.5，仍未完成完整 journal/checkpoint 文件或正式發布接線。

## 資料與保留的語意

`AppSyncCanonicalOperationBlockCodec` 編碼單一帳號的一組 canonical 操作。帳號只保存一次；每筆操作保留裝置、epoch、sequence、domain、entity、generation、kind、建立時間、origin、授權識別、因果 high-watermarks 及具型別欄位。操作識別由裝置／epoch／sequence 表達，不重複保存組合後的 operation ID。

操作依裝置、epoch、sequence 排序，拒絕重複識別，包括內容相同的重複。欄位依數字 ID 排序，因果資訊依 key 排序；所有字串排序使用跨平台一致的 UTF-16 code-unit 順序，不受語系影響。來源集合／map 順序不影響編碼結果。

這是中介模型，不能直接交給現有 reducer。19 個 domain 的實體識別已改以結構化 component tuple 傳輸；整數使用 zig-zag LEB128，字串可參照共用表。`rss:`、`event:`、`fid:`、`category:` 等可由 domain 決定的前綴不重複寫入。legacy 邊界可從 typed key 重建完全相同的字串與識別衍生欄位，拒絕數字別名、未知 domain、錯誤 arity、fingerprint 或閱讀模式。

刪除授權依 authorization ID 存入共用 proof 表，操作只保留 ID 參照。proof 保存 domain、scope、原始 operation count 與 expiresAt；拒絕遺失、重複、未使用、domain 不符、過期或數量不足的 proof。checkpoint 可能只保留部分刪除勝出者，因此允許參照數少於原始 count，但絕不縮小原始 count。它保留現有防護證據，不等於新增發送者身分認證；正式匯入仍須執行批次刪除防護。

## 區塊 revision 2

二進位排列為 `YOB3`、單位元組 revision `2`、字串表、帳號 token、proof 表、操作數量與操作 tuples。proof tuple arity 為 5，依序為 authorization ID、domain ID、scope、operation count、expiresAt。它是內部區塊，並非 envelope codec 1 的完整文件根節點；尚未發布的 revision 1 不宣告相容。

每筆 tuple 先寫入 arity `12`，再依序寫入：device、epoch、sequence、domain ID、entity、generation、kind ID、timestamp、origin ID、authorization、causal context、field body。因果資訊是 count 加排序後的 key/watermark pairs；field body 先保存位元組長度，再使用既有欄位 codec。

| 類別 | 固定識別碼 |
| --- | --- |
| Operation kind | Put 1、Patch 2、Delete 3、RelationAdd 4、RelationRemove 5 |
| Origin | UserAction 1、Migration 2、RemoteReplay 3 |
| Identity token | 1 為 inline UTF-8、2 為字串表索引；authorization 另允許 0 表示 absent |
| Scalar reference | 原本 Text/Identifier/Enum tag 加上 `0x80`，後接字串表索引 |

domain/field 沿用 canonical registry 的固定數字 ID。count、length、index 與非負整數使用最短 unsigned LEB128；timestamp 使用 zig-zag LEB128。欄位中的 integer、decimal、boolean、null 與既有格式相同。沒有字串表時，既有欄位 golden vector 保持不變。

## 共用表與界限

字串表同時考慮裝置、epoch、entity、authorization、causal key 與文字類欄位。只選取出現至少兩次，而且以最大可能索引寬度及額外 framing allowance 估算後仍較小的值；短字串或單次出現的值保留 inline。文字類型仍有各自的 scalar tag，共用字串不會混淆 Text、Identifier 與 Enum。

預設區塊上限 8 MiB、展開估算上限 16 MiB、操作及字串表各最多 100,000 項。每筆因果資訊最多 1,024 項；識別字串最多 1,024 UTF-8 bytes；表格單一字串最多 128 KiB。展開估算計入未使用參照時的欄位編碼、重複識別字串、因果資訊及 framing allowance。這些是防止無界配置的程式限制，不是裝置峰值記憶體測量。

讀取時先檢查輸入大小，配置集合前檢查 count 與剩餘位元組，逐項檢查索引、arity、型別、欄位政策、順序及展開預算。呼叫端必須傳入預期帳號；區塊帳號不符即拒絕。Delete/RelationRemove 不接受 field body 或 Migration origin；Patch 不接受空欄位。

完整解碼後重新 canonical 編碼比對，拒絕未使用的表格項目、非必要參照與可替代編碼。此檢查具有額外 CPU／記憶體成本，後續裝置 benchmark 必須計入。失敗一律回傳固定例外訊息，無部分操作結果、原始資料或底層例外內容。

## 驗證與尚待工作

測試涵蓋固定 bytes、操作／map 重排、所有 scalar/kind/origin、因果及授權識別保留、重複值去重、跨越 127 的索引寬度、逐位元組截斷、損壞 tuple、錯誤參照、非正規表格、大小界限、政策繞過及與封套組合的 round trip。

工作 3.2 尚待 journal metadata 與完整文件共用表的整合。checkpoint provenance operation table 正由 `AppSyncCanonicalCheckpointCodec` 接續實作；正式讀取／發布 adapter、父層資料重建、跨平台 golden 驗證與裝置 benchmark 仍待完成，v3 writer 保持未啟用。
