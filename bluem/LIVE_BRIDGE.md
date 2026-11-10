# 影視內播介面，修訂 11

這個服務讓 Android 9 以上的影視使用同機藍色 M 的 Morphe 公開直播解析。它不顯示 Activity、不啟動外部播放器；影視負責取得片段與解碼。只支援公開、標記 `live=1` 的 AVC/AAC 直播，目前最多 720p。

## 信任與協定

- 套件：`app.morphe.android.youtube`。
- 明確綁定服務：`com.sylong.bluem.live.LiveResolveService`。
- Metadata：`com.sylong.bluem.LIVE_BRIDGE=1`；`com.sylong.bluem.UPDATE_CLIENT_REVISION=11`。
- 生產呼叫者：`tw.com.sylong.tvcar`，簽章 SHA-256 `b6f8718ac28ae96e1b76bfe7a8c4687e73d2c4f2dcd187a25c6af9427993a9a0`。
- QA 建置額外允許 `tw.com.sylong.tvcar.direct` 與本機 debug 公開憑證；生產建置不接受 QA 呼叫者。

以 Android Messenger 傳送 `what=1`，`Bundle` 只接受 11 字元 `videoId`、1～64 字元 `requestId`，並設定 `replyTo`。服務使用 Android 提供的 `Message.sendingUid` 檢查套件與簽章，不信任 payload 宣稱的身分。

回應為 `what=2`，`Bundle.requestId` 對應原請求，`Bundle.result` 為 schema 1 JSON。成功資料包含 `ok`、`backend=morphe`、`videoId`、媒體 client user-agent、AVC 影音軌描述；失敗只含錯誤代碼。串流網址限 HTTPS Googlevideo，不輸出帳號、Cookie 或授權標頭。影視原生層驗證資料後，只把當次應用程式內 HLS URL 傳給頁面。

`what=3` 與相同 request ID 可取消解析。同一 UID 的新請求取消前一請求，完成回覆即解除擁有權；呼叫程序死亡、最後解除綁定或服務銷毀也會取消。逾時與重試由影視控制。

## 上游相容性與發布

`CheckRecoveryContract` 檢查實際 APK 中的 `StreamingDataRequest`、StreamData、PlayerResponse protobuf 與 ClientType 反射 API；任何簽名改變都阻擋建置。`PatchApk` 呼叫 `PatchLiveBridgeManifest.apply`，一般建置會編入全部 `src/client` Java，包含本服務。

`CheckUpdateContract` 另檢查服務類別、唯一 manifest 服務宣告、內播 metadata 及修訂 11。`UpdatePolicy.MIN_RECOVERY_REVISION=11` 與發布清單的 `client.revision=11` 一致。受保護的既有雲端流程負責生產簽章；功能分支及 QA APK 不等同正式發布。

```powershell
python bluem/cloud.py tools
python bluem/run_tests.py
python -m unittest discover -s bluem -p 'test_*.py'
```

本機 QA 使用已驗證 SHA-256 的 2026100919 正式基底，保留原影音核心與資源，只增加橋接服務並改用隔離測試簽章。API 契約及 37 項 Python 測試、更新政策與 TLS 實際迴環握手檢查已通過。測試環境為無視窗、無音效的 API 35 x86_64 模擬器，影音成功代表解碼計數前進；未宣稱已在使用者車機或所有頻道測試。

影視配套程式：[TV 分支](https://github.com/SYLONG7708/TV/tree/feat/bluem-live-provider-20261111)。正式使用必須同時安裝具有本介面的藍色 M，以及由影視原金鑰簽署的新版影視；保留既有安裝與資料。
