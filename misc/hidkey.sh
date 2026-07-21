#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

FIFO="/data/local/tmp/pixel_keyboard.fifo"
MODFILE="/data/local/tmp/pixel_keyboard.mod"

asroot() { su -c "$*"; }

get_mod() {
  asroot "test -f '$MODFILE' || echo 0 > '$MODFILE'"
  asroot "cat '$MODFILE' 2>/dev/null" | tr -d '\r\n' | sed 's/[^0-9]//g' || echo 0
}

set_mod() {
  local mod="$1"
  asroot "printf '%s\n' '$mod' > '$MODFILE'"
}

send_report() {
  local mod="$1"
  local key="$2"
  asroot "test -p '$FIFO'" || exit 0
  asroot "printf '%s\n' '{ \"id\": 1, \"command\": \"report\", \"report\": [$mod,0,$key,0,0,0,0,0] }' > '$FIFO'"
}

tap_key() {
  local key="$1"
  local mod
  mod="$(get_mod)"
  send_report "$mod" "$key"
  usleep 30000 2>/dev/null || sleep 0.03
  send_report "$mod" 0
}

toggle_modbit() {
  local bit="$1"
  local mod
  mod="$(get_mod)"
  mod=$(( mod ^ bit ))
  set_mod "$mod"
  send_report "$mod" 0
}

cmd="${1:-}"
[[ -n "$cmd" ]] || exit 0
cmd="$(echo "$cmd" | tr '[:lower:]' '[:upper:]')"

case "$cmd" in
  CTRL)  toggle_modbit 1 ;;
  SHIFT) toggle_modbit 2 ;;
  ALT)   toggle_modbit 4 ;;
  GUI|WIN|META) toggle_modbit 8 ;;
  CLEAR|RESET)
    set_mod 0
    send_report 0 0
    ;;
  ESC)  tap_key 41 ;;
  TAB)  tap_key 43 ;;
  ENTER|RETURN) tap_key 40 ;;
  BSP|BACKSPACE) tap_key 42 ;;
  SPACE) tap_key 44 ;;
  DEL|DELETE) tap_key 76 ;;
  HOME) tap_key 74 ;;
  END)  tap_key 77 ;;
  PGUP|PAGEUP) tap_key 75 ;;
  PGDN|PAGEDOWN) tap_key 78 ;;
  UP)    tap_key 82 ;;
  DOWN)  tap_key 81 ;;
  LEFT)  tap_key 80 ;;
  RIGHT) tap_key 79 ;;
  A) tap_key 4 ;; B) tap_key 5 ;; C) tap_key 6 ;; D) tap_key 7 ;; E) tap_key 8 ;;
  F) tap_key 9 ;; G) tap_key 10 ;; H) tap_key 11 ;; I) tap_key 12 ;; J) tap_key 13 ;;
  K) tap_key 14 ;; L) tap_key 15 ;; M) tap_key 16 ;; N) tap_key 17 ;; O) tap_key 18 ;;
  P) tap_key 19 ;; Q) tap_key 20 ;; R) tap_key 21 ;; S) tap_key 22 ;; T) tap_key 23 ;;
  U) tap_key 24 ;; V) tap_key 25 ;; W) tap_key 26 ;; X) tap_key 27 ;; Y) tap_key 28 ;;
  Z) tap_key 29 ;;
  *)
    exit 0
    ;;
esac
