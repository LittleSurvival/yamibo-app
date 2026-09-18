# Recovery 背景排程證據

Recovery phase 與 `nextRetryAtEpochMillis` 只能描述待做的工作，不能證明背景排程器已接受它。migration 51 新增 device-local `AppSyncRecoveryWork`，每個 session 保存目前的 request UUID、尚待完成交接的 predecessor UUID、retry key、執行期限，以及 requested／enqueued／started／retired 時間。此表不進入 AppSync 或可攜備份。

Retry key 綁定 generation、phase、replacement fingerprint、retry identity 與 failure count。相同工作尚未開始時，重複準備及重啟會回傳相同 UUID；phase、期限或重試身分變更後，舊 UUID 不再有執行權限。已執行的工作需要接續時使用新的 UUID。NeedsAttention 與 Completed 不建立工作。

Android worker 在同步結束後，先保存 request，再以該 UUID 建立 WorkManager request，附帶 request ID input，使用獨立於週期同步設定的 manual recovery chain。只有 enqueue operation 完成、且 WorkManager 查得到對應紀錄，才保存 enqueued 證據。enqueue 回呼結果不明時重新查同一 UUID；找不到實際紀錄就讓目前 worker 重試，不宣告已排程。程序若在 enqueue 與 SQL 確認之間終止，尚未成功結束的目前 worker 可重跑並對同一 UUID 對帳。

接續 worker 開始時再次驗證帳號、session 與 request，才標記 started 並進入同步。已開始的 worker 若在更新 phase／retry 後、保存 successor 前中斷，允許原 UUID 重跑以完成交接。若 successor 已存入 SQL 但尚未確認 enqueue，會保留 predecessor UUID，允許它在重啟後繼續接續同一個 successor；不把 successor 提前標記 started。新工作一旦確認 enqueue 或已開始，predecessor 就失去重跑權限。其餘過時 callback 直接結束；同步 lease 已由另一個 worker 持有時，交由持有者接續，避免週期工作與手動工作各自追加一份相同鏈。

UI 只有在 retry deadline 對應目前 request、enqueued 已確認且尚未 started 時，才顯示已排程的重試時間。Android UI 定期查 WorkManager：已完成、取消或原本確認過但已消失的紀錄會撤銷排程證據；尚未確認的 request 不會因 enqueue 途中的短暫查無紀錄而被取消。手動同步仍可使用。

SQLite 測試涵蓋重啟／重複確認、舊 UUID、重試期限改變、人工恢復、終態、撤銷及交易回滾；migration 測試確認既有 recovery evidence 保留。UI 測試區分期限與真正 enqueue 證據。WorkManager 程序死亡／重開機／取消時序的 emulator 驗收仍待完成；iOS 背景排程接線亦不由這份 Android 實作宣告完成。
