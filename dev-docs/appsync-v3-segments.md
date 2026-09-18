# v3 原生分段傳輸

`AppSyncV3SegmentCodec` 先以正式 document reader 驗證 v3 journal／checkpoint，再切分既有封套文字。封套內 canonical bytes 已經 gzip 一次並 Base64 一次；分段只加入 JSON framing，不再壓縮或 Base64 包裝。

分段與 root 分別使用 `YAMIBO_APP_SYNC_SEGMENT:v3`、`YAMIBO_APP_SYNC_ROOT:v3` 四行 frame。第二行為 canonical JSON 的 SHA-256，第三行為 JSON payload。解碼要求完整 frame、固定欄位、摘要相符及重新編碼後逐字相等；不接受前後插入文字或重複 framing。

分段包含 account、kind、identity、generation、index、count、chunk 與下一段的 Blog ID／SHA-256。generation 為完整原始封套的 SHA-256。tail 的 next 為 null；其餘段需要下一段實體文件的參照，因此發布次序由尾到頭。`reference` 僅驗證傳入文件格式，不構成遠端提交或回讀成功的證明。

root 保存原始 v3 metadata、generation、封套 SHA-256／字元數、段數與 head 參照。每個下一段摘要涵蓋下一段的完整文件及後續鏈結；root 因此承諾完整有序鏈。重組逐段核對帳號、kind、identity、generation、index／count、完整文件摘要，拒絕遺失、循環、提前或延後終止，並在累積內容前檢查宣告長度。完成後重新驗證封套、canonical document 及 root metadata 一致性。

規劃使用二分搜尋量測最終文件，預留最大 Blog ID、摘要與段數所需空間，並在開始前檢查 root 的最壞大小。每段同時符合設定的字元與 UTF-8 byte 預算；Unicode surrogate pair 不會被切開。預設上限為 4,096 段及 16 MiB + 4 KiB 的完整封套。無法驗證、超過語意／總量限制或 metadata 無法容納時，不回傳可發布計畫。

正式 `YamiboAppSyncJournalRemote` 已能辨識並讀取原生 v3 root，逐段 GET 核對 Blog ID 與完整分段標題，再提供既有 canonical cloud planner／index-bound checkpoint 驗證。root 使用既有 journal／checkpoint 主文件標題供 discovery 尋找；分段使用 v3 segment 標題。HTML reader 保留 v3 root／segment 的換行。缺段會回報可重試；損壞 root／chain 保留 canonical 讀取問題，不能被視為空雲端，也不能交給 legacy writer 原地覆寫。

目前完成規劃、codec、重組及 production reader。durable 分段發布、attempt／reconciliation／index commit、reader capability 提升與 writer rollout 尚待接線；此文件不表示已啟用 v3 發布。測試涵蓋兩種文件、大型封套、確切預算邊界、Unicode、無效來源、段數與累積大小限制、遺失／竄改／順序／循環／跨帳號、真實 fake-provider reader 與 checkpoint index 證據。
