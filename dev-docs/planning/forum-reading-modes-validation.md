# 三種閱讀模式測試與效能驗收計畫

本報告分別記錄讀者開到的樓層／圖片、從點擊到正文出現的時間，以及為該次開啟送出的請求。各輪均保留產品來源、測試 APK、通過格數與失敗紀錄。功能基線為 `9b0e8d3`，本輪載入效能的基線為 tag `12`。[下載與進度互通設計](forum-reading-modes.md) · [11 月 25 日下午的版本與結果](#11-月-25-日下午-android-驗證)

## 功能分支交付狀態

產品提交 `e953b60` 包含三種閱讀模式、共用離線儲存及閱讀進度修正。交付時逐一核對 56 個產品檔案，與最後建置快照 `87879e95` 的差異 SHA-256 相同。下表的八位快照識別碼均指該輪產品差異，實際建置和畫面驗證版本各列於對應案例。

| 範圍 | 結果和驗收界線 |
| --- | --- |
| Android 下載與續讀 | `24287161` 從真實下載入口完成六方向飛航冷開及六方向暖往返，共 12/12。`4ddeb2ee` 另驗論壇與漫畫之間的留言續讀、小說與論壇之間的段落續讀，冷開及往返共 4/4。缺少相鄰下載頁時，直接返回保留原進度，手動滑到另一樓後保存新進度。 |
| Android 本機相容性 | `87879e95` 的 79 項 JVM 測試全數通過。正式產品 APK 在下載資料夾失去授權或被搬移後可重新選取；現版及 tag 12 各自建立的本機備份，均由正式產品 APK 在空安裝匯入並重啟核對。較早的雙主題圖卡、舊 CBZ 及實際按鈕重抓另見逐案紀錄。[Android 驗證詳表](#11-月-25-日第二輪-android-功能驗收) |
| Android 效能 | 最近一次正式對照使用 `51b5a34f` 與 tag 12。40 對有 39 對完整，候選第 12 對在點帖前崩潰。配對 p50 的點帖到正確正文增加 81.8 ms，到首滑可見反應增加 75.7 ms，超過 +1 ms 門檻。最後產品快照仍待同一規格重測，P01–P04 全矩陣亦待驗。 |
| 真站與失敗恢復 | 11 月 25 日兩部指定漫畫的公開帖頁分別回應 403，當日跨頁、漏章與排序仍待真站資料。兩起偶發 Android 啟動崩潰的根因及修復仍待確認。 |
| iOS 與 AppSync | 本次交付快照已通過 iOS 模擬器編譯、原生測試、下載首次開啟、預存下載包跨模式續讀及單圖修復。實際下載後的飛航冷開與效能仍待驗。[11 月 26 日驗證紀錄](#2026-11-26-ios-驗證)既有兩台 Android 對受控同步服務的結果保留於 F13。雲端同步功能的停用及其交付由另一分支處理。 |

## 2026-11-26 iOS 驗證

以下各次 Apple Actions 使用產品提交 `e953b60d` 的程式碼。表中提交指實際執行的驗證分支版本。

| 案例 | 通過結果 | 驗證提交與證據 |
| --- | --- | --- |
| 原生測試與編譯 | 11/11，無跳過。實際備份檔還原至全新 SQLite 資料庫後，文字與圖片位置一致；另驗過期及同時間戳的舊格式進度重播。Compose iOS Simulator 目標編譯成功。 | [`acacd3a9` 原生測試 XML](https://github.com/LittleSurvival/yamibo-app/actions/runs/45756399154) |
| 下載首次開啟與遷移 | 6/6。論壇、小說、智能漫畫各在 Yamibo 預設與深色配色開啟一次，並核對舊下載遷移後的正文、圖片雜湊及缺圖數。 | [`dabb9fef` XCTest 與截圖](https://github.com/LittleSurvival/yamibo-app/actions/runs/45756074353) |
| 跨模式續讀 | 6/6。三種模式兩兩切換、返回、終止 App 後重開，均找回 PID 1402 的指定圖片。使用預先放入的下載包；從 App 實際下載後再飛航冷開仍待驗。 | [`0fea754b` 六方向重驗](https://github.com/LittleSurvival/yamibo-app/actions/runs/45759795983) |
| 缺圖手動重載 | 2/2。兩種配色各點一次「重新載入」，受控伺服器各收到一次缺失圖片的 GET。翻頁返回後圖片仍可顯示，儲存檔雜湊吻合，缺圖數歸零。 | [`bf862e55` XCTest 與請求紀錄](https://github.com/LittleSurvival/yamibo-app/actions/runs/45756523298) |

跨模式首輪為 5/6。「漫畫 → 小說」在進入閱讀器前點到「下載管理」列內的圖示。測試改點整列按鈕，確認開啟下載管理後，以相同的位置斷言重跑全部六個方向。[首輪執行紀錄](https://github.com/LittleSurvival/yamibo-app/actions/runs/45756272611)

## 2026-11-25 Android 修復與驗收腳本

本輪產品以 `fbb5048` 的本機工作樹為基礎。測試程式修改於相鄰的 `yamibo-reader-qa` 驗證工作樹，以 `466afe3` 為基礎。兩個提交編號都早於本輪修改。以下命令在驗證工作樹執行，輸出目錄放在系統暫存區。

| 驗證對象 | 方法與已觀察行為 |
| --- | --- |
| CBZ、下載與更新 | Android JVM 的下載生命週期套件使用 SQLite、ZIP 與實際檔案。模擬遷移發佈前後中斷、補圖中途刪除、切換帳號或資料夾後返回。重建 repository 後檢查正文、缺圖數、影像內容、錨點與發送次數。這些中斷由測具注入。 |
| 三入口的實體下載 | 同一批測資有 5 個圖片位置、3 個相異 PNG。每個入口各送出 1 次正文和 3 次圖片 GET。論壇存 4 個 PID，正文 ZIP 為 6,212 B。小說及智能漫畫各存 3 個 PID，正文 ZIP 為 4,849 B。三個入口各自存的圖片均為 17,140 B。從已完成的智能漫畫下載補整串論壇，多存 1 個回覆 PID，新增圖片檔數與圖片 GET 均為 0。 |
| 已下載內容的請求 | N01 在 Android 真正開啟三個入口，連線及飛航模式共 6 格。裝置端三條傳輸路徑與受控伺服器均記錄 0 次內容請求。每條監測路徑亦有正對照。 |
| 智能漫畫目錄 | 在 Android 上重放兩頁結果。完整時依序為 120、140、160。第二頁改回 HTTP 403 後顯示 120、140、150、160，保留完整性警示，資料庫維持原來三章；另外用過期的章節清單再次儲存，仍保留 120。四次搜尋頁請求分別來自兩次刷新，兩者之間的再次開啟命中本機。JVM 亦以三部先前實際收錄的作品標題重放章數、合刊、附贈、同名與反向順序。 |
| Reader 錯誤圖卡 | 共 12 組。HTTP 504、無法解壓的舊 CBZ、缺少圖片，分別在兩種主題及兩種容器實測。一般容器使用 tag 12 的圖示、預留比例及底色，小容器為 300×105 dp、170% 字級。逐一比對截圖像素、標題、原因、來源與按鈕位置；按一次重載後檢查伺服器請求及成功顯示的圖片。圖卡測具控制離線修復回傳值，刪檔與保存另由 repository 套件驗證。 |
| 請求與效能門檻 | `check_observers.py` 會實際發送控制請求並測試竄改資料、假零請求及效能超標。N01 裝置命令另需取得應用程式內部三條傳輸路徑與受控伺服器的對照紀錄。P01–P04 的輸入與原始指標各自經過門檻檢查。正式每情境 40 輪效能報告須附可比的實際樣本。 |

```powershell
.\gradlew.bat :shared:testDebugUnitTest :composeApp:assembleDebug --offline
python -B .github/scripts/reader-validation/check_observers.py
python -B .github/scripts/apple-validate/reader-modes/validate.py android-catalog --serial $serial --package $package --output "$env:TEMP/reader-catalog"
python -B .github/scripts/apple-validate/reader-modes/validate.py android-cards --serial $serial --package $package --output "$env:TEMP/reader-cards"
$qa = "$env:TEMP/reader-verification"
python -B .github/scripts/apple-validate/reader-modes/validate.py android-downloads --serial $serial --package $package --output "$qa/primary"
foreach ($source in 'comic','novel','forum') {
    python -B .github/scripts/apple-validate/reader-modes/validate.py android-downloads --serial $serial --package $package --mode $source --output "$qa/seed-$source"
    python -B .github/scripts/apple-validate/reader-modes/validate.py android-downloads --serial $serial --package $package "--supplement-$source" "$qa/seed-$source/android-download-provenance.json" --output "$qa/combined-$source"
}
python -B .github/scripts/apple-validate/reader-modes/validate.py android-downloads --serial $serial --package $package --repeat-existing "$qa/primary/android-download-provenance.json" --output "$qa/repeat"
python -B .github/scripts/apple-validate/reader-modes/generate_fixtures.py android --serial $serial --package $package --revision $revision --download-provenance "$qa/primary/android-download-provenance.json" --combined-download-provenance "$qa/combined-comic/android-download-provenance.json" --combined-novel-download-provenance "$qa/combined-novel/android-download-provenance.json" --combined-forum-download-provenance "$qa/combined-forum/android-download-provenance.json" --output "$qa/modes"
python -B .github/scripts/reader-validation/check_observers.py android-n01 --help
$latency = ".github/scripts/reader-validation/latency_contract.py"
# 兩個乾淨的 detached checkout 分別來自 tag 12 和驗證的產品差異。
python -B $latency android-prepare $baselineCheckout abtag12
python -B $latency android-prepare $candidateCheckout abcurrent
# 兩個 checkout 分別執行 :composeApp:assembleReleaseRun，安裝到同一專用裝置。
# READER_TRACE_SHELL 指向此機的 Perfetto trace_processor_shell，PYTHONUTF8=1。
$env:READER_REQUIRE_PAINT = "1"
python -B $latency android-reader forum-reference --serial $serial --label baseline --output "$qa/ref-baseline"
python -B $latency android-reader forum-reference --serial $serial --label candidate --output "$qa/ref-candidate"
python -B $latency android-reader matrix --serial $serial --route forum --reference-root $qa --purpose aa --rounds 7 --wait-for-body-draw --output "$qa/aa"
python -B $latency android-reader matrix --serial $serial --route forum --reference-root $qa --purpose ab --rounds 40 --wait-for-body-draw --output "$qa/ab"
python -B .github/scripts/reader-validation/latency_contract.py android-summary --aa "$qa/aa/rounds.json" --ab "$qa/ab/rounds.json" --output "$qa/latency-summary.json"
python -B .github/scripts/apple-validate/reader-modes/validate.py android-trace-report "$sample/flow.pftrace" --package $benchmarkPackage --windows "$sample/windows.json" --output "$sample/frames-report.json"
python -B .github/scripts/apple-validate/reader-modes/validate.py android-frame-batch "$qa/ab" --rounds 40 --output "$qa/frame-summary.json"
python -B .github/scripts/reader-validation/latency_contract.py android-phase-summary "$qa/ab" --output "$qa/phase-summary.json"
```

`$serial` 指向獨立測試模擬器，`$package` 為已安裝的驗證 APK 套件名，`$revision` 與該 APK 的來源對應。跨模式的來源、目標與返回各自留存 UI 和 SQLite 證據。未執行的平台、缺少原始證據或數值不符的案例，仍計入分母。iOS 本輪暫緩。

### 11 月 25 日上午進度

- 乾淨 Android QA APK `c44d5137` 的六個跨模式往返均通過，包含原模式離開、切到目標、返回及終止程序後重新開啟。驗收留存 41 張截圖、41 份 UI 階層及 SQLite 錨點。原始報告位於 `%TEMP%/yamibo-reader-final-warm-C44D5137/android-cases.csv`。
- 這版亦通過兩個主題共 12 張圖卡與智能漫畫目錄實測。實際的 HTTP 504、損壞 CBZ、缺檔均使用原有直式佔位，觸發同一個重試入口。證據位於 `%TEMP%/yamibo-reader-ui-C44D5137`。JVM 共 65 個測試，含 41 個下載生命週期案例。
- 真實按鈕下載的原始庫來自 APK `0b3665d0`。用新版冷啟可獨立驗證升級後的定位。論壇第 3 頁切作者，需區分「已有作者頁 1 的本機副本」和「只有論壇第 3 頁可以過濾作者」。明確點選哪個實體頁與最後實際呈現哪段分別留在紀錄。
- 比對相同資料重新下載後的實體檔。論壇由 32,762 B 降至 32,682 B，小說與漫畫各自從 26,497 B 降至 26,264 B。差額均來自 manifest，每個入口仍保有原來的 PID、ZIP 與三張圖片。對同一份小說改用漫畫重新下載，增量為 0 B、圖片 GET 為 0。完整檔案清單及雜湊在 `%TEMP%/yamibo-reader-provenance-1125/clean-downloads/storage-comparison.json`。
- 不含診斷記錄的正文提交修正版產物為 `%TEMP%/yamibo-reader-page-projection-qa.apk`，SHA-256 `154055ad…77f5`。9 帖的解析在舊候選三輪只需 0.4–0.8 ms，資料提交至解析開始卻等了 89–144 ms。調整後讓已有資料直接準備正文並提交，異動語系或追加頁面時重用未改動的帖文。下載頁和一般快取也依原順序在同一次背景工作讀取。另修正沒有智能目錄時，從已下載後頁選小說可在本機查首樓作者。這個快照的 QA build 與 65 個 JVM 測試已通過。

隨後在 `154055ad` 以三模式、連線與飛航六種組合重跑 N01，六格全部通過，輸出在 `%TEMP%/yamibo-reader-network-154055ad`。用實際下載按鈕建立資料後，強制停止程序、清一般快取並開飛航模式，從下載管理跨模式冷開，六個方向都已取得合格結果。下載 APK → 讀取 APK 分別為 F01、F02 的 `0b3665d0 → c44d5137`，F03、F05 的 `0b3665d0 → ec863711`，F04 的 `c44d5137 → 154055ad`，F06 的 `c44d5137 → ec863711`。每格的來源、落點與版本收在 `%TEMP%/yamibo-reader-provenance-1125/cold-offline-acceptance-index.json`。F06 還另外在「只有論壇全帖」的資料夾從實體第 3 頁開小說。舊候選落在該樓首圖，`ec863711` 回到 PID 1403 的第 2 段、作者 UID 7，仍顯示有證據的本機第 3 頁。在同時具有兩種範圍的資料夾，實測由全帖第 3 頁回到作者第 1 頁的同一段。無智能漫畫目錄時，對話框會在需要時從已下載的第 1 頁取得樓主。純全帖證據在 `%TEMP%/yamibo-reader-provenance-1125/full-only-final/scope-check.json`。

睡前最終 debug APK 的 SHA-256 為產品 `399d37eadcb8ddde553d7eb5343502f94ffa1721289fbad43123b0f58232bc05`、驗證版 `f0e0d584c7c11dc423a8ccd6e44b460d7c888b8dc98199cc61007cf70a10d0c7`。整合版 Android JVM 67 項全部通過，其中 41 項涵蓋下載生命週期，另有明確點選頁面的 PID 和切換文字轉換模式後再切回的回歸測試。8 份原始 XML 保存在 `%TEMP%/yamibo-reader-pre-sleep-jvm`。首次冷開時另有一筆畫面先取 3 張、首次下載再取 3 張的紀錄，和同一下載重按後零傳輸的樣本分別計數。

相同的最終 QA APK 已重驗 4/6 個暖切換往返。F01 保留序列 A、B、A 中第 3 張的出現位置；F04 返回論壇時恢復原來其他作者的留言；F05、F06 在論壇實體第 3 頁與小說作者第 1 頁雙向回到 PID 1403 第 2 段。四格均經殺進程重開，共 17 個斷言及 26 組截圖與 UI 階層。F01 檔案在 `%TEMP%/yamibo-reader-ordinary-extra-f0e0d584`，另三格在 `%TEMP%/yamibo-reader-ordinary-warm-f0e0d584`。F02、F03 最終版待重驗。

### 13:00 接續工作

排程以台北時間 06:30 休眠、13:00 喚醒並從同一對話接續。Windows 實際進出睡眠的時間收在下方的下午驗證。時間跨過睡眠的樣本不作為效能證據。既有驗證工作樹保留腳本，`%TEMP%/yamibo-reader-release-ab-20261125` 保留兩個來源版本、APK 指紋、錄影、trace 與環境資訊。

先對照產品工作樹與過夜前最後一輪 APK 的來源指紋。若不同，重新建置最新候選，讓同一個模擬器從同一論壇列表點選 TID 535389、PID 40718127，驗證首樓位置與像素相同後再量。正式執行使用暫存目錄內的 `resume-1300.ps1`，目標是七對同版本 A/A 與四十對 tag 12／候選 A/B，保留每次輸入到畫出正文的總耗時、首個手勢反應、滑入、拖曳、設定等幀間距。若先導樣本仍明確退化，先依診斷時段修正再做正式簽核。下午先在獨立效能模擬器 5564 重現最新候選六對偏慢的載入區間，對照點擊到 API 開始、回應到正文提交，以及提交到畫面呈現，並保存每段 CPU 與顯示時間。正式配對期間避開 Gradle 和其他主動操作的模擬器。在另一個時段以最終 QA APK 補跑暖切換 F02、F03，並在同一 APK 重驗六個離線方向。另循冷開先顯示、再下載的路徑記錄圖片 URL、快取狀態與第二次 GET 的觸發點，確認能否重用已抓的三張圖。閱讀端 `ImageViewer.kt` 用 Coil 顯示圖片；下載端 `DownloadRepositoryImpl.downloadedOrFetch` 先查目前帳號和儲存位置的已下載副本，未命中才呼叫 `DownloadImageFetcher`。追查時核對同一 URL 的原始回應是否仍可讀，以及帳號、存取範圍、資料新鮮度是否相符。iOS 依本輪要求暫緩。

喚醒後以 `%TEMP%/yamibo-reader-20261125-wake.txt` 和 Windows 工作排程器的上次執行結果對照實際時間。這台電腦的無人喚醒睡眠逾時為 120 秒，喚醒任務會暫時保持執行，最晚至 17:00 結束。下午本輪工作完成時，把完成時間寫入 `%TEMP%/yamibo-reader-20261125-work-complete.txt`，任務便會在 30 秒內解除這項暫時要求。效能腳本已核對兩個 APK 與當前產品來源指紋，可直接採用睡前最後候選 `88557be5`；產品有後續異動時，腳本會另建相符的候選。

### 11 月 25 日下午 Android 驗證

Windows 系統事件記錄電腦於台北時間 06:30:02 進入睡眠，13:02:48 由 ACPI Wake Alarm 喚醒。上午的錄影與下午的樣本各自計時。睡前 QA APK `f0e0d584` 現已通過六個暖切換方向。下午補驗的 F02、F03 含小說轉漫畫、漫畫轉全帖、作者限定副本與重開後的落點，原始檔在 `%TEMP%/yamibo-reader-ordinary-afternoon-f0e0d584`。同一 APK 的兩主題錯圖重試共 12 格與智能目錄也已重驗。

下午先用睡前產品 `d865399e` 的獨立診斷版重現兩次。三項進度 SQL 都已結束後，等待回到 UI 才繼續找頁分別花了 32.6、65.0 ms；接著再派發讀取又花了 4.5、22.0 ms。查到沒有下載或普通快取本身合計只需 1.1、0.9 ms。回應後寫樓層索引分別用了 16.3、35.6 ms，另外寫普通快取用了 47.7、51.8 ms。九帖的解析兩次都約 0.4 ms。原始時間在 `%TEMP%/yamibo-reader-release-ab-20261125/wake-stage-d865-1` 與 `wake-stage-d865-2`。

候選現在在同一趟背景工作上依序解析進度、找本機頁、必要時發出請求。樓層索引與普通快取的 metadata 在同一筆 SQLite 交易提交，圖片及正文的檔案寫入在交易之外；普通快取淘汰頁面時亦清除該頁的線上樓層映射。首次整頁錯誤再按重試，會重新解析當初選中的目的地，成功後再開始保存進度。明確指定 PID 的作者頁若已收到失敗回應，會等讀者按重試才再次請求。圖片轉成離線下載時查閱相同存取範圍的 Coil 原始回應，重用前核對 HTTP 新鮮度、完整性與圖檔。損壞副本和已確認更新的圖片仍走重新抓取。

兩個修後診斷樣本中，查完進度到開始找本機頁縮為 0.06、0.23 ms，合併後的快取寫入為 23.96、36.81 ms。其中一次網路 API 花了 4,606 ms。另按每幀實際的正文 draw、App surface token 和 SurfaceFlinger 的 display token 核對，同一個正文幀在 tag 12 的兩次 Compose 測量排版用了 78.13、71.94 ms，修後診斷版為 77.30、89.42 ms。RenderThread 繪製該幀分別為 94.31、81.91 ms 與 90.07、103.10 ms。這些數字來自軟體繪圖的模擬器，仍需正式配對判讀版本差異。診斷報告在 `%TEMP%/yamibo-reader-release-ab-20261125/wake-diagnostic-report.md`。

清除普通快取時，SQLite 原有 `LIKE '12_%'` 會誤中 TID 123 和 1299。現與記憶體快取使用相同的字面前綴語意，只清目標 TID 的頁與索引。正常論壇首樓沒有封面時，現在只檢查當前存取範圍已在快取中的頁。作者的頁序、斷頁處以及舊有快取鍵各自驗證。從歷史恢復封面的資料庫操作移到正文可顯示後，重遇同一張自動封面會保留原時間戳與手動選擇。

下午第一輪的本機產品差異 SHA-256 為 `c254cf94374b41c1369afe892ba785a67262c62bb7b4a50541d0af8df9249366`。建置前後重新計算結果相同。Android debug 產品 APK 為 `4e2280a68b272a2bfe0f4c103fefa9f488ad23f501deebbe8c6b1f13aafaa363`、QA APK 為 `82bc9c0bd028249d656f569bb0412c04892e5122b09e6cf023278519f5c0617b`。八份 JUnit XML 合計 71 通過，失敗、錯誤、跳過均為 0。涵蓋兩種同前綴 TID 的獨立清除、索引交易回滾、重新開啟相同封面時的手動選擇與時間戳，以及一萬頁論壇只有少數頁在快取的情況。原始輸出在 `%TEMP%/yamibo-reader-afternoon-c254cf94`。原 Google APIs 映像的前測使用不可偵錯的 releaseRun APK，tag 12 為 `b9c77f99…66f0`，候選為 `4a4e38e2…d628`。在 Android 16 Play Store 映像（build 13818094，user/release-keys）上，兩版使用相同的只讀樓層查核與計時標記。該環境實際 APK 的 SHA-256 為 tag 12 `8c7a20bb0f31ea27c8f6ecf7dbd07e35191a8951aae232a62fdd9626d05c0164`（19,488,216 B）、候選 `386172b4545c37c67e927971c850ee79afe76b9bf40bc71cc6567ec3230dddd1`（19,921,940 B）。這兩個量測用 APK 相差 433,724 B（+2.23%）。按 APK 的 ZIP 目錄比較，其中 422,153 B 來自壓縮後的 DEX。下方另量使用者實際下載資料夾的大小。

**跨模式進度與整頁重試。** 第一輪 QA APK `82bc9c0b` 從三個一般下載入口建立本機資料，並用同一 APK 重開。六個方向各驗兩次，清除一般快取、終止程序並開飛航模式後從下載管理冷開為 6/6，暖切換、返回再重新啟動確認持久化也是 6/6。漫畫 A、B、A 序列保留第三個位置；論壇的其他作者留言在作者視圖隱藏，回論壇後仍可恢復。在全帖第 3 頁與作者第 1 頁之間切換，同一個 PID 1403、第 2 段仍能定位。兩次受控 HTTP 503 分別從論壇指定頁、小說指定作者與 PID 開啟。兩者都只在按下重試後才再發一次第 3 頁請求（200），正常預載相鄰第 2 頁各一次，返回後的歷史均為第 3 頁的可見 PID。原始檔在 `%TEMP%/yamibo-reader-same-apk-1125/same-apk-validation.json` 的 `candidate-matrix`、`candidate-warm`，以及同層 `reader-retry-503/reader-retry.json`。

**已下載內容的網路請求。** 同一個第一輪 QA APK 開啟先前凍結的舊版下載資料（來源 QA `015e1f42`，fixture SHA-256 `44e942d0…163a`）。論壇、小說、智能漫畫各在線上和飛航模式開啟，在首屏與隨後 5.20–5.22 秒閒置期間，六格均為 0 次內容請求、0 個未完成請求。每格另外對論壇 API、Coil 及原生下載三條路徑各送一次，18 次正對照均由伺服器確認 HTTP 200。六場原始記錄與其他功能證據的索引為 `%TEMP%/yamibo-reader-same-apk-1125/candidate-complete.json`。

**從 reader 再按下載的傳輸與容量。** 兩版本各自從空 Coil 快取進入同一個本機 HTTP 帖子，核對讀屏與下載的每個 GET、實體快取鍵與原圖 SHA。測試資料庫的擁有者為 UID 7，HTTP 則以訪客身分取得三張圖。表內先列讀屏請求數，再列按下載之後的請求數。

| 來源回應 | 睡前 QA | 下午 QA `82bc9c0b` |
| --- | ---: | ---: |
| 沒有明確新鮮期限 | 3 + 3 | 3 + 3 |
| `Cache-Control: private, max-age=300` | 3 + 3 | 3 + 0 |

21 筆實際 GET 全部回 200。另一個案例故意損壞一張離線圖，56 秒前收到的原圖還在上述 300 秒的期限內。按原佔位的「重新載入」後只抓該 URL 一次，落盤 SHA 與原圖一致，佔位消失。裝置測試的 12 種 cache 狀態中，新鮮完整的同範圍回應可直接使用，另 11 種均回到網路路徑，包含 206、404、no-store、Vary、資料截斷與不同授權範圍。相同測資兩版下載後的論壇總量均為 32,682 B，小說及漫畫分別為 26,264 B。三入口各有 5 個位置、3 個實體圖檔，共 17,140 B。HTTP、檔案與裝置 UI 原始紀錄在 `%TEMP%/yamibo-reader-same-apk-1125/transfer-comparison.json`、同層的 `candidate-forced` 與 `candidate-cache-controls`。原始回應的 metadata 讀取集中在 `readFreshReaderImage`，沿用 Coil 3.5 的格式解析與 HTTP cache strategy。無法解讀舊格式時回到原本下載流程。升級 Coil 時可用這 12 個裝置案例核實行為。

正式量測前，原有 Android 16 Google APIs 客體在三對校準中，tag 12 完成 3 次，候選完成 1 次。首輪 ProfileInstaller 的類型轉換例外、後續 Compose 例外均留存；移除全部計時標記的同源 APK 亦曾在 Compose 與 Tink 路徑崩潰。整個模擬器冷開、確認 boot ID 已變後，Android 自身的 `system_server` 也有 ART abort。原客體的這組資料不足以驗收版本快慢。另建同映像的新資料區後，無標記的兩版各完成 3/3 次；帶標記候選另有一次可見的論壇清單解析錯誤，當下只有截斷的 HTML 診斷，原因未確定。保留 `c254cf94-preflight-fresh/pilot-ab-3/rounds.json`、`c254-nohook-postboot/all-guest-crashes.txt`、`c254cf94-fresh-device/ref-candidate/preflight-list-diagnostic.xml`，均在效能暫存目錄，與正式樣本分開。

五對 Play Store 校準中，兩版各 5/5 次均進入正文、滑動並開啟設定。tag 12 的五份 trace 均完整，候選僅三份完整。一份在 Perfetto 寫完前就被取回，另一份的 32 MiB 環形緩衝已覆寫點擊時段。採集器現等待 Perfetto 的實際寫入完成訊息、核對主客體檔案大小，並檢查從點擊到設定面板的 trace 覆蓋與 App FrameTimeline。緩衝調為 96 MiB。校準用的首段參考 PNG 指紋為 `f26e0cc4…134c`，其解碼灰階像素指紋為 `2e9bebde…8166a`。兩者定位同一段正文，H.264 錄影解碼後的像素另逐次記錄。五對校準的首幀在相同座標有八種灰階雜湊。原始檔在 `c254cf94-play36/reference-and-observed-pixels.json`。

正式 A/A 七對均完成。14 次都使用同一個 tag 12 APK、同一個 boot 與 ART 狀態，正文和首滑都有對應的實際呈現幀。第 2 對在同版錄得 3,201.7 ms 的首屏差。兩次滑入完成分別在點擊後 470.1／482.4 ms，首次繪製正文為 4,458.6／1,315.0 ms，實際呈現為 4,619.5／1,417.8 ms。主要差距發生在轉場結束後、繪製正文前。正式 APK 沒有 HTTP 分段標記，無法把這段等候直接歸因於網路。原始檔為 `formal-20261125-140512/aa-7/02-baseline/flow.pftrace` 與 `02-candidate/flow.pftrace`。

正式 A/B 第 20 對，候選從首頁進論壇時發生一次應用程式崩潰（PID 23590）。`HomePageParser → Ksoup.parse → Entities.EscapeMode.<clinit> → CharacterReader.consumeMatching` 在解讀函式庫內建的 2,125 筆 HTML entity 表時拋出 `Character cannot be cast to Boolean`。尚未點帖，該次沒有首屏時間。兩個 APK 解析到相同的 Ksoup 0.2.6 與 yamibo-api 1.1.29 AAR，相關七個 DEX 方法（包含 lambda bridge）逐條正規化後一致。相鄰候選輪均完成。舊 Google APIs 校準中論壇列表曾回報另一種 Class→String 例外，缺少同一堆疊。兩次各自保留。共用下載採用的 kmp-zip 2.1.0 讓 resolved Kotlin stdlib 從 2.4.10 升到 2.4.21、Okio 從 3.18.1 升到 3.18.2，現有證據無法將崩潰歸因於這些版本。第 34 對的 tag 12 也在點帖前崩潰（PID 12146）。兩次都在 `HomePageParser` 首次初始化同一張 entity 表，此次葉端是 `CharacterReader.nextIndexOf` 讀取空值。兩版各有一筆獨立的應用程式可靠性失敗。原始堆疊與程序退出紀錄在 `formal-20261125-140512/ab-40/20-candidate` 與 `34-baseline`。 正式量測後關閉 Perfetto，另做 20 對同 APK 冷程序啟動。tag 12 成功 20/20，候選成功 19/20。四十次都沒有重現上述 Ksoup 例外。候選第 12 次有另一筆 `ProfileTranscoder.readMethodBitmap` 中的 `TreeMap cannot be cast to Comparable` 崩潰，先前原客體曾在同一方法出錯。這組獨立分母和完整堆疊在 `independent-cold-launch-20/launch-reliability.json` 與 `12-candidate/parser-24859.log`。

**正式 tag 12 與候選 `c254cf94` 的結果。** 從管理版點選同一置頂帖（TID 535389、PID 40718127），兩版共 40 對／80 次。兩版各有 39 次開到正文，兩次點帖前崩潰分別發生在第 20、34 對，剩 38 個完整配對。比較使用同一 boot、ART verify/install、首段位置及輸入路徑。下表單位為 ms，採 nearest-rank 分位數。前三欄僅使用這 38 對。第四欄先對每一對相減，再取分位數。

| 從同一次點帖起算 | tag 12 p50 / p95 | 候選 p50 / p95 | 各分位的候選增量 | 每對差 p50 / p95 |
| --- | ---: | ---: | ---: | ---: |
| 正文第一次正確呈現 | 1,411.3 / 2,076.8 | 1,645.8 / 2,280.0 | +234.5 / +203.2 | +191.3 / +932.2 |
| 第一次滑動有可見反應 | 1,515.2 / 2,243.4 | 1,732.5 / 2,429.0 | +217.3 / +185.6 | +213.8 / +965.4 |

兩項指標各有 33/38 對的候選慢逾 1 ms。正式腳本保留同版 A/A 波動及缺對，+1 ms 門檻無法簽核。首屏 p50 差的不確定範圍是 −3,064 至 +3,495 ms。每版各 39 個成功入口中，滑入完成到正文開始畫的 p50 是 776.6 → 1,002.8 ms，正文開始畫到呈現為 112.4 → 109.2 ms。這批差距集中在開始畫之前。第 17 對基準和第 37 對兩側拉檔時傳輸中斷，保存原半檔，利用同一次 guest 錄影、輸入、繪製標記與完整 trace 重建，沒有重做使用者操作。這三次的主機 CPU 快照需依各自的恢復紀錄查核。

滑入中段有足夠呈現幀可量間距的基準 35 次、候選 34 次，其中各有 22／19 次能以錄影確認至少 33.4 ms 的停頓。基準第 4 輪由畫面約 46% 滑至 73% 相隔 72.4 ms，候選第 17 輪約 46% 至 61% 相隔 85.9 ms。兩幀均有 App 與 SurfaceFlinger CPU deadline miss。三次拖曳各自取單次最長幀距的 p50 為 38.2 → 49.3、33.4 → 33.5、33.7 → 33.2 ms。開設定至面板穩定的 p50 為 570.0 → 568.9 ms。完整資料在 `%TEMP%/yamibo-reader-release-ab-20261125/formal-20261125-140512` 的 `comparison.json`、`ab-stage-and-gesture-summary.json`、`ab-frame-summary.json` 與 `capture-incidents.json`。

**解析與排程對照。** 兩版在 yamibo-api 1.1.29 的暫存診斷 AAR 裡使用同一個觀測點，記下 Ktor Android 收到且解碼完整 HTML 的時間、SHA 及解析結束的時間。五對真站回應的十份 HTML 指紋各異。接收完畢到解析返回的配對差 p50 為 +141.5 ms。另一組五對重播同一份真實訪客 HTML（74,099 B，SHA-256 `f099613be9baabefb17de554dd5519fa72ab2a2081f9e96907041cb685fb8ed3`，固定等候 120 ms）。tag 12 的解析在主執行緒，候選在 Default worker，兩者均屬 Android top-app 群組，nice 值分別為 −10 和 0。從完整 HTML 到 API 解析返回的 p50 為 140.1 → 409.4 ms，其中實際在 CPU 上執行 121.8 → 128.7 ms，可執行但等待 CPU 為 18.3 → 231.7 ms。該雙核心模擬器同時執行 App、RenderThread、錄影編碼與 SurfaceFlinger；各執行緒占核時段不能直接加總當成單一停頓的成因。原始固定回應、真站及逐執行緒結果在同一暫存目錄下的 `public-body-stage/diagnostics-c254`。Android 的執行緒指引亦說明提高優先權可能影響 UI 和 RenderThread 的呈現時限。[Android 執行緒優先權](https://developer.android.com/topic/performance/threads#thread-priority)

初版計時工具在點帖後反覆擷取模擬器畫面，以便正文剛顯示就送出首滑。四條件交錯對照在相同回應下顯示，這項監看會拖慢畫面本身。舉一個四格均完成的配對，tag 12 從 27 次擷取、1,261 ms 首屏變為 3 次、566 ms；`c254cf94` 從 40 次、1,734 ms 變為 2 次、772 ms。第二種方法監聽實際點擊之後的同 PID 正文 draw，再做必要的畫面確認。兩種方法事後均用同一組錄影及 SurfaceFlinger 呈現證據判定 T1/T2。先前的 38 對屬高頻擷取條件。另一次固定來源的 tag 12 控制在解析時發生 SIGSEGV，原生堆疊 PC 位於 ART JIT code cache 的 `CharacterKt.codePointValueAt`，程序 PID 26708。這筆發生在固定內容已經收到之後，與先前的兩次啟動崩潰各自保留。

所有六組交錯監看（原三組及後續獨立對照）中，三組的四個條件均有完整首屏與首滑呈現證據。每輪截圖次數 p50 為 tag 12 的 25 → 2 張、`c254cf94` 的 26 → 2 張。以同一輪相減，兩版首屏各縮短 507／525 ms。高頻擷取時候選相對 tag 12 的首屏配對差 p50 為 +199.2 ms，低頻擷取為 +138.0 ms。首滑呈現差分別為 +199.8／+170.6 ms。監看工具會放大差距，降低干擾後仍可量到版本差。逐格結果在 `public-body-stage/diagnostics-c254/monitor-crossover-final.json`。

第一個後續修正 `f141aba3` 在已取得首個頁面的背景工作中準備正文與頁面索引，再一起交給 UI。語系或已載入資料改變時會重算。Debug 與 QA 皆建置成功，JVM 71/71。整合快照 `51b5a34f` 同時將載入佔位的六張漸層卡改為在繪製時讀取動畫、按卡片大小重用筆刷，並在 Android 尚未選定下載資料夾時直接跳過本機頁的 I/O 切換。兩個主題、七個動畫相位的座標與 8 位元 RGB 在 2,259,264 個數學取樣位置一致。整合快照 JVM 72/72，包含未設定資料夾時同步返回、選定後可以讀到既有檔案。產品 debug APK 為 `9cf0477082d6376286bc46c5d03a46039c3bd4e9e2e96f36cd4e8893287951a6`，QA 為 `56c1cfcd35e0b4cbef69f04e6ec0622e624597281f146e22defb5c66bba37b9c`。建置前後產品指紋一致，原始檔在 `%TEMP%/yamibo-reader-afternoon-51b5a34f`。 另用 Cairo 於兩種主題、各十個固定動畫相位重畫相同裁切和漸層。兩種主題各 9/10 相位逐像素相同；剩餘相位最多差 1/255，1,314,000 個取樣像素中淺色有 221 個、深色有 189 個存在此量化差。這組軟體光柵資料在 `%TEMP%/yamibo-reader-projection-validation-1125/fixed-phase`。

低干擾的固定正文預檢中，`f141aba3` 五對的點擊到首屏配對差 p50 為 +95.5 ms。另一批 `51b5a34f` 三對均完成，tag 12／整合版的首屏 p50 為 544.2／711.3 ms、首滑呈現為 659.3／845.6 ms。先逐對相減的 p50 則分別為 +188.8／+207.1 ms。收到相同 HTML 後的解析為 60.9／152.4 ms，解析配對差 +93.9 ms。空下載資料夾查找 3.8／0.3 ms，快取儲存 7.1／22.2 ms。另有兩個真站預檢配對，首屏差一個 +404.2 ms、另一個 −513.4 ms。三批的配對、來源及採集條件分開，不能把不同批次的 p50 相減來宣稱單項修正的成效。原始資料在 `public-body-stage/diagnostics-f141` 和 `public-body-stage/diagnostics-51b5`。

在上述三對固定內容預檢中，單看送出首滑到畫面真的移動，三對的 tag 12／`51b5a34f` 各為 50.7／40.1、54.5／41.6、44.3／58.6 ms。兩對另取的真站資料則分別慢約 24、16 ms。固定內容從點齒輪到設定面板穩定為 573.9／569.3、563.9／563.6、562.9／571.4 ms。兩版設定面板實作和 250 ms 滑入動畫參數相同。這些時間涵蓋動畫呈現；從它們無法確認手勢處理、進度儲存或設定面板的哪一段造成單幀停頓。

**Android 實際主題對照。** 在相同裝置與頁面，`f141aba3` 和 `51b5a34f` 的一般頁與明確 PID 入口各保留四幀載入動畫，淺、深兩種主題的六塊佔位高度、位置與裁切一致。兩版進到 HTTP 503 的整頁錯誤後，標題與重試按鈕的邊界也一致。此處另發現既有可讀性問題：Default 的「載入失敗」標題與卡底對比 11.749:1，Default Dark 的 `#2C1810` 疊 `#2A1E18` 僅 1.042:1；兩版深色標題區的 RGB 雜湊相同。這裡量的是整頁錯誤，reader 內單張圖的「圖片載入失敗」另有獨立驗證。資料在 `%TEMP%/yamibo-reader-projection-validation-1125/51b5/skeleton-equivalence.json` 與同層 `error-theme-contrast.json`。

**整合版 `51b5a34f` 裝置驗收。** 用 `82bc9c0b` 從 App 正常入口下載的三模式副本升級到 `56c1cfcd` QA APK，六方向飛航冷開與六方向暖切換、返回、程序重啟均通過（12/12）。另用同一 `56c1cfcd` 實際下載一份小說，清一般與 Coil 快取後飛航冷開。同一 PID 的正文可由「安靜的書頁」切到「安静的书页」，重啟後仍是簡體，再切繁體及默認亦成功。三模式各於連線和飛航開啟舊版本機副本（6/6），監測窗內 0 次內容請求，窗外 18 次正控制全部回 200。Default 和 Default Dark 的整頁 503 在指定頁及指定 PID 共四格重試成功，均保存第 3 頁。首次繁簡測具主動 force-stop 後誤取不存在的 PID，另一次深色參照擷取錯過預期 503；兩筆原始失敗及補驗均有留存。原始整合索引在 `%TEMP%/yamibo-reader-projection-validation-1125/51b5/upgrade-results.json`。功能 AVD 於 16:10:33 關閉，效能測量在 16:10:34 接手。

**低干擾正式比較。** 兩版皆使用 releaseRun，tag 12 APK 的 SHA-256 為 `8c7a20bb0f31ea27c8f6ecf7dbd07e35191a8951aae232a62fdd9626d05c0164`，整合版為 `96b28431a228699e6fe155e69c7e6ce3d01eb88b20402165e105f6b105fb9fd0`。兩版以相同手動路徑開管理版置頂帖，從放開手指起算，用正文與首滑的實際呈現幀計時。原始目錄是 `%TEMP%/yamibo-reader-release-ab-20261125/formal-51b5-low-20261125-161034`。同一 tag 12 校準 7/7 對都有呈現證據。正式比較第 12 對候選在點帖前退出。Android 記錄 PID 3599 發生主執行緒例外；Compose 無障礙服務正取得語意節點座標，於 `GraphicsLayerOwnerLayer.getMatrix` 讀取空物件。該輪有 `ab-40/12-candidate/android-crash-pid-3599.log` 與系統退出紀錄，沒有可計時的正文。

40 對正式比較全部嘗試。tag 12 有 40/40 次、新版有 39/40 次成功進入正文，共 39 個完整配對。下表的終點取 Android 實際顯示時間。錄影另用來核對正文與首滑反應所在的影格。此次首屏及首滑各有 92/92 個可唯一對應的呈現幀（同版校準及完整配對合計）。

| 從同一次點帖起算（ms） | tag 12 p50 / p95 | `51b5a34f` p50 / p95 | 各分位的候選增量 | 每對差 p50 / p95 | 新版慢逾 1 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 正確正文實際呈現 | 1,076.3 / 2,012.2 | 1,158.1 / 2,079.7 | +81.8 / +67.5 | +77.1 / +1,303.4 | 29/39 |
| 首次滑動產生可見位移 | 1,231.5 / 2,145.0 | 1,307.1 / 2,213.2 | +75.7 / +68.2 | +75.0 / +1,304.1 | 28/39 |

兩項指標在本輪都高於基準。首屏 p50 的校準不確定區間為 −2,825.7 到 +2,974.4 ms；同一 tag 12 的第 4 對首屏相差 2,873.1 ms。原定 +1 ms 門檻未獲驗證，正式比較結果為 `inconclusive`。高頻擷取的前一輪與本輪的量測條件不同，只能比較各自同輪的兩版。完整輸出見同一目錄的 `comparison.json`。

**滑入、捲動與設定。** 成功開啟的基準 40 次、候選 39 次中，點擊至正文開始繪製的 p50 為 951.5 → 1,032.3 ms，滑入已結束到開始畫正文為 571.4 → 655.0 ms。開始畫正文到畫面顯示為 128.6 → 125.7 ms，各階段分位數不能直接相加。此次低干擾量測在兩版的滑入中段均未找到同時獲錄影及呈現證據支持的 ≥33.4 ms 停頓；逐次最長幀距的 p50 皆為 17.8 ms。前輪兩版確曾出現中段停頓。此次前三次手指拖曳各自最長幀距的 p50 為 50.3 → 51.0、49.9 → 50.0、33.3 → 33.3 ms。在設定面板進場，點擊到穩定為 569.4 → 572.8 ms（p50）；各次最長幀距的 p95 為 150.4 → 168.1 ms，單次最大值為 169.9 → 176.7 ms。這些幀距能解釋為何面板總時間接近仍會感到卡頓，造成慢幀的具體程式碼尚未確定。原始報表是同一目錄的 `ab-stage-and-gesture-summary.json` 及 `ab-frame-summary.json`。

**關閉錄影後的解析診斷。** 對同一版診斷 APK、同一個 boot 和固定的 74,099 B 正文，另做三對不啟動錄影編碼器的冷開，6/6 次均核對到正常入口、相同 SHA 與正文位置。下表由相同程式標記起算，從已解碼並確認 SHA 到 API 解析返回。單位是 ms，先逐對相減再取 p50。

| 三對固定正文 | tag 12 / 新版的解析時間 p50 | 解析配對差 | 在 CPU 執行的配對差 | 可執行但等 CPU 的配對差 |
| --- | ---: | ---: | ---: | ---: |
| 開啟錄影 | 60.5 / 152.2 | +93.9 | +15.6 | +82.4 |
| 關閉錄影 | 47.1 / 76.8 | +27.7 | +12.8 | +17.0 |

關閉錄影後仍量到解析差與排程差。兩組各只有三對；無錄影的組別只用於判讀解析，不提供正文或手勢的實際呈現時間。兩組的逐執行緒資料在 `public-body-stage/diagnostics-51b5/recorded-scheduling.json`、`no-encoder-scheduling.json`，可由 QA 工作樹的 `latency_contract.py android-parser-no-encoder` 與 `android-parser-scheduling` 重做。

**另做 20 對冷啟可靠性檢查。** 移除錄影和 Perfetto，重新安裝本輪兩個正式 APK，在同一 boot 由正常入口冷開看板並查置頂目標。tag 12 與新版各完成 19/20。新版第 14 次（PID 18611）在 AndroidX ProfileInstaller 解讀 method bitmap 時發生 `TreeMap cannot be cast to Comparable`，與早先 `c254cf94` 的獨立冷啟例外相同。tag 12 的第 17 次（PID 19705）顯示「登入狀態已失效或尚未登入，請重新登入」，到等待期限仍無置頂帖，該次未見致命崩潰。這 40 次未重現正式第 12 對的無障礙例外。三個 APK 均包含 baseline.prof 和 baseline.profm，兩個出現 ProfileInstaller 例外的版本其 profile 指紋不同。實際成因仍待確認。原始輸出在本輪正式目錄的 `independent-cold-launch-20/launch-reliability.json`，包含失敗畫面與每個程序的日誌；`packaged-profile-inventory.json` 收錄 APK 內的 profile 指紋。QA 計時腳本也會在失敗時按應用程式 UID 保存 crash buffer，判讀時需核對 PID 和退出時間。獨立再跑觀測器自測，4 個有效 HTTP 控制成立，12 個刻意無效的控制全部被拒。

### 扣除請求後的 200 ms 預算

正式 39 對的新版點擊到正確正文 p50 是 1,158.1 ms。正式 APK 未加入 HTTP 分段，不能從此數字推出網路或本機的占比。以下改用同源診斷 APK，以同一次點擊與同一次呈現相減。請求區間定為呼叫 API 至完整 HTML 解碼完成，涵蓋連線、傳輸、解碼及其中的排程等待。扣掉整段後的剩餘時間是較保守的本機成本。已下載頁面直接命中是另一條路徑。

| 新版的一次實際操作（ms） | 真站第 1 次 | 真站第 2 次 |
| --- | ---: | ---: |
| 點帖到開始呼叫 API | 106.0 | 111.2 |
| 呼叫 API 到完整 HTML 解碼 | 1,608.8 | 683.0 |
| 從 HTML 到帖子解析返回 | 94.3 | 104.0 |
| 解析返回到 repository 交付（含寫普通快取、PID 索引） | 34.7 | 28.2 |
| 頁面交付到 reader 提交正文狀態 | 37.9 | 29.2 |
| 提交到正文首次 draw | 69.0 | 72.4 |
| 首次 draw 到該幀實際呈現 | 147.2 | 91.6 |
| **點帖到呈現，全程** | **2,097.9** | **1,119.6** |
| **全程扣除請求區間** | **489.1** | **436.6** |
| **只計 HTML 已收到到呈現** | **383.1** | **325.4** |

同批 tag 12 的全程扣請求是 375.4、359.0 ms。固定重播同一份 74,099 B HTML，開錄影的三次新版各為 643.6、569.3、488.7 ms。另關閉錄影以減少雙核心客體的競爭，三次新版從點擊扣請求僅到正文 draw 已是 284.2、310.9、265.7 ms；只計收到正文到 draw 則為 171.5、178.5、165.1 ms。無錄影這組沒有可用來驗收 T1 的像素對照和實際呈現時間。

真站第 2 次的 reader 載入工作在點帖後 100.0 ms 才開始，API 在 111.2 ms 開始。此前主執行緒的 Compose 重組區間有 43.4 ms。查進度約 2.3 ms、下載副本 0.3 ms、普通快取 2.8 ms。畫面約在點擊後 378.1 ms 滑入完成，完整 HTML 在 794.2 ms 抵達，兩者重疊。收到後解析 104.0 ms，另一次九帖轉 reader 正文資料的計算僅 0.95 ms。提交之後的 72.4 ms 裡，68.8 ms 落在 Compose 測量與排版。正文 draw 後，App buffer 就緒花 81.4 ms，再經 10.2 ms 呈現。該段 RenderThread 的 shader 編譯共 40.8 ms。各執行緒和巢狀 trace 區間會重疊。原始檔為 `public-body-stage/diagnostics-51b5/live-draw-2/02-candidate/flow.pftrace`、同層 `physical-endpoints.json`，以及兩組 `live-summary.json`、`replay-summary.json`。

另以正式 39 個配對的正文實際顯示幀編號，逐幀對回 UI 的 Choreographer 及 RenderThread 的 DrawFrames。相同名稱的巢狀區間取聯集。兩版各有 39/39 份成功對應。下表為逐輪中位數，這些行的範圍可重疊。

| 正文首次呈現的同一幀（ms） | tag 12 | 新版 |
| --- | ---: | ---: |
| UI 執行緒的該幀 | 78.4 | 90.2 |
| Compose 測量與排版 | 59.8 | 64.9 |
| RenderThread 的該幀 | 109.4 | 105.8 |
| RenderThread 上的 shader 編譯 | 45.6 | 45.1 |
| 從正文 draw 到實際呈現 | 128.6 | 125.7 |

39/39 次新版首幀都觀察到 shader 編譯。測試為兩核心、軟體繪圖的模擬器，每輪清除 app 資料後冷進入。這個成本在 tag 12 也存在，日常暖開和實機 GPU 的分布仍需獨立量測。報表位於正式目錄的 `first-body-frame-cpu-render-breakdown.json`。[Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)

以真站第 2 次為例，光是請求前 111.2 ms 加上提交正文後的 164.0 ms 就達 275.2 ms。要達「點擊到實際呈現扣掉請求 ≤200 ms」，應先驗證把已有的讀取流程提前到確認點帖時，讓它和進場組合重疊；再逐項減少正確首屏所需的組合／排版，以及 HTML 解析、同步快取寫入。提前開工仍須沿用原本的進度、授權、下載優先順序及相同在途請求。暖開、無 HTTP 的本機頁和冷開分開計時，一律以正確 PID／段落的實際呈現為終點。

### 下一輪優化順序

1. **先縮短正文可交給 UI 前的等待。** `ThreadPageReads.fetch` 已取得並驗證整頁後，仍同步等普通快取將頁面序列化、寫檔、提交 PID 索引才返回。固定正文三對的寫入時間 p50 為 7.1 → 22.2 ms。先用驗證版讓已驗證的記憶體頁可交給 reader，再量第一幀。若進入產品，需沿用既有快取與相同範圍的請求合併，保持檔案和索引一致，並測儲存中清快取、換帳號、立即下載與程序中止。使用者的閱讀錨點和按下下載後的保存成功條件仍需獨立驗收。
2. **在相同正文定位 HTML 解析的 CPU 成本。** 使用同一份 74,099 B HTML，分段量 DOM 建立、狀態檢查、各樓的元素查詢及 DTO 建立，同時記實際占用 CPU 與等待排程的時間。`yamibo-api` v1.1.29 的標籤路徑對樓層及其正文子節點各掃一次，再按 tag ID 去重。可先試合併重複的走訪，逐欄對照所有帖文、圖片與標籤次序；此處尚無單項效益數字。把整頁解析放入 UI 執行緒會與轉場競爭，兩版目前執行緒與排程時間不同。
3. **獨立驗證設定面板的第一段動畫。** 正式新版第 32 次點設定後約 40–216 ms 間相隔 176.7 ms。對應 trace 中主執行緒有 79.5 ms 在 `postAndWait`，RenderThread 有 61.9 ms 在 `eglSwapBuffersWithDamageKHR`，兩段可重疊。`NovelReaderSettingsPanel` 目前用可捲動的 `Column` 同時組合所有設定及底部主題選擇器。先單獨比較按區塊使用 `LazyColumn` 的版本，保留設定順序、首屏外觀與動畫，分開量首次打開、再次打開以及捲到主題選擇器時的最長幀和 p95。這是待驗假設，現有 trace 沒有指出某個控制項佔滿這 176.7 ms。

每次只改一條路徑。先在固定內容與相同位置確認結果和單項差距，再以相同 APK 條件重跑 tag 12 正式配對。將點擊到呈現、點擊到可滑、滑入中段、連續拖曳和設定面板的慢幀分別列出。

### Android reader 轉場、捲動與設定

比較 tag `12` 的 `releaseRun` 與本機候選。兩者同用 API 36、720 × 1280、60 Hz 的模擬器，以及測量觸控時間的同一段程式。逐幀比對帖子 572407 首樓正文，並用裝置單調時鐘把錄影與 SurfaceFlinger 完成呈現對齊。候選第一組定位用的 APK 雜湊為 `ecba478a`。這組與其他功能驗證、建置共享主機，供定位卡頓使用。

| 從一般歷史列點入候選版，同一次操作 | 實際畫面與 trace |
| --- | --- |
| 點擊 → 滑入中段 | 點擊後第 434 ms 與 579 ms 才各呈現一次，間隔 145 ms。錄影顯示前一幀還留著一部分歷史列表。此間主執行緒有 72 ms 位於 Compose 的 `AndroidOwner:measureAndLayout`，約 51 ms 已可執行、等候 CPU。 |
| 點擊 → 正文 → 首個滑動反應 | 首樓正文在 1,139 ms 才畫到正確位置，1,373 ms 看見首個滑動的實際位移。789–1,140 ms 的 351 ms 幀間距位於正文出現前，另計於載入成本。 |
| 點設定 → 面板滑入 | 沿正常 reader 選單開啟。278–387 ms 的兩幀間隔 109 ms，主執行緒有 89 ms 停在繪製同步等待 `postAndWait`，RenderThread 有 86 ms 在 `eglSwapBuffersWithDamageKHR`。這個樣本能定位到模擬器繪圖提交。實機與特定設定項目的成本尚待實測。 |
| 同帖經搜尋網址開啟 | 錄影可見搜尋 → 定位連結 → 骨架 → 正文。從點擊到正確正文，tag `12` 為 2,217 ms，候選為 4,285 ms。每版僅一次，網路及前置路由均包含在內。兩版本都呈現設定動畫的長幀，最長各為 135／133 ms。 |

在 tag `12` 的一次歷史列開啟中，錄影從上一頁直接跳到正文，中間沒有可辨識的完整滑入過程。相鄰呈現相隔 1,498 ms，該區間主執行緒有 1,244 ms 在 `postAndWait`，RenderThread 有 989 ms 在 `flush commands`。同一舊版樣本的三次手指拖曳區間最大幀距各為 112、74、44 ms，開啟設定的最大幀距為 141 ms。這是在模擬器錄得的舊版現象。該版重開同一首樓時落點比初次開啟偏移，原始 XML 與時間成對保留。

原始證據在系統暫存目錄的 `yamibo-reader-release-ab-20261125`，包括 `history-dry-candidate`、`dry-baseline-3` 與 `dry-candidate-1`。各目錄有 `flow.mp4`、`flow.pftrace`、`motion-events.json` 與 `windows.json`，已分析的資料另存 `frames-report.json`。首滑偵測及結束位置的失敗紀錄仍保留。完整計畫要求同路徑、配對、固定起始位置和 40 輪；單輪觀察不能判定 +1 ms 的回歸是否存在。


從「管理版」正常點選置頂帖 `535389` 的配對試跑，兩版均開到 PID `40718127`、0%，首行「一、入站前言」在錄影中位置相同，模板 SHA-256 為 `f26e0cc4…134c`。候選產品差異 SHA-256 為 `3a4da807…ac9b`，實測 `releaseRun` APK 為 `ae350398…e7ef`。7 對交錯，14 次均有原始輸入、畫面及 trace。這組在其他功能 QA 仍執行時錄得。

| 指標（ms） | tag 12 p50 / p95 | 候選 p50 / p95 |
| --- | ---: | ---: |
| 點擊到正確正文呈現 | 1,336 / 1,510 | 1,797 / 1,904 |
| 同一次點擊到首個可見捲動反應 | 1,450 / 1,710 | 1,912 / 2,086 |
| 手勢開始到看見位移 | 43 / 94 | 51 / 86 |
| 點設定到面板停穩 | 570 / 630 | 569 / 583 |

正文呈現每對候選多 171、366、497、309、636、356、190 ms，配對差中位數為 +356 ms。這組載入結果未過門檻。若只看「手指滑動後多久有反應」會漏掉主要差額。首滑手指接觸期間的呈現幀距合併 p95 為 49.8 → 63.4 ms，設定動畫的相同統計為 50.5 → 63.2 ms。七對的原始檔與每段分布在 `forum-pilot-ab-7/rounds.json` 及 `frame-summary.json`。資料庫查詢、取得正文、背景解析與 GPU 等候須依各自時段歸因。


將正文準備移入頁面載入後，在新的七對量測中，`216eae22` 的點擊至正確正文 p50/p95 為 1,659/2,195 ms，這批 tag `12` 為 1,520/2,329 ms。配對差為 +111、+287、+361、−168、−741、+881、−156 ms，中位數 +111 ms。同一次點擊到首個實際滑動反應的 p50/p95 為 1,837/2,328 ms，對照 1,687/2,499 ms。七對均測得正確正文。當批基線不同，兩批配對差不能直接相減作為修正的因果效益，也未達 +1 ms 驗收要求。詳見暫存證據 `first-paint-ab-7/pilot-summary.json` 與 `frame-batch.json`。

最後候選 `88557be5`（產品差異指紋 `d865399e`）完成七對交錯試跑，14 次均在同頁、同一段及同一像素位置受測。點帖至正文畫出的 p50/p95，tag 12 為 1,464.8/1,570.1 ms，候選為 1,608.4/1,798.9 ms，中位數增加 143.5 ms。同次點擊至首個手勢產生可見位移，從 1,613.8/1,702.6 ms 增至 1,759.0/1,965.5 ms。七對有六對的兩項時間都變慢。手勢本身至可見位移的中位數增加 7.7 ms，點設定至面板穩定增加 8.6 ms。候選的設定動畫最長幀間距達 132.7 ms。這組錄影辨識正文的相鄰影格間隔中位數為 305.1 ms，另有主機同時執行功能驗證的影響。正式 1 ms 門檻仍待處理。原始資料在 `%TEMP%/yamibo-reader-release-ab-20261125/combined-miss-ab-3`，內有全部七對；同層的 `pre-sleep-performance-report.md` 彙整三版結果。

<a id="shared-library-verification"></a>

## 2026-11-25 共用下載庫與舊 CBZ

公開驗證提交為 `466afe3`，對應 rebase 前的產品提交 `7ecf968`（本機重放後為 `fbb5048`）。這些提交的 common、Android、iOS 產品來源與翻譯逐檔相同。後續本機的圖卡與其他修正另列證據。Android 執行 `:shared:testDebugUnitTest :composeApp:assembleDebug`，並在獨立模擬器使用正式的檔案選取與 reader 操作。 [隔離驗證提交](https://github.com/LittleSurvival/yamibo-app/commit/466afe3)

同帳號、同下載資料夾內，管理頁依 TID 合併來源，正文依 PID 儲存，圖片依 SHA-256 共用。Android 和 iOS 的 reader 沿用每個來源的圖片次序與出現位置。在下載管理批次轉換時，未勾選自動移除便保留損壞的 CBZ。使用者可以在轉換前或失敗後勾選「自動移除損壞的 CBZ」，設定只作用於本次轉換。缺檔、權限／一般 I/O 錯誤、未知成員及衝突的既有版本均保留原檔。

| 實際執行 | 結果 |
| --- | --- |
| Android 共用測試、Debug APK | 32 項通過。涵蓋首次部分成功、更新失敗保留原版本、RSS 和帖子共用 PID／圖片、最後引用移除、壞包預設保留及選擇移除、散圖錨點、舊封面 URI、索引中斷重建和開啟目標頁時跳過萬筆清單。測試碼在驗證分支。 |
| Android SAF，完整 CBZ | 在下載管理頁實際執行轉換。從模擬器取回檔案逐一比對，3 張圖片共 17,140 bytes，與原 CBZ 成員逐位元組相同。正文包只有 1401、1402、1403 三個 PID。轉換後可開智能漫畫。 |
| Android SAF，壞包與選項 | 預設和預設深色主題都開過轉換確認與失敗對話框。預設保留、取消後仍可重試，恢復正確 CBZ 後可完整匯入。深色勾選重試後，原包移除，保留 3 個 PID、2 張可讀圖片並記錄缺 1 張；其餘兩張均與舊包原始位元組相同。 |
| Android 圖片模組 | 未移除壞包時，在第 1/3 張的「圖片載入失敗」標準圖卡內顯示「這張離線圖片無法讀取」。移除後圖卡在原位顯示「這張圖片缺少離線檔案」。兩者沿用相同的「重新載入」按鈕。reader 重試時才檢查該舊 CBZ；確認損壞後先保存可讀成員與缺圖位置，刪除此包，再連線補這張圖。兩種狀態都能翻到第 2/3 張並顯示正常圖片。用無效網域重試顯示失敗訊息並停在原位。在小說正文斷網按「重新載入」後核對資料夾，僅 TID 143 的舊包被移除，TID 142 的壞包仍在。TID 143 保留 2 張、標記缺 1 張。恢復連線後在同一張卡重試，3 張齊全，補回的 PNG 與固定版本原圖的 10,782 bytes 相同。 |
| 第一輪對照的橫卡（395b007） | 此次對齊的是 #89 合併後的橫卡。使用者記得的直式卡在 tag 12，這輪對比無法驗證直式佔位與圖片圖示。以 TID 144 相同網址與樓層，對照舊版 HTTP 504 和候選 CBZ 失敗。兩種主題的標題、網址、重載按鈕文字裁切均逐像素相同。中文原因的字型行高讓卡片多 6 px。舊版 side-by-side 測試 App 僅更改套件名及該帖的網路測資輸入。 |
| Android 資料庫升級 | 早期本機測試 APK 在同一 schema 版本留下兩張實驗表，覆蓋安裝後開管理頁曾因缺少 OfflineInventory 而退出。保留該 DB 後，用一份含原資料、符合先前 schema 45 的獨立副本啟動候選版本，驗證升至 46、新增四張表並可正常轉換。 |
| iPhone UI | 驗證版 `466afe3`，iPhone 17 Pro 模擬器（iOS 26.4.1）。下載的 XCTest 結果共 6/6 通過、0 跳過。論壇、小說、智能漫畫各跑預設與預設深色。深色的論壇與智能漫畫使用損壞 CBZ，測試另驗了 3 個 PID、2 份有效圖片與缺 1 張。UI run 早於下節的本機修正。 [UI run 與 xcresult](https://github.com/LittleSurvival/yamibo-app/actions/runs/45466409142) |
| iOS native | 同版 native 工作因 macOS arm64 runner 容量不足結束，執行 0 個步驟。較早的 `c092ea4` 有 native 測試通過的紀錄。 [本版 native 工作](https://github.com/LittleSurvival/yamibo-app/actions/runs/45466408920) · [先前 native 通過](https://github.com/LittleSurvival/yamibo-app/actions/runs/45464198703) |

### Reader 圖片失敗卡的歷史基準

tag `12`（`6d07ed8`）的直式佔位會依圖片幾何資料保留高度，中央依序顯示原圖示、標題、原因、網址與重試。2026-11-19 的 `3c7b90f` 為了讓小尺寸的重載可見，將所有尺寸改成橫排並刪掉圖示與高度。之後的 `395b007` 只能作為橫卡參考。這次用 tag 12 的獨立套件重開模擬器上的 TID 144，實際觸發 Coil 的 HTTP 504，再以目前工作樹比較。僅為兩版共存更改歷史 APK 的套件名。 [tag 12 原始元件](https://github.com/LittleSurvival/yamibo-app/blob/6d07ed8ac348a2f06a0bcbafacb2b99ad3086e94/composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/thread/image/ImageViewer.kt#L394) · [橫排的來源](https://github.com/LittleSurvival/yamibo-app/commit/3c7b90f3c8de2e666fe966751ff01e00baf9afcd)

| Android 驗證 | 實際結果 |
| --- | --- |
| 正式 reader 的一般 HTTP 失敗 | 歷史 tag 12 用 TID 144。現行版從另設的 QA 帖子開相同圖片與首樓正文，走一般網路圖片路徑。最終深、淺色重拍均使用 TID 9145，在同一資源的 URL 加入測試查詢參數以避開已命中的快取。飛航時 Coil 實際回報 HTTP 504。正常寬度保留舊圖示、標題、原因、底線網址及重載。深色時兩版 HTTP 卡實測均高 1,589 px，圖示均為 192 × 192 px（64 dp）。最終版和 tag 12 的淺色卡底都是 `#F3F3F3`，深色畫面取樣均為 `#2C2523`。中途曾量到 `#FFF7E0`，追到 #98 的 `3e20d77` 後已修正。 |
| 損壞 CBZ | 在 TID 144 第一樓顯示同一個元件，原因列是「這張離線圖片無法讀取」。這個樣本的卡高 1,776 px。包內其餘兩張圖的原始尺寸為 800 × 1,200。187 px 差額吻合 reader 用已知圖片比例 1.50 和冷開估計值 1.35 預留高度的結果，沒有採集當下的記憶體比例值。 |
| 受限容器與大字 | 前一輪 300 × 105 dp、170% 字號的第一次實作保住了重載，但原因在首屏被遮住。最終版在橫屏與直屏均量到原因首屏高 43 px（21.5 dp），重載可點區高 100 px（50 dp）。兩個方向皆能捲到來源網址並點擊重載一次。另外在 100 × 130 dp 的離線封面與最高 105 dp 的缺圖上，按鈕可點區皆高 48 dp。在隔離的 Android 畫面合計驗五例，五次點擊各觸發一次對應回調。該測具的網路原因由測試輸入，真正的 Coil 504 另見上列。 |
| 編譯與共用層 | 產品與驗證工作樹各自產生 Android Debug APK，另外執行 `:shared:testDebugUnitTest --rerun --no-build-cache`，7 組 47 項通過、0 失敗、0 跳過。這一版的 iOS UI 尚未重跑。 |

裝置與最終 APK 的 SHA-256 均為 `8dce035a756f2a1c0c299b17ac2505ecceaefd1c24221d0c333b73f5e4c9814c`。本機兩主題原圖、UI XML、`tag12-reader-cards-light.png`、`tag12-reader-cards-dark.png` 與 `tag12-card-observations.json` 位於 `.tmp/offline-library/`。小尺寸截圖和按鈕回調另見其下的 `reader-error-layout-qa/final/report.md`。

原始 Android 截圖及拉回的檔案保存在本機 `.tmp/offline-library/`。上表為已執行範圍。跨兩台裝置的 AppSync、24 個跨模式冷啟／往返、兩平台各情境 40 輪首屏延遲與資料夾供應器長帖效能仍需執行。不同網址缺少附件版本和尺寸證據時可各傳輸一次，內容相同只保留一份。

<a id="local-integration"></a>

### Rebase 後的本機整合

產品工作樹基於 `fbb5048`，驗證工作樹基於 `466afe3`。下表記錄未提交的本機修正。測試使用實際 SQLite、檔案與封包，並以可控制的來源計算請求次數。模擬器操作、真站回放和線上請求分別記錄。

| 範圍 | 重現與結果 |
| --- | --- |
| 本機整合結果 | 產品工作樹 `:composeApp:assembleDebug` 成功。驗證工作樹執行 `:shared:testDebugUnitTest :composeApp:assembleDebug` 成功，共 7 組、47 項通過，失敗、錯誤及跳過各 0。兩樹逐一比對共用及平台產品來源、資源與翻譯的 717 個受版控檔案，統一文字換行後內容相同。逐檔摘要與 XML 組數見本機 `working-tree-validation.json`。 |
| 論壇、標籤、RSS 共用一頁 | 原先更新後正文剩一張圖，RSS 管理列仍列兩張。本機修正一次更新所有受影響來源的圖數與容量，直接編輯正文也會回收失去最後引用的圖片。移除一個來源、重開 App 後，其他來源仍可讀。若該來源還有舊包，管理頁繼續標示可遷移。 |
| 下載佇列 | 原先 RSS 因缺圖失敗，論壇補齊圖片後，RSS 仍顯示失敗。本機修正同步更新佇列並寫回，重開亦能校正舊狀態。主動刷新完整副本時若失敗，仍顯示失敗供重試。 |
| 切換帳號 | 掃描途中由 A 切至 B，曾將 A 的結果寫進 B 的快取，使 B 的下載清單看似空白。修正後核對發起掃描時的帳號與資料夾，重開後也從選定資料夾重建曾混用的舊快取。另以兩個帳號各下載一帖重現：B 開過內容管理後，回到 A，已下載數為 1，佇列卻是空的。帳號與下載鍵一起核對後，相同 TID、不同 TID 的兩組測試都能保留各自的標題、任務和磁碟紀錄，切回與重開皆可見。 |
| 不同帳號排入同一帖 | 在 A 暫停全帖下載、切換 B 排入同一 TID 單頁後恢復。修正前，B 的首樓確實存入 `public fixture A`。修正後 B 取得 `public fixture B`，切回 A 能使用原先預取的 `public fixture A`，合計 2 次頁面取得。兩帳號排入同一 RSS／標籤鍵時也各自保有查詢、章名與來源頁。這些是隔離環境的合成正文。 |
| 請求與寫入量 | 同一 PID 圖序 A、B、A 首存共抓 1 頁、2 張圖，寫 1 個正文包與 2 份圖片。為完整頁面增加 RSS 來源只寫 1 次 manifest，正文請求、正文包重寫與圖片讀寫均為 0。新增第二頁或只改其文字，僅寫入含 PID 1403 的正文包。另一來源在首個頁面回應後、儲存前加入，兩個來源合計抓 1 頁、1 張圖。 |
| 智能漫畫目錄 | 對保存的五頁真站資料，用正式目錄函式重放 160 個 TID，混入 30 筆重複候選。七組有獨立預期的作品共 98 個入口，升降冪皆符合，包括 58 話的《DOUBLE HELIX BLOSSOM》。這次向板塊、搜尋與四帖發出的 6 次匿名請求均為 HTTP 403。TID 579092 只有一筆標題「。05」，章號仍待相鄰章佐證。存檔也無法驗證同作者第三個圖樓與跨頁續貼。 |

在控制例中讓同一話的「下篇」先發帖，原排序會先列下篇。修正辨識明確章號後的「上／中／下篇」，正序顯示上、中、下，倒序反轉。年份、無關括號內容與 TID 579092 的歧義均保留。重放上述 160 個真帖入口，修正前後輸出一致。

本機證據位於 `.tmp/offline-library/`，含 395b007 橫卡／當時候選圖卡對照、離線重試前後的檔案、首次失敗的測試紀錄及目錄預期清單（`smart-comic-catalog-verdict.json`）。以上請求與讀寫計數屬於 repository 測試；P01–P04 的首屏與捲動量測仍使用下列逐輪驗收標準。

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
- N01–N08 需要實際 App 的論壇、Coil 和下載 transport 與受控伺服器雙端紀錄。驗證器在本機 HTTP 對照通過 2 個合法案例，並識別了重複請求、漏記與未宣告跳轉 3 個錯誤。全離線零嘗試與首屏請求優先序仍待實測。
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
| F12 失敗及恢復 | 斷網、403／登入失效、5xx、超時、資料夾失效、空間不足；下載刷新在寫新包與切換引用之間中止並重啟。 | 無無限重試；仍可讀最後完整副本，指出缺項與失敗原因；權限失效不借用他人副本。恢復後只補缺項，重複執行結果相同。 |
| F13 升級與同步 | C 從舊版升級，在遷移中途停止再執行；Android／iOS 分別寫入與還原；操作清歷史、刪下載、另一裝置上線及舊版同步。 | 原入口能開舊包；普通／小說及作者範圍不混合。清歷史後不復活、離線內容仍在；遠端進度不代表此裝置有檔案。逐項註記舊格式能還原的精度。 |
| F14 UI 與真站 | 預設與深色主題開啟選擇、部分可讀、失敗詳情及重試。真站在不同話開《DOUBLE HELIX BLOSSOM》與《青梅竹马BIG LOVE》，再核對小數話及分段排序。 | 實際操作按鈕與渲染內容；保存當時分頁清單與手動核對結果。真站拒絕存取、登入過期或資料變動時如實記錄，不把歷史話數當成當天真值。 |

### PID 與圖片去重驗收

以下是 F09、F11–F13 與 N05 的待驗案例。先清一般快取，在同一帳號準備一個單頁作者帖。PID 1402 的圖序為 A、B、A，PID 1403 再引用 A。兩圖的位元組、雜湊及長度已知。透過正常下載入口操作，分別記錄伺服器收到的請求、下載庫列數、在用的 PID 正文及實際圖片資料份數。檢查所有檔案與封包內的圖片項目，並將圖片位元組與索引、封包開銷分開計算。

| 操作 | 預期結果 |
| --- | --- |
| 先由 RSS 下載，再由標籤、智能漫畫、小說及論壇要求相同的已知作者範圍。另從空庫同時發起相同範圍的下載。 | 下載庫有 1 筆 TID，正文有 2 個 PID。首輪及並行輪各取作者頁 1 次、A 和 B 各 1 次。依序重複加入時，額外正文和圖片傳輸皆為 0。磁碟共存 2 份圖片資料，圖片用量等於 A、B 長度之和。三種模式均能離線開啟；回到 1402 內第二次出現的 A，仍定位在第 3 張。 |
| 將下載範圍擴為全帖，該頁另有一則新回覆引用 A；另測兩個頁次的邊界不同。 | 覆蓋未知時只抓必要的論壇頁。1402、1403 沿用原正文，新增回覆有自己的 PID；A 的額外圖片請求和額外實體皆為 0。切換作者與全帖視圖均顯示正確順序。 |
| 同一附件透過不同網址出現，再測無已知關聯的網址送回相同位元組，以及同網址有可驗證的新版本。 | 已知同版、同尺寸的附件共用一次傳輸。無關聯網址可各取一次，驗證後相同內容只留一份。新版本若有不同位元組，更新成功後每個來源都讀到新版。另以不同帳號和無權限回應確認隔離。 |
| 讓較舊的頁面回應最後抵達，在新圖驗證前中斷，再重啟並完成更新。另從含兩份不同版本的舊下載升級。 | 核對正文和圖片雜湊，所有入口都能開最後完整版本。舊回應不能蓋過已確認的編輯。舊包版本順序不明時，離線仍可讀；取得來源確認後才發布替代版本。重跑遷移後資料份數一致，原閱讀錨點可還原。 |
| 依序刪除 RSS、標籤、其餘下載引用，再重開 App。 | 有引用時仍可離線讀，圖片用量不變；移除最後引用後回收 A、B，圖片用量扣除兩者各一次，閱讀歷史仍在。 |

儲存層另記正文包、圖片檔各自的讀寫次數與位元組。加入已完整涵蓋的新來源時，預期圖片內容讀寫皆為 **0 bytes**、正文包重寫 **0 次**。只改正文時，預期圖片重寫 **0 bytes**。讓第二個入口在第一個來源已收到 HTTP 回應、尚未發布時加入，兩項來源應共用同一次傳輸且各自保留下載引用。模擬 manifest 已發布、SQL 索引未更新時中止，以及 SQL 索引遺失後從下載資料夾重建，核對 PID、來源、圖序及實體用量。

用同一份長帖與圖片，比對目前 CBZ 和提議格式在 Android SAF、iOS 的整帖下載總寫入量、單樓編輯耗時、首圖開檔次數及 T1／T2 p95。補測 10,000 筆無關下載的 P04，首屏前讀取無關圖片或封包 **0 次**。這些數字用來檢查單檔圖片和每帖正文包的成本。 [儲存與實作方案](forum-reading-modes.md#proposed-implementation)

### 共用下載庫的回歸範圍

掃描單位包含兩個 Gradle 模組、Android／iOS 實作、SQLDelight schema、iOS 宿主、翻譯與建置腳本。先找下載介面、任務 key、封包路徑、本機圖片 URI 及歷史資料表，再沿呼叫端查到下表。每一列的「核對」都是驗收要求；實跑結果另外記錄。

| 使用入口與程式位置 | 容易受影響的行為與核對方式 |
| --- | --- |
| 下載管理、個人頁、設定容量。 `DownloadQueueScreen`、`DownloadedContentPresentation`、`SettingsCategoryScreen` | 一帖只有一筆，同一頁可有多個來源；容量按實體圖計算，設定裡的帖頁／標籤章數仍按原始來源計算。覆蓋篩選、全選、清除單項與整組。 |
| 板塊選帖、小說詳情、收藏批次、標籤、RSS 和更新頁。 `FavoriteBatchDownload`、`FavoritePostAddDownloadSheet`、`UpdatesScreen`、`SmartComicDownloadDialog` | 原來源 key 仍可刷新、取消及重試；作者頁和全帖的分頁範圍各自保留。檢查不同來源同時加入、由任一來源更新編輯樓層、回看整部下載狀態。 |
| 論壇、小說、漫畫、圖檢視器、Coil。 `ThreadPageValidation`、`ReaderResume`、`ReaderImages`、`ImageViewer`、`ArchivedImageFetcher` | 從舊 CBZ 連結、散圖及新圖片連結冷啟。驗證字節、PID、重複圖的出現次序、缺圖重試和直接打開指定樓層；本機讀取要與受控網路紀錄對照。 |
| 封面、歷史、收藏與深連結。 `ContentCoverRepositoryImpl`、`ReaderImageAnchor`、`ReaderTextAnchor`、`InAppLinkNavigator` | 散圖移除前重映封面和該帖的圖片錨點；舊版 CBZ 來源識別與歷史時間保持可用。刪掉一個來源後檢查剩餘來源與離線閱讀。 |
| Android SAF、iOS 選定資料夾、資料庫與重新掃描。 `AndroidDownloadStorageProvider`、`IOSDownloadStorageProvider`、`ReaderDownload.sq` | 驗證換資料夾、換帳號、安裝升級、空間不足、壞圖、無正文舊包、發布期間終止及索引遺失。只在新檔案可讀後回收舊來源；從資料夾可重建索引。 |
| Android 背景服務、下載佇列及移除任務。 `DownloadForegroundService`、`AndroidDownloadBackgroundController`、`DownloadRepositoryImpl` | 前景下載轉背景、結束程序再啟動、取消最後一個消費者，核對任務身分、進度和最後完整版本。刪除與寫入並行時，另一個來源仍可開圖。 |
| 備份還原與 AppSync。 `BackupModels`、`OperationRecordingReadHistoryRepository`、兩平台歷史 repository | 換機測試應分別核對「同步回來的閱讀位置」與「這台裝置實際有檔案」。備份還原／清歷史後，逐項確認時間、作者範圍、舊圖片連結與離線清單。 |

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
| N08 | 正文已載，重按刷新／重試。受控端回應一般權限 403 或持續不回應。另測重新登入成功後的重試。 | 一般權限 403 首次 1 次，靜置重送 0。無回應時多次點擊只保留 1 個在途請求。取消後不排新工作；恢復後一次使用者重試至多 1 輪。獨立記錄登入／WAF 挑戰的合法往返，以免掩蓋重送正文。 |

另跑兩個導致「請求數看似正常，實際很慢」的案例。讓舊話的預載占滿受控端連線，再開新話，驗證新首圖在釋放舊預載前可請求並繪出；把單張圖變成大檔，驗證篩選、倒序及切背景不會重新傳輸該圖。每個請求記排隊、首次位元組、下載完成與傳輸量，檢查重複傳送和首圖優先順序。

計數器要先通過負向對照：驗證工具故意多送一次相同資源、停用圖片 transport 計數、讓伺服器回 redirect 各一次，確認重複、遺漏及跳轉都能被發現。若兩端計數無法解釋，該輪不可用來證明「零請求」。

## Reader 首屏與卡頓

**計時點。** T0 是使用者在板塊／下載管理點帖或在詳情按「繼續」的單調時鐘時間。T1 是首個已呈現且位置正確的內容幀，文字比對 PID、段落及可見字樣，漫畫比對目標圖片的視覺標記已實際繪出。T1 後立即注入固定手勢，另記手勢開始和預期畫面變化首次呈現的 T2。報告 T1−T0、T2−T0、T2−T1 與手勢自身延遲，若測試端未能按時注入就保留為儀器失敗。另記目錄可用時間、上一幀至下一幀的間距、主執行緒工作、HTTP waterfall。小說另計「點帖 → 詳情可操作」及兩段總和，扣除測試手勢間人為停留。使用同時鐘的輸入與呈現事件，截圖比對在量測後進行，避免 UI dump 輪詢影響數值。

本輪比較的基線是 Git tag `12`（`6d07ed8`）。主要成本用 **T0 到 T1**（點擊到正確正文首幀）和 **T0 到 T2**（同一次點擊到第一個手勢有可見反應）表示。兩者都包含轉場、佈局和實際繪製。另報注入手勢前的停留，保留各段原始時戳。錄影追蹤首個正文像素與正文到達最終位置，Perfetto 對照每次呈現。轉場中段、各次滑動、點開設定各有獨立的活躍時間窗。靜止閱讀期間的長空檔不計為捲動掉幀。

本輪載入回歸門檻為 **+1 ms**。報告同一路徑的配對差、p50、p95、觀測精度和同版本 A/A。60 Hz 螢幕每次更新約 16.7 ms。若信賴區間或 A/A 波動跨過門檻，結論為「未能證明低於 +1 ms」。下方既有的較寬預算僅保留作歷史數字。

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

## 11 月 25 日非效能驗收狀態

第一輪 Android 產品快照為 `51b5a34f`，裝置功能 QA APK 為 `56c1cfcd`。本節保留當時的結果。後續修復及裝置驗證見[第二輪 Android 功能驗收](#11-月-25-日第二輪-android-功能驗收)。

| 範圍 | 截至本輪的證據與缺口 |
| --- | --- |
| 三模式續讀、離線與重試 | 舊測試版三入口實際下載的副本在新版六方向飛航冷開 6/6、暖切換與重啟 6/6。新版另有小說新下載、清快取、繁簡切換與重開。三模式各連線、飛航共 6/6 次開本機副本皆零內容請求。指定頁／PID 的整頁 503 在兩主題共 4/4 次重試回到第 3 頁。 |
| 去重、CBZ 與錯圖 | 整合版 JVM 72/72。三入口建立下載、實際儲存用量與補抓單張損壞圖片有較早 Android QA 版本的裝置證據。損壞舊 CBZ 的保存可讀圖片、移除目標舊包與補回原圖，以及 tag 12 直式圖卡的 12 格有各自版本的裝置紀錄。目前版本的整頁 503 重試不能代替單張圖卡與舊包流程的完整複驗。 |
| 已觀察到的問題 | 最新版由看板進入時，在點帖前發生 1 次 Compose 無障礙座標崩潰。另做 20 次候選冷啟，遇到 1 次 AndroidX ProfileInstaller 崩潰。兩案均待確認成因及修正。Default Dark 整頁「載入失敗」標題對比為 1.042:1，屬既有可讀性缺陷。 |
| 其餘 Android 驗收 | F07–F14、N02–N08 各有部分案例或共用層測試，整套端到端結果仍有缺口。包括收藏／RSS／標籤等入口、快速切換後舊進度晚寫、智能漫畫真站跨頁漏章與同名／權限、下載佇列進背景與程序中止、下載失敗後的恢復、備份及雙裝置 AppSync。真站曾對匿名漫畫板塊、搜尋及帖頁回 403；存檔重放保留為獨立證據。 |
| iOS | 依本輪範圍暫緩。早期 `466afe3` 的六個 iPhone UI 案例通過；目前產品快照的 iOS 結果仍空缺。 |


## 11 月 25 日第二輪 Android 功能驗收

本輪以 Beta 功能分支的未提交工作樹測試。下列八位代碼為產品差異的 SHA-256 前綴，各 APK 的 SHA-256 另存於原始證據。測試器、Android 系統選檔器及本地受控 HTTP 伺服器各自記錄觀察值。iOS 與首屏、轉場、滑動耗時均待獨立驗收。 AppSync 依使用者指示暫停。下列同步結果僅保留先前已執行的證據，本輪完成條件只涵蓋本機閱讀、下載與本機備份。

| 項目 | 修復及實測結果 |
| --- | --- |
| F08 寫入先後 | 同帖先記論壇 100、再記 RSS 150，最後提交舊論壇位置 80。修正前同步佇列出現 100、150、80，修正後兩個來源各建立一筆，另一份 SQLite 重放兩次後保持各自段落。另測同毫秒精確圖位被舊格式圖序取代，接收端能丟棄舊的重複圖定位。 |
| F13 清歷史 | 「論壇／RSS 同帖」本機一次移除兩個來源，舊版只發出一筆刪除通知。修正後，單帖、選取、混合選取、清所有文字、清全部歷史五種操作都比對兩份資料庫，重播後確認位置與無關帖一致。 |
| F13 備份、跨裝置 | `6ddec58a`、兩台獨立飛航 Android 16。發送端從系統選檔器授權 SAF，實際寫出備份；接收端合併、再次傳入、冷開，保留兩個來源的段落，以及同一樓第二次出現的圖片。較早備份不覆蓋後來的圖序。再傳回缺少新欄位的數字圖序與兩筆刪除操作，驗證舊圖位不會套到新紀錄、清除後不復活、接收端的下載索引為空。同步操作由 ADB 離線搬運，未對外部帳號發文。兩份 APK 相同，兩個安裝目錄各自獨立。 |
| F13 升級 | 以未修改的 tag `12`（`6d07ed8`）建置，同套件更新。兩台均用舊版的「載入備份 → 合併新增」還原新版檔案，從 schema 42 讀回兩筆段落與原模式圖序。一般更新使用 `59e472a0` 產品 APK。另一次在 SQLDelight 的 42→43 遷移交易尚未提交時強制停止，核對整份既有歷史及表結構回到 42，再由同版 QA APK 冷開。兩次最終都為 schema **46**，SQLite `quick_check` 為 ok，既有三筆歷史逐欄一致，沒有自動補造精確圖位或離線副本。首輪腳本誤用早期計畫的 45；已改從實際遷移版本計算，再從舊版資料重做。 |
| F11／F12 部分下載 | `42abc118` 由論壇、小說、漫畫的實際按鈕建立下載，三者各有五個圖片位置，均只存三份圖像，共 17,140 bytes。服務寫入中強停後，下一次啟動自行完成三頁。實際 SAF 損壞舊 CBZ 只補 C 一次，兩張有效圖及無關 CBZ 的 SHA 相同。 |
| 單圖與整頁失敗 | `42abc118` 原直式圖卡 12/12。一般網路失敗與壞 CBZ 共用原卡，「重新載入」走對應修復。日夜整頁標題逐圖測得，深色從 1.042:1 改為 11.366:1，預設維持 11.749:1。原比對器曾把狀態列的一個同色像素算入圖卡高度，已改量與圖標相連的背景，再以同一 APK 重跑。 |
| N03 跳頁與預載 | 由論壇與指定作者小說直接開第 3 頁，修正前未操作就額外取第 2 頁。修正後初次僅取第 3 頁一次，正向／反向操作後仍可載入相鄰頁。受控 HTTP 兩端以同一請求識別碼核對。 |
| N07 漫畫圖片 | 冷開的七個圖位 A、B、A、B、C、D、E 逐頁核對畫面及樓層。快速 A→B→A 時舊候選對 B 發出四次 GET；`59e472a0` 的同條路徑只發一次。滑出相鄰頁後則會取消該工作。 |
| 已觀察到的兩次崩潰 | Android 16 Play 的 Compose 無障礙座標例外與 AndroidX ProfileInstaller 轉碼例外保留原失敗。額外 36 次冷開皆無 fatal，33/36 進入指定置頂帖，三次 UI 等待逾時。Android 15 和 16 的兩版本各一次真 TalkBack 導覽皆成功；同一 APK 的實際 profile 反覆轉碼成功。尚缺可重現步驟與已驗證根因。 |
| F07 入口與 F08 快切 | 實際點收藏、歷史、RSS、標籤、指定樓層、板塊與小說詳情／繼續，對照 TID、PID 和作者。兩個板塊分別設圖讀與小說，強停再開各保留其設定。快速切話、滑動再回背景共 1.379 秒，重開停在新的第 2 張。手動標記未讀能跨多次冷啟保留，非作者回覆的未讀標記仍在。後續在連續捲動模式另驗出首圖段內偏移及同圖滑動落盤缺陷，單頁模式的通過結果並未涵蓋這兩項。 |
| N02、N04 與 N06 | 開已驗證的兩話目錄後，旋轉、反轉排序、關閉並重開，兩列按預期逆轉，額外目錄、正文、圖片請求皆為 0。刻意阻塞過期目錄的搜尋 7.87 秒，當頁仍可讀、可翻到下一圖並退出。小說從真列表進詳情再進正文，冷開 1 個正文 GET，保鮮重開 0 個，過期 1 個，手動刷新 1 個且進 reader 時沒有重送。已下載版本從離線副本進入和繼續皆為 0 個。 |
| F12 錯因與重試 | `a20401f4` 下載管理用既有狀態顯示 HTTP 403、503、斷線與真的 30 秒讀取逾時，冷開仍能看到原因。部分副本保留；重試只取一張缺圖。403 刪除的是失敗任務時，比對已記錄的第 3 頁 PID、ZIP、圖片和 manifest 均相同。亮色失敗原因文字從 2.923:1 提高到 11.749:1，深色為 16.187:1。 |
| F13 實際同步引擎與版本互通 | `72f7b522`、兩台 Android 各執行產品 OperationSyncEngine，測試伺服器以 HTTP 儲存兩份真實 journal。接收端斷線不出現新資料，恢復後讀回原段落與同樓重複圖片。發送端離線清除文字歷史，兩端恢復後連續同步兩次，文字不復活，漫畫圖位保留。共 12 筆 HTTP，包含模擬站台暫停時的 503。另以 tag 12 原始碼及候選編譯的 registry、journal 和 checkpoint codec 雙向驗證，保留位置欄位及兩筆刪除標記。此項驗收以兩端進度及刪除後重播的結果判定。測試封包全程由本機伺服器保存。 |
| 真站與需要授權的範圍 | Android 訪客能打開置頂帖 TID 535389，指定 PID 40718593 的連結進入第 3 樓。真站的關鍵字搜尋顯示需登入。漫畫板 fid 30 匿名回 403；已保存受控站、舊存檔與過往真站各自的證據，目前的全站漏章率仍無法由這些樣本判定。 |
| F10 作品、排序與帳號 | `71169085` 實際 Android SQLite 及作品 repository。跨頁回傳重複 TID，合併後順序只留 120、140、160。第二頁被拒時，畫面暫時顯示新發現的 150，已驗證的三話資料仍完整保留。另一組同名作品以兩個帳號各自儲存相同種子帖、不同成員。第一個帳號的作品改名並插入較早的一話，重新建立 repository 後成員順序為 1、2、4，上次讀的 TID 仍為第 4 話。另一帳號保持原名及原來兩個成員，跨帳號專屬章節查詢皆為空。 |
| F08 連續模式 | 首圖保存偏移 480 px，舊版冷開畫面實際少還原 480 px；同一圖內移動也漏存。第一版修正遇到 440 px 的載入佔位，冷開誤跳第二張。`71169085` 等待該圖完成尺寸量測，首圖 480 px 精確還原，再滑 184 px 存成 664 px；HOME 後沒有重寫，冷開亦停在 664 px。中段的 232 px、使用者先滑 182 px、13.4 秒晚回、HTTP 503、途中切話、無歷史進首圖均有實際畫面及資料庫對照。錯圖仍能往下一張，舊圖回覆沒有拉回畫面。 |
| F09／N05 舊檔及變動後定位 | 同樓的 B 第二次出現原在 4/5，重排後重開停在 3/5，刪除該次出現後退回同樓首張 A（2/4）。重開沒有覆寫原始 PID、圖識別值、出現次序及時間。整帖只缺第 2 頁時只抓該頁一次；舊標籤漫畫只有圖、缺第 1 頁正文時，經實際遷移後只取第 1 頁，不重抓圖片。兩種情況再次整帖下載均新增 0 個正文 GET、0 個圖片 GET、0 個封包。 |
| F08 同步與本機晚寫交錯 | 兩份獨立 SQLite 先各自寫入。另一裝置時間 300 的文字及漫畫位置，恰在本機時間 200 的舊回呼即將寫入時套用。舊版保住本機 300，仍向同步佇列送出兩筆 200。產品快照 `c58545e9` 在同一筆交易做寫入與登記，讀回先前時間，兩個舊回呼都沒有排出同步操作。Android JVM 78/78。 |
| F14 選擇器與不完整下載 | `71169085` 在預設、深色實際操作本話 → 全部 8 話 → 本話，選取數皆為 1 → 8 → 1。第 5 話標成部分、第 6 話完整；第 7 話只剩圖，從下載管理看到缺正文說明，點開始閱讀後實際顯示 1/1 與原圖。兩主題各比對到 710,844 個符合的像素。真站兩個作品的首次訪客請求皆回 403。 |
| N01 開啟完整離線副本 | `71169085`，論壇、小說、智能漫畫各在有網路及飛航模式從下載管理開啟。6/6 均命中正確內容，App 的論壇、圖片、下載觀測點與受控伺服器能核對，內容請求皆為 0。首輪輸入 SHA 大小寫格式被驗證器拒絕，保留為工具輸入失敗。再用整合版 `24287161` 從三入口重做同六格，正確首屏後各續觀察 5.23–5.34 秒，App 嘗試、伺服器收到、未完成的內容請求三者均為 0。觀察窗外的 18 個探針識別碼在兩端各出現一次。 |
| N03 已定位頁重用 | 從正式連結解析入口只給 TID/PID。真 SDK 發出一次 findpost，第 3 頁已隨回應交給 reader，畫面出現指定 PID，SQLite 只有第 3 頁。其後五秒沒有另外取第 1 或第 3 頁。 |
| N07 大圖與取消、N08 快取觀測 | 舊章 5,473,675 bytes 的圖在 1,589,248 bytes 取消，新章可在舊來源封鎖期間完成原圖。單頁模式的大圖經旋轉、範圍切換及回背景後不重傳。302 後的 200 分別列出；條件式請求對照有初次 200 與之後無實體的 304。實際 reader 也可直接命中 Coil 的磁碟副本。 |
| F04／F05 段落置中 | `71169085` 在兩條路徑讀到正確 PID，畫面仍停錯。小說第 2 段切論壇時，頁首沒有前一頁，畫面中央落到圖 C。從漫畫回論壇回覆時，頁末缺少下一頁，可見段落下移 250 px；之後存成樓內偏移 0。修正只在離線、定位被頁面邊界擋住且該作用域已有相鄰頁時讀本機。獨立裝置重現前一個錯位後，修正包顯示第 2 段。後一個案例修正包來源與返回的第 1 段都落在 y818–1008，樓內比例同為 0.333771348。 |
| F12 遺失資料夾或系統授權 | 外部搬移已選 Android 資料夾，`71169085` 的下載佇列在冷啟讀不到原位置，連續三次發生未處理的例外。另以 Android 的授權 API 真的釋放讀寫權限，管理頁露出底層錯誤，設定的資料夾標籤觸發兩次主執行緒崩潰。保留原 queue、manifest 與退出記錄，以同一批資料驗證恢復。 |
| F11／F13 刪引用與刪歷史 | `71169085`，從 UI 移除同帖文字歷史後，獨立的漫畫歷史仍有一筆，manifest、每個 PID 的正文及三張圖的雜湊相同。從下載管理重開漫畫顯示 C，重開論壇停在 PID 1402 第 2 段。只移除全帖第 1 頁後仍有三筆下載引用；移除整組、冷開後，實體圖、正文 ZIP、佇列均為 0，歷史逐欄相同。最後提交的空 manifest 為 140 bytes。 |
| N07 連續捲動的請求窗口 | `24287161`。兩組舊版冷開都在任何手勢前傳送 A–E 五圖，螢幕只容納 A 與 B。未知圖的固定高度使遠端圖過早進入載入範圍。修正後只傳 A、B，A 先抵達，往下捲到 E 每張共一次。另以 800×160 的短圖驗證，五張可見圖全自動補滿首屏。單頁快速往返、離窗取消、503 繼續滑、留空及雙向捲動另測，503 錯誤圖卡維持原 693 px 及控制項相對位置。觀測器更正以全螢幕實際像素辨識邊緣可見的 10 px 與 35 px，原先兩份多抓的樣本依然失敗。 |
| F08 作品層級的捲動設定 | 全域設定為單頁、作品鎖定為連續時，舊版先以單頁開啟並回到 A，SQLite 已明確存 B、480 px。讀取既有的記憶體設定並避免初始還原時執行切換模式的捲動後，`24287161` 在全域捲動及作品覆寫捲動均回到 B。以同圖 0 px 的畫面作對照，像素平移量兩者均為 480。 |
| F04 缺鄰頁時返回 | 用一般論壇 UI 在有完整下載的範圍記下回覆位置，再以只下載全帖第 2 頁的範圍開啟。修正前直接返回會將原樓內比例 0.269382 蓋成 0。產品快照 `4ddeb2ee` 的原值及返回後均為 0.295664；下一次真的拖到另一樓 PID 1402 的第 3 圖後，該位置可寫入。前後兩次的原始位置各由實際手勢建立。 |
| F01–F06 六個方向 | `24287161` 於兩台裝置同一 QA APK 完成。連續六個飛航冷開均有清快取、前後不同程序 PID、實際下載來源及正確內容；六個暖往返都回到來源位置，再殺程序重開仍相同。共 12/12。含缺鄰頁保護的 `4ddeb2ee` 再從實際入口下載，專門複驗 F04／F05 的冷開與暖往返，4/4。兩輪都逐筆要求下載 APK 與 reader APK 的 SHA-256 相同。早期 `71169085` 的 F03 暖返因外部 SQLite 觀測失敗保留原紅；驗證程式現會等待最多兩秒取得資料庫鎖並保留具體錯誤。 |
| F12 完整復原與同版本機備份 | `87879e95` 在撤銷 Android 讀寫權、保留授權但從外部搬走資料夾兩種情境均可開下載管理、儲存設定及本地備份設定。兩個設定頁均顯示需選資料夾；透過系統選擇器重授並冷開後，看得見 PID 1402 的正文和圖。失效前、中、後比對原 queue、manifest、每個 PID 的正文 ZIP 和三張圖，所有雜湊相同，2/2。另以正式產品 APK 在有授權的空資料夾建立一份 10,650 bytes 備份，清除隔離測試安裝後用系統選檔器匯入；原 TID 140／PID 1403 的 22 個欄位及重啟後逐項相同。 |
| F13 tag 12 實際產生的舊備份 | 原版 tag 12 在 schema 42，從備份頁實際寫出 10,068 bytes 檔案。清除該測試安裝，用 `87879e95` 正式 APK 在空的 schema 46 由系統選檔器「合併新增」。兩筆文字與一筆漫畫圖序逐欄和舊版相同，重啟仍一致，沒有憑空建立新版精確圖位。 |
| 本機備份驗證腳本 | 最後以 `87879e95` 正式 APK 從兩個乾淨安裝各跑一輪，writer 分別為現版及原版 tag 12。新版本機備份保留兩筆文字、單筆圖序、兩筆文字精確定位及一筆圖片精確定位；舊版保留兩筆文字及單筆數字圖序。系統文件選擇器實際匯入後和重啟後均逐欄相符，2/2。前兩次腳本失敗保留於原始紀錄：首次觀測讀錯欄名並把資料夾列入備份檔；另一次 Android 選檔器把導覽列的 Downloads 認成抽屜列。修正工具後，兩版均從空安裝完整重跑。 |

最終 Android 產品差異 SHA-256 為 `87879e95185dfdef3317989b18b2f5cd496b0b2da9094484fe6c1a4cc095d7ea`。正式 APK 為 `a5025760c3c6861b6f351c04bf29c170d66b35476ee94c46bff603d9b4399af2`，隔離測試 APK 為 `0cfbf4235bfe43a61e4607fb4c7b1dddfbd7c5db73114478b7d4493ba7c42280`。各案例所用快照分別列於上表。

**本機復驗。** 在驗證工作樹的 `generate_fixtures.py` 執行 `android`（六方向、加 `--require-same-apk-download`）、`android partial-resume`（缺鄰頁）、`android local-backup`（用產品設定頁實際建立、讀取備份，可指定 tag 12 為 writer）或 `android schema-upgrade`（使用 `--interrupt` 驗遷移中斷）。`check_observers.py` 的 `android-n01` 核對三條網路路徑，`:shared:testDebugUnitTest` 共 79 個案例。實際下載與復原在 `%TEMP%/yamibo-reader-allfunctional-1125/`，網路驗證在 `%TEMP%/yamibo-network-comic-20261125-174950/`，其餘模擬器原始記錄在 `D:/yamibo-nonperf-1125-sync/`。兩次早期崩潰及追加啟動抽查在 `%TEMP%/yamibo-startup-causality-1125/`。

**剩餘本輪待驗。** 真站智能漫畫兩部作品跨頁目錄及小數話／分段排序 0/4；兩起偶發啟動崩潰的根因與修復確認 0/2。訪客搜尋顯示登入要求，登入後的結果路徑已以受控帳號驗證。兩種主題及存檔重放另列在上表。
