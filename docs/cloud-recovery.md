# 官方同步與自動復原

雲端每日檢查官方穩定 Manager，簽章發布與 Blue M 各自保留每 6 小時的檢查。2026-09-16 發布的官方 1.31.1
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

## 本機背景 Codex 修復

2026-10-20 起，`tools/morphe_codex_guard.py` 補上雲端重試無法處理的程式修復。
它直接讀取本儲存庫 main 的發布、建置、官方同步與 Blue M 工作，不依賴郵件，也不把
`Recover failed cloud workflows` 的綠燈當成發布成功。官方同步診斷內的 `state=blocked`
同樣會交給 Codex，包含 workflow 顯示成功但尚未完成上游整合的情況。

流程如下：

1. 每 5 分鐘檢查，先留 5 分鐘給既有雲端重試；不處理外部 fork、PR 或主動取消的工作。
2. 相同來源及問題去重，以作業系統檔案鎖防止重複啟動。每個問題每日最多 3 次修復，
   失敗後至少冷卻 30 分鐘，之後延長；次日恢復檢查，不永久忽略問題。
   下次修復讀取前次修改、測試與雲端失敗日誌，接續處理未通過之處。
3. 在新的獨立 clone 啟動隱藏的 `codex exec`，保留診斷、日誌、修改與結構化結果。
   使用本機既有登入；不把 Codex 登入憑證上傳 GitHub。
   下載的上游、診斷與建置輸出保留在本機，不納入修復提交；內嵌儲存庫也會被拒絕。
4. Codex 提供修改；守護程序檢查既有 cron、簽章公鑰、套件身分與更新政策，
   再執行全部 Python 測試及修復分支的兩種 Manager 建置、單元測試與 Lint。
5. 僅在確切修復提交通過、且 main 尚未變動時，以普通 fast-forward push 更新 main。
   隨後執行並驗證原本失敗的工作；任何一步失敗均記錄為未解決，保留既有可用發布。
6. 權限／登入／用量限制等不可自行解決的錯誤會留下原因並退避，不謊報修復完成。

每 30 分鐘另用固定簽章公鑰驗證公開更新清單，比對 Release 資產雜湊、完整性與
`app-release.json`／`patches-bundle.json` 指標；連續三次異常會進入修復流程。
發布修復後立即再驗，不能僅憑 workflow 綠燈宣稱手機能取得正確更新。

非互動啟動與 JSON 結果回傳採用
[OpenAI 的 Codex 非互動模式](https://learn.chatgpt.com/docs/non-interactive-mode)。
依賴快取修復只對已確認存在於 Maven Central 的確切套件啟用
[`--refresh-dependencies`](https://docs.gradle.org/current/userguide/dependency_caching.html)，
不以任意替換版本或關閉驗證掩蓋真正的依賴／編譯錯誤。

### Windows 安裝及狀態

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/install_morphe_guard.ps1 -Start
```

預設位置為 `E:\CODEX\Automation\MorpheRecovery`：

- `status.json`：最近檢查、目前階段、失敗原因與實際驗證網址。
- `state.json`：問題去重、重試時間與次數；先保存狀態才啟動 Codex，避免崩潰後重開風暴。
- `jobs\<時間與問題編號>\`：獨立原始碼、失敗日誌、Codex 結果、本機測試、雲端驗證。
- `installation.json`：啟動方式及排程註冊結果。
- 建立 `STOP` 檔可停止新檢查及修復；移除後恢復。正在執行的工作會保留其結果。

登入項目啟動隱藏監督程序，子程序退出時重啟。若帳號無法建立專用 Windows 排程，
安裝器會備份既有 Codex 大腦啟動腳本，再加入非同步的隱藏喚醒；不改原排程時間及原維護命令。
子程序亦關閉 Git／GitHub CLI 的互動提示，並依
[Git Credential Manager 官方設定](https://github.com/git-ecosystem/git-credential-manager/blob/main/docs/environment.md#gcm_interactive)
禁止憑證登入彈窗；失效登入會留下錯誤紀錄，等帳號恢復後重試。
需電腦開機、使用者登入、網路及 GitHub／Codex 登入可用。關機期間雲端排程照常，
本機 Codex 修復會在下次登入後接續。此機制檢查建置與更新發布鏈，不能直接讀取未連線手機的執行錯誤。
