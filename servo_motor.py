"""Servo motor control for Raspberry Pi 5.

Physical pin 32 maps to BCM GPIO 12 and supports PWM output.
"""

from __future__ import annotations

import argparse
import time

try:
	import RPi.GPIO as GPIO
except ImportError as exc:  # pragma: no cover - hardware specific
	raise SystemExit("RPi.GPIO is required on Raspberry Pi") from exc


SERVO_PIN_PHYSICAL = 32
SERVO_PIN_BCM = 12
SERVO_FREQUENCY = 50


class ServoMotor:
	def __init__(self, pin: int = SERVO_PIN_BCM, frequency: int = SERVO_FREQUENCY):
		self.pin = pin
		self.frequency = frequency
		self._cleaned = False
		GPIO.setmode(GPIO.BCM)
		GPIO.setup(self.pin, GPIO.OUT)
		self._pwm = GPIO.PWM(self.pin, self.frequency)
		self._pwm.start(0)

	@staticmethod
	def _angle_to_duty_cycle(angle: float) -> float:
		angle = max(0.0, min(180.0, angle))
		return 2.5 + (angle / 180.0) * 10.0

	def set_angle(self, angle: float) -> None:
		self._pwm.ChangeDutyCycle(self._angle_to_duty_cycle(angle))
		time.sleep(0.5)

	def cleanup(self) -> None:
		if self._cleaned:
			return
		self._cleaned = True

		# Drop the PWM reference before GPIO cleanup so PWM.__del__ won't run
		# after the global chip handle has already been cleared.
		pwm = self._pwm
		self._pwm = None
		if pwm is not None:
			pwm.stop()
			del pwm

		GPIO.cleanup(self.pin)


def main() -> None:
	parser = argparse.ArgumentParser(description="Control a servo motor")
	parser.add_argument("angle", type=float, help="Target angle in degrees (0-180)")
	parser.add_argument(
		"--pin",
		type=int,
		default=SERVO_PIN_BCM,
		help=f"BCM GPIO pin to use (default: {SERVO_PIN_BCM}, physical pin {SERVO_PIN_PHYSICAL})",
	)
	args = parser.parse_args()

	servo = ServoMotor(pin=args.pin)
	try:
		servo.set_angle(args.angle)
	finally:
		servo.cleanup()


if __name__ == "__main__":
	main()
