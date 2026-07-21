#!/data/data/com.termux/files/usr/bin/bash
PREFIX=/data/data/com.termux/files/usr
HOME_TERMUX=/data/data/com.termux/files/home
PATH=$PREFIX/bin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin
LOG=$HOME_TERMUX/.termux/boot/start-gyro-control.log
TASKER_DIR=$HOME_TERMUX/.termux/tasker
ENVFILE=$TASKER_DIR/sensor-relay.env

mkdir -p "$TASKER_DIR"

cat > "$TASKER_DIR/clutch.sh" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
/data/data/com.termux/files/usr/bin/mosquitto_pub -h 100.108.8.60 -t "android/clutch" -m "TOGGLE"
EOS

cat > "$TASKER_DIR/click.sh" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
/data/data/com.termux/files/usr/bin/mosquitto_pub -h 100.108.8.60 -t "android/click" -m "TOGGLE"
EOS

chmod 700 "$TASKER_DIR/clutch.sh" "$TASKER_DIR/click.sh"

ENABLE_SENSOR_RELAY=1
[ -f "$ENVFILE" ] && . "$ENVFILE"

if [ "${ENABLE_SENSOR_RELAY:-1}" = "1" ] && [ -x "$TASKER_DIR/sensor-relay-start.sh" ]; then
  "$TASKER_DIR/sensor-relay-start.sh" >> "$LOG" 2>&1 || true
fi

{
  echo "[$(date +%F_%T)] gyro boot prep"
  if command -v /data/data/com.termux/files/usr/bin/mosquitto_pub >/dev/null 2>&1; then
    echo "mosquitto_pub: ok"
  else
    echo "mosquitto_pub: MISSING"
  fi
  ls -l "$TASKER_DIR/clutch.sh" "$TASKER_DIR/click.sh"
} >> "$LOG" 2>&1
