# AppSync v3 封套基礎

更新日期：2026-09-17。對應 OpenSpec 工作 3.1；尚未接入正式發布流程或宣告 v3 讀取能力。

## 格式與識別碼

`AppSyncV3EnvelopeCodec` 接收已完成 canonical 編碼的位元組，壓縮一次，再於文字傳輸邊界進行 Base64 編碼。封套固定為以下 12 行，以 LF 分隔，最後一行後沒有換行；欄位不可重排、重複或新增。

```text
[YAMIBO_APP_SYNC_ENVELOPE:v3:BEGIN]
schema=3
codec=1
compressor=1
kind=1
account=<帳號綁定識別>
identity=<產物識別>
length=<未壓縮位元組數>
canonical=<canonical 位元組的 SHA-256>
integrity=<標頭與壓縮位元組的 SHA-256>
payload=<含標準 padding 的 Base64>
[YAMIBO_APP_SYNC_ENVELOPE:v3:END]
```

識別碼固定為 schema 3、canonical tuple codec 1、gzip compressor 1；kind 1 為 journal、2 為 checkpoint。Codec 1 是預留給完整 tuple 格式的識別，本階段只驗證其傳輸封套。Gzip 是現有相依套件提供的基礎方案，尚未完成 Android/iOS 壓縮候選效能比較。

`canonical` 是原始 canonical 位元組的 SHA-256 小寫十六進位字串。`integrity` 是從 BEGIN 到 canonical 行（包含該行結尾 LF）的 UTF-8 位元組，串接實際壓縮位元組後計算的 SHA-256；不包含 integrity、payload 或 END 行。它將帳號、種類、產物識別與壓縮內容綁定，但不提供發送者身分驗證。

帳號與產物識別必須非空、沒有首尾空白、ASCII 控制字元或無效 UTF-16，且各自不超過 1,024 UTF-8 位元組。數字使用無正負號、無多餘前導零的十進位 Int；摘要固定為 64 個小寫十六進位字元。

## 解碼界限與回傳值

預設未壓縮上限為 64 MiB、壓縮上限為 12 MiB，可由呼叫端設定更小界限。文字長度上限為 `ceil(壓縮上限 / 3) * 4 + 4096` 字元。切行最多產生 13 段，並在解壓縮前檢查宣告大小、Base64 正規形式、壓縮大小及 integrity。這些是本階段的程式上限，尚未作為裝置峰值記憶體驗收結果。

呼叫端必須提供預期帳號、kind 與 identity，任一不符即拒絕。解壓縮逐段讀取，輸出至多超過宣告長度一個位元組就停止；同時檢查 gzip trailer、精確輸出長度與 canonical 摘要。解碼器不接受 gzip 尾端額外資料。

- `VerifiedBytes`：僅完成傳輸檢查。後續 tuple/schema 解碼器仍須驗證表格索引、tuple arity、型別與配置界限，才能送入 reducer。
- `Unsupported`：已辨識但不支援的 schema、codec 或 compressor。在 payload 解碼及 integrity 驗證前回傳，不能當成可信能力宣告或有效資料。
- `Invalid`：只有固定原因代碼，不回傳使用者 payload 或底層例外訊息。

## 驗證與下一步

8 項單元測試涵蓋兩種產物的 deterministic round trip、空值與精確上限、未知版本、帳號與識別綁定、嚴格 framing/Base64、重新計算 integrity 後仍無法繞過的解壓縮界限、摘要與 gzip 損壞，以及 UTF-8 metadata 界限。

2026-09-18 已補上[操作區塊與共用字串表](appsync-v3-operation-block.md)，包含固定 arity 與展開預算；它尚非完整文件根節點。下一階段需完成結構化實體鍵與文件 metadata，再銜接 3.3 checkpoint operation 去重及完整 3.5 配置限制。工作 3.4、3.6、3.8、3.10、3.11 的完整產物接線、效能選型、分段、相容性閘門與跨平台 golden vectors 均尚未完成。本階段不啟用 v3 writer，也不執行資料清理。
