# Android App

這個目錄是定向越野遊戲的 Android 手機端。現階段 App 先提供可操作的 MVP 介面，用來確認地圖圖釘、檢核點管理與 BLE beacon RSSI 視覺回饋。

## 目前功能

- 遊戲介面：
  - 顯示一個固定 1:1 長寬比的方框作為現場地圖。
  - 若管理介面已匯入底圖，遊戲介面會使用同一張底圖。
  - 顯示每個檢核點圖釘與檢核點狀態。
  - 掃描 Manufacturer ID `0xFFFF` 的 BLE advertisement。
  - 當掃到與檢核點 `Beacon Data` 相同的 payload 時，依 RSSI 與最近看到時間加強圖釘光暈。
  - 接近強度達門檻後可手動簽到檢核點，同一檢核點只記錄第一次簽到。
  - 簽到後，App 會發射最近簽到檢核點的 `Beacon Data`，供 Raspberry Pi 判斷玩家手機是否靠近互動裝置。
  - 尚未完成任何簽到前，App 不會發射手機 beacon。
  - 已簽到的檢核點會在地圖圖釘與狀態列使用不同顏色標示。
  - 顯示簽到記錄，並在簽到成功後提示玩家可到互動裝置前拍攝團體照。
- 管理介面：
  - 新增檢核點名稱與 `Beacon Data`。
  - 選取檢核點後，可在地圖上放置或拖曳圖釘。
  - 選取檢核點後，可修改該檢核點的 `Beacon Data`。
  - 可匯入圖片作為地圖底圖，並切換調整模式拖曳底圖位置。
  - 可放大、縮小或置中底圖。
  - 地圖、新增表單、檢核點編輯與列表會分段顯示，避免手機畫面過度擁擠。
  - 目前檢核點、底圖與簽到記錄保存在 App runtime state，重啟 App 後會回到預設狀態。

## Beacon 設定

目前 Raspberry Pi 端測試設定：

- Manufacturer ID: `0xFFFF`
- Pi beacon data: `00110044`
- Phone beacon data: 使用最近簽到檢核點的 `Beacon Data`

Android App 目前的預設檢核點 `Beacon Data`：

- 入口：`00110044`
- 中庭：`00110045`
- 終點：`00110046`

玩家簽到後，Android App 會用該檢核點的 `Beacon Data` 發射手機 beacon。Raspberry Pi 端會掃描手機 beacon，RSSI 達到門檻且連續命中後啟動互動裝置。

如果多個檢核點都使用相同 payload，手機端只能知道附近有符合格式的 beacon，無法分辨是哪一個實體檢核點，因此所有相同 payload 的檢核點都會同時顯示接近效果，也會同時達到可簽到狀態。

正式場域建議讓每台 Raspberry Pi 使用不同 checkpoint ID 或不同 manufacturer data payload，並同步更新管理介面的檢核點 `Beacon Data` 與該 Raspberry Pi 端預期的手機 beacon data。

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

第一次開啟 App 時需要授權藍牙權限。Android 12 以上會要求 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE`，Android 11 以下會要求定位權限以支援 BLE 掃描。

## 後續工作

- 將檢核點資料保存到本機資料庫或檔案。
- 決定正式 beacon payload 格式，讓 App 可以區分不同 Raspberry Pi 檢核點。
- 加入正式地圖圖片或可縮放平面圖。
- 串接遊戲關卡狀態、任務提示與完成紀錄。
