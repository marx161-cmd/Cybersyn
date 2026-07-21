#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

TERMUX_HOME="/data/data/com.termux/files/home"
TASKER_DIR="${TERMUX_HOME}/.termux/tasker"

HIDRUNNER="${TASKER_DIR}/hidrunner.sh"
HIDKEY="${TASKER_DIR}/hidkey.sh"
CLUTCH="${TASKER_DIR}/clutch.sh"
CLICK="${TASKER_DIR}/click.sh"

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

action="${1:-}"
target="${2:-}"

if [[ -z "$action" ]]; then
  echo "Usage: dispatch_input.sh <ACTION> [TARGET]" >&2
  exit 1
fi

action="$(printf '%s' "$action" | tr '[:lower:]' '[:upper:]')"

case "$action" in
  CLUTCH|CLUTCH_TOGGLE)
    run_root_script "$CLUTCH"
    ;;
  CLICK|CLICK_TOGGLE|CLICK_HOLD)
    run_root_script "$CLICK"
    ;;
  HID_START)
    run_root_script "$HIDRUNNER" start
    ;;
  HID_STOP)
    run_root_script "$HIDRUNNER" stop
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
