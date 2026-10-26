# 藍色 M：播放恢復與穩定更新

2026-10-26 客戶端 revision 5：以 Morphe 1.48.0／YouTube 21.20.405 為基底。
啟動時實際詢問 JavaScriptEngineSupport；不支援的 WebView 不再選 TV／TV Simply，
自動保留不需 JavaScript 的 visionOS 1.02／Android Studio 路徑。舊簽署頻道清單也不能
重新啟用不相容來源。只有連續正常進度確認實際取流來源後才記住它；暫時失敗的來源
冷卻十分鐘，既有每次故障與全域重試上限保留。錯誤容器內的文字也會辨識登入、內容
限制與伺服器錯誤。單靠狀態初始值或跳轉位置不算播放成功。

後續 YouTube 自動更新必須同時具備簽署清單 client.revision >= 5，以及 APK metadata
com.sylong.bluem.UPDATE_CLIENT_REVISION >= 5，避免只有版本號較高的舊復原程式覆蓋本次修正。
MicroG 更新仍獨立判斷。若共用頻道尚未包含本次程式碼，保留目前可用 APK，並記錄
waiting_for_compatible_recovery_update；不能將此狀態稱為全部最新。

本次實機為 ums512_1h10_Natv／Android 10／WebView 87，與下方歷史 UIS7870 驗證不同。
可自動處理暫時串流、載入及連線故障；無法保證第三方服務永遠可用，也不繞過帳號或內容限制。

此目錄維護已套用 Morphe 修補的 YouTube APK。它沿用既有套件、正式簽章及使用資料，搭配官方 Morphe MicroG；不依賴車機上的 Manager 重新修補。

## 播放恢復

每秒讀取 Morphe 影片進度、實際載入／錯誤畫面及 App 自己的 Android 媒體狀態。一般緩衝停滯約 6 秒、明確「播放時發生問題／輕觸以重試」約 2 秒後，先重新取得串流；失敗再嘗試本機可執行的 TV、Android Creator 或 VisionOS 來源。透過原生播放器重建保留影片、清單、位置與速度。新影片、拖曳及重新載入後設有保護時間。

暫停、已結束、無可用網路及 Shorts 不觸發切換。一次故障先重取一次、最多再切換兩次；全部失敗後冷卻再試，全域滾動十分鐘最多六次。持續實際播放 15 秒後記住成功來源，正常播放 90 秒後重設該次故障的重試次數，長影片遇到後續新故障仍可恢復。離線期間等待網路恢復；登入、私人影片、內容移除等提示停止無效重試，不更動帳號。

當媒體狀態顯示正在播放但實際位置持續不動，也會觸發較保守的恢復。錯誤發生後若控制器位置消失，使用同一影片的最近正常位置。主頁與內部播放入口均持續追蹤；21.16.256 的 InternalMainActivity 實際是指向 MainActivity 的 activity-alias。

「緩存」在此透過取得新的串流請求處理，不清除帳號、觀看紀錄或整個 App 資料。僅對前景的一般播放器自動重新載入。背景音訊與 Shorts 同樣會阻止更新安裝打斷播放。

## 自動更新

GitHub Actions 每六小時檢查官方穩定 patches、Desktop CLI 及 MicroG。依 patches 標示的非實驗目標選取 YouTube 版本，驗證 Google APK 簽章、四種 ABI、Android 9 相容性、所有修補結果及播放 API。建置工作與持有正式金鑰的簽署工作分開執行。

官方版本查詢透過 `tools/bluem_probe.py` 使用排程的唯讀 GitHub 憑證；憑證只送至 GitHub API，不隨重新導向送到下載站。暫時性網路或執行器失敗納入雲端復原，最多三次嘗試；簽章、雜湊、修補或程式錯誤保留現有版本並等待處理。

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

[正式下載 2026100901](https://github.com/SYLONG7708/Morphe/releases/tag/blue-m-2026100901) · [雲端建置與發布通過](https://github.com/SYLONG7708/Morphe/actions/runs/37813372361)

2026-10-09 回歸修正：使用者實機停在 37:47 並顯示 `player_error_view`，Android 媒體狀態為 ERROR。點擊原生重試可恢復，原始服務端錯誤已不在環狀日誌中，因此不把它武斷歸為特定 token 或硬體故障。舊版未直接辨識錯誤畫面，且同一影片的重試上限在恢復正常後不會重設；本版修正這兩項恢復缺口。

同日 7870 故障注入測試通過：內部播放 alias 持續追蹤、使用者暫停不觸發重播、可見錯誤約兩秒重取、第二次故障改來源、37 分鐘位置保留、登入提示不重試、離線等待與連線恢復，以及正常播放 95 秒後再次錯誤仍可恢復。斷網情境使用 QA 網路狀態注入，未改動車機網路設定；測試 Receiver 不進入正式 Manifest。

正式 GitHub 更新驗證通過：UIS7870 的正式客戶端由 `2026100806` 自行升至 `2026100901`，播放中延後安裝，暫停後完成，保留原始安裝日期。已安裝 APK 與 Release 公開下載的 SHA-256 一致，正式 Manifest 不註冊 QA Receiver。短暫 ADB 離線已重新連線，未清除 App 資料。

更新後正式版從原故障位置 37:47 連續觀察 125 秒，截圖顯示播放至 39:49；Android 媒體狀態與影片位置持續前進，沒有播放錯誤畫面或崩潰。此項未注入故障，與前述恢復測試分開記錄。


2026-10-26 實機驗收：原 code2026100902 在同一影片等待 50 秒仍未起播；
公開 code2026100905 在 35 秒觀察內仍緩衝。修正後 QA 兩次起播觀察為
4.36／4.89 秒，正式 code2026100907 為 4.33 秒，實際 Wi-Fi 恢復後切換
下一部影片約 4.25 秒開始播放，另連續觀察 100 秒未見錯誤。
QA 注入的伺服器提示約 2.02 秒啟動重取，約 8.17 秒恢復播放並保留進度；
暫停、登入提示與離線不反覆重試。正式版實際關閉 Wi-Fi 8 秒再恢復，
約 4.28 秒取得驗證連線；當時影片仍有緩衝，不能當作長時間斷網恢復測試。
最終 APK 已從實機拉回逐位元組雜湊核對；原 HOME 與兩 App 首次安裝日期相同，
未卸載、未清除資料。這是本機修正與實機驗收，尚未將 revision 5 推送共用頻道。
