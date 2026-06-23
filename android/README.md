# Android App

這個目錄是定向越野遊戲的 Android 手機端。現階段 App 先提供可操作的 MVP 介面，用來確認地圖圖釘、檢核點管理與 BLE beacon RSSI 視覺回饋。

## 目前功能

- 遊戲介面：
  - 顯示一個方框作為現場地圖。
  - 顯示每個檢核點圖釘與檢核點狀態。
  - 掃描 Manufacturer ID `0xFFFF` 的 BLE advertisement。
  - 當掃到與檢核點 `Beacon Data` 相同的 payload 時，依 RSSI 與最近看到時間加強圖釘光暈。
- 管理介面：
  - 新增檢核點名稱與 `Beacon Data`。
  - 選取檢核點後，可在地圖上放置或拖曳圖釘。
  - 選取檢核點後，可修改該檢核點的 `Beacon Data`。
  - 目前資料保存在 App runtime state，重啟 App 後會回到預設檢核點。

## Beacon 設定

目前 Raspberry Pi 端測試設定：

- Manufacturer ID: `0xFFFF`
- Pi beacon data: `00110044`

Android App 目前也以 `00110044` 作為預設檢核點 `Beacon Data`。如果多個檢核點都使用相同 payload，手機端只能知道附近有符合格式的 beacon，無法分辨是哪一個實體檢核點，因此所有相同 payload 的檢核點都會同時顯示接近效果。

正式場域建議讓每台 Raspberry Pi 使用不同 checkpoint ID 或不同 manufacturer data payload，並同步更新管理介面的檢核點 `Beacon Data`。

## 建置與執行

在 `android/` 目錄下執行：

```bash
./gradlew assembleDebug
```

Windows PowerShell 可執行：

```powershell
.\gradlew.bat assembleDebug
```

若 Android 手機已開啟 USB 偵錯並連線：

```powershell
.\gradlew.bat installDebug
```

第一次開啟 App 時需要授權藍牙掃描權限。Android 12 以上會要求 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`，Android 11 以下會要求定位權限以支援 BLE 掃描。

## 後續工作

- 將檢核點資料保存到本機資料庫或檔案。
- 決定正式 beacon payload 格式，讓 App 可以區分不同 Raspberry Pi 檢核點。
- 加入正式地圖圖片或可縮放平面圖。
- 串接遊戲關卡狀態、任務提示與完成紀錄。
