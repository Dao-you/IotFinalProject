# `rpi_beacon_camera.py` 相關程式結構

這份文件只整理 `rpi_beacon_camera.py` 執行時會用到的 Python 程式與流程。`le_advertiser.py`、`le_scanner.py` 是獨立測試工具，沒有被 `rpi_beacon_camera.py` import，因此不納入主流程說明。

## 會用到的本地檔案

| 檔案 | 被誰使用 | 功能 |
|---|---|---|
| `rpi_beacon_camera.py` | 主入口 | 啟動 Pi BLE 廣播、掃描指定手機 beacon、達到 RSSI 條件後啟動相機 |
| `bluez_venv.py` | `rpi_beacon_camera.py` | 補上系統 Python 套件路徑，讓 virtualenv 可以找到 `dbus` / `gi` |
| `camera.py` | `rpi_beacon_camera.py` | 提供 `run_camera_session()`，負責相機、人臉追蹤、YA 手勢、倒數與拍照 |

## Import 關係

```mermaid
flowchart LR
    Main[rpi_beacon_camera.py] --> Bluez[bluez_venv.py<br/>enable_system_dbus_path]
    Main --> Camera[camera.py<br/>run_camera_session]
    Main --> Bleak[bleak.BleakScanner]
    Main --> Bluezero[bluezero.adapter<br/>bluezero.advertisement]

    Camera --> OpenCV[cv2]
    Camera --> MediaPipe[mediapipe]
    Camera --> GPIO[gpiozero]
    Camera --> OLED[luma.oled / PIL<br/>需要時才 import]
```

## `rpi_beacon_camera.py`

### 主要責任

`rpi_beacon_camera.py` 是 Raspberry Pi 端的整合主程式。它同時做兩件事：

1. 用 `PiBeaconAdvertiser` 發送 Pi 自己的 BLE beacon。
2. 用 `PhoneBeaconWatcher` 掃描外部裝置送出的指定 beacon，當訊號夠強且連續命中後，呼叫 `camera.run_camera_session()`。

### 主要設定

| 常數 | 預設值 | 說明 |
|---|---:|---|
| `PI_LOCAL_NAME` | `RPi_112360104` | Pi beacon 的 local name |
| `MANUFACTURER_ID` | `0xFFFF` | manufacturer data ID |
| `PI_BEACON_DATA` | `00 11 00 44` | Pi 廣播出去的資料 |
| `PHONE_BEACON_DATA` | `00 11 00 44` | 掃描時要比對的 beacon 資料 |
| `RSSI_TRIGGER_DBM` | `-60` | RSSI 達到此門檻才算靠近 |
| `REQUIRED_STRONG_HITS` | `3` | 需要連續幾次強訊號才觸發 |
| `CAMERA_COOLDOWN_SECONDS` | `3` | 相機 session 結束後冷卻秒數 |
| `CAMERA_SESSION_TIMEOUT_SECONDS` | `15` | 單次相機 session 最長秒數 |

### 類別與函式

| 名稱 | 功能 |
|---|---|
| `PiBeaconAdvertiser.start()` | 找到藍牙 adapter、開啟電源、建立 BLE advertisement、註冊並開始廣播 |
| `PiBeaconAdvertiser.stop()` | 取消註冊 advertisement，停止廣播 thread |
| `PhoneBeaconWatcher.handle_detection()` | 每次掃到 BLE 廣播時執行，負責比對 manufacturer data、RSSI、連續命中次數與 cooldown |
| `wait_for_phone_beacon(watcher)` | 啟動 `BleakScanner`，等待 watcher queue 收到有效觸發事件 |
| `parse_args()` | 解析 `--preview`、`--rssi`、`--strong-hits`、`--cooldown`、`--camera-timeout` |
| `main(args)` | 主迴圈：廣播、等待 beacon、啟動相機、冷卻後繼續等待 |

## 主程式流程

```mermaid
flowchart TD
    Start[啟動 rpi_beacon_camera.py] --> Args[parse_args]
    Args --> Path[enable_system_dbus_path]
    Path --> ImportBluezero[import bluezero adapter / advertisement]
    ImportBluezero --> Init[建立 PiBeaconAdvertiser 與 PhoneBeaconWatcher]
    Init --> StartAdv[開始 Pi beacon 廣播]
    StartAdv --> Loop[進入無限主迴圈]
    Loop --> Scan[wait_for_phone_beacon]
    Scan --> Match{manufacturer data<br/>是否等於 PHONE_BEACON_DATA}
    Match -->|否| Scan
    Match -->|是| Rssi{RSSI 是否達門檻}
    Rssi -->|否| ResetHits[strong_hits 歸零]
    ResetHits --> Scan
    Rssi -->|是| Hits{連續 strong_hits<br/>是否足夠}
    Hits -->|否| Scan
    Hits -->|是| Cooldown{是否通過 cooldown}
    Cooldown -->|否| Scan
    Cooldown -->|是| Trigger[產生觸發事件]
    Trigger --> Preview{是否使用 --preview}
    Preview -->|是| StopAdv[暫停 Pi beacon]
    Preview -->|否| Camera[run_camera_session]
    StopAdv --> Camera
    Camera --> Restart{preview 模式?}
    Restart -->|是| StartAgain[重新開始 Pi beacon]
    Restart -->|否| Sleep[等待 cooldown 秒]
    StartAgain --> Sleep
    Sleep --> Loop
```

## Preview 模式差異

`--preview` 會開 OpenCV 視窗。程式在 preview 模式下會先停止 Pi beacon，再進入相機 session；相機結束後再重新開始廣播。

```mermaid
flowchart LR
    Trigger[beacon 觸發] --> Check{--preview?}
    Check -->|否| Headless[直接 run_camera_session<br/>show_preview=False]
    Check -->|是| Pause[advertiser.stop]
    Pause --> PreviewCamera[run_camera_session<br/>show_preview=True]
    PreviewCamera --> Resume[advertiser.start]
```

## `bluez_venv.py`

`rpi_beacon_camera.py` 在 import `bluezero` 前會先呼叫：

```python
enable_system_dbus_path()
```

它會把 `/usr/lib/python3/dist-packages` 加入 `sys.path`。原因是 Raspberry Pi OS 的 `python3-dbus`、`python3-gi` 常透過 apt 安裝在系統 Python 路徑，而 virtualenv 可能找不到這些套件。

流程很短：

```mermaid
flowchart LR
    Call[enable_system_dbus_path] --> Exists{dist-packages<br/>是否存在}
    Exists -->|否| End[結束]
    Exists -->|是| InPath{是否已在 sys.path}
    InPath -->|是| End
    InPath -->|否| Append[加入 sys.path]
```

## `camera.py`

`rpi_beacon_camera.py` 只直接使用 `camera.py` 的一個函式：

```python
run_camera_session(
    stop_after_capture=True,
    session_timeout=args.camera_timeout,
    show_preview=args.preview,
)
```

不過 `run_camera_session()` 內部會建立多個物件來完成相機任務。

### `run_camera_session()` 會用到的類別

| 類別 | 在 session 中的功能 |
|---|---|
| `StepperMotor` | 控制馬達左右旋轉，讓人臉中心靠近畫面中心 |
| `Buzzer` | YA 倒數時 beep，session 開始時也會 beep 一次 |
| `OledDisplay` | 顯示 `YA`、倒數秒數與完成動畫；初始化失敗時會自動停用 |
| `FaceDetector` | 使用 Haar cascade 偵測人臉 |
| `FaceTracker` | 根據單人或多人臉部中心，決定馬達是否要轉動 |
| `YaGestureDetector` | 用 MediaPipe Hands 判斷 YA 手勢、控制倒數與拍照 |

### 相機 session 流程

```mermaid
flowchart TD
    Start[run_camera_session] --> InitHW[初始化馬達、蜂鳴器、OLED]
    InitHW --> InitCV[初始化人臉偵測與 YA 偵測]
    InitCV --> OpenCam[開啟 USB camera]
    OpenCam --> IsOpen{相機是否開啟成功}
    IsOpen -->|否| Error[清理硬體後丟出錯誤]
    IsOpen -->|是| Ready[OLED 顯示 YA<br/>蜂鳴器 beep]
    Ready --> Loop[讀取每一幀]
    Loop --> Timeout{是否超過 session_timeout}
    Timeout -->|是| Cleanup[清理並結束]
    Timeout -->|否| Read[cap.read]
    Read --> Flip[畫面水平翻轉]
    Flip --> FaceDetect[偵測人臉]
    FaceDetect --> Track[FaceTracker 控制馬達居中]
    Track --> Gesture[YaGestureDetector 偵測 YA]
    Gesture --> Capture[更新倒數與拍照狀態]
    Capture --> Draw[畫中心線、人臉框、狀態文字]
    Draw --> Preview{show_preview?}
    Preview -->|是| Show[顯示 OpenCV 視窗]
    Preview -->|否| StopCheck{拍照且 stop_after_capture?}
    Show --> StopCheck
    StopCheck -->|否| Loop
    StopCheck -->|是| Cleanup
    Cleanup --> Return[回傳是否有拍到照片]
```

### 人臉追蹤流程

```mermaid
flowchart TD
    Faces[FaceDetector.detect 回傳 faces] --> HasFace{是否偵測到人臉}
    HasFace -->|否| Stop[馬達停止<br/>is_centering=False]
    HasFace -->|是| Count{人臉數量}
    Count -->|1| Single[使用單一人臉中心]
    Count -->|多個| Multi[計算所有人臉中心的平均]
    Single --> ErrorX[計算 target_center 與畫面中心差距]
    Multi --> ErrorX
    ErrorX --> DeadZone{是否超過 dead_zone}
    DeadZone -->|右偏| StepLeft[馬達往一側轉<br/>is_centering=True]
    DeadZone -->|左偏| StepRight[馬達往另一側轉<br/>is_centering=True]
    DeadZone -->|在範圍內| Centered[馬達停止<br/>is_centering=False]
```

### YA 拍照流程

```mermaid
flowchart TD
    Frame[每一幀] --> Hands[MediaPipe Hands]
    Hands --> Ya{是否符合 YA 手勢}
    Ya --> State[update_capture_state]
    State --> CanCapture{鏡頭是否已居中}
    CanCapture -->|否| Centering[狀態 Centering<br/>重置倒數]
    CanCapture -->|是| Cooldown{是否仍在拍照 cooldown}
    Cooldown -->|是| CooldownState[狀態 Cooldown<br/>重置倒數]
    Cooldown -->|否| IsYa{是否 YA}
    IsYa -->|否| NotYa[狀態 Not YA<br/>重置倒數]
    IsYa -->|是| Countdown[開始或延續 3 秒倒數]
    Countdown --> Done{倒數是否完成}
    Done -->|否| Beep[蜂鳴器 beep<br/>OLED 顯示秒數]
    Done -->|是| Save[儲存 photos/ya_時間.jpg]
    Save --> Fireworks[OLED 完成動畫]
    Fireworks --> Return[回傳 captured=True]
```

## 從主程式到拍照完成的完整路徑

```mermaid
sequenceDiagram
    participant Main as rpi_beacon_camera.py
    participant Bluez as bluez_venv.py
    participant Adv as PiBeaconAdvertiser
    participant Watcher as PhoneBeaconWatcher
    participant Cam as camera.run_camera_session

    Main->>Bluez: enable_system_dbus_path()
    Main->>Adv: start()
    loop 持續等待
        Main->>Watcher: wait_for_phone_beacon()
        Watcher-->>Main: beacon 強度達標
        alt preview 模式
            Main->>Adv: stop()
        end
        Main->>Cam: run_camera_session(...)
        Cam-->>Main: captured True / False
        alt preview 模式
            Main->>Adv: start()
        end
        Main->>Main: sleep(cooldown)
    end
```

## 執行方式

無預覽視窗：

```bash
python rpi_beacon_camera.py
```

有預覽視窗：

```bash
python rpi_beacon_camera.py --preview
```

常用參數：

```bash
python rpi_beacon_camera.py --rssi -55 --strong-hits 3 --cooldown 3 --camera-timeout 15
```

## 不在本文件範圍內的檔案

| 檔案 | 原因 |
|---|---|
| `le_advertiser.py` | 沒有被 `rpi_beacon_camera.py` import，只是獨立 BLE 廣播測試工具 |
| `le_scanner.py` | 沒有被 `rpi_beacon_camera.py` import，只是獨立 BLE 掃描測試工具 |
