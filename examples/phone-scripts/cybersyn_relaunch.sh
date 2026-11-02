#!/data/data/com.termux/files/usr/bin/sh
# Safe Cybersyn relaunch (root). Started DETACHED by heal_all.sh (a task cannot kill its own
# engine and survive) or synchronously by comrade's ~/bin/pixel-heal.
#   cybersyn_relaunch.sh [rerun]   rerun = afterwards fire "Pixel: heal all" again (JDSP etc.)
#   DELAY=<s> (default 3) = wait first so the calling heal task can finish + post its notification.
# Why: cybersyn-pub.fifo can get unlinked while Cybersyn keeps the deleted inode open ->
# SpectreBoard i3/numpad + spacer + cybersyn-send silently dead (2026-09-01, 2026-11-02).
# npud guard: a relaunch while npud survives can spawn a 2nd npud+Gemma -> OOM (2026-10-29);
# new npud pids that appear after the relaunch are killed, the old one is kept. scope.md 2026-11-02.
H=/data/data/com.termux/files/home
PATH=/data/data/com.termux/files/usr/bin:/system/bin:$PATH; export PATH
PKG=com.termux.cybersyn
FIFO=/data/data/com.termux/files/usr/tmp/cybersyn-pub.fifo
LOG=$H/.local/share/cybersyn_relaunch.last
mkdir -p "$H/.local/share"; date +%s > "$H/.local/share/cybersyn_relaunch.stamp"
fifo_state() {
  p=$(pidof $PKG); [ -p $FIFO ] || { echo MISSING; return; }; [ -n "$p" ] || { echo NOREADER; return; }
  ls -l /proc/$p/fd 2>/dev/null | grep cybersyn-pub.fifo | grep -q deleted && { echo STALE; return; }; echo OK
}
npud_pids() { ps -A -o pid,args | grep -E 'npud|poll_e' | grep -v grep | awk '{print $1}' | tr '\n' ' '; }
sleep "${DELAY:-3}"
: > "$LOG"; log() { echo "$*" >> "$LOG"; }
st=$(fifo_state); before=$(npud_pids)
# /system/bin/am explicitly: Termux's own `am` is first in PATH and has no force-stop (2026-11-02)
/system/bin/am force-stop $PKG; sleep 1
/system/bin/monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
i=0; while [ $i -lt 15 ]; do sleep 2; [ "$(fifo_state)" = OK ] && break; i=$((i+1)); done
st2=$(fifo_state)
[ "$st2" = OK ] && log "ime fifo: FIXED ($st -> relaunched Cybersyn)" || log "ime fifo: FAIL ($st, still $st2 after relaunch)"
if [ -n "$(echo $before)" ]; then sleep 3; dup=""
  for p in $(npud_pids); do case " $before " in *" $p "*) ;; *) dup="$dup $p";; esac; done
  [ -n "$dup" ] && { kill $dup; log "npud: killed duplicate(s)$dup (old npud kept)"; }
fi
if [ "$1" = rerun ]; then
  sleep 4   # engine up before RUN_TASK
  /system/bin/am broadcast -a com.termux.cybersyn.action.RUN_TASK -n $PKG/.core.external.AutomationCliReceiver \
    --es com.termux.cybersyn.extra.TASK_NAME "Pixel: heal all" >/dev/null 2>&1
fi
cat "$LOG"
