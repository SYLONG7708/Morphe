# AutoPatch Hub

AutoPatch Hub 是獨立品牌的 GPL-3.0-or-later 衍生專案，來源為
[Morphe Manager](https://github.com/MorpheApp/morphe-manager)。它把管理器、
修補套件、YouTube 本機修補及 MicroG-RE 整合為一個已簽章、可判斷裝置版本的
自動更新通道。

> 本專案並非 Morphe 官方專案，也未獲官方背書。「Morphe」僅在標示上游來源與
> 相容性時使用；App 名稱、套件 ID、圖示、簽署金鑰與發布管道均完全獨立。

## 已完成的零配置功能

- 預設每小時由 Android WorkManager 檢查一次。
- 自動辨識 Android SDK 與 CPU ABI，選擇相符版本。
- 冷啟動及背景檢查時，自動下載 AutoPatch Hub 與可安全覆蓋的 MicroG-RE；
  MicroG 有更新時直接交給 Android 安裝器，Shizuku／Root 可靜默完成，一般裝置
  只保留系統確認。
- 若車機預載的 MicroG 是其他廠商簽章，會在下載前辨識並保持原狀；Android
  無法用不同簽章的官方 APK 安全覆蓋。
- 逐一驗證釘選 RSA 簽章、SHA-256、檔案大小、套件名稱、版本、最低 SDK、
  APK 簽署者及既有安裝簽署鏈。
- 每次自動流程都先從
  [MorpheApp/morphe-patches](https://github.com/MorpheApp/morphe-patches)
  更新官方修補套件，再判斷手機裡的 YouTube 是否為精確相容版本。
- 新補丁先在暫存檔檢查 `Patcher-Version` 並完整載入；只有與目前 Manager 相容時
  才原子替換舊版。不相容或損壞的上游版本會保留最後一份可用補丁。
- 冷啟動時優先使用修補套件明確相容的已安裝／已保存 YouTube 原始檔；若沒有，
  私人使用版會依補釘宣告的精確 `versionCode` 在裝置端下載相符原版，再核對 APK
  簽章完整性、Google 簽署者、套件名稱、版本名稱與版本碼，全部通過才保存並修補。
- 已是最新版 Morphe 時不重複修補；YouTube 不相容時顯示建議來源版本，不會硬補。
- 通知可直接開啟正確動作；App 內「安全更新中心」會同時顯示管理器、
  YouTube、修補套件與 MicroG-RE 狀態。
- 發布清單具有遞增序號與防降版保護；下載站遭竄改也無法偽造有效版本。

## 唯一無法取消的 Android 限制

一般未授權手機必須顯示 Android 系統安裝確認。若已授權 Shizuku 或 Root，
才可直接靜默安裝。這是 Android 的安全規則，不是還需要手動配置的功能。

本專案不鏡像、不發布 Google 原版 YouTube APK，也不發布預先修補的 YouTube APK。
這個私人使用版在沒有本機相容來源時，可從 APKPure 下載服務取得補釘指定的精確原版；
檔案必須同時通過獨立 APK 簽章驗證及官方補釘包的 Google 憑證、套件名、版本名、
版本碼核對才會使用。使用時仍須遵守 APKPure 條款與所在地法規；自動取得失敗時，
原有 APKMirror／手動選檔流程會保留。修補全程在裝置本機執行並沿用持久簽署金鑰。

## 一般使用

1. 從 GitHub Releases 安裝 AutoPatch Hub。
2. 首次開啟時允許通知；若要全靜默安裝，可另外授權 Shizuku 或 Root。
3. 開啟 App；會先更新補釘與 MicroG，再自動使用或下載精確相容的 YouTube 原版，
   驗證通過後在本機開始修補。
4. 可在「設定 → 進階 → 安全更新中心」查看四個元件；通知或 YouTube 列仍可
   手動重新開啟同一套安全流程。

完整安全設計請看 [架構與安全性](docs/架構與安全性.md)，圖文式單頁說明請開啟
[零配置操作指南](docs/零配置操作指南.html)。

## 授權

GPL-3.0-or-later。上游附加條款保留於 [NOTICE](NOTICE)，修改與第三方來源記錄於
[NOTICE-AUTOPATCH-HUB.md](NOTICE-AUTOPATCH-HUB.md) 及
[THIRD_PARTY_SOURCE.md](THIRD_PARTY_SOURCE.md)。
