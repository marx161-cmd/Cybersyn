#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PID=/data/local/tmp/volume_daemon.pid
LOG=/data/local/tmp/volume_daemon.log
SCRIPT=/data/data/com.termux/files/home/.termux/tasker/volume_daemon.py
PYTHON=/data/data/com.termux/files/usr/bin/python3

stop_daemon() {
  if [ -f "$PID" ]; then
    sudo kill -9 "$(cat "$PID")" 2>/dev/null || true
    rm -f "$PID"
  fi
}

start_daemon() {
  stop_daemon
  nohup sudo "$PYTHON" "$SCRIPT" >>"$LOG" 2>&1 &
  echo $! > "$PID"
}

case "${1:-}" in
  start) start_daemon ;;
  stop) stop_daemon ;;
  status)
    if [ -f "$PID" ] && sudo kill -0 "$(cat "$PID")" 2>/dev/null; then
      echo "running pid=$(cat "$PID")"
    else
      echo "not running"
      exit 1
    fi
    ;;
  *)
    echo "Usage: volume_daemon.sh {start|stop|status}" >&2
    exit 1
    ;;
esac
