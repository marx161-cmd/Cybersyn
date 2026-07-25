#!/data/data/com.termux/files/usr/bin/sh
# cybersyn-telemetry — periodic device-state capture (SQLite, 5-min interval).
#
# Captures: battery, thermal, memory, CPU, top processes, GPU memory, NPU
# workers, SELinux denial count, logcat error tail, MQTT heartbeat.
#
# Subcommands: start | stop | status | once | query '<sql>'
#
# ---- Tunables ----------------------------------------------------------------
INTERVAL_SEC=300
DB=/data/data/com.termux/files/home/.cybersyn/telemetry/telemetry.db
PIDFILE=/data/data/com.termux/files/home/.cybersyn/telemetry_daemon.pid
MQTT_BROKER=100.108.8.60
MQTT_TOPIC=cybersyn/telemetry
LOGTAIL_LINES=200
# ------------------------------------------------------------------------------
SELF="$0"
: "${B:=/sys/class/power_supply/battery}"
: "${W:=/sys/class/power_supply/wireless}"

getv() { cat "$1" 2>/dev/null || echo ""; }
TMPDIR="${TMPDIR:-/data/data/com.termux/files/usr/tmp}"
mkdir -p "$TMPDIR" "$(dirname "$DB")" 2>/dev/null

sql()  { sqlite3 -bail -batch "$DB" "$@"; }
sql_esc() { printf '%s' "$1" | sed "s/'/''/g"; }

col_exists() { sql "SELECT 1 FROM pragma_table_info('$1') WHERE name='$2';" | grep -q 1; }
add_col()   { col_exists "$1" "$2" || sql "ALTER TABLE $1 ADD COLUMN $2 $3;"; }

# ---- Schema (CREATE TABLE + auto-migration for new columns) ------------------
init_db() {
  sql "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;"

  sql "CREATE TABLE IF NOT EXISTS telemetry (
    ts_epoch         INTEGER PRIMARY KEY,
    ts_iso           TEXT NOT NULL,
    uptime_s         INTEGER,
    batt_level        INTEGER,  batt_temp_dc      INTEGER,
    batt_current_ua   INTEGER,  batt_voltage_uv    INTEGER,
    batt_status       TEXT,     batt_charge_type   INTEGER,
    batt_health       TEXT,     wireless_on        INTEGER,
    tz_battery        INTEGER,  tz_soc             INTEGER,
    tz_gpu            INTEGER,  tz_tpu             INTEGER,
    tz_big            INTEGER,  tz_big_mid         INTEGER,
    tz_mid            INTEGER,  tz_little          INTEGER,
    tz_mem            INTEGER,  tz_charging        INTEGER,
    tz_quiet          INTEGER,
    mem_total         INTEGER,  mem_free           INTEGER,
    mem_available     INTEGER,  mem_swap_total     INTEGER,
    mem_swap_free     INTEGER,
    load_1m           INTEGER,  load_5m            INTEGER,  load_15m INTEGER,
    screen_state      TEXT,
    charge_cool_state TEXT,     dc_icl_force_ua    INTEGER,
    gpu_mem_total_kb  INTEGER,  gpu_mem_top_kb     INTEGER,
    gpu_mem_top_proc  TEXT,
    npu_worker_count  INTEGER,  npu_worker_rss_kb  INTEGER,
    npu_worker_cpu_ticks INTEGER,
    avc_denials       INTEGER,
    logcat_warn_tail  TEXT,
    mqtt_ok           INTEGER,
    proc_cybersyn     INTEGER,  proc_mosquitto     INTEGER,
    proc_poll_e       INTEGER,  proc_embed         INTEGER,
    proc_charge_cool  INTEGER,  proc_sshd          INTEGER
  );"

  # Migrations: add any new columns without touching existing data
  add_col telemetry updatable_crashing    "INTEGER DEFAULT 0"
  add_col telemetry reset_flags_count     "INTEGER DEFAULT 0"
  add_col telemetry apex_sessions         "INTEGER DEFAULT 0"
  add_col telemetry attempted_boot_count  "INTEGER DEFAULT 0"

  sql "CREATE INDEX IF NOT EXISTS idx_telem_ts ON telemetry(ts_epoch);"
  sql "CREATE TABLE IF NOT EXISTS top_processes (
    ts_epoch  INTEGER NOT NULL, rank  INTEGER NOT NULL,
    pid       INTEGER, rss_kb  INTEGER, cpu_ticks INTEGER,
    state     TEXT,    comm    TEXT,
    PRIMARY KEY (ts_epoch, rank)
  );"
  sql "CREATE INDEX IF NOT EXISTS idx_topproc_ts ON top_processes(ts_epoch);"
}

# ---- Data collectors ---------------------------------------------------------
collect_now() {
  TS_EPOCH=$(date +%s)
  TS_ISO=$(date -u +%Y-%m-%dT%H:%M:%SZ)

  read -r UPTIME_S _ < /proc/uptime 2>/dev/null; UPTIME_S="${UPTIME_S%%.*}"; UPTIME_S="${UPTIME_S:-0}"

  B_LVL=$(getv "$B/capacity");    B_LVL="${B_LVL:-0}"
  B_TMP=$(getv "$B/temp");        B_TMP="${B_TMP:-0}"
  B_CUR=$(getv "$B/current_now"); B_CUR="${B_CUR:-0}"
  B_VOL=$(getv "$B/voltage_now"); B_VOL="${B_VOL:-0}"
  B_STS=$(getv "$B/status");      B_STS="${B_STS:-unknown}"
  B_CTY=$(getv "$B/charge_type"); B_CTY="${B_CTY:--1}"
  B_HLT=$(getv "$B/health");      B_HLT="${B_HLT:-unknown}"
  WL_ON=$(getv "$W/online");      WL_ON="${WL_ON:-0}"

  therm() { v=$(getv "/sys/class/thermal/${1}/temp" 2>/dev/null); [ -z "$v" ] && echo 0 || echo "$v"; }
  TZ_BATTERY=$(therm thermal_zone0);  TZ_SOC=$(therm thermal_zone7)
  TZ_GPU=$(therm thermal_zone20);      TZ_TPU=$(therm thermal_zone21)
  TZ_BIG=$(therm thermal_zone16);      TZ_BIG_MID=$(therm thermal_zone17)
  TZ_MID=$(therm thermal_zone18);      TZ_LITTLE=$(therm thermal_zone19)
  TZ_MEM=$(therm thermal_zone24);      TZ_CHG=$(therm thermal_zone10)
  TZ_QUIET=$(therm thermal_zone9)

  mem() { awk -v k="$1" '$1==k":"{print $2}' /proc/meminfo 2>/dev/null || echo 0; }
  MEM_TOTAL=$(mem MemTotal); MEM_FREE=$(mem MemFree); MEM_AVAIL=$(mem MemAvailable)
  MEM_SWAP_TOTAL=$(mem SwapTotal); MEM_SWAP_FREE=$(mem SwapFree)

  read -r _LA1 _LA5 _LA15 _ _ < /proc/loadavg 2>/dev/null
  LA1=$(echo "${_LA1:-0}" | awk '{printf "%d", $1*100}')
  LA5=$(echo "${_LA5:-0}" | awk '{printf "%d", $1*100}')
  LA15=$(echo "${_LA15:-0}" | awk '{printf "%d", $1*100}')

  # ---- GPU memory (dumpsys gpu) ----------------------------------------------
  GPU_MEM_TOTAL=0; GPU_MEM_TOP_KB=0; GPU_MEM_TOP_PROC=""
  GPU_RAW=$(dumpsys gpu 2>/dev/null)
  GPU_MEM_TOTAL=$(echo "$GPU_RAW" | sed -n 's/.*Global total: *//p' | head -1)
  GPU_MEM_TOP_LINE=$(echo "$GPU_RAW" | grep "Proc " | awk '{print $3, $5}' | sort -t' ' -k2 -rn | head -1)
  if [ -n "$GPU_MEM_TOP_LINE" ]; then
    GPU_MEM_TOP_PROC=$(echo "$GPU_MEM_TOP_LINE" | awk '{print $1}')
    GPU_MEM_TOP_KB=$(echo "$GPU_MEM_TOP_LINE" | awk '{print $2}')
  fi
  GPU_MEM_TOTAL="${GPU_MEM_TOTAL:-0}"; GPU_MEM_TOP_KB="${GPU_MEM_TOP_KB:-0}"

  # ---- NPU workers -----------------------------------------------------------
  NPU_COUNT=0; NPU_RSS=0; NPU_CPU_TICKS=0
  for npu_pid in $(pgrep -f 'libpoll_e_worker|libembeddinggemma' 2>/dev/null); do
    NPU_COUNT=$((NPU_COUNT + 1))
    # RSS from /proc/pid/status (labeled, reliable)
    npu_rss_kb=$(awk '/^VmRSS:/{print $2}' "/proc/$npu_pid/status" 2>/dev/null)
    NPU_RSS=$((NPU_RSS + ${npu_rss_kb:-0}))
    # CPU ticks from /proc/pid/stat (fields 14+15 after stripping comm)
    stat_line=$(cat "/proc/$npu_pid/stat" 2>/dev/null)
    if [ -n "$stat_line" ]; then
      rest=$(echo "$stat_line" | sed 's/^[^)]*) //')
      utime=$(echo "$rest" | awk '{print $12}')
      stime=$(echo "$rest" | awk '{print $13}')
      NPU_CPU_TICKS=$((NPU_CPU_TICKS + ${utime:-0} + ${stime:-0}))
    fi
  done

  # ---- Top processes (top 15 by RSS) -----------------------------------------
  TMP_PS="$TMPDIR/cybersyn-telem-ps.$$"
  ps -eo pid,rss,stat,args --no-headers --sort=-rss 2>/dev/null | head -15 > "$TMP_PS"

  # ---- Process counts --------------------------------------------------------
  pg_count() { v=$(pgrep -cf "$1" 2>/dev/null); echo "${v:-0}"; }
  P_CYBERSYN=$(pg_count cybersyn)
  P_MOSQUITTO=$(pg_count mosquitto)
  P_POLL_E=$(pg_count libpoll_e_worker)
  P_EMBED=$(pg_count libembeddinggemma)
  P_CHARGE_COOL=$(pg_count charge-cool)
  P_SSHD=$(pg_count sshd)

  # ---- Screen state ----------------------------------------------------------
  SCREEN_STATE=$(dumpsys power 2>/dev/null | sed -n 's/.*mWakefulness=//p' | head -1)
  SCREEN_STATE="${SCREEN_STATE:-unknown}"

  # ---- Charge-cool daemon ----------------------------------------------------
  CC_STATUS="stopped"
  CC_PID=$(getv /data/data/com.termux/files/home/.cybersyn/charge_cool.pid)
  [ -n "$CC_PID" ] && [ -d "/proc/$CC_PID" ] && CC_STATUS="running"

  # ---- DC_ICL ----------------------------------------------------------------
  DC_ICL_FORCE=0
  [ -e /sys/kernel/debug/gvotables/DC_ICL/force_int_active ] && {
    if [ "$(getv /sys/kernel/debug/gvotables/DC_ICL/force_int_active)" = "1" ]; then
      DC_ICL_FORCE=$(getv /sys/kernel/debug/gvotables/DC_ICL/force_int_value)
    fi
  }

  # ---- SELinux AVC denials ---------------------------------------------------
  AVC_COUNT=$(dmesg 2>/dev/null | grep -c "avc:"); AVC_COUNT="${AVC_COUNT:-0}"

  # ---- OTA health / crash recovery state -------------------------------------
  UPDATABLE_CRASHING=$(getprop sys.init.updatable_crashing 2>/dev/null); UPDATABLE_CRASHING="${UPDATABLE_CRASHING:-0}"
  RESET_FLAGS_COUNT=$(find /data/server_configurable_flags/reset_flags -type f 2>/dev/null | wc -l)
  APEX_SESSIONS=$(ls /metadata/apex/sessions/ 2>/dev/null | wc -l)
  BOOT_COUNT=$(getprop persist.device_config.attempted_boot_count 2>/dev/null); BOOT_COUNT="${BOOT_COUNT:-0}"

  # ---- Logcat warning+ tail (root required) ----------------------------------
  LOGTAIL=""
  LOGTAIL=$(su -c "logcat -d -v time *:W 2>/dev/null | tail -n $LOGTAIL_LINES" 2>/dev/null)
  # Replace newlines with ⏎ for single-line SQL storage; escape single quotes
  LOGTAIL=$(echo "$LOGTAIL" | sed "s/'/''/g" | tr '\n' '\036')

  # ---- MQTT ------------------------------------------------------------------
  MQTT_OK=0
  MQTT_PUB="${PREFIX:-/data/data/com.termux/files/usr}/bin/mosquitto_pub"
  timeout 3 "$MQTT_PUB" -h "$MQTT_BROKER" -t "cybersyn/blazer/alive" \
    -m "{\"ts\":$TS_EPOCH}" -q 0 2>/dev/null && MQTT_OK=1 || MQTT_OK=0
}

# ---- Write to DB -------------------------------------------------------------
write_tick() {
  local bsts_e bhlth_e screen_e cc_e logtail_e
  bsts_e=$(sql_esc "$B_STS"); bhlth_e=$(sql_esc "$B_HLT")
  screen_e=$(sql_esc "$SCREEN_STATE"); cc_e=$(sql_esc "$CC_STATUS")
  logtail_e=$(sql_esc "$LOGTAIL"); gpu_top_e=$(sql_esc "$GPU_MEM_TOP_PROC")

  sql "INSERT INTO telemetry VALUES (
    $TS_EPOCH, '$(sql_esc "$TS_ISO")', $UPTIME_S,
    $B_LVL, $B_TMP, $B_CUR, $B_VOL,
    '$bsts_e', $B_CTY, '$bhlth_e', $WL_ON,
    $TZ_BATTERY, $TZ_SOC, $TZ_GPU, $TZ_TPU,
    $TZ_BIG, $TZ_BIG_MID, $TZ_MID, $TZ_LITTLE,
    $TZ_MEM, $TZ_CHG, $TZ_QUIET,
    $MEM_TOTAL, $MEM_FREE, $MEM_AVAIL,
    $MEM_SWAP_TOTAL, $MEM_SWAP_FREE,
    $LA1, $LA5, $LA15,
    '$screen_e', '$cc_e', $DC_ICL_FORCE,
    $GPU_MEM_TOTAL, $GPU_MEM_TOP_KB, '$gpu_top_e',
    $NPU_COUNT, $NPU_RSS, $NPU_CPU_TICKS,
    $AVC_COUNT, '$logtail_e', $MQTT_OK,
    $P_CYBERSYN, $P_MOSQUITTO, $P_POLL_E, $P_EMBED,
    $P_CHARGE_COOL, $P_SSHD,
    $UPDATABLE_CRASHING, $RESET_FLAGS_COUNT, $APEX_SESSIONS, $BOOT_COUNT
  );"

  rank=1
  while IFS=' ' read -r pid rss state args; do
    comm=$(basename "$args" 2>/dev/null); comm="${comm#\{}"; comm="${comm%% *}"
    [ ${#comm} -gt 80 ] && comm="$(echo "$comm" | cut -c1-77)..."
    rss="${rss:-0}"; state="${state:-?}"
    ticks=0
    stat_raw=$(cat "/proc/$pid/stat" 2>/dev/null)
    if [ -n "$stat_raw" ]; then
      rest=$(echo "$stat_raw" | sed 's/^[^)]*) //')
      utime=$(echo "$rest" | awk '{print $12}')
      stime=$(echo "$rest" | awk '{print $13}')
      ticks=$(( ${utime:-0} + ${stime:-0} ))
    fi
    sql "INSERT INTO top_processes VALUES ($TS_EPOCH, $rank, $pid, $rss, $ticks, '$state', '$(sql_esc "$comm")');"
    rank=$((rank + 1))
  done < "$TMP_PS"
  rm -f "$TMP_PS"
}

capture_tick() {
  collect_now
  init_db
  write_tick

  # MQTT summary
  if [ "$MQTT_OK" = "1" ]; then
    timeout 3 "$MQTT_PUB" -h "$MQTT_BROKER" -t "$MQTT_TOPIC" \
      -m "{\"ts\":$TS_EPOCH,\"batt_pct\":$B_LVL,\"batt_tmp\":$B_TMP,\"soc_t\":$TZ_SOC,\"gpu_t\":$TZ_GPU,\"mem_avail_mb\":$((MEM_AVAIL/1024)),\"load_x100\":$LA1,\"avc\":$AVC_COUNT,\"npu_rss_mb\":$((NPU_RSS/1024)),\"gpu_mem_mb\":$((GPU_MEM_TOTAL/1024)),\"screen\":\"$screen_e\",\"cc\":\"$cc_e\"}" \
      -q 0 2>/dev/null &
  fi
}

# ---- Daemon helpers ----------------------------------------------------------
daemon_alive() { local p; p=$(getv "$PIDFILE"); [ -n "$p" ] && [ -d "/proc/$p" ]; }

# ---- Subcommand dispatch -----------------------------------------------------
case "${1:-status}" in
  once) capture_tick; echo "captured ts=$TS_EPOCH" ;;
  start)
    if daemon_alive; then echo "already running (pid $(getv "$PIDFILE"))"; exit 0; fi
    init_db
    if command -v setsid >/dev/null 2>&1; then setsid sh "$SELF" run </dev/null >/dev/null 2>&1 &
    else nohup sh "$SELF" run </dev/null >/dev/null 2>&1 & fi
    echo $! > "$PIDFILE"
    echo "started (pid $(getv "$PIDFILE"), interval=${INTERVAL_SEC}s, db=$DB)" ;;
  stop)
    p=$(getv "$PIDFILE"); [ -n "$p" ] && kill "$p" 2>/dev/null
    rm -f "$PIDFILE"; echo "stopped" ;;
  status)
    if daemon_alive; then echo "running (pid $(getv "$PIDFILE"))"; else echo "not running"; fi
    init_db
    rows=$(sql "SELECT COUNT(*) FROM telemetry;" 2>/dev/null)
    first=$(sql "SELECT MIN(ts_epoch) FROM telemetry;" 2>/dev/null)
    last=$(sql "SELECT MAX(ts_epoch) FROM telemetry;" 2>/dev/null)
    echo "rows: ${rows:-0}  first: ${first:-never}  last: ${last:-never}  db: $(du -m "$DB" 2>/dev/null | cut -f1)M"
    if [ "${rows:-0}" -gt 0 ] 2>/dev/null; then
      sqlite3 -bail -batch -column -header "$DB" \
        "SELECT datetime(ts_epoch,'unixepoch','localtime') AS time, batt_level||'%' bat, batt_temp_dc/10.0||'C' tmp, mem_available/1024||'MB' mem, printf('%.2f',load_1m/100.0) load, npu_worker_count npu, npu_worker_rss_kb/1024||'MB' npu_rss, gpu_mem_total_kb/1024||'MB' gpu, avc_denials avc FROM telemetry ORDER BY ts_epoch DESC LIMIT 3;" 2>/dev/null
    fi ;;
  query)
    if [ -z "$2" ]; then echo "usage: $0 query '<sql>'"; exit 2; fi
    init_db; sqlite3 -bail -batch -column -header "$DB" "$2" ;;
  run)
    echo $$ > "$PIDFILE"
    trap 'rm -f "$PIDFILE"; exit 0' INT TERM
    while :; do capture_tick; sleep "$INTERVAL_SEC"; done ;;
  *) echo "usage: $0 {start|stop|status|once|query '<sql>'}"; exit 2 ;;
esac
