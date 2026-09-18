# 傳輸文字與文件分派

更新日期：2026-09-18。正式 `YamiboAppSyncJournalRemote` 的 HTML 轉文字改用 `appSyncReaderText`。v1/v2 保留既有 `text()` 行為；偵測到 v3 marker 時，使用保留空白的文字讀取並將 BR／段落／區塊邊界轉為換行，只移除空白行與 CRLF 差異。這保留 envelope metadata 的實際字元，不將帳號中的空白或跳脫符號重新解讀為另一個值。DOM 建立前有輸入大小上限，之後仍受 envelope 更嚴格的限制。

不從混雜內容截取看似有效的 frame。可見前後綴、重複封包、額外非空行或完整性不符會失敗。HTML 換行修復不提供身份驗證；SHA-256 仍只驗證資料完整性。

`AppSyncTransportEnvelopeDispatcher.readJournal/readCheckpoint` 要求外部預期帳號與文件／replica 身分，傳回區分的 legacy journal、legacy checkpoint、canonical journal、canonical checkpoint、Unsupported 或 Invalid。Unsupported 不等於有效文件，也不可當作可信 cohort capability。遇到 v3 marker 不降級嘗試 legacy parse。

分段 root 重建前先核對外層 kind、account 與 identity，重建後再驗證內層文件。既有 `validateJournal/validateCheckpoint` 保留相容介面。測試包含 v1/v2 相容、v3 HTML 包裝與 Unicode／跳脫字元、損毀與重複 frame、owner 錯配、缺段及外層帳號錯配。

正式 remote 現在也將 v3 文件接到 load model：`canonicalDocuments` 保留完整驗證後的 journal／checkpoint，`canonicalReadIssues` 保留固定且不含 payload 的未知版本／無效文件原因。發現路徑與索引／連結快取路徑都不會把這些資料當作 legacy 解析失敗而悄悄忽略。有效文件保留 blog ID 與 canonical metadata；這不是 index membership、freshness 或 activation 授權。

`AppSyncV3DocumentCodec.discover` 允許在尚未知道 artifact identity 時解碼，但仍要求外部帳號與種類，並核對 checkpoint 內外 ID。正式 journal discovery 要求 envelope identity 為 `deviceId:deviceEpoch`，且與根節點 owner 一致。已知 journal link、checkpoint ID／摘要與 segmented root identity 仍須吻合。明確指定 identity／owner 的既有 reader 介面保留嚴格核對。

目前 engine、bootstrap、手動 force-push/pull、journal retirement 遇到 canonical 文件或讀取問題會暫停 legacy 處理；不觸發空雲端推送、不發布舊格式、不建立清理證据。直接 legacy journal 覆寫、retirement delete 與雲端重設也拒絕處理 canonical 來源。這是正式讀取接線期間的過渡狀態，不能當成已完成 v3 同步。

仍須將這些文件經可信 index 驗證後交給 canonical activation/reduction，再完成本機 mutation routing、混合版本與 rollout gate。完成前不廣告 reader ready、不啟用 v3 writer。現有 v2 carrier 的分段重建不等於 native v3 segmentation。
