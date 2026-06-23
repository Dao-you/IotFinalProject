# AGENTS.md

## 專案目標

這個專案是一套以 Bluetooth beacon 定位為基礎的數位化定向越野互動遊戲裝置。

玩家使用 Android 手機在場域中移動。每台 Raspberry Pi 會作為一個檢核點並發送固定格式的 BLE beacon，Android App 會掃描附近檢核點、顯示接近程度，並在玩家靠近後完成簽到。玩家簽到後，手機會改為發送該檢核點的手機 beacon，讓具備完整外設的 Raspberry Pi 判斷玩家是否靠近互動裝置。

完整 Raspberry Pi 節點會結合 USB 相機、人臉偵測、YA 手勢辨識、水平馬達控制、蜂鳴器與 OLED 顯示。當手機 beacon RSSI 達到門檻且連續命中後，Pi 會啟動相機 session，追蹤玩家並在 YA 手勢倒數完成後拍照。

Demo 不是每台 Raspberry Pi 都有相機、螢幕、蜂鳴器或馬達，所以專案同時支援 BLE-only 的單機檢核點模式。

## 目前完成狀態

### Raspberry Pi 端

- `rpi_beacon_camera.py` 是 Raspberry Pi 主程式。
- 預設完整模式會：
  - 發送 Pi/checkpoint BLE beacon。
  - 掃描 Android 手機發出的 manufacturer data。
  - 在 RSSI 達到門檻且連續命中指定次數後啟動相機 session。
  - 支援 `--preview` 顯示 OpenCV 預覽。
  - 預覽模式下會暫停 Pi beacon，避免 OpenCV Qt 視窗與 BlueZ/GLib event loop 在同一流程內衝突；session 結束後重新廣播。
  - 非預覽模式可 headless 運作。
- `rpi_beacon_camera.py --beacon-only` 是給沒有相機/GPIO 外設的單機檢核點模式：
  - 只發送 Pi/checkpoint beacon。
  - 不掃描手機 beacon。
  - 不載入 `camera.py`，因此不需要 OpenCV、MediaPipe、GPIO、蜂鳴器或 OLED 依賴。
- `--pi-name`、`--manufacturer-id`、`--pi-data`、`--phone-data` 可設定 BLE 名稱與 payload；未指定 `--phone-data` 時會使用 `--pi-data`。
- `scripts/setup_rpi_standalone.sh` 會在其他 Raspberry Pi 上一鍵設定 BLE-only 單機節點，建立最小 venv 並安裝 systemd service。
- `requirements-rpi-standalone.txt` 是 BLE-only 節點的最小 pip 依賴。
- `bluez_venv.py` 已處理 virtualenv 內找不到系統 `dbus`/`gi` 套件的問題。
- `le_advertiser.py`、`le_scanner.py` 是 BLE debug 範例，可作為調整 beacon 格式或排查廣播/掃描問題的參考。

### 相機互動

- `camera.py` 已完成 USB 相機互動流程。
- 支援 Haar cascade 人臉偵測。
- 支援水平馬達自動居中。
- 支援多人居中：偵測到多張臉時，使用所有人臉中心點的幾何中心作為旋轉依據；單人時維持單人中心邏輯。
- 支援 MediaPipe YA 手勢偵測與倒數拍照。
- 如果鏡頭還在旋轉居中，系統不會拍照，會等居中完成後才允許 YA 倒數。
- 拍照結果會存到 `photos/`，檔名格式為 `ya_YYYYMMDD_HHMMSS.jpg`。
- OLED 初始化失敗時會停用顯示；馬達、蜂鳴器與相機仍是完整模式的必要硬體。

### Android 端

- Android app 位於 `android/`，使用 Kotlin、Jetpack Compose 與 Material 3。
- App 會掃描 Manufacturer ID `0xFFFF` 的 BLE advertisement。
- 遊戲頁可顯示地圖、檢核點圖釘、RSSI 接近強度、簽到狀態與簽到紀錄。
- 接近強度達門檻後可手動簽到檢核點；同一檢核點只記錄第一次簽到。
- 簽到後，手機會開始發送最近簽到檢核點的 `Beacon Data`，供 Raspberry Pi 互動裝置觸發。
- 尚未完成任何簽到前，Android 不發送手機 beacon。
- 管理頁可新增、修改、刪除檢核點，拖曳圖釘位置，匯入地圖底圖並調整底圖位置/縮放。
- 目前檢核點、底圖與簽到紀錄保存在 runtime state，重啟 App 後會回到預設狀態。

## 重要檔案

- `rpi_beacon_camera.py`
  - Raspberry Pi 主程式。
  - 完整模式負責 Pi beacon 廣播、手機 beacon 掃描、RSSI 觸發與相機 session 啟動。
  - `--beacon-only` 負責沒有外設的單機檢核點。
- `camera.py`
  - 相機、人臉偵測、多人居中、馬達控制、YA 手勢偵測、蜂鳴器/OLED 提示與拍照流程。
- `scripts/setup_rpi_standalone.sh`
  - 在其他 Pi 上設定 BLE-only 單機檢核點的腳本。
- `requirements.txt`
  - 完整 Raspberry Pi 節點所需 pip 套件。
- `requirements-rpi-standalone.txt`
  - BLE-only 單機節點的最小 pip 套件。
- `bluez_venv.py`
  - 讓 Python virtualenv 可以使用 Raspberry Pi OS 透過 apt 安裝的 `dbus`/`gi`。
- `le_advertiser.py`、`le_scanner.py`
  - BLE 廣播/掃描 debug 範例。
- `android/app/src/main/java/com/example/iotproject/`
  - Android App 的 BLE 掃描、BLE 發射、權限、資料模型與 Compose UI。
- `android/README.md`
  - Android app 的細部功能與建置說明。

## 目前 beacon 設定

預設測試設定如下：

- Pi local name: `RPi_112360104`
- Manufacturer ID: `0xFFFF`
- Default Pi/checkpoint beacon data: `0x00110044`
- Default phone beacon data: 未指定時跟 `--pi-data` 相同
- RSSI trigger default: `-60 dBm`
- Required strong hits default: `3`
- Camera cooldown default: `3` 秒
- Camera session timeout default: `15` 秒

Android 預設檢核點：

- 入口：`00110044`
- 中庭：`00110045`
- 終點：`00110046`

多台 Pi demo 時，建議每台 Pi 使用不同 `--pi-data`，並在 Android 管理頁設定對應檢核點的 `Beacon Data`。完整互動裝置若要被手機簽到後觸發，Pi 端 `--phone-data` 必須和 Android 簽到後發出的 payload 對上；未指定時會自動跟 `--pi-data` 相同。

## 執行方式

### 完整 Raspberry Pi 節點

在 Raspberry Pi 5 上執行前，先安裝系統套件：

```bash
sudo apt install python3-dbus python3-gi
```

Python 套件安裝：

```bash
pip install -r requirements.txt
```

執行整合流程，無預覽：

```bash
python rpi_beacon_camera.py
```

執行整合流程，開啟預覽：

```bash
python rpi_beacon_camera.py --preview
```

常用參數：

```bash
python rpi_beacon_camera.py --pi-data 00110044 --phone-data 00110044 --rssi -55 --strong-hits 3 --cooldown 3 --camera-timeout 15
```

只測試相機互動流程：

```bash
python camera.py
```

### BLE-only 單機檢核點

在沒有相機、馬達、蜂鳴器或 OLED 的 Pi 上，執行：

```bash
bash scripts/setup_rpi_standalone.sh --beacon-data 00110045 --local-name RPi_CP2
```

此腳本會安裝 BLE 系統套件、建立 `.venv-standalone`，並建立/啟動 `iot-orienteering-beacon.service`。

單次手動執行可使用：

```bash
python rpi_beacon_camera.py --beacon-only --pi-data 00110045 --pi-name RPi_CP2
```

### Android App

在 `android/` 目錄下建置：

```bash
./gradlew assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

連線 Android 手機並安裝：

```powershell
.\gradlew.bat installDebug
```

第一次開啟 App 時需要授權 BLE 掃描與發射權限。

## 後續工作

- 將 Android 檢核點、地圖底圖與簽到紀錄保存到本機資料庫或檔案。
- 決定正式 beacon payload 格式，避免多裝置場域中難以區分角色與檢核點。
- 建立每個檢查點的 ID、關卡狀態與任務規則。
- 增加任務完成後的資料記錄，例如照片路徑、完成時間、玩家 ID、檢查點 ID。
- 評估是否要把 BLE 廣播/掃描與相機預覽拆成不同 process，降低 OpenCV Qt 與 BlueZ/GLib event loop 衝突。
- 測試多人場景下的人臉誤偵測、遮擋與鏡頭居中的穩定度。
- 釐清完整互動裝置與 BLE-only 檢核點在 demo 場域中的配置方式。

## 開發注意事項

- Raspberry Pi 端目前以 BlueZ、bluezero、bleak 實作 BLE 功能。
- virtualenv 內如果出現 `No module named dbus`，確認 `python3-dbus` 已透過 apt 安裝，並確認程式有先呼叫 `enable_system_dbus_path()`。
- 修改 `rpi_beacon_camera.py` 時，要保留 headless 完整模式與 `--beacon-only` 單機模式。
- 不要把 `camera.py` 的相機/GPIO 依賴重新放回 `rpi_beacon_camera.py` 的 top-level import，否則 BLE-only Pi 會無法啟動。
- 修改 BLE 流程時，要同時檢查 Android `DEFAULT_MANUFACTURER_ID`、`DEFAULT_BEACON_DATA_HEX`、Pi 端 `--pi-data`、`--phone-data` 是否仍一致。
- 修改相機流程時，要維持「馬達正在居中時不能拍照」這個規則。
- 修改多人居中時，要保留單人情境與原本操作手感。
- 不要把測試照片、cache、virtualenv 或本機硬體輸出檔納入 commit。
