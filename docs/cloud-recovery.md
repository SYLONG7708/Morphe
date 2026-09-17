# 官方同步與自動復原

雲端每 6 小時檢查官方穩定 Manager。2026-09-16 發布的官方 1.31.1
改用 `InstalledAppSource` 區分原版與已修補 APK，與 SyMorphe 的一鍵來源選擇
產生三處合併衝突。此次修復保留官方辨識、SyMorphe 自動選擇、所選補丁來源及精確版本檢查。

## 自動處理

- `tools/sync_upstream.py` 先嘗試一般 Git 合併。已審核的衝突由
  `tools/upstream_resolution.py` 套用 `config/upstream-resolutions.json` 的修復規則。
- 每條規則核對共同基底、本地、官方三份檔案 SHA-256，另驗修復 patch 與結果 SHA-256。
  規則與 patch 只從合併前的本地主分支讀取；任一份內容變動便不套用舊規則。
- 官方 1.31.1 的規則同時遷移一鍵流程呼叫端，不能只刪掉 Git 衝突標記。
- 修復後仍须通過兩種 Manager 的測試、Lint 及組裝，才推送主分支並要求正式簽章發版。
- 同步與發版都受 `Recover failed cloud workflows` 監控。暫時性網路錯誤在步驟內退避重試，
  runner 失敗也可重新執行；每個 workflow run 最多總共三次嘗試。
- 自動復原只接受本儲存庫 main 的同步／發版工作流，拒絕 PR、外部 fork、過期提交及成功工作。
  編譯、測試、憑證、雜湊、未知衝突不以盲目重跑處理。
- 每次同步保存 `official-sync-diagnosis-*`；復原保存 `cloud-recovery-*` 診斷產物及工作摘要。
  同步失敗會清除本次尚未完成的合併，保留既有可用 Release，讓下一次重試可從乾淨狀態開始。
- 排程發版同時比較已發布 tag 與目前來源；即使補丁／MicroG 版號沒變，只要 Manager
  有變更仍會發版。更新指標、維護心跳與純文件修改不造成重複發版。

使用者不需要在電腦或車機輸入維護指令。Android 安裝仍遵循正常確認流程。
未知官方介面變更仍可能需要新增經審核的修復，系統會留下實際失敗及保留舊版本，
不將「未同步」標示為「已更新」。GitHub 的舊失敗郵件也不會因新工作成功而消失。
