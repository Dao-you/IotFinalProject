import argparse
import asyncio
import threading
import time

from bleak import BleakScanner

from bluez_venv import enable_system_dbus_path


enable_system_dbus_path()

from bluezero import adapter
from bluezero import advertisement

from camera import run_camera_session


PI_LOCAL_NAME = "RPi_112360104"
MANUFACTURER_ID = 0xFFFF

PI_BEACON_DATA = b"\x00\x11\x00\x44"
PHONE_BEACON_DATA = b"\x00\x11\x00\x44"

RSSI_TRIGGER_DBM = -60
REQUIRED_STRONG_HITS = 3
CAMERA_COOLDOWN_SECONDS = 10
CAMERA_SESSION_TIMEOUT_SECONDS = 60

class PiBeaconAdvertiser:
    def __init__(self):
        self.advert = None
        self.manager = None
        self.thread = None
        self.adapter_address = None
        self.advert_id = 1

    def start(self):
        if self.advert is not None:
            return

        dongles = list(adapter.Adapter.available())
        if not dongles:
            raise RuntimeError("BT Device not found")

        self.adapter_address = dongles[0].address
        dongle = adapter.Adapter(self.adapter_address)
        if not dongle.powered:
            dongle.powered = True

        self.advert = advertisement.Advertisement(self.advert_id, "broadcast")
        self.advert_id += 1
        self.advert.local_name = PI_LOCAL_NAME
        self.advert.manufacturer_data(MANUFACTURER_ID, PI_BEACON_DATA)

        self.manager = advertisement.AdvertisingManager(self.adapter_address)
        self.manager.register_advertisement(self.advert, {})

        self.thread = threading.Thread(target=self._run, args=(self.advert,), daemon=True)
        self.thread.start()

        print(f"Advertising Pi beacon on {self.adapter_address}")
        print(f"Name: {PI_LOCAL_NAME}")
        print(f"Manufacturer data: 0x{PI_BEACON_DATA.hex()}")

    def _run(self, advert):
        try:
            advert.start()
        except Exception as exc:
            print(f"Beacon advertiser stopped: {exc}")

    def stop(self):
        if self.advert is None:
            return

        advert = self.advert
        manager = self.manager

        try:
            if manager is not None:
                manager.unregister_advertisement(advert)
        except Exception as exc:
            print(f"Cannot unregister beacon cleanly: {exc}")

        try:
            advert.stop()
        except Exception as exc:
            print(f"Cannot stop beacon cleanly: {exc}")

        if self.thread is not None and self.thread.is_alive():
            self.thread.join(timeout=2)

        self.advert = None
        self.manager = None
        self.thread = None


class PhoneBeaconWatcher:
    def __init__(self, rssi_trigger_dbm, required_strong_hits, camera_cooldown_seconds):
        self.queue = asyncio.Queue()
        self.rssi_trigger_dbm = rssi_trigger_dbm
        self.required_strong_hits = required_strong_hits
        self.camera_cooldown_seconds = camera_cooldown_seconds
        self.strong_hits = 0
        self.last_trigger_at = 0
        self.last_log_at = 0

    def handle_detection(self, device, advertisement_data):
        payload = advertisement_data.manufacturer_data.get(MANUFACTURER_ID)
        if payload != PHONE_BEACON_DATA:
            return

        rssi = advertisement_data.rssi
        name = device.name or advertisement_data.local_name or "Unknown"
        now = time.monotonic()

        if now - self.last_log_at >= 1:
            print(
                f"Phone beacon: {name} {device.address} "
                f"RSSI={rssi} dBm data=0x{payload.hex()}"
            )
            self.last_log_at = now

        if rssi < self.rssi_trigger_dbm:
            self.strong_hits = 0
            return

        self.strong_hits += 1
        if self.strong_hits < self.required_strong_hits:
            return

        if now - self.last_trigger_at < self.camera_cooldown_seconds:
            return

        self.last_trigger_at = now
        self.strong_hits = 0
        self.queue.put_nowait(
            {
                "name": name,
                "address": device.address,
                "rssi": rssi,
                "data": payload.hex(),
            }
        )


async def wait_for_phone_beacon(watcher):
    scanner = BleakScanner(detection_callback=watcher.handle_detection)

    print(
        "Scanning for phone beacon "
        f"0x{PHONE_BEACON_DATA.hex()} at RSSI >= {watcher.rssi_trigger_dbm} dBm..."
    )

    await scanner.start()
    try:
        return await watcher.queue.get()
    finally:
        await scanner.stop()


def parse_args():
    parser = argparse.ArgumentParser(
        description="Advertise the Pi beacon, scan phone beacons, and trigger camera capture."
    )
    parser.add_argument(
        "--preview",
        action="store_true",
        help="show the OpenCV camera preview window during triggered camera sessions",
    )
    parser.add_argument(
        "--rssi",
        type=int,
        default=RSSI_TRIGGER_DBM,
        help=f"RSSI trigger threshold in dBm, default: {RSSI_TRIGGER_DBM}",
    )
    parser.add_argument(
        "--strong-hits",
        type=int,
        default=REQUIRED_STRONG_HITS,
        help=f"required consecutive strong beacon detections, default: {REQUIRED_STRONG_HITS}",
    )
    parser.add_argument(
        "--cooldown",
        type=int,
        default=CAMERA_COOLDOWN_SECONDS,
        help=f"seconds to wait after a camera session, default: {CAMERA_COOLDOWN_SECONDS}",
    )
    parser.add_argument(
        "--camera-timeout",
        type=int,
        default=CAMERA_SESSION_TIMEOUT_SECONDS,
        help=f"camera session timeout in seconds, default: {CAMERA_SESSION_TIMEOUT_SECONDS}",
    )
    return parser.parse_args()


async def main(args):
    advertiser = PiBeaconAdvertiser()
    watcher = PhoneBeaconWatcher(
        rssi_trigger_dbm=args.rssi,
        required_strong_hits=args.strong_hits,
        camera_cooldown_seconds=args.cooldown,
    )

    advertiser.start()
    if args.preview:
        print("Camera preview: enabled")
    else:
        print("Camera preview: disabled")

    try:
        while True:
            trigger = await wait_for_phone_beacon(watcher)

            print(
                "Strong phone beacon detected: "
                f"{trigger['name']} {trigger['address']} RSSI={trigger['rssi']} dBm"
            )
            print("Starting camera session")

            if args.preview:
                print("Pausing Pi beacon while preview window is open")
                advertiser.stop()

            try:
                captured = run_camera_session(
                    stop_after_capture=True,
                    session_timeout=args.camera_timeout,
                    show_preview=args.preview,
                )
            finally:
                if args.preview:
                    print("Restarting Pi beacon")
                    advertiser.start()

            if captured:
                print("Camera session completed with photo")
            else:
                print("Camera session ended without photo")

            print(f"Cooling down for {args.cooldown} seconds")
            await asyncio.sleep(args.cooldown)

    finally:
        advertiser.stop()


if __name__ == "__main__":
    try:
        asyncio.run(main(parse_args()))
    except KeyboardInterrupt:
        print("\nStopped")
