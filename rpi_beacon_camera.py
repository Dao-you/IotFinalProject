import argparse
import asyncio
import os
import shutil
import subprocess
import threading
import time

from bleak import BleakScanner

from bluez_venv import enable_system_dbus_path


enable_system_dbus_path()

import dbus
from bluezero import adapter
from bluezero import advertisement


PI_LOCAL_NAME = "RPi_112360104"
MANUFACTURER_ID = 0xFFFF

PI_BEACON_DATA = b"\x00\x11\x00\x44"

RSSI_TRIGGER_DBM = -60
REQUIRED_STRONG_HITS = 3
CAMERA_COOLDOWN_SECONDS = 3
CAMERA_SESSION_TIMEOUT_SECONDS = 15
ADVERTISEMENT_REGISTRATION_TIMEOUT_SECONDS = 5
BTMGMT_ADVERTISEMENT_INSTANCE = 1
BTMGMT_ADD_ADVERTISEMENT_TIMEOUT_SECONDS = 15
BTMGMT_COMMAND_TIMEOUT_SECONDS = 5
BLE_AD_FLAGS_GENERAL_DISCOVERABLE = b"\x02\x01\x06"


def format_hex(data):
    return f"0x{data.hex()}"


def parse_hex_bytes(value):
    clean_value = value.strip()
    for token in ("0x", "0X", ":", "-", "_", " "):
        clean_value = clean_value.replace(token, "")

    if not clean_value:
        raise argparse.ArgumentTypeError("hex payload cannot be empty")

    if len(clean_value) % 2 != 0:
        raise argparse.ArgumentTypeError("hex payload must contain full bytes")

    try:
        return bytes.fromhex(clean_value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("hex payload contains invalid characters") from exc


def parse_manufacturer_id(value):
    try:
        manufacturer_id = int(value, 0)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("manufacturer id must be an integer") from exc

    if manufacturer_id < 0 or manufacturer_id > 0xFFFF:
        raise argparse.ArgumentTypeError("manufacturer id must fit in 16 bits")

    return manufacturer_id


def load_camera_session_runner():
    try:
        from camera import run_camera_session
    except ImportError as exc:
        raise RuntimeError(
            "Camera mode requires the full camera dependencies. "
            "Use --beacon-only on Raspberry Pi units without camera/GPIO peripherals."
        ) from exc

    return run_camera_session


class PiBeaconAdvertiser:
    def __init__(self, local_name, manufacturer_id, beacon_data):
        self.advert = None
        self.manager = None
        self.thread = None
        self.adapter_address = None
        self.advert_id = 1
        self.btmgmt_instance = BTMGMT_ADVERTISEMENT_INSTANCE
        self.btmgmt_active = False
        self.backend = None
        self.local_name = local_name
        self.manufacturer_id = manufacturer_id
        self.beacon_data = beacon_data

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
        self.advert.local_name = self.local_name
        self.advert.manufacturer_data(self.manufacturer_id, self.beacon_data)

        self.manager = ConfirmingAdvertisingManager(self.adapter_address)
        self.manager.register_advertisement(self.advert, {})

        self.thread = threading.Thread(target=self._run, args=(self.advert,), daemon=True)
        self.thread.start()

        if not self.manager.registration_event.wait(
            timeout=ADVERTISEMENT_REGISTRATION_TIMEOUT_SECONDS
        ):
            print(
                "Warning: BlueZ did not confirm advertisement registration. "
                "Check bluetoothd experimental support and journal logs.",
                flush=True,
            )
            self.backend = "bluez-dbus"
        elif self.manager.registration_error is not None:
            error = self.manager.registration_error
            self._stop_dbus_advertisement()
            print(
                "BlueZ D-Bus advertisement registration failed; "
                f"falling back to btmgmt: {error}",
                flush=True,
            )
            self._start_btmgmt_advertisement()
        else:
            self.backend = "bluez-dbus"

        print(f"Advertising Pi beacon on {self.adapter_address}")
        print(f"Name: {self.local_name}")
        print(f"Manufacturer ID: 0x{self.manufacturer_id:04X}")
        print(f"Manufacturer data: {format_hex(self.beacon_data)}")
        print(f"BLE backend: {self.backend}")

    def _run(self, advert):
        try:
            advert.start()
        except Exception as exc:
            print(f"Beacon advertiser stopped: {exc}")

    def stop(self):
        if self.advert is None and not self.btmgmt_active:
            return

        if self.btmgmt_active:
            self._stop_btmgmt_advertisement()

        self._stop_dbus_advertisement()

    def _stop_dbus_advertisement(self):
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
        if self.backend == "bluez-dbus":
            self.backend = None

    def _start_btmgmt_advertisement(self):
        adv_data = self._build_btmgmt_advertising_data()
        result = self._run_btmgmt(
            ["add-adv", "-d", adv_data.hex(), str(self.btmgmt_instance)],
            check=True,
            timeout=BTMGMT_ADD_ADVERTISEMENT_TIMEOUT_SECONDS,
        )

        self.btmgmt_active = True
        self.backend = "btmgmt"
        print(
            f"Advertisement registered with btmgmt instance {self.btmgmt_instance}",
            flush=True,
        )

    def _stop_btmgmt_advertisement(self):
        self._run_btmgmt(
            ["rm-adv", str(self.btmgmt_instance)],
            check=False,
            timeout=BTMGMT_COMMAND_TIMEOUT_SECONDS,
        )
        self.btmgmt_active = False
        if self.backend == "btmgmt":
            self.backend = None

    def _build_btmgmt_advertising_data(self):
        manufacturer_payload = (
            self.manufacturer_id.to_bytes(2, byteorder="little") + self.beacon_data
        )
        manufacturer_ad = (
            bytes([len(manufacturer_payload) + 1, 0xFF]) + manufacturer_payload
        )
        adv_data = BLE_AD_FLAGS_GENERAL_DISCOVERABLE + manufacturer_ad

        if len(adv_data) > 31:
            raise RuntimeError(
                "btmgmt legacy advertising data cannot exceed 31 bytes; "
                f"got {len(adv_data)} bytes"
            )

        return adv_data

    def _run_btmgmt(self, args, check, timeout=BTMGMT_COMMAND_TIMEOUT_SECONDS):
        command = self._btmgmt_command() + args
        process = subprocess.Popen(command, stdin=subprocess.PIPE)
        try:
            returncode = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired as exc:
            message = (
                "btmgmt command timed out: "
                f"{' '.join(command)}"
            )
            process.kill()
            process.wait()
            if check:
                raise RuntimeError(message) from exc
            print(message, flush=True)
            return None
        finally:
            if process.stdin is not None:
                try:
                    process.stdin.close()
                except BrokenPipeError:
                    pass

        if returncode != 0 and check:
            raise RuntimeError(
                "btmgmt command failed: "
                f"{' '.join(command)}"
            )

        return subprocess.CompletedProcess(command, returncode)

    def _btmgmt_command(self):
        btmgmt_path = shutil.which("btmgmt")
        if btmgmt_path is None:
            raise RuntimeError("btmgmt was not found; install the bluez package")

        if hasattr(os, "geteuid") and os.geteuid() != 0:
            sudo_path = shutil.which("sudo")
            if sudo_path is None:
                raise RuntimeError(
                    "btmgmt fallback requires root privileges or passwordless sudo"
                )
            return [sudo_path, "-n", btmgmt_path]

        return [btmgmt_path]


class ConfirmingAdvertisingManager(advertisement.AdvertisingManager):
    def __init__(self, adapter_addr=None):
        super().__init__(adapter_addr)
        self.registration_event = threading.Event()
        self.registration_error = None

    def register_advertisement(self, advert, options=None):
        self.registration_event.clear()
        self.registration_error = None

        def handle_registered():
            print("Advertisement registered", flush=True)
            self.registration_event.set()

        def handle_error(error):
            self.registration_error = error
            print(f"Failed to register advertisement: {error}", flush=True)
            self.registration_event.set()

        self.advert_mngr_methods.RegisterAdvertisement(
            advert.path,
            dbus.Dictionary(options or {}, signature="sv"),
            reply_handler=handle_registered,
            error_handler=handle_error,
        )


class PhoneBeaconWatcher:
    def __init__(
        self,
        manufacturer_id,
        phone_beacon_data,
        rssi_trigger_dbm,
        required_strong_hits,
        camera_cooldown_seconds,
    ):
        self.queue = asyncio.Queue()
        self.manufacturer_id = manufacturer_id
        self.phone_beacon_data = phone_beacon_data
        self.rssi_trigger_dbm = rssi_trigger_dbm
        self.required_strong_hits = required_strong_hits
        self.camera_cooldown_seconds = camera_cooldown_seconds
        self.strong_hits = 0
        self.last_trigger_at = 0
        self.last_log_at = 0

    def handle_detection(self, device, advertisement_data):
        payload = advertisement_data.manufacturer_data.get(self.manufacturer_id)
        if payload != self.phone_beacon_data:
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
        f"{format_hex(watcher.phone_beacon_data)} at RSSI >= {watcher.rssi_trigger_dbm} dBm..."
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
        "--beacon-only",
        action="store_true",
        help="advertise the Pi/checkpoint beacon only; do not scan phone beacons or start camera",
    )
    parser.add_argument(
        "--pi-name",
        default=PI_LOCAL_NAME,
        help=f"BLE local name for this Raspberry Pi, default: {PI_LOCAL_NAME}",
    )
    parser.add_argument(
        "--manufacturer-id",
        type=parse_manufacturer_id,
        default=MANUFACTURER_ID,
        help=f"BLE manufacturer id, default: 0x{MANUFACTURER_ID:04X}",
    )
    parser.add_argument(
        "--pi-data",
        type=parse_hex_bytes,
        default=PI_BEACON_DATA,
        help=f"manufacturer data advertised by this Raspberry Pi, default: {format_hex(PI_BEACON_DATA)}",
    )
    parser.add_argument(
        "--phone-data",
        type=parse_hex_bytes,
        default=None,
        help="phone beacon manufacturer data to trigger the camera; default: same as --pi-data",
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
    args = parser.parse_args()
    if args.phone_data is None:
        args.phone_data = args.pi_data
    return args


async def main(args):
    run_camera_session = None
    if not args.beacon_only:
        run_camera_session = load_camera_session_runner()

    advertiser = PiBeaconAdvertiser(
        local_name=args.pi_name,
        manufacturer_id=args.manufacturer_id,
        beacon_data=args.pi_data,
    )
    watcher = PhoneBeaconWatcher(
        manufacturer_id=args.manufacturer_id,
        phone_beacon_data=args.phone_data,
        rssi_trigger_dbm=args.rssi,
        required_strong_hits=args.strong_hits,
        camera_cooldown_seconds=args.cooldown,
    )

    advertiser.start()
    if args.beacon_only:
        print("Beacon-only mode: camera and phone-beacon scanner are disabled")
        try:
            while True:
                await asyncio.sleep(3600)
        finally:
            advertiser.stop()
        return

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
