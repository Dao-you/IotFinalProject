#!/usr/bin/python3

import cv2
import time
import os
import mediapipe as mp
from gpiozero import OutputDevice

PHOTO_DIR = "photos"
os.makedirs(PHOTO_DIR, exist_ok=True)


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
            return None

        face = max(faces, key=lambda f: f[2] * f[3])
        x, y, w, h = face

        face_center_x = x + w // 2
        error_x = face_center_x - self.center_x

        if error_x > self.dead_zone:
            self.motor.step(direction=-1, steps=self.motor_steps)

        elif error_x < -self.dead_zone:
            self.motor.step(direction=1, steps=self.motor_steps)

        else:
            self.motor.stop()

        return face


class YaGestureDetector:
    def __init__(self, cooldown=5, countdown_seconds=3):
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
        self.last_capture_time = 0
        self.ya_start_time = None

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

    def update_capture_state(self, frame, is_ya):
        now = time.time()

        if now - self.last_capture_time < self.cooldown:
            self.ya_start_time = None
            return False, "Cooldown"

        if not is_ya:
            self.ya_start_time = None
            return False, "Not YA"

        if self.ya_start_time is None:
            self.ya_start_time = now

        elapsed = now - self.ya_start_time
        remaining = self.countdown_seconds - elapsed

        if remaining > 0:
            return False, f"YA detected. Photo in {int(remaining) + 1}"

        timestamp = time.strftime("%Y%m%d_%H%M%S")
        filename = os.path.join(PHOTO_DIR, f"ya_{timestamp}.jpg")

        cv2.imwrite(filename, frame)

        self.last_capture_time = now
        self.ya_start_time = None

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

    FRAME_WIDTH = 320
    FRAME_HEIGHT = 240

    motor = StepperMotor(MOTOR_PINS, delay=0.002)
    face_detector = FaceDetector("haarcascade_frontalface_default.xml")
    face_tracker = FaceTracker(
        motor=motor,
        frame_width=FRAME_WIDTH,
        dead_zone=40,
        motor_steps=4
    )

    ya_detector = YaGestureDetector(
        cooldown=5,
        countdown_seconds=3
    )

    cap = cv2.VideoCapture(0, cv2.CAP_V4L2)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, FRAME_WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, FRAME_HEIGHT)

    if not cap.isOpened():
        motor.cleanup()
        raise RuntimeError("Cannot open USB camera")

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
            face = face_tracker.track(faces)

            is_ya = ya_detector.process(frame)
            captured, status_text = ya_detector.update_capture_state(frame, is_ya)
            if captured:
                captured_any_photo = True

            cv2.line(
                frame,
                (FRAME_WIDTH // 2, 0),
                (FRAME_WIDTH // 2, FRAME_HEIGHT),
                (255, 0, 0),
                1
            )

            if face is not None:
                x, y, w, h = face

                cv2.rectangle(frame, (x, y), (x + w, y + h), (0, 255, 0), 2)

                face_center_x = x + w // 2
                face_center_y = y + h // 2

                cv2.circle(frame, (face_center_x, face_center_y), 5, (0, 0, 255), -1)

            if is_ya:
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
        ya_detector.close()
        cap.release()
        cv2.destroyAllWindows()

    return captured_any_photo


def main():
    run_camera_session(stop_after_capture=False)


if __name__ == "__main__":
    main()
