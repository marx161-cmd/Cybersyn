#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

TERMUX_HOME="/data/data/com.termux/files/home"
TASKER_DIR="${TERMUX_HOME}/.termux/tasker"

HIDRUNNER="${TASKER_DIR}/hidrunner.sh"
HIDKEY="${TASKER_DIR}/hidkey.sh"

CYBERSYN_SEND="/data/data/com.termux/files/usr/bin/cybersyn-send"

need_exec() {
  local file="$1"
  if [[ ! -x "$file" ]]; then
    echo "Missing executable: $file" >&2
    exit 1
  fi
}

run_root_script() {
  local file="$1"
  shift
  need_exec "$file"
  local cmd
  printf -v cmd '%q' "$file"
  if [[ $# -gt 0 ]]; then
    local arg
    for arg in "$@"; do
      printf -v cmd '%s %q' "$cmd" "$arg"
    done
  fi
  su -c "$cmd"
}

dispatch_hid() {
  local token="$1"
  need_exec "$HIDKEY"
  "$HIDKEY" "$token"
}

# Publish "topic<TAB>payload" through Cybersyn's ONE persistent MQTT connection via the
# cybersyn-send ingest wrapper. Previously this spawned its own `libcybersyn-mqtt.so pub`,
# opening a second connection per call; funnelling through cybersyn-send keeps the whole
# phone on the single connection Cybersyn already holds (MqttIngest -> MqttBridge).
mqtt_publish() {
  local topic="$1" payload="$2"
  need_exec "$CYBERSYN_SEND"
  "$CYBERSYN_SEND" "$topic" "$payload"
}

action="${1:-}"
target="${2:-}"

if [[ -z "$action" ]]; then
  echo "Usage: dispatch_input.sh <ACTION> [TARGET]" >&2
  exit 1
fi

action="$(printf '%s' "$action" | tr '[:lower:]' '[:upper:]')"

case "$action" in
  CLUTCH|CLUTCH_TOGGLE)
    # NOTE: this is the relay's android/clutch topic (the gyro-cursor gate), not our
    # "clutch"-the-key-label concept. Preserved exactly as before for Task 121 etc.
    mqtt_publish "android/clutch" "TOGGLE"
    ;;
  CLICK|CLICK_TOGGLE|CLICK_HOLD)
    # NOTE: this is the relay's android/click topic (click-and-hold/drag), not a plain
    # single click. Preserved exactly as before for Task 121 etc.
    mqtt_publish "android/click" "TOGGLE"
    ;;
  HID_START)
    run_root_script "$HIDRUNNER" start
    ;;
  HID_STOP)
    run_root_script "$HIDRUNNER" stop
    ;;
  GYRO_START)
    mqtt_publish "android/clutch" "ON"
    ;;
  GYRO_STOP)
    mqtt_publish "android/clutch" "OFF"
    ;;
  HOLD_START)
    mqtt_publish "android/click" "ON"
    ;;
  HOLD_STOP)
    mqtt_publish "android/click" "OFF"
    ;;
  HID|KEY)
    if [[ -z "$target" ]]; then
      echo "dispatch_input.sh HID <TOKEN>" >&2
      exit 1
    fi
    dispatch_hid "$target"
    ;;
  CTRL|SHIFT|ALT|GUI|WIN|META|CLEAR|RESET|ESC|TAB|ENTER|RETURN|BSP|BACKSPACE|SPACE|DEL|DELETE|HOME|END|PGUP|PAGEUP|PGDN|PAGEDOWN|UP|DOWN|LEFT|RIGHT|A|B|C|D|E|F|G|H|I|J|K|L|M|N|O|P|Q|R|S|T|U|V|W|X|Y|Z)
    dispatch_hid "$action"
    ;;
  *)
    echo "Unknown action: $action" >&2
    exit 1
    ;;
esac
