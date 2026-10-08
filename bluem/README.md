# 藍色 M：播放恢復與穩定更新

此目錄維護已套用 Morphe 修補的 YouTube APK。它沿用既有套件、正式簽章及使用資料，搭配官方 Morphe MicroG；不依賴車機上的 Manager 重新修補。

## 播放恢復

每秒讀取 Morphe 播放器的影片進度與實際載入指示器。連續緩衝且進度停滯約 6 秒時，切換 TV → Android Creator → TV Simply，呼叫 Morphe 原生重新載入以保留影片、播放清單、位置及速度。明確播放錯誤的等待時間較短；新影片與拖曳後設有保護時間。

暫停、已結束、無可用網路及 Shorts 不觸發切換。每支影片最多切換兩次，全域十分鐘最多六次，避免網路或服務中斷時無限重播。正常播放 90 秒後記住可用來源，後續優先採用。

「緩存」在此透過取得新的串流請求處理，不清除帳號、觀看紀錄或整個 App 資料。僅對前景的一般播放器自動重新載入。背景音訊與 Shorts 同樣會阻止更新安裝打斷播放。

## 自動更新

GitHub Actions 每六小時檢查官方穩定 patches、Desktop CLI 及 MicroG。依 patches 標示的非實驗目標選取 YouTube 版本，驗證 Google APK 簽章、四種 ABI、Android 9 相容性、所有修補結果及播放 API。建置工作與持有正式金鑰的簽署工作分開執行。

簽署後再次核對套件、版本、憑證及 SHA-256。先發布不可覆寫的 APK 資產並下載驗證，再於同一個 Git commit 更新 `channels/blue-m-stable.json` 和簽章。任何失敗都不推進穩定頻道。Blue M 發布不改變 Manager 的 latest release。

App 開啟後與使用期間每六小時檢查頻道；故障重試有退避。只有簽章正確、版本較新且架構相容的 APK 才下載與安裝。播放中等待，閒置後原地更新。首次需要允許「安裝未知應用程式」；Android 12 以上符合平台條件時使用無互動自我更新，其餘情況依系統要求確認。MicroG 更新也受 Android 安裝權限限制。

App 被系統完全關閉時不另外啟動常駐服務；下次開啟接續檢查。第三方串流、官方來源或 GitHub 中斷時無法保證永久可用，失敗時保留已安裝版本。

## 驗證與維護

使用 Python 3.11+、JDK 21、Android platform 37.0 / build-tools 36.0.0：

```text
python bluem/cloud.py tools
python bluem/run_tests.py
cd bluem
python -m unittest test_cloud test_recommended_policy
```

`cloud.py probe` 產生建置計畫，`cloud.py assemble` 產生已驗證的未簽署產物；CI 的獨立 job 執行 `publish.py prepare` 和 `publish.py publish`。私鑰不存放於 Git、交付目錄或共享知識庫。

QA APK 使用僅限 loopback 的測試頻道。QA 故障注入 Receiver 要求 Android DUMP 簽章權限，正式 APK 不註冊此 Receiver，也不允許明文更新來源。測試 APK 不作為一般交付版本。

2026-10-08 實機：UIS7870 / Android 13。以受控停滯訊號驗證自動換來源，切換後影片位置繼續前進，連續播放超過 90 秒並記住正常來源。此測試驗證偵測器與重新載入路徑，不代表模擬了每種 YouTube 伺服器故障。

同日完成兩次 App 自行安裝測試：QA 頻道升至正式客戶端，再由正式 GitHub 頻道升至 `2026100804`。播放期間延後安裝，暫停閒置後無須互動完成更新；兩次均保留原始安裝日期。這是已允許安裝權限的 Android 13 實測，其他車機仍依系統權限處理。

[正式下載](https://github.com/SYLONG7708/Morphe/releases/tag/blue-m-2026100804) · [雲端建置與發布通過](https://github.com/SYLONG7708/Morphe/actions/runs/37786182041)
