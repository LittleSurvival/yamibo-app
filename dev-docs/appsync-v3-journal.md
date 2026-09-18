# AppSync v3 canonical journal

更新日期：2026-09-18。Journal 與 checkpoint 現在均有 canonical 文件根節點，透過 `AppSyncV3DocumentCodec` 組合封套；正式 publisher、legacy adapter 與 rollout 尚未切換。

`AppSyncCanonicalJournalCodec` 的二進位根節點依序為 `YJR3`、revision 1、device、epoch、writer nonce、first/last sequence、observed coverage、checkpoint acknowledgements、heartbeat、protocol read/write version、app version、published-through presence/value、操作區塊長度與原始位元組。文字使用長度前綴 UTF-8；count、索引及非負整數採最短 unsigned LEB128，heartbeat 採 zig-zag。帳號保存於操作區塊。

Metadata-only journal 保留 observed、acknowledgements 與 published-through，first/last 固定為零；published-through 的 absent 與明確零值分開表示。非空 journal 要求單一 device/epoch、連續序號及精確 first/last。Published-through 若存在，不能低於 retained last 或自己的 observed watermark。V3 文件要求 write version 3、read version 至少 3；這不會自動改變正式 capability 廣告。

Coverage 依 replica key 排序，acknowledgements 依 checkpoint ID 排序且不得重複。預設根節點 16 MiB，observed entry、acknowledgement 及其 coverage entry **合計**最多 100,000 項，避免每個子集合各自合法卻累積過量。每個 metadata 字串最多 1,024 UTF-8 bytes，拒絕空白、控制字元與無效 UTF-8。操作區塊繼續套用自身的 encoded／expanded 預算。

`AppSyncV3DocumentCodec` 先驗證封套，再依 caller 指定的種類讀取正確根節點。Journal 同時比對預期帳號、產物識別、device/epoch；checkpoint 同時比對預期帳號及 checkpoint ID。外層 fingerprint 正確但內部帳號、種類、識別或結構錯誤時，不傳回任何部分文件。未知 envelope codec/compressor 仍回傳 Unsupported；傳輸錯誤與 canonical 內容錯誤均不暴露 payload 或底層例外。

兩種根節點都只產生原始 canonical 位元組，外層封套負責唯一一次 gzip 和最終 Base64。Checkpoint 不包含獨立壓縮 snapshot。測試比對封套解壓後與根節點編碼的位元組完全一致，並涵蓋 metadata-only 固定向量、欄位／操作順序、sequence gap、owner mismatch、Long 邊界、metadata 總預算、截斷與外層有效但內層不合法的文件。

後續仍需正式 legacy/canonical adapter、父層 join 與 materializer、production reader dispatch、帳號／cohort writer gate、分段與裝置 benchmark。不得以 codec 單元測試通過代替這些整合及跨平台驗收。
