#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SERVICE_NAME="iot-orienteering-beacon"
PI_LOCAL_NAME="RPi_112360104"
MANUFACTURER_ID="0xFFFF"
PI_BEACON_DATA="00110044"
VENV_DIR="$PROJECT_DIR/.venv-standalone"
ENABLE_SERVICE=1

usage() {
    cat <<USAGE
Usage: scripts/setup_rpi_standalone.sh [options]

Set up this Raspberry Pi as a standalone BLE checkpoint beacon.
It does not install camera, GPIO motor, buzzer, or OLED dependencies.

Options:
  --beacon-data HEX       Pi/checkpoint manufacturer data, default: $PI_BEACON_DATA
  --local-name NAME       BLE local name, default: $PI_LOCAL_NAME
  --manufacturer-id ID    BLE manufacturer id, default: $MANUFACTURER_ID
  --service-name NAME     systemd service name, default: $SERVICE_NAME
  --project-dir PATH      project directory, default: $PROJECT_DIR
  --venv PATH             virtualenv path, default: $VENV_DIR
  --no-service            install dependencies but do not create/start systemd service
  -h, --help              show this help

Example:
  scripts/setup_rpi_standalone.sh --beacon-data 00110045 --local-name RPi_CP2
USAGE
}

run_root() {
    if [[ "${EUID}" -eq 0 ]]; then
        "$@"
    else
        sudo "$@"
    fi
}

write_root_file() {
    local target="$1"
    local content="$2"

    if [[ "${EUID}" -eq 0 ]]; then
        printf "%s\n" "$content" > "$target"
    else
        printf "%s\n" "$content" | sudo tee "$target" > /dev/null
    fi
}

normalize_hex() {
    local value="$1"
    value="${value//0x/}"
    value="${value//0X/}"
    value="${value//:/}"
    value="${value//-/}"
    value="${value//_/}"
    value="${value// /}"
    printf "%s" "${value^^}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --beacon-data)
            PI_BEACON_DATA="$(normalize_hex "${2:?missing value for --beacon-data}")"
            shift 2
            ;;
        --local-name)
            PI_LOCAL_NAME="${2:?missing value for --local-name}"
            shift 2
            ;;
        --manufacturer-id)
            MANUFACTURER_ID="${2:?missing value for --manufacturer-id}"
            shift 2
            ;;
        --service-name)
            SERVICE_NAME="${2:?missing value for --service-name}"
            shift 2
            ;;
        --project-dir)
            PROJECT_DIR="$(cd "${2:?missing value for --project-dir}" && pwd)"
            VENV_DIR="$PROJECT_DIR/.venv-standalone"
            shift 2
            ;;
        --venv)
            VENV_DIR="${2:?missing value for --venv}"
            shift 2
            ;;
        --no-service)
            ENABLE_SERVICE=0
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ "$(uname -s)" != "Linux" ]]; then
    echo "This setup script must run on Raspberry Pi OS or another Linux host with BlueZ." >&2
    exit 1
fi

if [[ ! "$PI_BEACON_DATA" =~ ^[0-9A-Fa-f]+$ || $(( ${#PI_BEACON_DATA} % 2 )) -ne 0 ]]; then
    echo "--beacon-data must be an even-length hex byte string." >&2
    exit 1
fi

if [[ ! "$MANUFACTURER_ID" =~ ^(0x[0-9A-Fa-f]+|0X[0-9A-Fa-f]+|[0-9]+)$ ]]; then
    echo "--manufacturer-id must be a decimal or 0x-prefixed integer." >&2
    exit 1
fi

if [[ ! "$PI_LOCAL_NAME" =~ ^[A-Za-z0-9_.-]+$ ]]; then
    echo "--local-name may only contain letters, numbers, dot, underscore, and dash." >&2
    exit 1
fi

if [[ ! "$SERVICE_NAME" =~ ^[A-Za-z0-9_.@-]+$ ]]; then
    echo "--service-name contains unsupported characters." >&2
    exit 1
fi

cd "$PROJECT_DIR"

echo "Installing Raspberry Pi BLE system packages..."
run_root apt-get update
run_root apt-get install -y \
    bluetooth \
    bluez \
    python3-dbus \
    python3-gi \
    python3-pip \
    python3-venv \
    rfkill

if command -v rfkill > /dev/null 2>&1; then
    run_root rfkill unblock bluetooth || true
fi

if command -v systemctl > /dev/null 2>&1; then
    run_root systemctl enable --now bluetooth
fi

echo "Creating standalone Python environment at $VENV_DIR..."
python3 -m venv "$VENV_DIR"
"$VENV_DIR/bin/python" -m pip install --upgrade pip
"$VENV_DIR/bin/python" -m pip install -r "$PROJECT_DIR/requirements-rpi-standalone.txt"

RUN_COMMAND="$VENV_DIR/bin/python $PROJECT_DIR/rpi_beacon_camera.py --beacon-only --pi-name $PI_LOCAL_NAME --manufacturer-id $MANUFACTURER_ID --pi-data $PI_BEACON_DATA"

if [[ "$ENABLE_SERVICE" -eq 0 ]]; then
    echo "Standalone setup complete."
    echo "Run manually with:"
    echo "  $RUN_COMMAND"
    exit 0
fi

ENV_FILE="/etc/default/$SERVICE_NAME"
SERVICE_FILE="/etc/systemd/system/$SERVICE_NAME.service"

ENV_CONTENT="$(cat <<EOF
PI_LOCAL_NAME=$PI_LOCAL_NAME
MANUFACTURER_ID=$MANUFACTURER_ID
PI_BEACON_DATA=$PI_BEACON_DATA
EOF
)"

SERVICE_CONTENT="$(cat <<EOF
[Unit]
Description=IoT Orienteering standalone BLE checkpoint beacon
After=bluetooth.service dbus.service
Requires=bluetooth.service

[Service]
Type=simple
WorkingDirectory=$PROJECT_DIR
EnvironmentFile=$ENV_FILE
ExecStart=$VENV_DIR/bin/python $PROJECT_DIR/rpi_beacon_camera.py --beacon-only --pi-name \${PI_LOCAL_NAME} --manufacturer-id \${MANUFACTURER_ID} --pi-data \${PI_BEACON_DATA}
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
)"

echo "Writing $ENV_FILE and $SERVICE_FILE..."
write_root_file "$ENV_FILE" "$ENV_CONTENT"
write_root_file "$SERVICE_FILE" "$SERVICE_CONTENT"

run_root systemctl daemon-reload
run_root systemctl enable --now "$SERVICE_NAME"

echo "Standalone beacon service is running."
echo "Beacon data: $PI_BEACON_DATA"
echo "Check status with: systemctl status $SERVICE_NAME"
echo "View logs with: journalctl -u $SERVICE_NAME -f"
