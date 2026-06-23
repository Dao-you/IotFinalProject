# IoT Orienteering Interactive Checkpoint

這個專案是一套 Raspberry Pi + Android 手機的定向越野互動遊戲裝置。Android 手機負責掃描場域中的 Raspberry Pi 檢核點 beacon、顯示地圖與簽到狀態；完整硬體版 Raspberry Pi 會在偵測到玩家手機靠近後啟動相機互動，追蹤人臉、辨識 YA 手勢並拍照。

Demo 場域可以混用兩種 Pi：

- 完整互動節點：有 USB 相機、水平馬達、蜂鳴器、OLED，可執行相機任務。
- BLE-only 單機檢核點：沒有相機與其他外設，只廣播 checkpoint beacon，讓 Android 可以定位與簽到。

## 系統流程

1. Raspberry Pi 廣播 BLE manufacturer data，代表一個檢核點。
2. Android App 掃描 Pi beacon，依 RSSI 顯示接近程度。
3. 玩家靠近檢核點後在 App 內簽到。
4. Android 簽到後開始發送該檢核點的手機 beacon。
5. 完整互動節點掃描手機 beacon，RSSI 達門檻後啟動相機 session。
6. Pi 追蹤玩家人臉置中；偵測到 YA 手勢且鏡頭已居中後倒數拍照。

## 目前功能

### Raspberry Pi

- `rpi_beacon_camera.py` 可廣播 Pi/checkpoint beacon。
- 完整模式可掃描 Android 手機 beacon 並以 RSSI + 連續命中次數觸發相機。
- `--preview` 可顯示 OpenCV 預覽；預覽期間會暫停 Pi beacon，session 結束後恢復。
- `--beacon-only` 可讓沒有相機/GPIO 外設的 Pi 只當 BLE 檢核點使用。
- `camera.py` 支援人臉偵測、多人幾何中心居中、YA 手勢、倒數、蜂鳴器、OLED 與照片儲存。
- 照片儲存在 `photos/`，檔名格式為 `ya_YYYYMMDD_HHMMSS.jpg`。

### Android

- Kotlin + Jetpack Compose App，位於 `android/`。
- 掃描 Manufacturer ID `0xFFFF` 的 Pi beacon。
- 遊戲頁顯示地圖、檢核點、RSSI 接近強度、簽到按鈕與簽到紀錄。
- 管理頁可新增/修改/刪除檢核點、拖曳圖釘、匯入地圖底圖並調整位置與縮放。
- 玩家簽到後，App 會發射該檢核點的 `Beacon Data` 給 Raspberry Pi 互動節點偵測。

## 專案結構

```text
.
├── rpi_beacon_camera.py          # Raspberry Pi 主程式：完整模式與 beacon-only 模式
├── camera.py                     # 相機、人臉追蹤、YA 手勢、馬達、蜂鳴器、OLED
├── bluez_venv.py                 # 讓 venv 使用系統 dbus/gi 套件
├── le_advertiser.py              # BLE 廣播 debug 範例
├── le_scanner.py                 # BLE 掃描 debug 範例
├── requirements.txt              # 完整 Pi 節點 pip 依賴
├── requirements-rpi-standalone.txt
├── scripts/
│   └── setup_rpi_standalone.sh   # BLE-only Pi 一鍵設定腳本
└── android/                      # Android App
```

## Beacon 設定

預設測試值：

- Manufacturer ID: `0xFFFF`
- Pi local name: `RPi_112360104`
- Default Pi/checkpoint beacon data: `00110044`
- Default phone beacon data: 未指定時與 `--pi-data` 相同
- RSSI trigger: `-60 dBm`
- Required strong hits: `3`

Android 預設檢核點：

- 入口：`00110044`
- 中庭：`00110045`
- 終點：`00110046`

多台 Pi demo 時，請讓每台 Pi 使用不同 `--pi-data`，並在 Android 管理頁設定相同的 `Beacon Data`。完整互動節點若要接收手機觸發，`--phone-data` 必須與 Android 簽到後發出的 payload 一致。

## Raspberry Pi 完整互動節點

安裝 Raspberry Pi OS 系統套件：

```bash
sudo apt install python3-dbus python3-gi
```

安裝 Python 套件：

```bash
pip install -r requirements.txt
```

無預覽執行：

```bash
python rpi_beacon_camera.py
```

開啟預覽：

```bash
python rpi_beacon_camera.py --preview
```

指定 beacon 與觸發參數：

```bash
python rpi_beacon_camera.py --pi-data 00110044 --phone-data 00110044 --rssi -55 --strong-hits 3 --cooldown 3 --camera-timeout 15
```

只測試相機互動：

```bash
python camera.py
```

## Raspberry Pi BLE-only 單機檢核點

沒有相機、螢幕、蜂鳴器或馬達的 Pi，使用單機設定腳本：

```bash
bash scripts/setup_rpi_standalone.sh --beacon-data 00110045 --local-name RPi_CP2
```

腳本會：

- 安裝 `bluetooth`、`bluez`、`python3-dbus`、`python3-gi`、`python3-venv` 等必要套件。
- 建立 `.venv-standalone`。
- 安裝 `requirements-rpi-standalone.txt`。
- 建立並啟動 `iot-orienteering-beacon.service`。
- 用 `rpi_beacon_camera.py --beacon-only` 持續廣播該 Pi 的 checkpoint beacon。

常用檢查：

```bash
systemctl status iot-orienteering-beacon
journalctl -u iot-orienteering-beacon -f
```

如果只想安裝依賴、不建立服務：

```bash
bash scripts/setup_rpi_standalone.sh --no-service
```

也可手動執行 BLE-only 模式：

```bash
python rpi_beacon_camera.py --beacon-only --pi-data 00110045 --pi-name RPi_CP2
```

## Android App

建置 debug APK：

```bash
cd android
./gradlew assembleDebug
```

Windows PowerShell：

```powershell
cd android
.\gradlew.bat assembleDebug
```

安裝到已啟用 USB 偵錯的手機：

```powershell
.\gradlew.bat installDebug
```

第一次開啟 App 時需要授權 BLE 權限。Android 12 以上會要求 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`BLUETOOTH_ADVERTISE`；Android 11 以下會要求定位權限以支援 BLE 掃描。

## 開發注意事項

- `rpi_beacon_camera.py --beacon-only` 不應載入 `camera.py`，否則沒有外設的 Pi 會被 OpenCV/MediaPipe/GPIO 依賴卡住。
- 修改 BLE payload 時，同步檢查 Raspberry Pi 參數與 Android 管理頁的 `Beacon Data`。
- 修改相機流程時，保留「馬達正在居中時不能拍照」的規則。
- Android 目前尚未持久化檢核點、底圖或簽到紀錄。
- 不要把 `photos/`、venv、cache 或硬體測試輸出加入版本控制。
