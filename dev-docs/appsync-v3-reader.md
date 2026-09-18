# 傳輸文字與文件分派

更新日期：2026-09-18。正式 `YamiboAppSyncJournalRemote` 的 HTML 轉文字改用 `appSyncReaderText`。v1/v2 保留既有 `text()` 行為；偵測到 v3 marker 時，使用保留空白的文字讀取並將 BR／段落／區塊邊界轉為換行，只移除空白行與 CRLF 差異。這保留 envelope metadata 的實際字元，不將帳號中的空白或跳脫符號重新解讀為另一個值。DOM 建立前有輸入大小上限，之後仍受 envelope 更嚴格的限制。

不從混雜內容截取看似有效的 frame。可見前後綴、重複封包、額外非空行或完整性不符會失敗。HTML 換行修復不提供身份驗證；SHA-256 仍只驗證資料完整性。

`AppSyncTransportEnvelopeDispatcher.readJournal/readCheckpoint` 要求外部預期帳號與文件／replica 身分，傳回區分的 legacy journal、legacy checkpoint、canonical journal、canonical checkpoint、Unsupported 或 Invalid。Unsupported 不等於有效文件，也不可當作可信 cohort capability。遇到 v3 marker 不降級嘗試 legacy parse。

分段 root 重建前先核對外層 kind、account 與 identity，重建後再驗證內層文件。既有 `validateJournal/validateCheckpoint` 保留相容介面。測試包含 v1/v2 相容、v3 HTML 包裝與 Unicode／跳脫字元、損毀與重複 frame、owner 錯配、缺段及外層帳號錯配。

目前正式 remote 已接入 HTML 文字修復；typed document result 尚須接到 remote load model 與 engine activation。整個 v3 讀取能力仍不可宣告完成，writer rollout 保持未啟用。
