#!/data/data/com.termux/files/usr/bin/sh
# Pixel "heal all": one pass over every Cybersyn-supervised mechanism, with
# FUNCTIONAL checks (does it work?), fixing what is fixable from the phone and
# asking comrade (relay script pixel-heal-comrade) for the rest. Cybersyn task
# "Pixel: heal all" (examples/pixel-heal-all.yaml, useRoot). Also run from
# comrade: ~/bin/pixel-heal. Result: notification + ~/.local/share/heal_all.last.
# scope.md 2026-11-01.
H=/data/data/com.termux/files/home
PREFIX=/data/data/com.termux/files/usr
PATH="$PREFIX/bin:/system/bin:$PATH"; export PATH
OUT=$H/.local/share/heal_all.last
ENV=$H/.config/heal_all.env     # HOME_SSID=... (never in the repo)
[ -f "$ENV" ] && . "$ENV"
mkdir -p "$H/.local/share"
: > "$OUT"
say() { echo "$*" | tee -a "$OUT"; }
as1000() { # run a Termux command as uid 1000 even when we are root; std fds closed so no
           # helper child can keep the task's output pipe open (Cybersyn waits for EOF)
  if [ "$(id -u)" = "0" ]; then
    su 1000 -c "export PREFIX=$PREFIX HOME=$H LD_LIBRARY_PATH=$PREFIX/lib PATH=$PREFIX/bin:/system/bin; $*" </dev/null >/dev/null 2>&1
  else sh -c "$*" </dev/null >/dev/null 2>&1; fi
}

# --- 1. sshd (port, not pgrep) ---------------------------------------------
if nc -w 2 -z 127.0.0.1 8022 >/dev/null 2>&1; then say "sshd: OK"
else sh "$H/.termux/tasker/sshd_watchdog.sh" </dev/null >/dev/null 2>&1; sleep 2
  nc -w 2 -z 127.0.0.1 8022 >/dev/null 2>&1 && say "sshd: FIXED (restarted)" || say "sshd: FAIL (still not listening)"; fi

# --- 2. Tailscale ------------------------------------------------------------
# interface name drifts (tun0 -> tun1 seen 2026-11-02): look for our 100.x/32 on any tun*
if ip -br addr 2>/dev/null | grep -q '^tun[0-9]* .* 100\.' && ping -c 1 -W 2 100.108.8.60 >/dev/null 2>&1; then say "tailscale: OK (comrade reachable)"
else say "tailscale: FAIL (no tun* with a 100.x address, or comrade unreachable -> open the Tailscale app)"; fi

# --- 2b. Cybersyn ingest FIFO (SpectreBoard i3/numpad/spacer, cybersyn-send) -------
# Unlinked-while-open = everything through it silently dead. A task cannot relaunch its own
# engine, so a DETACHED cybersyn_relaunch.sh does it (3 s later) and re-runs this task.
# Loop guard: no second relaunch within 180 s. scope.md 2026-11-02.
FIFO=$PREFIX/tmp/cybersyn-pub.fifo
cp=$(pidof com.termux.cybersyn)
if [ ! -p "$FIFO" ]; then fst=MISSING
elif ls -l /proc/$cp/fd 2>/dev/null | grep cybersyn-pub.fifo | grep -q deleted; then fst=STALE
else fst=OK; fi
if [ "$fst" = OK ]; then say "ime fifo: OK"
else
  last=$(cat "$H/.local/share/cybersyn_relaunch.stamp" 2>/dev/null || echo 0)
  if [ $(( $(date +%s) - last )) -lt 180 ]; then say "ime fifo: FAIL ($fst, Cybersyn was relaunched <3 min ago; not looping)"
  else
    setsid nohup sh "$H/.termux/tasker/cybersyn_relaunch.sh" rerun </dev/null >/dev/null 2>&1 &
    say "ime fifo: $fst -> relaunching Cybersyn in 3 s, then this heal re-runs by itself"
  fi
fi

# --- 3. Wi-Fi band + mobile data ---------------------------------------------
wifi=$(dumpsys wifi 2>/dev/null | grep -m1 -o 'mWifiInfo SSID: "[^"]*".*Frequency: [0-9]*MHz' )
ssid=$(echo "$wifi" | sed -n 's/.*SSID: "\([^"]*\)".*/\1/p')
freq=$(echo "$wifi" | sed -n 's/.*Frequency: \([0-9]*\)MHz.*/\1/p')
rssi=$(echo "$wifi" | sed -n 's/.*RSSI: \(-[0-9]*\).*/\1/p')
mobile=$(settings get global mobile_data1 2>/dev/null)
band="off"; [ -n "$freq" ] && { [ "$freq" -gt 3000 ] && band="5 GHz" || band="2.4 GHz"; }
if [ -n "$HOME_SSID" ] && [ "$ssid" = "$HOME_SSID" ]; then
  say "wifi: OK home $band ${rssi} dBm, mobile data=$mobile (steerer owns it)"
else
  if [ "$mobile" = "0" ]; then svc data enable </dev/null >/dev/null 2>&1; say "mobile data: FIXED (was OFF away from home -> ON)"
  else say "mobile data: OK ($mobile), wifi=${ssid:-none} $band"; fi
fi
# steerer's own view = the newest "home steer: Status(...)" line in logcat (tag
# AutomationCliReceiver). NOT triggered from here: a RUN_TASK broadcast from inside a
# running task waits on the engine and turns this task into a 60 s timeout
# (2026-11-01). ~/bin/pixel-heal on comrade runs the status task first; from the
# phone the line may be older, so its time is shown.
line=$(logcat -d -s AutomationCliReceiver 2>/dev/null | grep 'home steer: Status(' | tail -1)
if [ -n "$line" ]; then
  say "steerer (as of $(echo "$line" | cut -c1-14)): $(echo "$line" | grep -o 'Status(.*' | cut -c1-200)"
else say "steerer: no status line in logcat yet (run task 'Home Wi-Fi steer: status')"; fi

# --- 4. JDSP desk send (engine state + sender) --------------------------------
PREF=/data/data/com.termux.jdsp/shared_prefs/dsp_desksend.xml
TAP=/data/data/com.termux.jdsp/files/tap.bin
if grep -q 'desksend_enable" value="true"' "$PREF" 2>/dev/null; then
  as1000 "sh $H/.termux/boot/70-jdsp-tap-send"
  playing=$(dumpsys audio 2>/dev/null | grep -c 'state:started')
  rate=$(od -An -t u4 -j 20 -N 4 "$TAP" 2>/dev/null | tr -d ' '); flags=$(od -An -t u4 -j 24 -N 4 "$TAP" 2>/dev/null | tr -d ' ')
  : "${rate:=0}" "${flags:=0}"
  if [ "${playing:-0}" -eq 0 ]; then say "jdsp desk send: sender up, nothing playing (engine state unknown until audio plays)"
  elif [ "$rate" -ne 0 ] && [ $((flags & 1)) -eq 1 ]; then say "jdsp desk send: OK (engine tap on, ${rate} Hz, mute local=$(( (flags >> 1) & 1 )))"
  else
    am broadcast -n com.termux.jdsp/me.timschneeberger.rootlessjamesdsp.receiver.SessionReceiver \
      -a android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION --ei android.media.extra.AUDIO_SESSION 0 \
      --es android.media.extra.PACKAGE_NAME com.termux.jdsp >/dev/null 2>&1
    sleep 3
    rate=$(od -An -t u4 -j 20 -N 4 "$TAP" 2>/dev/null | tr -d ' '); flags=$(od -An -t u4 -j 24 -N 4 "$TAP" 2>/dev/null | tr -d ' ')
    [ "${rate:-0}" -ne 0 ] && [ $(( ${flags:-0} & 1 )) -eq 1 ] && say "jdsp desk send: FIXED (re-attached JDSP effect, tap on)" \
      || say "jdsp desk send: FAIL (engine still not publishing after re-attach; rate=$rate flags=$flags)"
  fi
  # the Oppo half + comrade relays: ask comrade's relay through Cybersyn's own MQTT
  # doorway (cybersyn-send -> ingest FIFO -> the single hub connection). The reply
  # cannot come back this way; comrade pushes it as its own notification (pixel-notify).
  if [ -x "$PREFIX/bin/cybersyn-send" ]; then
    as1000 "$PREFIX/bin/cybersyn-send cybersyn/comrade/action 'script:pixel-heal-comrade sending=${playing:-0}'" \
      && say "comrade: asked to check its relays + the Oppo receiver (result = separate notification)" \
      || say "comrade: FAIL (cybersyn-send could not reach the Cybersyn FIFO; is Cybersyn running?)"
  else say "comrade: SKIP (cybersyn-send missing in Termux)"; fi
else say "jdsp desk send: off in the app (nothing to do)"; fi

# --- 5. Cybersyn plumbing ------------------------------------------------------
pidof libcybersyn-mqtt.so >/dev/null 2>&1 && say "mqtt hub: OK" || say "mqtt hub: FAIL (no libcybersyn-mqtt.so process; Cybersyn restarts it on its own, else relaunch Cybersyn)"
am broadcast -n 'com.termux.cybersyn.stub2/.IslandRemote$Show' -a com.termux.cybersyn.stub2.SHOW_ISLAND >/dev/null 2>&1 && say "island remote: re-shown"
as1000 "sh $H/.termux/boot/60-nowplaying" && say "nowplaying: ensured"

# --- 6. task slots (pid_max 32768; 25K alert watcher exists) ------------------
n=$(ls /proc 2>/dev/null | grep -c '^[0-9]'); say "tasks: $n$( [ "$n" -gt 20000 ] && echo ' (HIGH, leak? read /data/adb/thread-watch)')"

# --- report --------------------------------------------------------------------
summary=$(grep -c -E 'FIXED' "$OUT"); fails=$(grep -c -E 'FAIL' "$OUT")
title="Pixel heal: $summary fixed, $fails failing"
as1000 "termux-notification --id pixel-heal --title '$title' --content \"\$(cat $OUT)\"" >/dev/null 2>&1 \
  || cmd notification post -S bigtext -t "$title" pixel-heal "$(cat "$OUT")" >/dev/null 2>&1 || true
exit 0
