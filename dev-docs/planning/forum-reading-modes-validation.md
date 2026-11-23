# 三種閱讀模式測試與效能驗收計畫

這份計畫對應[下載與進度互通設計](forum-reading-modes.md)。目前已執行第一輪功能與效能抽驗。下方保留基線 `9b0e8d3`、各次候選版本和仍需執行的正式驗收。交付時要能回答三件事：讀者開到了哪裡、多久能開始讀，以及為了這次開啟實際送了哪些請求。

## 2026-11-23 第一版驗證報告

**本輪已在 Android 用同一份真站下載冷啟三種閱讀器。正式跨平台與效能驗收仍未完成。** 產品修訂為 `90c8780`。驗證分支為 `stable/v0.1.0/forum-reading-modes-d211f9a-9b0e8d3-validation`，測試與儀器位於該分支。下表逐項標出實際測到的版本。

| 項目 | 執行與結果 | 證據界線 |
| --- | --- | --- |
| Android 共用層與 repository | 產品 `90c8780`，驗證 `ada23db`。`:shared:testDebugUnitTest` 執行 20 項，失敗 0、跳過 0。`:composeApp:assembleDebug` 成功。涵蓋作者跨樓、同圖出現次序、跨模式錨點、請求合併與取消、資料庫、下載生命週期。 | JVM 測試使用實際 SQLite 與 CBZ。故障注入替換來源及儲存介面。 [測試修訂](https://github.com/LittleSurvival/yamibo-app/commit/ada23db) |
| Android 真站下載與離線開啟 | Pixel 8 Pro 模擬器、API 36。下載中文百合漫畫區一話，保存 31 張圖。開飛航模式，三次終止並重開 App，從「我的 → 下載管理 → 下載內容管理」分別開小說、論壇、智能漫畫。兩個文字畫面出現該帖內容，漫畫顯示 `1 / 31`。 | `7376834` 開發期間的裝置抽驗。未專門清空一般快取，也未同步記錄 transport 嘗試數。N01 仍待驗。 |
| Android 真站目錄 | 在 `90c8780` 的程式碼下，從中文百合漫畫區的《青梅竹马BIG LOVE》「发售图+贺图」進入，手動刷新後檢查升降冪。 | 詳見下方真站核對。 |
| 缺圖、刷新與大量下載 | 缺兩張中的一張時，任務回報失敗、正文及另一張可讀。重試只傳缺圖，再執行一次圖片傳輸為 0。有完整舊包時新版失敗，舊正文與封包 generation 保留。另以 10,000 筆無關下載作對照，開目標頁讀一筆頁面紀錄、列舉 0 次。明確開下載庫才列舉並得到 10,001 筆。 | 前者的計數來自注入的圖片 fetcher。後者驗證 repository 操作次數。N05 與 P04 仍需受控站和實際 UI 的完整證據。 [下載生命週期測試](https://github.com/LittleSurvival/yamibo-app/blob/ada23db/shared/src/androidUnitTest/kotlin/me/thenano/yamibo/yamibo_app/repository/download/ReaderDownloadLifecycleTest.kt) |
| Android 資料庫升級 | 從 schema 42 升到 43，51 筆文字紀錄與 13 筆圖片紀錄的摘要逐組一致，`quick_check` 回傳 `ok`。 | 此抽驗只涵蓋該次升級。schema 45 的全程升級、遷移中斷及兩裝置同步保留在 F13。 |
| iOS 原生 | `7376834` 第一次跑 9 項，有 1 項清除閱讀歷史失敗。修正測試，將新增作品與取得 row ID 放入和產品相同的寫入交易，每個測試使用獨立原生資料庫。9 項全部通過。後續 `ab39d4f` 也通過 9 項及 Compose 模擬器目標編譯。 | [首次失敗](https://github.com/LittleSurvival/yamibo-app/actions/runs/45064748370) · [同產品修訂重驗](https://github.com/LittleSurvival/yamibo-app/actions/runs/45065521200) · [缺圖修訂重驗](https://github.com/LittleSurvival/yamibo-app/actions/runs/45065738858) |

最終產品 `90c8780` 對應驗證修訂 `ada23db`。Apple runner 的 XML 記錄 9 項原生測試全部通過，無失敗、錯誤或跳過，Compose iOS Simulator 目標編譯成功。 [最終原生測試與 artifact](https://github.com/LittleSurvival/yamibo-app/actions/runs/45066364328)

**iPhone UI 實跑 0／3 通過。** 驗證修訂 `f4a2d8f` 搭配產品 `7376834`，在獨立模擬器的下載目錄放入作者 7、TID 140 的三樓三圖樣本。三個 XCTest 均實際執行，失敗 3、跳過 0。 [XCTest 執行、畫面附件與 .xcresult](https://github.com/LittleSurvival/yamibo-app/actions/runs/45065012620)

| iPhone 入口 | 預期 | 實際結果與重驗條件 |
| --- | --- | --- |
| 智能漫畫 | 從「我的」開下載項目，看見第一張視覺標記並翻到第二張。 | 啟動更新對話框顯示「發現新版本／稍後」，XCTest 找不到「我的」。首圖與翻頁均未執行。下輪需處理這個啟動狀態後重跑。 |
| 論壇 | 初次開下載時顯示第一樓 `READER_FLOOR_1`。 | 成功進入閱讀器，失敗時 UI 樹有 `3#`、`READER_FLOOR_3` 及 `1/1 100%`。第一樓位置斷言失敗。 |
| 小說 | 初次開同一作者下載時顯示 `READER_FLOOR_1`。 | 成功進入小說閱讀器，UI 樹同樣顯示第三樓及頁尾。需逐案重置閱讀歷史，對照恢復前的位置及第一個可見樓層，才能區分首次定位與前案留下的進度。 |

這三個結果保留為 `7376834` 的歷史紀錄。後續使用相同樣本、逐案重裝 App 的對照結果見下節。

### iPhone first-open regression

Each case installs a fresh app on a dedicated iPhone Simulator and seeds the same author-scoped page (TID 140, author 7, PIDs 1401–1403). XCTest opens Profile → Downloads → Downloaded content, enters the page and dismisses the in-app update prompt when it appears. Forum and Novel require the first visible floor marker to settle on floor 1 without a scroll gesture. Smart Comic must paint the first image, advance from `1 / 3` to `2 / 3` after one swipe, and paint the second image in its distinct fixture color. The image URLs use an unresolvable fixture host. The simulator stays connected for startup services. These cases do not measure network attempts.

The control used product `90c8780` and harness `8489336c`. With the iOS system appearance set to light, Forum and Novel opened at floor 3. With the system appearance set to dark, Forum opened at floor 3 and Novel opened at floor 1. Both Smart Comic cases passed. All three text failures showed page-end progress of 100% after a clean installation. Both system appearances used the same cream Yamibo chooser surface. [Control run and screenshots](https://github.com/LittleSurvival/yamibo-app/actions/runs/45157022982)

Product `d10f7dca` keeps the end-of-page controls out of the lazy list until it can display a body entry. Previously the keyed refresh action could be measured first while the posts were being parsed. When body entries appeared above that action, the list retained its position near the end. With the same harness and fixture, all six cases passed on validation revision `c191ca1b`; the two text readers started at floor 1 in every case. The first attempt timed out while starting the simulator appearance service, before any tests ran. [Passing repeat on the same revision](https://github.com/LittleSurvival/yamibo-app/actions/runs/45157096225/attempts/2) · [Startup timeout](https://github.com/LittleSurvival/yamibo-app/actions/runs/45157096225/attempts/1)

The candidate passed 20/20 Android shared tests, built the Android debug APK, passed 9/9 iOS native tests and compiled the Compose iOS Simulator target. The Android build used the validation checkout's debug signing because the primary checkout points to a missing local signing key. [Apple native run and XML](https://github.com/LittleSurvival/yamibo-app/actions/runs/45157149539)

The final UI run used the same product code and harness `fe36880a`. Each test checked the rendered chooser surface against the selected Yamibo palette before opening the reader. All six passed on the simulator. [XCTest results and screenshots](https://github.com/LittleSurvival/yamibo-app/actions/runs/45164053906)

| Built-in Yamibo palette | Forum | Novel | Smart Comic |
| --- | --- | --- | --- |
| Default (cream) | First visible marker at floor 1 | First visible marker at floor 1 | First and second local images painted in order |
| Default Dark | First visible marker at floor 1 | First visible marker at floor 1 | First and second local images painted in order |

These are first opens of one complete download. Resume from saved progress, incomplete downloads and cross-source selection have separate acceptance cases.

### 真站核對

《青梅竹马BIG LOVE》的升冪清單有 8 個合刊，依序為 1–6、7–17、18–28、29–40、41–46、47–60、61–67、68–80，接著是附贈與賀圖，共 10 帖。刷新前後的 10 個標題逐項一致，降冪恰為倒序。另從賀圖（7 張）點進第 68–80 話（34 張），畫面顯示 `1 / 34`，目錄將兩筆進度分別列在各自的話。其他作品的歷史樣本仍需重抓核對，尤其是 58 話跨搜尋頁和同一作者在第三樓續貼的真帖。

### Android 捲動抽驗

固定帖 TID 572407，Pixel 8 Pro 模擬器 1344 × 2992。兩版皆為獨立套件的非 debug `releaseRun`。從首頁搜尋完整 URL 進帖，各暖身 1 次、交錯各 7 輪，每輪 12 次相同捲動。原腳本預期標題含 `v0.0.3` 及 `1#`，首次暖身無法識別更新後的帖，留下失敗紀錄。重測保留同一 TID，核對實際標題 `v0.0.10`、首樓作者、正文開頭及頁面進度。

| 每輪量值的中位數 | 基線 `9b0e8d3` | 候選 `7376834` | 差額 |
| --- | ---: | ---: | ---: |
| 首頁啟動、搜尋、進帖至 UI 輪詢辨識 | 10,971 ms | 10,643 ms | −328 ms |
| 捲動幀耗時 p95 | 48 ms | 48 ms | 0 ms |
| 卡頓幀比例 | 9.44% | 9.30% | −0.14 個百分點 |

每格依序列基線與候選，保留所有計分輪。

| 輪次 | 幀數 | p95（ms） | 卡頓比例 |
| --- | ---: | ---: | ---: |
| 1 | 332 / 317 | 48 / 57 | 11.75% / 16.4% |
| 2 | 275 / 335 | 48 / 57 | 13.82% / 15.52% |
| 3 | 339 / 344 | 48 / 48 | 9.44% / 9.3% |
| 4 | 331 / 328 | 48 / 53 | 14.5% / 15.24% |
| 5 | 354 / 362 | 23 / 30 | 1.41% / 1.38% |
| 6 | 355 / 361 | 22 / 30 | 0.85% / 1.39% |
| 7 | 357 / 360 | 27 / 31 | 0.56% / 0.83% |

候選的捲動 p95 在 7 組配對中有 6 組較慢，差距 4–9 ms，另一組持平。配對差額中位數為 +7 ms。兩版本各自取中位數皆為 48 ms 會掩蓋這個結果。早晚輪的卡頓比例波動大，部分樣本與主機建置重疊，需在固定負載下重測與歸因。導航時間包含啟動、輸入搜尋與 UI dump 輪詢。正式 T0→T1 首屏、T2 手勢、A/A 噪音檢查、每情境 40 輪及 iOS 效能均待執行。

### 驗收缺口與重現

在本輪正式清單中，已附上完整預期、實際和逐項證據的 Android「F14／live-big-love」通過。覆蓋稽核為 **1／382**，因其餘列缺少完整驗收證據而以結束碼 1 返回。上表的單元測試、部分離線操作及捲動抽樣另列。沒有把一次抽樣換算成多個方向或平台通過。

- F01–F06 的 24 條跨平台定向結果仍待逐條對位。下載實體共享與最後引用回收、刷新中斷、舊版 AppSync 互通、兩個主題的缺檔詳情與重試，尚待完成或補驗。
- N01–N08 需要實際 App 的論壇、Coil 和下載 transport 與受控伺服器雙端紀錄。驗證器在本機 HTTP 對照通過 2 個合法案例，並識別了重複請求、漏記與未宣告跳轉 3 個錯誤。全離線零嘗試、429 的重試間隔與首屏請求優先序仍待實測。
- P01–P04 需要正確呈現幀、輸入、網路與快取狀態一致的兩平台樣本，並量測 1,000 章與 10,000 筆下載對首屏的實際影響。下方固定門檻繼續適用。
- 在驗證分支執行 `./gradlew :shared:testDebugUnitTest :composeApp:assembleDebug` 可重跑 Android 測試。用 `python .github/scripts/reader-validation/coverage.py template <csv>` 建立 382 列案例清單，`audit <csv>` 逐列要求預期、實際及證據。Apple 以各執行連結的修訂和 artifact 為準。 [驗證分支](https://github.com/LittleSurvival/yamibo-app/tree/stable/v0.1.0/forum-reading-modes-d211f9a-9b0e8d3-validation) · [Apple 操作說明](../apple-emulator-validation.md)

## 先固定資料與觀察方式

使用可逐次重設的測試帳號、下載目錄、資料庫與受控來源站。下表是要建立的 fixture 規格。為每份輸入手寫預期 PID、作者、分頁、段落、圖序、內容雜湊及請求清單。預期值獨立於產品 parser；UI 測試必須從正常入口操作。真實論壇保留作最後的格式與行為抽驗。

| 資料 | 內容和用途 |
| --- | --- |
| A．分頁與圖序 | TID 140，作者 7 的樓層 PID 1401、1402、1403 分布在「全帖第 1、2、3 頁」，在「只看作者第 1 頁」可見，中間穿插作者 8 的留言。1402 的第 3 張有唯一視覺標記；放入跨樓重複圖、同樓二次出現的圖、懶載入、表情圖、缺檔。另備純文字帖、目標 PID 被刪及首樓無圖的變體。 |
| B．目錄 | 單話、58 話三頁、千話合成目錄；含合刊、附錄、重複 TID、同名不同作者、改名、加入更早話數。固定鐘測快取壽命 2 小時的前 1 毫秒、剛到期、到期後。 |
| C．下載與升級 | 同一發行版產生的全帖副本、作者副本、只有圖的舊 RSS／標籤包、七成完成的新包；保留一份未遷移快照。另備損壞封包、儲存資料夾失效、空間不足、兩個來源共用檔案及第二個帳號。 |
| D．效能回放 | 一般樣本用 50 樓、20 張 1080×1600 圖，固定正文傳輸大小約 64 KiB、每張 JPEG 約 256 KiB，以相同檔案雜湊回放。另設 1,000 章目錄和含 10,000 筆無關下載的裝置資料庫。冷網路對照固定 RTT 100 ms、10 Mbit/s、無隨機失敗；慢目錄案例單獨阻擋目錄 5 秒。列出首屏實際需讀的圖片與必須返回的資料。 |

每輪明確分開程序狀態（新程序／存活程序）、記憶體、磁碟頁面與圖片 cache、持久下載、目錄時效及網路。測下載時用空的一般 cache，測 cache 時用空的下載目錄。從板塊進入的「冷」代表該 reader 在此程序首次開啟；另從下載管理重啟 app 測離線冷啟。各版本回復等價快照，先檢查命中來源再採樣。

## 功能與資料一致性

**F01 至 F06 每一列各跑兩條路徑。** ① 從來源模式下載，清一般 cache，終止程序，飛航冷啟後從下載管理切到目標模式。② 在來源模式停於指定錨點，切到目標再切回，驗證畫面、持久紀錄和所屬作者。每列在 Android 和 iOS 各執行；同模式重開另作對照。

| ID | 來源 → 目標 | 必查結果 |
| --- | --- | --- |
| F01 | 智能漫畫 → 小說 | 新下載能顯示作者正文；在 1402 第 3 張停留，小說定位到該樓同圖。 |
| F02 | 小說 → 智能漫畫 | 讀到作者 1402 段落，只能對到樓層時，漫畫開該樓第一張；切回仍能還原小說段內位置。 |
| F03 | 智能漫畫 → 論壇 | 定位到全帖第 2 頁的 1402；手上只有作者副本時，仍可讀已存樓層，不能把缺少的留言標成已下載。 |
| F04 | 論壇 → 智能漫畫 | 全帖下載可提供同一原圖；論壇停在作者 8 的留言時，漫畫保留原漫畫位置，切回仍到該留言。 |
| F05 | 小說 → 論壇 | 作者第 1 頁的 1403 在全帖是第 3 頁；兩邊對到同一 PID 與段落。 |
| F06 | 論壇 → 小說 | 在全帖第 3 頁的 1403 切到只看作者，驗證作者範圍與段落；另測純文字帖及不屬於該作者的樓層。 |

| ID | 操作與故障注入 | 通過條件 |
| --- | --- | --- |
| F07 入口 | 由板塊、搜尋、收藏、歷史、RSS、標籤、指定樓層連結、下載管理進入；設定兩板不同預設後重啟。 | 明確點選的 TID／PID、下載作者範圍及既有入口語意正確；只有「繼續閱讀」可跳到作品上次所在話。小說詳情與按「開始／繼續」皆納入。 |
| F08 進度與競態 | 在 2 秒內翻頁、切話、返回、進背景；安排舊寫入最後完成；停在第二樓第 3 張後重啟。另測單頁、連續捲動、上下話、回看與手動未讀。 | 新動作勝出，TID、PID、圖序／段內位置正確。重組、目錄倒序和刷新不寫入一筆新進度。讀完漫畫不會清掉未讀留言。程序強停分別在已確認落盤前後測；未提交的新位置與已提交的舊位置不能混寫。 |
| F09 章節邊界 | 逐樓比對 A 的線上、離線圖序，讀到第三個作者樓及跨頁續貼；移除或重排一張圖後重開。 | 圖片顯示次數、所屬 PID、同圖出現次序與黃金清單一致；表情排除，缺圖占位可重試。無唯一對應就回到可辨識樓層，保留原精確紀錄。 |
| F10 目錄與權限 | B 的快取命中、過期、失效重試、分頁重複／中斷；在搜尋中切帳號、點同名作品；資料更新時倒序和切話。 | 部分結果不覆蓋完整目錄或冒充整部；未授權資料不顯示；已讀、已下載及目前章均依內容身分保留。 |
| F11 下載生命週期 | 本頁／整帖、本話／選話／整部、重複排隊；RSS、標籤及智能入口同時下載同一張。逐一取消、重試、只刪任務、刪一個來源、刪最後引用。 | 缺項與完整狀態正確；已核實相同的資源只傳輸一份；最後引用移除才釋放實體，容量不重複計算；刪下載保留進度。 |
| F12 失敗及恢復 | 斷網、403／登入失效、429、5xx、超時、資料夾失效、空間不足；下載刷新在寫新包與切換引用之間中止並重啟。 | 無無限重試；仍可讀最後完整副本，指出缺項與失敗原因；權限失效不借用他人副本。恢復後只補缺項，重複執行結果相同。 |
| F13 升級與同步 | C 從舊版升級，在遷移中途停止再執行；Android／iOS 分別寫入與還原；操作清歷史、刪下載、另一裝置上線及舊版同步。 | 原入口能開舊包；普通／小說及作者範圍不混合。清歷史後不復活、離線內容仍在；遠端進度不代表此裝置有檔案。逐項註記舊格式能還原的精度。 |
| F14 UI 與真站 | 預設與深色主題開啟選擇、部分可讀、失敗詳情及重試。真站在不同話開《DOUBLE HELIX BLOSSOM》與《青梅竹马BIG LOVE》，再核對小數話及分段排序。 | 實際操作按鈕與渲染內容；保存當時分頁清單與手動核對結果。真站遭限流、登入過期或資料變動時如實記錄，不把歷史話數當成當天真值。 |

## 網路請求契約

在驗證版本同時觀察 repository 的載入意圖、論壇 HTTP client、Coil／圖片下載 transport，以及受控伺服器實收的請求。每列記錄開帖前已有的 in-flight、首屏前、首屏後到工作結束，並續觀察 5 秒。未結束的任務記 timeout。背景 AppSync、頭像等另列原因及流量，任何無法歸因的 reader 請求都要查明。記錄方法、匿名化資源鍵、作者範圍、頁、觸發點、狀態、位元組及耗時，不保存 cookie、formhash 或帶憑證的完整網址。

表中「次」指受控端實際 HTTP attempt。快取命中、檔案讀取、repository 呼叫與網路 attempt 各有獨立欄位。正常 fixture 的成功回應無 redirect；另設 redirect、304、重試案例，每一跳、HEAD 與失敗 attempt 都計數。飛航模式額外查出站呼叫嘗試，避免伺服器收不到封包就誤判為零。可預期的握手、翻頁和重試須在執行前列入該 fixture 的清單。

| ID | 設定與動作 | 請求預算與順序 |
| --- | --- | --- |
| N01 | 下載管理開完整本機副本，三模式冷啟；對照組開網但無主動刷新。 | 兩組 reader 的正文、圖片、查 PID、人物驗證、搜尋與 CDN 探測皆 **0**。在開網組檢查本機優先，在飛航組檢查無嘗試。 |
| N02 | 智能漫畫正文、圖片、有效目錄皆已命中。重開、開關目錄、倒序、旋轉或重組。 | 以上各組皆 **0**。邊界 2 小時前仍命中；到期後轉 N04。單純操作目錄不可重新抓當前帖。 |
| N03 | 冷開 A 的指定頁。預設位置及目標 PID 查頁各用獨立 fixture。 | 已知頁僅該 TID／頁／範圍 **1** 次，每張必需首屏圖 **1** 次。若來源協定需先查 PID，僅允許預列的一次定位及必要的目標頁；定位已帶有效正文則目標頁再抓 **0**。禁止先載無關第 1 頁。 |
| N04 | 正文及首圖已在本機，漫畫目錄過期；正常開啟、暫停搜尋 5 秒、開兩個相同消費者。 | 首屏依賴的外送請求 **0**。需更新的同一帳號／板塊／作品只建立一輪目錄刷新，登入有效時每輪至多一次既有 formhash 更新流程，搜尋首結果和每個必要後頁各一次。5 秒內能讀、翻頁和返回；取消一個消費者不重啟另一份查詢。 |
| N05 | 只缺一張圖、只缺一頁文字、或舊漫畫包缺正文。分別執行補下載，再執行一次。 | 各自只取預列的缺項，每個一次；其他已驗證資源 **0**。第二次補齊後同一組請求 **0**。 |
| N06 | 小說「板塊 → 詳情 → 繼續閱讀」，測下載已齊、新鮮頁及需更新頁。 | 前兩組正文 **0**；需更新時相同頁／作者至多 **1** 次，詳情與 reader 共用完成或進行中的結果。主動刷新另開觀察窗，該頁只更新一次。 |
| N07 | 缺圖冷開，UI 顯示與 Coil 預載同時要求當前圖；急切 A→B→A、返回、切帳號。 | 同安全範圍內同一個在途資源只傳一次；已存圖再次開啟 **0**。固定單頁 fixture 僅允許首圖及預定相鄰圖，首圖優先，滾動模式以可見窗另列。離開後不再排舊頁，新頁不被晚回應覆寫；不同帳號不可錯誤合併。 |
| N08 | 正文已載，重按刷新／重試。受控端分別回一般權限 403、429（Retry-After 5 秒）或持續不回應；另測合法重新登入後重試。 | 一般權限 403 首次 1 次，靜置重送 0；429 的 5 秒內重送 0，允許重試後至多 1 次；無回應時多次點擊只保留 1 個在途請求。取消後不排新工作；恢復後一次使用者重試至多 1 輪。獨立記錄登入／WAF 挑戰的合法往返，以免掩蓋重送正文。 |

另跑兩個導致「請求數看似正常，實際很慢」的案例。讓舊話的預載占滿受控端連線，再開新話，驗證新首圖在釋放舊預載前可請求並繪出；把單張圖變成大檔，驗證篩選、倒序及切背景不會重新傳輸該圖。每個請求記排隊、首次位元組、下載完成與傳輸量，檢查重複傳送和首圖優先順序。

計數器要先通過負向對照：驗證工具故意多送一次相同資源、停用圖片 transport 計數、讓伺服器回 redirect 各一次，確認重複、遺漏及跳轉都能被發現。若兩端計數無法解釋，該輪不可用來證明「零請求」。

## Reader 首屏與卡頓

**計時點。** T0 是使用者在板塊／下載管理點帖或在詳情按「繼續」的單調時鐘時間。T1 是首個已呈現且位置正確的內容幀，文字比對 PID、段落及可見字樣，漫畫比對目標圖片的視覺標記已實際繪出。T1 後立即注入固定手勢，另記手勢開始和預期畫面變化首次呈現的 T2。報告 T1−T0、T2−T1 與手勢自身延遲，若測試端未能按時注入就保留為儀器失敗。另記目錄可用時間、上一幀至下一幀的間距、主執行緒工作、HTTP waterfall。小說另計「點帖 → 詳情可操作」及兩段總和，扣除測試手勢間人為停留。使用同時鐘的輸入與呈現事件，截圖比對在量測後進行，避免 UI dump 輪詢影響數值。

**基線已看到的程式路徑。** 智能漫畫初始組目錄在 Compose 的 `remember` 內解析和排序；過期目錄經登入狀態刷新、搜尋和分頁。圖片單頁閱讀會將目前頁和相鄰頁加入預載。小說詳情在顯示本機資料後仍呼叫 `fetchThread`。這些路徑適合下 trace，實際耗時與有無重複傳輸要由下列測試判定。 [圖片閱讀器](../../composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/thread/reader/ImagesReaderScreen.kt) · [目錄](../../shared/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/repository/forum/SmartComicCatalog.kt) · [小說詳情](../../composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/thread/detail/novel/NovelThreadDetailScreen.kt)

以下是**提議在首輪基線前固定的驗收值**，單位為毫秒。適用於同平台、同裝置設定、同非 debug 組態與 D 的固定資料。每個模式分開報告，保留未達標的基線。若需改門檻，要記錄理由及新舊兩套判定。

| ID | 負載 | 驗收 |
| --- | --- | --- |
| P01 | 存活程序從相同入口重開，全本機；另測程序重啟後首次從下載管理開啟。 | T1 暖開 p50 ≤350、p95 ≤650；新程序首次開 reader p50 ≤650、p95 ≤1,100。都須通過 N01。 |
| P02 | 固定網路冷開，逐模式量到目標樓／圖；另將目錄或不影響當頁的端點掛住。 | 正常受控網路 T1 p95 ≤2,200；目錄被掛住時必須在釋放它前達到 T1、T2。對照同內容快回應，掛住時 T1 p95 增量 ≤100。 |
| P03 | 60 Hz 同裝置，自點帖到首個操作，再做固定翻頁／捲動。分開統計進場與操作區間。 | 本機 T2−T1 p95 ≤100；每段呈現幀間距 p95 ≤33.4，無超過 100 的使用者可感停頓；固定捲動的掉幀率相對基線增加 ≤2 個百分點。亦列 16.7／33.4／50／100 ms 分布、總幀數與 deadline misses；使用有輸入或動畫的區間，靜止時間不算掉幀。 |
| P04 | 同一可見頁，依次使用 58／1,000 章目錄、0／10,000 筆無關下載；冷開後立即切話、存進度、查看並倒序目錄。 | 相對小集合 T1 p95 增量 ≤100；首屏前對無關封包的開檔及逐項檢查 **0**。追蹤 SQL、檔案、HTML／標題解析、Compose、主線程與圖片解碼；目錄刷新、重新索引與存檔不可阻塞第一次操作。 |

每個可比較的 P01／P02 場景另設回歸線：候選 p50、p95 各自增量 ≤ `max(50 ms, 基線相同分位 × 10%)`，並同時符合絕對門檻。新增模式間切換無舊版等價入口時只判絕對門檻、功能和請求預算；另外量相同內容的舊入口作參考。舊版若已超標，改善幅度和剩餘差距都要列出。

同一進程連續開關 reader 30 次，交替開大型漫畫與純文字帖。記錄第 1、10、30 次的首屏、記憶體峰值與返回後閒置時的存活 reader、解碼任務及網路工作。已退出畫面殘留 observer、持續增加的未完成任務、OOM 或第 30 次已超過同類暖開門檻都要列為失敗；PSS／RSS 受圖片快取影響，需以物件與任務證據判斷原因。

## 執行、歸因與交付

1. **建立可重現基線。** 紀錄基線、候選及驗證提交，OS、硬體／模擬器、刷新率、電量／熱狀態、build variant、帳號狀態、fixture 雜湊、網路及快取重設步驟。固定 D 的裝置 profile，先讓兩版都到相同入口。用 A/A 同版重跑檢查儀器噪音；噪音大到足以翻轉判定則先修量測，保留該次資料。
2. **分層執行。** 共用層以固定時鐘和可控制的排程驗證錨點、轉換、索引、取消與同步。repository 整合測試以真實資料庫／檔案和受控 HTTP 站重跑 F08–F13、N01–N08；Android 與 iOS 各由正常 UI 執行 F01–F14 及請求、效能關鍵場景。各平台獨立判定。真站驗證目錄分頁、登入與圖片格式，固定回放負責數字回歸，避免站點當天延遲污染結論。
3. **量首屏與互動。** 每條效能路徑每版先做一次不計分的工具暖身，再依案例復原快照。交錯版本次序，7 輪先找明顯異常；正式每版每情境 40 次，以排序後第 38 筆作 p95。保存每輪、p50、p95、最大值、差多少毫秒與百分比。Android 依專案既有方法錄 Perfetto／FrameTimeline、gfxinfo；固定 reader 滾動腳本只作既有路徑對照，板塊點帖另量。iOS 對應輸入、呈現與 signpost，用 Instruments 對齊主執行緒與卡頓。模擬器及實機數字分開，對外宣稱實機流暢度前須有相應實機樣本。 [Android 量測方法](../../.agents/skills/compare-android-performance/SKILL.md) · [Apple 驗證](../apple-emulator-validation.md)
4. **定位失敗。** 隔離 API、圖片、目錄、歷史查詢、封包查找，各自注入延遲。若 T1 緊隨不相關工作完成，檢查 await、共享鎖或執行緒爭用；若 N06/N07 超額，比對資源鍵、入口、顯示、預載與重試的發起者。保留首個失敗 trace 和修正後同條件結果，回歸相鄰模式。
5. **驗收記錄。** 逐案例與平台填 `ID／資料版本／步驟／預期／實際／PASS、FAIL、BLOCKED 或未測／證據`。附請求明細、原始逐輪 CSV、首屏截圖或錄影、trace、DB 與 manifest 前後差異和重現方式。錯位置、掉進度、跨帳號、誤報完整、重複或未核准請求、超過效能門檻、必要場景缺證，均不能標示整體通過。超時和崩潰留在分母，另列完成率；不以重跑或排除慢樣本換取通過。

執行前展開預期清單，以案例、子情境、平台及模式為鍵。僅 F01–F06 就有 6 個方向 × 2 條路徑 × 2 平台，共 24 個必要結果，另加同模式對照。自動比對預期清單與實收結果；漏列、重複列、零個測試或只有成功截圖都不算完成。平台無法取得或資料不可比時，保留該列及阻礙原因。

測試來源、fixtures、QA 啟動和蒐證程式依規範留在驗證分支；本地原始證據放在 `.tmp/qa/`，交付時列明驗證分支、命令與 Actions artifact。 [測試隔離規範](../test-isolation.md)
