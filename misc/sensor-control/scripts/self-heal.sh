#!/usr/bin/env bash
set -euo pipefail

echo "[check] mosquitto"
if ! systemctl is-active --quiet mosquitto; then
  echo "[heal] starting mosquitto"
  sudo systemctl restart mosquitto
fi

echo "[check] /dev/uinput"
if [[ ! -e /dev/uinput ]]; then
  echo "[fail] /dev/uinput missing"
  exit 1
fi

echo "[check] input group membership"
if ! id -nG "$USER" | tr ' ' '\n' | rg -qx input; then
  echo "[warn] user '$USER' is not in input group"
fi

echo "[check] sensor-control service"
if ! systemctl --user is-active --quiet sensor-control.service; then
  echo "[heal] restarting sensor-control"
  systemctl --user restart sensor-control.service
fi

echo "[check] broker path"
if ! timeout 2 mosquitto_pub -h 127.0.0.1 -t android/clutch -m TOGGLE >/dev/null 2>&1; then
  echo "[warn] local publish test failed"
fi

echo "[ok] self-heal finished"
