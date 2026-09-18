# Canonical checkpoint 啟用交易

更新日期：2026-09-18。

`AppSyncVerifiedCanonicalCheckpoint.verify` 驗證已取得的 index 與 checkpoint 內容，要求預期帳號、blog ID、checkpoint ID 及 canonical SHA-256 完全吻合。相同 blog 的多筆引用拒絕；未知 codec、損毀、legacy 文件或缺少索引引用都不產生啟用證據。這層不負責 provider 發現、索引新鮮度、鎖定或網路身份驗證；SHA-256 是完整性檢查。

正式 remote 的載入結果現在包含 `verifiedCanonicalCheckpoints`。它使用本次取得的原始 index reader HTML 及已驗證的 canonical 文件建立證據；重新檢查 canonical bytes 的長度與摘要，防止變更記憶體中的 document 後沿用原 metadata。Index reader 頁面的 blog ID／標題必須吻合，preloaded index 也只能供同一 blog 使用。索引不再引用某份 checkpoint 時，文件仍可被發現，但不會得到啟用證據。

`AppSyncCanonicalCheckpointActivator` 在同一個 SQLite transaction 內讀取 outbox、準備 pending 合併、替換 canonical/materialized state，並透過既有 operation store 記錄 applied receipts、causal coverage 與 verified checkpoint。合併失敗或後續寫入失敗會完整回滾；outbox 的原始內容與 lifecycle 不會被確認或刪除。

啟用入口亦接受驗證後的 canonical operation block 與後續 legacy 操作。它們與本機 outbox 共用連續序號／身份衝突／授權檢查，再一起提交 applied receipts 與本機 coverage。原遠端 checkpoint row 仍保留原始 coverage，不因後續 journal 或 pending 操作而擴大。Journal 集合挑選、writer 身份衝突、索引新鮮度與正式 engine 路由仍須由上層整合。

本機合併結果使用內容衍生的 `local:` checkpoint ID。verified checkpoint row 保存的是遠端 ID、摘要及遠端 coverage；pending 增加的 coverage 只屬於本機 state，不得作為雲端清理依據。重試相同結果不重套資料；有新 pending 修改時會得到新的本機 ID。

SettingsStore 位於 SQLite 之外，因此在最外層交易提交後 reconcile。設定寫入失敗回傳 `Applied(settingsReconciled=false)`，準確表示資料庫已提交；重試可補做 reconcile。呼叫端必須處理這個旗標，不能顯示所有工作已完成。

五項 SQLite 測試涵蓋 pending 保留與再次編輯、遠端／本機 coverage 區分、內層交易已完成後的外層回滾、外部設定失敗重試、無法匯入的 pending 及帳號錯配。三項索引證據測試涵蓋正確綁定、引用歧義、無效索引及不相容文件。

此 adapter 尚未接入正式同步 engine。正式啟用仍須整合 typed remote load、run lease、索引發現／新鮮度、canonical 本機 mutation routing、帳號重設與 legacy fallback。不得據此開啟 v3 writer、宣告 cohort reader ready 或刪除 legacy evidence。
