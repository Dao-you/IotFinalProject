#!/usr/bin/python3

import cv2
import math
import time
import os
import mediapipe as mp
from gpiozero import OutputDevice, PWMOutputDevice

PHOTO_DIR = "photos"
os.makedirs(PHOTO_DIR, exist_ok=True)


class Buzzer:
    def __init__(self, pin, beep_seconds=0.2, frequency=2000, active_high=True):
        self.device = PWMOutputDevice(
            pin,
            frequency=frequency,
            active_high=active_high,
            initial_value=0
        )
        self.beep_seconds = beep_seconds

    def beep(self):
        self.device.value = 0.5
        time.sleep(self.beep_seconds)
        self.device.value = 0

    def cleanup(self):
        self.device.value = 0
        self.device.close()


class StepperMotor:
    HALF_STEP_SEQ = [
        [1, 0, 0, 0],
        [1, 1, 0, 0],
        [0, 1, 0, 0],
        [0, 1, 1, 0],
        [0, 0, 1, 0],
        [0, 0, 1, 1],
        [0, 0, 0, 1],
        [1, 0, 0, 1]
    ]

    def __init__(self, pins, delay=0.002):
        self.pins = [OutputDevice(pin) for pin in pins]
        self.delay = delay
        self.step_index = 0

    def step(self, direction, steps=1):
        for _ in range(steps):
            self.step_index += direction
            self.step_index %= len(self.HALF_STEP_SEQ)

            pattern = self.HALF_STEP_SEQ[self.step_index]

            for pin, value in zip(self.pins, pattern):
                if value:
                    pin.on()
                else:
                    pin.off()

            time.sleep(self.delay)

    def stop(self):
        for pin in self.pins:
            pin.off()

    def cleanup(self):
        self.stop()
        for pin in self.pins:
            pin.close()


class FaceDetector:
    def __init__(self, cascade_path="haarcascade_frontalface_default.xml"):
        self.face_cascade = cv2.CascadeClassifier(cascade_path)

        if self.face_cascade.empty():
            raise RuntimeError("Cannot load haarcascade_frontalface_default.xml")

    def detect(self, frame):
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

        faces = self.face_cascade.detectMultiScale(
            gray,
            scaleFactor=1.1,
            minNeighbors=5,
            minSize=(30, 30)
        )

        return faces


class FaceTracker:
    def __init__(self, motor, frame_width=320, dead_zone=40, motor_steps=4):
        self.motor = motor
        self.center_x = frame_width // 2
        self.dead_zone = dead_zone
        self.motor_steps = motor_steps

    def track(self, faces):
        if len(faces) == 0:
            self.motor.stop()
            return None, None, False

        target_center = self.get_target_center(faces)
        error_x = target_center[0] - self.center_x

        if error_x > self.dead_zone:
            self.motor.step(direction=-1, steps=self.motor_steps)
            return faces, target_center, True

        elif error_x < -self.dead_zone:
            self.motor.step(direction=1, steps=self.motor_steps)
            return faces, target_center, True

        else:
            self.motor.stop()

        return faces, target_center, False

    def get_target_center(self, faces):
        if len(faces) == 1:
            x, y, w, h = faces[0]
            return x + w // 2, y + h // 2

        centers = []
        for x, y, w, h in faces:
            centers.append((x + w // 2, y + h // 2))

        center_x = sum(center[0] for center in centers) // len(centers)
        center_y = sum(center[1] for center in centers) // len(centers)

        return center_x, center_y


class YaGestureDetector:
    def __init__(self, cooldown=5, countdown_seconds=3, buzzer=None):
        self.mp_hands = mp.solutions.hands
        self.mp_draw = mp.solutions.drawing_utils

        self.hands = self.mp_hands.Hands(
            static_image_mode=False,
            max_num_hands=1,
            min_detection_confidence=0.6,
            min_tracking_confidence=0.6
        )

        self.cooldown = cooldown
        self.countdown_seconds = countdown_seconds
        self.buzzer = buzzer
        self.last_capture_time = 0
        self.ya_start_time = None
        self.last_countdown_beep = None

    def finger_is_up(self, lm, tip, pip):
        return lm[tip].y < lm[pip].y

    def is_ya(self, hand_landmarks):
        lm = hand_landmarks.landmark

        index_up = self.finger_is_up(lm, 8, 6)
        middle_up = self.finger_is_up(lm, 12, 10)
        ring_down = not self.finger_is_up(lm, 16, 14)
        pinky_down = not self.finger_is_up(lm, 20, 18)

        return index_up and middle_up and ring_down and pinky_down

    def process(self, frame):
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        result = self.hands.process(rgb)

        is_ya = False

        if result.multi_hand_landmarks:
            for hand_landmarks in result.multi_hand_landmarks:
                self.mp_draw.draw_landmarks(
                    frame,
                    hand_landmarks,
                    self.mp_hands.HAND_CONNECTIONS
                )

                if self.is_ya(hand_landmarks):
                    is_ya = True

        return is_ya

    def update_capture_state(self, frame, is_ya, can_capture=True):
        now = time.time()

        if not can_capture:
            self.ya_start_time = None
            self.last_countdown_beep = None
            return False, "Centering"

        if now - self.last_capture_time < self.cooldown:
            self.ya_start_time = None
            self.last_countdown_beep = None
            return False, "Cooldown"

        if not is_ya:
            self.ya_start_time = None
            self.last_countdown_beep = None
            return False, "Not YA"

        if self.ya_start_time is None:
            self.ya_start_time = now

        elapsed = now - self.ya_start_time
        remaining = self.countdown_seconds - elapsed

        if remaining > 0:
            countdown_number = math.ceil(remaining)
            if countdown_number != self.last_countdown_beep:
                if self.buzzer is not None:
                    self.buzzer.beep()
                self.last_countdown_beep = countdown_number
            return False, f"YA detected. Photo in {countdown_number}"

        timestamp = time.strftime("%Y%m%d_%H%M%S")
        filename = os.path.join(PHOTO_DIR, f"ya_{timestamp}.jpg")

        cv2.imwrite(filename, frame)

        self.last_capture_time = now
        self.ya_start_time = None
        self.last_countdown_beep = None

        print(f"Photo saved: {filename}")

        return True, "Photo Saved"

    def close(self):
        self.hands.close()


def run_camera_session(
    stop_after_capture=True,
    session_timeout=None,
    show_preview=True
):
    MOTOR_PINS = [17, 27, 22, 23]
    BUZZER_PIN = 12
    BUZZER_ACTIVE_HIGH = True

    FRAME_WIDTH = 320
    FRAME_HEIGHT = 240

    motor = StepperMotor(MOTOR_PINS, delay=0.002)
    buzzer = Buzzer(BUZZER_PIN, active_high=BUZZER_ACTIVE_HIGH)
    face_detector = FaceDetector("haarcascade_frontalface_default.xml")
    face_tracker = FaceTracker(
        motor=motor,
        frame_width=FRAME_WIDTH,
        dead_zone=40,
        motor_steps=4
    )

    ya_detector = YaGestureDetector(
        cooldown=5,
        countdown_seconds=3,
        buzzer=buzzer
    )

    cap = cv2.VideoCapture(0, cv2.CAP_V4L2)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, FRAME_WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, FRAME_HEIGHT)

    if not cap.isOpened():
        motor.cleanup()
        buzzer.cleanup()
        raise RuntimeError("Cannot open USB camera")

    buzzer.beep()

    session_started_at = time.time()
    captured_any_photo = False

    try:
        while True:
            if session_timeout is not None:
                elapsed = time.time() - session_started_at
                if elapsed >= session_timeout:
                    print("Camera session timeout")
                    break

            ret, frame = cap.read()

            if not ret or frame is None:
                print("Cannot read frame")
                break

            frame = cv2.flip(frame, 1)

            faces = face_detector.detect(frame)
            tracked_faces, target_center, is_centering = face_tracker.track(faces)

            is_ya = ya_detector.process(frame)
            captured, status_text = ya_detector.update_capture_state(
                frame,
                is_ya,
                can_capture=not is_centering
            )
            if captured:
                captured_any_photo = True

            cv2.line(
                frame,
                (FRAME_WIDTH // 2, 0),
                (FRAME_WIDTH // 2, FRAME_HEIGHT),
                (255, 0, 0),
                1
            )

            if tracked_faces is not None:
                face_centers = []

                for x, y, w, h in tracked_faces:
                    face_center_x = x + w // 2
                    face_center_y = y + h // 2
                    face_centers.append((face_center_x, face_center_y))

                    cv2.rectangle(frame, (x, y), (x + w, y + h), (0, 255, 0), 2)
                    cv2.circle(frame, (face_center_x, face_center_y), 4, (0, 0, 255), -1)

                if len(face_centers) > 1:
                    ordered_centers = sorted(face_centers, key=lambda center: center[0])

                    for start, end in zip(ordered_centers, ordered_centers[1:]):
                        cv2.line(frame, start, end, (255, 255, 0), 1)

                    cv2.circle(frame, target_center, 7, (0, 255, 255), -1)
                else:
                    cv2.circle(frame, target_center, 5, (0, 0, 255), -1)

            if is_centering:
                color = (0, 255, 255)
            elif is_ya:
                color = (0, 255, 0)
            else:
                color = (0, 0, 255)

            cv2.putText(
                frame,
                status_text,
                (15, 30),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.5,
                color,
                1
            )

            if show_preview:
                display_frame = cv2.resize(frame, (960, 720))
                cv2.imshow("Face Tracking + YA Countdown", display_frame)

                if cv2.waitKey(1) & 0xFF == ord("q"):
                    break

            if captured and stop_after_capture:
                break

    finally:
        motor.cleanup()
        buzzer.cleanup()
        ya_detector.close()
        cap.release()
        cv2.destroyAllWindows()

    return captured_any_photo


def main():
    run_camera_session(stop_after_capture=False)


if __name__ == "__main__":
    main()
