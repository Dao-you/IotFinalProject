import RPi.GPIO as GPIO
import time

# 設定 GPIO 模式
GPIO.setmode(GPIO.BCM)
GPIO.setwarnings(False)

# 定義控制引腳
control_pins = [17, 27, 22, 23]

# 設定所有引腳為輸出模式
for pin in control_pins:
    GPIO.setup(pin, GPIO.OUT)
    GPIO.output(pin, 0)

# 步進序列（半步模式運轉更平順）
seq = [
    [1,0,0,0],
    [1,1,0,0],
    [0,1,0,0],
    [0,1,1,0],
    [0,0,1,0],
    [0,0,1,1],
    [0,0,0,1],
    [1,0,0,1]
]

# 旋轉函數 (steps 為步數，delay 為每步間隔時間)
def rotate(steps, delay):
    for i in range(steps):
        for halfstep in range(8):
            for pin in range(4):
                GPIO.output(control_pins[pin], seq[halfstep][pin])
            time.sleep(delay)

# 讓馬達運轉
try:
    print("順時針旋轉一圈...")
    rotate(512, 0.001) # 28BYJ-48 一圈約 4096 個半步，故 512 個循環為一圈
    time.sleep(1)
    
    print("逆時針旋轉一圈...")
    seq.reverse()
    rotate(512, 0.001)
    seq.reverse() # 復原序列

except KeyboardInterrupt:
    print("程式終止")

finally:
    GPIO.cleanup() # 清理 GPIO 設定
