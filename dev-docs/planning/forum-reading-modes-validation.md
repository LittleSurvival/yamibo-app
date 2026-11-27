# 三種閱讀模式驗收

## 2026-11-28 進度

下載管理開啟閱讀器時只查目前章節與續讀章節。文字閱讀器首次打開目錄才建立樓層清單，捲動時依可見樓層範圍更新圖片預載。漫畫翻頁位移改在繪圖階段更新。點正文展開操作列的 Android 量測見下表，五項待驗收範圍仍列於文末。iOS 的下一個腳本為驗證分支的 `-42`，排定從 App 下載後離線續讀，本輪未執行。[Apple 驗證入口](../apple-emulator-validation.md)

## 已驗過

Android 三模式下載後的跨模式冷開和暖往返通過 12/12，留言與段落續讀複驗 4/4。iOS 預存下載包的跨模式續讀通過 6/6。另已驗過資料夾與備份復原、缺圖重載、圖片去重，以及兩平台長帖寫入與首圖開檔。[歷次功能驗收](https://github.com/LittleSurvival/yamibo-app/blob/3d5f66a61e98fa0f9e52cded7f2134b74c7ff8e9/dev-docs/planning/forum-reading-modes-validation.md) · [跨網址去重](https://github.com/LittleSurvival/yamibo-app/blob/fe6ff59f813eac92dba966e9bd3ad77d651a7bda/shared/src/androidUnitTest/kotlin/me/thenano/yamibo_app/repository/download/ReaderDownloadLifecycleTest.kt) · [iOS 寫入量測](https://github.com/LittleSurvival/yamibo-app/actions/runs/46049406969)

## 點正文展開操作列

Android releaseRun，60 Hz 模擬器，同機同帖配對，每個情境各一次。以錄影定位操作列開始出現至停穩，再從 SurfaceFlinger 取這段期間實際呈現的最長幀距。手指按下到動畫開始的等待另計。新版 APK 的 SHA-256 前八碼為 `e4351f42`。

| 點擊場景 | 展開至停穩（ms） | 動畫中最長幀距（ms） | App 錯過期限（幀） |
| --- | ---: | ---: | ---: |
| 小說正文，SwiftShader，首次 | 382.5 → 148.9 | 40.1 → 17.7 | 1 → 1 |
| 小說正文，SwiftShader，重開 | 390.0 → 148.0 | 32.5 → 17.8 | 2 → 1 |
| 智能漫畫，SwiftShader，首次 | 139.0 → 131.7 | 95.1 → 66.8 | 1 → 1 |
| 同一漫畫，RTX 4070 Ti SUPER，首次 | 100.1 → 100.1 | 18.1 → 20.3 | 0 → 0 |

淺色與「傳統黑」已核對文字操作列。可收回操作列、開目錄或設定並回到原樓。漫畫可開設定再回到同一張圖。

**目錄本身。** 文字樓層首次可辨由 305 降至 171 ms，動畫最長幀距 47 降至 32 ms。搜尋、清除、跳到第 2 樓與重開皆已驗過。漫畫章節首次可辨由 305 降至 289 ms，停穩仍約 338 ms，最長幀距 19 → 20 ms。已核對首次開在當前第 11 話，反轉排序後重開仍對準該話。

## 還缺哪些證據

| 範圍 | 剩下的日常操作 |
| --- | --- |
| Android 首屏與滑動 | 同機同帖與 tag 12 配對，從點帖量到正確正文實際畫出。首屏配對中位數容差 **+16.7 ms**，扣除請求後目標 **200 ms**，首滑端到端仍為 +1 ms。上輪真帖首屏 997.6 ms 含網路。進場、連滑（上輪最長幀距 44.4 ms）、開設定（31.5 ms）與本輪操作列的慢幀仍待驗收。 |
| 真站找帖、找話與找圖 | 從同作品的不同話進入，核對升降序、分話順序及作者在後續樓層／下一頁補的圖。登入後從搜尋點進正確 TID。同名作品／切帳號目前只有受控資料證據。 |
| iOS 下載後離線續讀 | 從 App 正常下載，清一般快取後飛航冷開。核對跨模式的文字段落、頁碼，以及從非作者留言切回時的位置。 |
| Android 冷啟 | 兩筆未結案的崩潰都發生在點帖前。ProfileInstaller 有正常冷啟紀錄，另一筆出現在無障礙服務讀取畫面座標時。 |
| 手機空間不足 | 下載更新中儲存空間用盡後，原有下載是否仍可讀，尚缺裝置證據。 |

受影響流程先跑一次，每版每情境最多四次。進場、拖曳、慣性、設定與目錄均看畫面實際呈現的幀。舊版驗證腳本會列出 +1 ms 的統計，首屏依新的 16.7 ms 門檻讀取配對差值。[量測腳本](https://github.com/LittleSurvival/yamibo-app/blob/465bdd89c3dbd30b604ac730d7d0c4888a016a79/.github/scripts/reader-validation/latency_contract.py)
