#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PID=/data/local/tmp/volume_daemon.pid
LOG=/data/local/tmp/volume_daemon.log
SCRIPT=/data/data/com.termux/files/home/.termux/tasker/volume_daemon.py
PYTHON=/data/data/com.termux/files/usr/bin/python3

# The pidfile is owned by the daemon, not by this script: volume_daemon.py takes an
# exclusive flock on it and writes its own pid. `echo $!` here would record the `sudo`
# wrapper instead, and killing the wrapper leaves the real (root) python and its mqtt
# helpers orphaned onto init — which is exactly how the stray helper subs accumulated.

daemon_pid() {
  [ -f "$PID" ] || return 1
  local p
  p=$(cat "$PID" 2>/dev/null) || return 1
  [ -n "$p" ] || return 1
  sudo kill -0 "$p" 2>/dev/null || return 1
  echo "$p"
}

stop_daemon() {
  local p
  if p=$(daemon_pid); then
    # TERM first so the daemon can drop the event0 grab and reap its own helpers.
    sudo kill "$p" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      sudo kill -0 "$p" 2>/dev/null || break
      sleep 0.2
    done
    sudo kill -9 "$p" 2>/dev/null || true
  fi
  rm -f "$PID"
}

start_daemon() {
  stop_daemon
  # Termux's python needs these across the sudo boundary, which scrubs the environment.
  export PREFIX=/data/data/com.termux/files/usr
  export LD_LIBRARY_PATH="$PREFIX/lib"
  nohup sudo env PREFIX="$PREFIX" LD_LIBRARY_PATH="$LD_LIBRARY_PATH" \
    "$PYTHON" "$SCRIPT" >>"$LOG" 2>&1 &
  # The daemon writes its own pid; give it a moment to claim the flock.
  sleep 1
}

# Health check for the Cybersyn watchdog task. It lives here rather than as an inline
# `sh -c` in the task because script.termux.run only accepts executables inside
# ~/.termux/tasker (TermuxScriptPolicy.normalizeExecutable) — an inline
# /usr/bin/sh invocation is rejected before it ever runs.
ensure_daemon() {
  if daemon_pid >/dev/null; then
    exit 0
  fi
  printf '[%s] watchdog: volume_daemon down, restarting\n' "$(date -Iseconds)" >>"$LOG"
  start_daemon
}

case "${1:-}" in
  start) start_daemon ;;
  stop) stop_daemon ;;
  ensure) ensure_daemon ;;
  status)
    if p=$(daemon_pid); then
      echo "running pid=$p"
    else
      echo "not running"
      exit 1
    fi
    ;;
  *)
    echo "Usage: volume_daemon.sh {start|stop|ensure|status}" >&2
    exit 1
    ;;
esac
