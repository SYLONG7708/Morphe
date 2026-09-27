# 官方 Manager 來源同步

`SYLONG7708/Morphe` 是保留客製授權、更新及簽章流程的正式儲存庫；官方來源為 `MorpheApp/morphe-manager`。另建儲存庫不能消除兩邊修改同一檔案時的合併衝突，因此維持同一個正式來源與可追溯的上游標籤。

## 自動流程

- 每日 01:37 UTC 檢查官方最新正式 Release。版本未變時不建置，也不發布。
- 可以合併時，先驗證 Python 工具、Android 單元測試、Lint，以及授權版與免裝置授權版的 Release 建置。全部通過才推送合併並啟動簽章發布。
- 原始碼衝突時，中止合併，保留現行已簽章 Release，保存 `upstream-sync.json` 診斷 artifact，並按官方標籤建立一次 GitHub Issue。此情況代表需要人工整合，排程不再每天重複發送失敗通知。
- 網路、權限、依賴、測試或建置錯誤仍會使工作流程失敗；復原流程只對暫時性錯誤有限度重試。

## 新版遇到衝突時

1. 從 `main` 建立隔離工作樹，抓取 Issue 指向的官方標籤與提交。保留原始工作目錄的未提交修改。
2. 解決衝突時保留客製授權、簽章、更新渠道與受審核的來源；不得直接採用官方發布工作流程覆蓋本儲存庫的工作流程。
3. 執行 `python3 -m unittest discover -s tools -p 'test_*.py'`，再依 `upstream-sync.yml` 跑兩種 Release 的單元測試、Lint 與建置。
4. 使用保留官方提交為第二父提交的 Git merge commit；合併 PR 時選 **Create a merge commit**，不要 squash。否則下次同步會再次遇到同一批歷史衝突。
5. 驗證 `config/upstream-manager.json` 的標籤與提交、正式簽章發布結果，再關閉對應 Issue。

任意未來的程式碼衝突無法安全地無條件自動解決；同步流程會明確記錄需要檢查的版本與檔案，並持續提供目前已驗證的版本。
