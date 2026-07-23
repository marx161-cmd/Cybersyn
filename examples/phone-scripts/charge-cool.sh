#!/data/data/com.termux/files/usr/bin/sh
# charge-cool — thermal charge governor for blazer on the wireless powerbank.
#
# Goal: charge normally toward 100% BUT keep it from cooking. It only ever
# THROTTLES the wireless input current (DC_ICL) to hold temperature under a
# setpoint; when the phone is cool it runs at full charge speed, so it still
# reaches a full battery whenever thermally possible. Wired charging and
# unplugged are never touched. Self-gating: only acts while on wireless.
#
# Runs as ROOT (Termux:Boot, or Cybersyn script.termux.run useRoot=true).
# Subcommands: start | stop | status | once | probe | run
#
# ---- Tunables (edit these) --------------------------------------------------
TEMP_TARGET_DC=380    # deci-degC: throttle to hold temperature at/below 38.0C
TEMP_HYST_DC=20       # 2.0C band: below (target-hyst) it ramps current back up
TEMP_CEIL_DC=410      # 41.0C: hard back-off straight to minimum current
SOC_CAP=100           # charge-to ceiling. 100 = full (no cap). Set <100 only if wanted.
STEP_UA=50000         # DC_ICL change per step (50 mA)
ICL_MIN_UA=100000     # never throttle below this (100 mA) -> keeps the Qi link alive
ICL_MAX_UA=1100000    # full-speed cap (~1.1 A, safely under OCP's 1.6 A)
TICK_ACTIVE=4         # seconds between steps while on wireless
TICK_IDLE=30          # seconds between checks while off wireless
# -----------------------------------------------------------------------------

# Paths (env-overridable so the servo math can be unit-tested against fixtures).
: "${B:=/sys/class/power_supply/battery}"
: "${W:=/sys/class/power_supply/wireless}"
: "${U:=/sys/class/power_supply/usb}"
: "${GV:=/sys/kernel/debug/gvotables/DC_ICL}"
: "${STATE_DIR:=/data/data/com.termux/files/home/.cybersyn}"
: "${DRY_RUN:=0}"          # 1 = record decisions, don't touch hardware
ICL_STATE="$STATE_DIR/charge_cool.setpoint"   # last DC_ICL setpoint (servo continuity)
DECISION="$STATE_DIR/charge_cool.decision"    # last actuator action (FORCE <ua> | RELEASE)
LOG="$STATE_DIR/charge_cool.log"
PIDFILE="$STATE_DIR/charge_cool.pid"
SELF="$0"

# gvotable debugfs force interface (confirmed live 2026-07-23: single-number attrs).
# force overrides the whole min-wins election, so ICL_MAX_UA stays under OCP and we
# keep our own TEMP_CEIL_DC as an extra safety net.
: "${GV_FORCE_VAL:=$GV/force_int_value}"
: "${GV_FORCE_EN:=$GV/force_int_active}"

getv() { cat "$1" 2>/dev/null; }
log()  { echo "$(date '+%F %T') $*" >> "$LOG"; tail -n 300 "$LOG" >"$LOG.t" 2>/dev/null && mv "$LOG.t" "$LOG" 2>/dev/null; }

ensure_debugfs() {
  [ -d "$GV" ] && return 0
  mount | grep -q ' /sys/kernel/debug ' || mount -t debugfs none /sys/kernel/debug 2>/dev/null
  [ -d "$GV" ]
}

dc_icl_force() {   # throttle input to <ua>
  ua="$1"; echo "FORCE $ua" > "$DECISION"
  [ "$DRY_RUN" = "1" ] && return 0
  ensure_debugfs || { log "ERR: no DC_ICL gvotable (debugfs?)"; return 1; }
  echo "$ua" > "$GV_FORCE_VAL" 2>/dev/null
  echo 1     > "$GV_FORCE_EN"  2>/dev/null
}
dc_icl_release() { # let normal (full-speed) charging run
  echo "RELEASE" > "$DECISION"
  [ "$DRY_RUN" = "1" ] && return 0
  [ -e "$GV_FORCE_EN" ] && echo 0 > "$GV_FORCE_EN" 2>/dev/null
}

clamp() { v="$1"; lo="$2"; hi="$3"; [ "$v" -lt "$lo" ] && v="$lo"; [ "$v" -gt "$hi" ] && v="$hi"; echo "$v"; }

# One governor tick. Returns 0 if on wireless (acted), 1 if not (released/idle).
tick() {
  wl=$(getv "$W/online"); [ -z "$wl" ] && wl=0
  ul=$(getv "$U/online"); [ -z "$ul" ] && ul=0
  if [ "$wl" != "1" ] || [ "$ul" = "1" ]; then
    dc_icl_release; echo "$ICL_MAX_UA" > "$ICL_STATE"
    return 1
  fi

  cap=$(getv "$B/capacity"); [ -z "$cap" ] && cap=50
  tdc=$(getv "$B/temp");     [ -z "$tdc" ] && tdc=0
  cur=$(getv "$ICL_STATE");  [ -z "$cur" ] && cur=$ICL_MAX_UA

  if [ "$tdc" -ge "$TEMP_CEIL_DC" ]; then
    new=$ICL_MIN_UA; why="hard-ceil(${tdc})"
  elif [ "$SOC_CAP" -lt 100 ] && [ "$cap" -ge "$SOC_CAP" ]; then
    new=$ICL_MIN_UA; why="soc-cap(${cap})"
  elif [ "$tdc" -gt "$TEMP_TARGET_DC" ]; then
    new=$(( cur - STEP_UA )); why="too-hot(${tdc})"
  elif [ "$tdc" -lt $(( TEMP_TARGET_DC - TEMP_HYST_DC )) ]; then
    new=$(( cur + STEP_UA )); why="cool-rampup(${tdc})"
  else
    new=$cur; why="hold(${tdc})"
  fi
  new=$(clamp "$new" "$ICL_MIN_UA" "$ICL_MAX_UA")
  echo "$new" > "$ICL_STATE"

  if [ "$new" -ge "$ICL_MAX_UA" ]; then dc_icl_release; else dc_icl_force "$new"; fi
  log "wireless cap=${cap} temp=${tdc} setpoint=${new}uA $why"
  return 0
}

daemon_alive() { p=$(getv "$PIDFILE"); [ -n "$p" ] && [ -d "/proc/$p" ]; }

mkdir -p "$STATE_DIR" 2>/dev/null
case "${1:-status}" in
  probe)
    ensure_debugfs && { echo "DC_ICL dir: $GV"; ls -la "$GV" 2>&1; echo "--- status ---"; getv "$GV/status"; } \
      || echo "DC_ICL gvotable not found (module loaded? debugfs mountable?)"
    ;;
  once) tick ;;
  start)
    if daemon_alive; then echo "already running (pid $(getv $PIDFILE))"; exit 0; fi
    if command -v setsid >/dev/null 2>&1; then setsid sh "$SELF" run </dev/null >/dev/null 2>&1 &
    else nohup sh "$SELF" run </dev/null >/dev/null 2>&1 & fi
    echo $! > "$PIDFILE"; echo "started (pid $(getv $PIDFILE))"
    ;;
  stop)
    p=$(getv "$PIDFILE"); [ -n "$p" ] && kill "$p" 2>/dev/null
    rm -f "$PIDFILE"; dc_icl_release; log "stop -> killed + released"; echo stopped
    ;;
  status)
    if daemon_alive; then echo "running (pid $(getv $PIDFILE))"; else echo "not running"; fi
    echo "--- DC_ICL ---"; ensure_debugfs && getv "$GV/status" | grep -iE "current=|FORCE"
    echo "--- last log ---"; tail -n 3 "$LOG" 2>/dev/null
    ;;
  run)
    echo $$ > "$PIDFILE"
    log "charge-cool daemon start (target=${TEMP_TARGET_DC}dC ceil=${TEMP_CEIL_DC}dC soc_cap=${SOC_CAP}% dry=${DRY_RUN})"
    trap 'dc_icl_release; rm -f "$PIDFILE"; log "daemon exit -> released"; exit 0' INT TERM
    while :; do
      if tick; then sleep "$TICK_ACTIVE"; else sleep "$TICK_IDLE"; fi
    done
    ;;
  *) echo "usage: charge-cool.sh {start|stop|status|once|probe|run}"; exit 2 ;;
esac
