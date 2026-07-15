# 下載系統測試表單

| ID | 功能 | 測試層級 | 驗證方式 | 預期結果 |
|---|---|---|---|---|
| D-01 | Download key 與路徑隔離 | Unit | 比對一般/作者模式 stable id | 不同模式不共用下載目錄 |
| D-02 | Manifest 與 page snapshot | Unit | encode/decode 後比對 | 欄位與 page 內容完整 |
| D-03 | 離線圖片映射 | Unit + Emulator | 關網後開已下載頁 | `PostImage` 與 HTML 圖片使用本地 URI |
| D-04 | 完整 Thread 下載 | Unit | 第 1 頁回傳 3 頁 | 佇列展開 1..3 頁 |
| D-05 | 最後頁 stale 標記 | Unit + UI | 檢查 manifest 與 Reader 底部 | 最後頁顯示刷新提示 |
| D-06 | 刷新成功覆蓋 | Unit + Emulator | 下載後重新刷新 | 新 snapshot 原子取代舊資料 |
| D-07 | 刷新失敗保留舊內容 | Unit | 模擬網路失敗 | 舊 manifest/page/images 可繼續讀 |
| D-08 | 清除目前頁 | Unit + UI | 清除單頁 | 只刪指定 key |
| D-09 | 清除完整 Thread | Unit + UI | 清除作者模式 Thread | 只刪相同 tid/authorId |
| D-10 | 佇列恢復 | Unit + Process restart | 以 Downloading queue 重建 repository | 轉回 Queued 並續跑 |
| D-11 | 背景下載 | Emulator | 下載中按 Home | foreground notification 存在且任務繼續 |
| D-12 | 未設定資料夾 | UI | 點下載 | snackbar 後進入備份設定 |
| D-13 | Updates 可刷新提示 | UI | 建立更新事件 | 已下載 Thread 顯示可刷新 |
| D-14 | Cache 隔離 | Unit + Emulator | 清除 app cache | 正式下載仍存在 |
| D-15 | 圖片原始 bytes | Unit | 比對 fetch bytes 與儲存 bytes | 不重新編碼、不失真 |
| D-16 | Android/iOS 編譯 | Build | Android、common metadata、iOS simulator | 全部成功 |
| D-17 | 排除最後頁下載 | Unit + UI | 三頁 Thread 選擇新選項 | 只下載第 1、2 頁 |
| D-18 | Catalog 下載狀態 | UI | 開啟已下載 Thread 目錄 | page header 顯示已下載或可刷新 |
| D-19 | 更新浮窗條件 | Unit + UI | 切換 Downloaded / UpdateAvailable | 只在最後頁且可更新時顯示 |
| D-20 | 更新浮窗關閉 | UI | 點擊浮窗 `×` | 本次 Reader session 隱藏提示 |
| D-21 | 最後頁底部刷新 | UI | 滑到最後頁內容末端 | 顯示重新整理最後一頁按鈕 |
| D-22 | 收藏批量下載範圍 | Unit + UI | 選取收藏項與集合後開啟下載 | 集合展開、重複項去重，dialog 顯示正確計數 |
| D-23 | 收藏批量下載映射 | Unit | 一般/小說/標籤/RSS 收藏各一項 | 走既有 full thread、tag all-pages、RSS all-pages enqueue API |
| D-24 | 收藏選取操作列 | UI | 窄螢幕進入多選並切換選取組合 | 動作列為兩排，按鈕不重疊，內容平滑下推 |
| D-25 | 收藏更新自動下載 | Unit + UI | 開啟偏好並建立 UpdateAvailable thread page | 只刷新 matching tid/author/page，不處理標籤/RSS 或非 UpdateAvailable 項 |
| D-26 | 下載內容重新載入自動更新 | Unit + UI | 開啟偏好後按下載內容管理重新載入 | 只刷新目前佇列中 UpdateAvailable 的下載項目 |

執行紀錄需填入日期、裝置序號、build variant、通過/失敗與證據路徑。

## 2026-06-26 執行紀錄

| 範圍 | 環境 | 結果 | 證據 |
|---|---|---|---|
| Shared / Compose unit tests | JVM debug/release | 通過 | `shared/build/reports/tests/`, `composeApp/build/reports/tests/` |
| Android compile/install | Pixel 8 Pro AVD, Android 16, `emulator-5554` | 通過 | `:composeApp:installDebug` |
| iOS compile | iOS Simulator Arm64 | 通過 | `:shared:compileKotlinIosSimulatorArm64`, `:composeApp:compileKotlinIosSimulatorArm64` |
| Profile / Download Queue | Pixel 8 Pro AVD | 通過 | `docs/qa/download-system/tmp-download-profile.png`, `docs/qa/download-system/tmp-download-queue-restored.png` |
| Page snapshot + original image | `tid=523359`, author mode | 通過 | `/sdcard/YamiboApp/YamiboDownloads/thread_523359_page_1_author_286861/` |
| Offline read after clearing Coil cache | Wi-Fi/data disabled | 通過 | `docs/qa/download-system/tmp-download-offline-reader.png`, `docs/qa/download-system/tmp-download-ui-offline-reader.xml` |
| Offline refresh failure preserves snapshot | Wi-Fi/data disabled | 通過 | snapshot file remained readable; crash log empty |
| Foreground service startup | Android 16 | 通過 | ActivityManager recorded allowed FGS start; channel `yamibo_downloads` exists |
