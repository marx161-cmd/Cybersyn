#!/data/data/com.termux/files/usr/bin/bash
set -u

PIPE=/data/local/tmp/pixel_keyboard.fifo
LOG=/data/local/tmp/pixel_keyboard.log
PID=/data/local/tmp/pixel_keyboard.pid

DESC='[0x05,0x01,0x09,0x06,0xA1,0x01,0x05,0x07,0x19,0xE0,0x29,0xE7,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08,0x81,0x02,0x95,0x01,0x75,0x08,0x81,0x03,0x95,0x05,0x75,0x01,0x05,0x08,0x19,0x01,0x29,0x05,0x91,0x02,0x95,0x01,0x75,0x03,0x91,0x03,0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,0x19,0x00,0x29,0x65,0x81,0x00,0xC0]'

stop_runner() {
  sudo sh -c '
    if [ -f /data/local/tmp/pixel_keyboard.pid ]; then
      kill "$(cat /data/local/tmp/pixel_keyboard.pid)" 2>/dev/null || true
      rm -f /data/local/tmp/pixel_keyboard.pid
    fi
    rm -f /data/local/tmp/pixel_keyboard.fifo
  '
}

start_runner() {
  stop_runner
  sudo sh -c "
    nohup sh -c '
      while true; do
        rm -f \"$PIPE\"
        mkfifo \"$PIPE\"
        {
          echo \"{ \\\"id\\\": 1, \\\"command\\\": \\\"register\\\", \\\"name\\\": \\\"PixelSaneKey\\\", \\\"vid\\\": 0x18d1, \\\"pid\\\": 0x2c40, \\\"bus\\\": \\\"bluetooth\\\", \\\"descriptor\\\": $DESC }\"
          echo \"{ \\\"id\\\": 1, \\\"command\\\": \\\"delay\\\", \\\"duration\\\": 800 }\"
          cat \"$PIPE\"
        } | /system/bin/hid - >>\"$LOG\" 2>&1
        sleep 1
      done
    ' >/dev/null 2>&1 & echo \$! > \"$PID\"
  "
}

case \"${1:-}\" in
  start) start_runner ;;
  stop)  stop_runner ;;
  *)
    echo \"Usage: hidrunner.sh {start|stop}\"
    exit 1
    ;;
esac
