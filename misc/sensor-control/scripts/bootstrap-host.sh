#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UNIT_TEMPLATE="$ROOT/systemd/sensor-control.service.in"
UNIT_DST="$HOME/.config/systemd/user/sensor-control.service"
ENABLE_NOW=0

if [[ "${1:-}" == "--enable-now" ]]; then
  ENABLE_NOW=1
fi

mkdir -p "$HOME/.config/systemd/user"
sed "s|@ROOT@|$ROOT|g" "$UNIT_TEMPLATE" > "$UNIT_DST"
systemctl --user daemon-reload

if [[ "$ENABLE_NOW" -eq 1 ]]; then
  systemctl --user enable --now sensor-control.service
else
  systemctl --user enable sensor-control.service
fi

echo "Installed unit: $UNIT_DST"
systemctl --user --no-pager --full status sensor-control.service | sed -n '1,40p' || true
