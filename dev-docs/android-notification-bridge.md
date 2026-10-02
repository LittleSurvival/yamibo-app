# Android 通知橋接（FCM / SSE）

## 目前狀態與範圍

本實作只改 Android：`composeApp/src/androidMain`、Android unit tests、Android auth hooks 及 app module 的建置設定。iOS/common runtime 不變。單一隔離分支：`stable-v0.0.8-addb0b5-android-notification-bridge`，基底 `addb0b5`。不修改通知 server、Discuz plugin、既有 PR/issues，也不發佈正式版本。

通知後端/VPS 尚待團隊提供，預設通知 origin **留空且停用橋接**；既有 WorkManager 定期檢查仍可運作。Firebase client configuration 已加入，但沒有 Firebase Admin key、service-account 私鑰、論壇 cookie 或裝置 token。部署、真機 FCM/SSE 端到端驗收尚未完成，不能以 client 編譯成功代替。

## 啟用與 Firebase

1. 通知 server 必須經 reverse proxy 以 `/notification-api` 掛在現有論壇的**同一個 HTTPS origin**。在 `local.properties` 設定 `yamibo.notifications.origin=https://<trusted-forum-host>`，或使用 Gradle `-Pyamibo.notifications.origin=https://<trusted-forum-host>`。實際值必須與 `YamiboRoute.Domain.build()` 同源；獨立 VPS hostname、HTTP、userinfo、path、query、fragment 全部拒絕，以免將 Discuz cookies 送到另一站。不要把 `/notification-api` 寫入 origin。
2. `composeApp/google-services.json` 是使用者提供的公開 client configuration：project `yamibo-notifications`、sender `798950086384`、Android app `1:798950086384:android:09baac11e89925d3c69435`，package `me.thenano.yamibo.yamibo_app`。Gradle 從此 JSON 產生 BuildConfig；Application 使用 `FirebaseOptions` 明確初始化，未使用會讓未註冊變體建置失敗的 Google Services variant task。Firebase auto-init/analytics/FID mode 關閉；只有已設定安全 origin 時才初始化。
3. 預設 debug package 是 `.debug`；未配置正式簽章的 `releaseRun` 是 `.run`。兩者尚未在 Firebase 註冊，會明確顯示「此安裝版本尚未配置 Firebase」並使用 SSE，不冒稱 Google 被封鎖。正式 package 的 release、或 repo 已支援的正式簽章 debug/releaseRun 流程可測 FCM。不要提交簽章私鑰；若要獨立 debug/run FCM，需在同專案註冊對應 package、下載正式 client 設定，並擴充目前精確 package 選擇邏輯。
4. 使用 `firebase-messaging:25.1.0` 的 `getToken/onNewToken` registration-token 相容模式。雖 API 已 deprecated，server 的 HTTP v1 `message.token` 需要 registration token，不能換成 FID 或啟用 `firebase_messaging_installation_id_enabled=true`。
5. 實際 FCM 選擇須通過 package config、Google Play services、限時取得 token、FCM TLS host 可達性及 server bind；不根據地區、SIM、語言或 IP 推測。註冊最多等待 8 秒，FCM TLS connect/read 分別最多 3 秒；結果快取 10 分鐘，網路切換觸發有節流重試。這是能力/連線檢查，**不是 provider 端到端投遞證明**，OEM/Doze/防火牆仍可延遲或阻擋。
6. Android「背景存取」畫面於進入/手動重新整理時顯示橋接/回退原因（保持畫面開啟時可能暫時落後 runtime 狀態）；系統通知權限沿用既有 Android 13+ 流程。

## 驗證、綁定、生命週期

- `POST /notification-api/session`：原生 Discuz cookie、`X-Yamibo-Session: 1`、`{"site_id":"yamibo"}`；回傳 bearer、verified UID、Unix expiry。只有 exchange 送 cookie；其他 request 只送短效 bearer，所有 request 禁止 redirects，不記錄 credentials/body。
- 逐次核對 returned UID 與 native account。401/403 停止，待登入變更或新前景 session；網路/server 失敗以 0.5–60 秒指數退避及 jitter 重試。
- `POST /devices`：provider `fcm`、registration token，以及已持有的 installation ID/proof。回應省略 `binding_secret` 時保留舊 secret。token rotation 以同一 proof 更新；若 token 在背景輪替，待下一次前景重新驗證後綁定，不在背景繞過五分鐘政策。409 不丟 proof 重新搶佔，422（provider disabled）回退 SSE。
- bearer 只在記憶體。installation proof 用 AndroidKeyStore AES-GCM 加密後寫 `noBackupFilesDir`，不進 backup，也不在 mute/OFF/logout 時遺失。金鑰/檔案損壞、首次 bind 已成功但 response 遺失可能需要新 provider token 或後台人工恢復；不得放寬 server ownership proof。
- 單一 application-scoped coordinator，以 cancelAndJoin 序列化 Activity 前背景、設定及帳號變更；auth repository 在 native logout 前撤銷，包含過期驗證觸發的 logout。舊 UID/cookie 的 IO 結果不得通知新帳號。
- server session TTL **30–300 秒，裝置有效期等於 session expiry**，沒有 refresh endpoint。前景提前 20 秒重新 exchange+bind；背景停止 SSE/renewal，不延長資格、不用無限 foreground service，也不假裝 WorkManager 可維持常駐 socket。
- FCM→SSE 先嘗試 unbind；SSE→FCM 成功 bind 後停止 SSE。更新綁定成功後才 revoke 舊 session，避免撤銷剛續期的裝置。普通進背景不 revoke 目前 FCM session。
- OFF/mute/logout 立即阻止可控 delivery、取消/清理相應工作與通知，best-effort unbind+revoke；離線/逾時不宣稱遠端立即撤銷。最遲 server 原有效期失效；已经交給 provider 的通知另見限制。

## SSE 與通知內容

`GET /events` 用 bearer，不需要 device binding。支援 SSE event/id/data、CRLF、comment heartbeat、多行 data 與 32 KiB frame 上限。opaque cursor 僅接受 48 位小寫 hex，不解碼、不當 event ID；成功/終止處理後才前移。`resync` 清除本 UID cursor/dedup 並重新查目前狀態；身份改變隔離 cursor。首次先連 stream 再做安全同步，重連包含 EOF backoff。

只接受 `notification.upsert`、`pm.created`、`pm.announcement` 的 v1/site/schema。source ID 以 Long 驗證，視為 invalidation hint；不渲染 HTML、不跟任意 URL、不將 badge 加一、不以 source ID 打開私訊。只用既有 `fetchHomePage` 查未讀，**不背景 fetch notice/PM 列表**，因目前 Discuz plugin 那些路徑可能標成已讀。

FCM `onMessageReceived` 及 SSE 共用 Android runtime：帳號/cookie fence、notification permission、enabled/mute gate、持久 bounded `(site_id, uid, event_id)` 去重與短暫合併。同一程序所有 polling/bridge fetch、dedup 與計數共享 mutex；靜音/開啟通知與最後一次顯示檢查另共用不跨網路 IO 的短 posting fence，避免使用者已靜音/開啟後仍因較早的 fetch 補發。FCM signal 交給有網路限制與 bounded retries 的 WorkManager，避免 callback 返回後 process 被殺而丟掉未完成 IO。

Realtime **不套用也不消耗既有定期檢查每日次數**；每日上限只管 WorkManager polling。所有 App 可控 realtime delivery 仍遵守靜音。通知僅顯示通用文字，沿用 message channel、私密 lock-screen visibility、immutable PendingIntent 與消息中心導向。靜音 action 綁 UID，換帳號後舊 action 不會靜音新帳號。FCM background click 的 `event`/`signal` extras 只用來觸發重新驗證目前登入，成功後開目前帳號消息中心，絕不打開原帳號 source。

## 必須明確保留的限制

1. **嚴格背景靜音尚未成立。** 現 server 發 `notification + data`，Android OS 可在 app callback 前自動顯示通知，app 無法逐次套用 mute/dedup/permission 以外的 policy。程式已備妥 data-only callback 路徑，但正式接受「靜音就真的靜音」前，後端須改為 Android **data-only**，再驗證前景、背景、程序重啟及離線 mute；本次不改 server。已送給 provider/OS 的 mixed notification 無法撤回。server provider TTL 預設可達七天，不能把五分鐘資格誤當通知最多存活五分鐘。
2. **長時間離開 App 不保證即時通知。** server 最長五分鐘 eligibility、Android 背景限制及 SSE 前景限定是三個獨立限制。沒有 Google 的背景情境沿用定期檢查；WorkManager 的執行受 Doze/OEM/電量/網路影響，並非固定準點或即時 push。
3. 可達 probe/cached token/server bind 只代表選路資格；無法證明每則 FCM 已到達。Play services 存在但 FCM 下行被阻擋仍可能漏掉 realtime；polling 保留作安全網。
4. 現 payload 無 UID/generation，晚到 push 不能被證明屬於目前帳號。只允許目前帳號重新驗證後的通用查詢/導向；不能藉推播內容還原私訊，也不能聲稱本地 dedup 可攔住 OS 已渲染的 mixed 通知。
5. VPS、reverse proxy、server FCM provider credentials、配額/防火牆、真機/無 GMS 裝置、已接受通知的撤銷情境未實地驗證。必須在部署時完成下面驗收。

## 驗證與部署驗收

本地：`./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:testDebugUnitTest :composeApp:assembleDebug :composeApp:assembleRelease --console=plain`；另外檢查 `git diff --check`、只有此一新增 dev-doc、iOS/common source 不變。單元測試涵蓋同源拒絕、JSON 型別/Long、SSE/cursor/frame limit、退避/到期、account/site 去重、cooldown、quota 與 realtime 分離、mute/失敗保留重試等。實際完成結果見分支提交與交付摘要；不能把未跑階段寫成通過。

2026-10-02 最終固定 source snapshot 驗證：Android debug/release Kotlin 與 APK 組裝成功；debug APK 已驗證 v2 signature，release APK 為 unsigned（未使用/提交正式簽章）。所有 Android JVM 測試 **724/724 通過**：composeApp 261、shared 463，零 skipped/errors。通知相關 protocol 19、bridge contract 5、delivery policy 15、realtime 5、既有 Android notification contract 6 均通過。固定 snapshot 的 837 個 source/build inputs 在驗證過程 hash 未變，兩次 diff whitespace 檢查通過。

一次平行 APK 組裝 + tests 執行時，既有 `FavoriteRemovalFallbackTest.duplicateConfirmedFallbackSubmitsOnlyOneActiveLocalDelete` 的 1 秒等待逾時；不改相關程式，改為 test-only 單 worker 重跑後全數通過。這項時序不穩定另行揭露，沒有隱藏失敗。Release lintVital 執行時舊 AGP 8.11.2 內嵌 lint 對 repo 原有 Kotlin 2.4 metadata 印出相容性診斷；APK 組裝仍成功，不代表完整靜態分析已可靠覆蓋 Kotlin binaries。

完整 `:composeApp:lintDebug` **未通過**：3 個 `RememberReturnType` errors、27 warnings。errors 位於 `MainActivity` 的 `createChineseConversionRepository`、`DownloadRepositoryImpl` remember block 與 `SystemBarsEffect` registry remember；逐段對照 `addb0b5` 皆未改動（未另跑 baseline lint），且皆實際回傳非 Unit，疑與上述 metadata 不相容有關。本次沒有擴大範圍修 unrelated UI 或調 Kotlin/AGP 版本。新增 bridge 的 3 個 `StaticFieldLeak` warnings 已檢查：單例只保存 `value.applicationContext`，FCM helper 同樣只接收該 application context，未持有 Activity。沒有 notification lint errors；仍不宣稱 lint 全綠。

未跑/無法在現階段完成：iOS build（本次不改 iOS）、實機/模擬器 UI 與 lifecycle、真實後端 FCM/SSE、Doze/OEM 與 data-only 嚴格靜音驗收。後端尚未部署；單元/結構/並行 helper tests 不取代這些整合驗證。

部署後逐项測：正式 package GMS token/bind；無 GMS 與 Google 受阻 fallback；debug/run 明確 config fallback；provider-disabled 422；token rotation/proof 省略；401/403/409；session 30/300 秒 expiry；前背景/旋轉/切帳/logout 與 fetch race；SSE 斷線重放/resync；FCM/SSE/polling 重複；Android 13 權限拒絕；同日 mute/off 及隔日恢復；data-only 冷啟動 worker；mixed 舊通知點擊不得洩漏前帳號；代理 3xx、錯誤 MIME、超大 frame；Doze/OEM、離線與幾小時背景。

## 依據

- 通知 server `cec609f9aba633b1efa22f9cf1084d825f13f09b`：[client contract](https://github.com/Yamibo300/yamibo-notification-server/blob/cec609f9aba633b1efa22f9cf1084d825f13f09b/docs/clients.zh-TW.md)、[HTTP](https://github.com/Yamibo300/yamibo-notification-server/blob/cec609f9aba633b1efa22f9cf1084d825f13f09b/internal/server/http.go)、[binding](https://github.com/Yamibo300/yamibo-notification-server/blob/cec609f9aba633b1efa22f9cf1084d825f13f09b/internal/store/sessions.go)、[FCM](https://github.com/Yamibo300/yamibo-notification-server/blob/cec609f9aba633b1efa22f9cf1084d825f13f09b/internal/delivery/fcm.go)。
- Discuz plugin `85935611474682b47b76f043ef1548fb2405111d`：[notice read side effect](https://github.com/Yamibo300/yamibo-dicuz-plugins/blob/85935611474682b47b76f043ef1548fb2405111d/plugins/yami_restful_api/src/features/home/space/notice/endpoint.php)。
- 官方：[FCM Android](https://firebase.google.com/docs/cloud-messaging/android/get-started)、[receive/background notification behavior](https://firebase.google.com/docs/cloud-messaging/android/receive)、[token management](https://firebase.google.com/docs/cloud-messaging/manage-tokens)。
