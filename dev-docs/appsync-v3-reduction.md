# Canonical 操作合併

更新日期：2026-09-18。`OperationReducer.reduceCanonical` 接受已表示為 canonical model 的 checkpoint 與 operation block。合併前驗證兩個輸入、帳號、操作 ID 的內容一致性、共享 proof 的一致性與合計引用數；同一 current entity 不接受多個 generation。

轉接只將 typed scalar 轉成既有 reducer 的純量表示，用同一份因果、欄位勝出、並行進度、刪除優先與 generation 裁決。輸出透過操作 ID 指回原始 canonical operation，不複製或補入 identity、cache、parent label。只有既有語意 validator 所需的衍生值會暫時重建；這些驗證用值不進入 reducer 或持久化 provenance。

canonical mutation 仍遵守允許的 kind／欄位及必要 Put 值。舊版缺少 Put 欄位清單的進度 domain 額外要求非 nullable 的 Essential 值。RSS／history Patch 只驗證有提供的時間值，不強迫攜帶未變動的 timestamp。收藏事件 Patch 仍只可更新 lifecycle markers，無法用 Patch 改寫事件 identity。

結果包含 canonical projections、目前勝出操作需要的 proof、衝突、隔離操作與實際接納操作。proof 的原始批次 count 不縮小。合併本身不推進 coverage、不 ack、不持久化，呼叫端不可因為收到較大 sequence 就跨越未知缺口。發生隔離時，上層仍須保留來源並處理問題。

此入口尚未接入正式 engine。pending legacy operations 的匯入結果（包含 Excluded／NoOp）、連續 coverage、bulk-delete policy gate、checkpoint activation 與 reader cohort 還需要整合；不能單憑 codec／reducer 通過測試啟用 v3 writer。
