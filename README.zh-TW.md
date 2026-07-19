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
- 背景下載 AutoPatch Hub 與 MicroG-RE。
- 逐一驗證釘選 RSA 簽章、SHA-256、檔案大小、套件名稱、版本、最低 SDK、
  APK 簽署者及既有安裝簽署鏈。
- 先更新修補套件，再判斷手機裡的 YouTube 是否為精確相容版本。
- 自動使用相容的已安裝／已保存 YouTube 原始檔，在手機內完成 split APK
  合併、修補與簽署。
- 通知可直接開啟正確動作；App 內「安全更新中心」會同時顯示管理器、
  YouTube、修補套件與 MicroG-RE 狀態。
- 發布清單具有遞增序號與防降版保護；下載站遭竄改也無法偽造有效版本。

## 唯一無法取消的 Android 限制

一般未授權手機必須顯示 Android 系統安裝確認。若已授權 Shizuku 或 Root，
才可直接靜默安裝。這是 Android 的安全規則，不是還需要手動配置的功能。

本專案不下載、不鏡像 Google 原版 YouTube APK，也不發布預先修補的 YouTube
APK。來源只會是手機中已安裝且相容的版本，或使用者先前保存的合法副本；修補
全程在手機本機執行並沿用持久簽署金鑰。

## 一般使用

1. 從 GitHub Releases 安裝 AutoPatch Hub。
2. 首次開啟時允許通知；若要全靜默安裝，可另外授權 Shizuku 或 Root。
3. 在「設定 → 進階 → 安全更新中心」查看四個元件。
4. 有相容的 YouTube 時點通知或 YouTube 列即可開始本機修補；其餘下載與版本
   選擇會自動完成。

完整安全設計請看 [架構與安全性](docs/架構與安全性.md)，圖文式單頁說明請開啟
[零配置操作指南](docs/零配置操作指南.html)。

## 授權

GPL-3.0-or-later。上游附加條款保留於 [NOTICE](NOTICE)，修改與第三方來源記錄於
[NOTICE-AUTOPATCH-HUB.md](NOTICE-AUTOPATCH-HUB.md) 及
[THIRD_PARTY_SOURCE.md](THIRD_PARTY_SOURCE.md)。
