#!/data/data/com.termux/files/usr/bin/sh
# "Pixel: unstick PiP" (examples/pip-unstick.yaml, useRoot): frees a PiP stuck in the status-bar top stash.
# Cause (2026-11-11): a SystemUI restart/crash while a PiP is open leaves WM's pinned task alive but the fresh
# WMShell never adopts it -> untappable, and relaunching the app does not unpin it. Only removing the pinned
# root task frees it (the video closes; the app usually remembers its place). Removes EVERY pinned task:
# it is a manual trigger, run it when PiP is stuck. Result: notification + ~/.local/share/pip_unstick.last.
H=/data/data/com.termux/files/home
PREFIX=/data/data/com.termux/files/usr
PATH="$PREFIX/bin:/system/bin:$PATH"; export PATH
OUT=$H/.local/share/pip_unstick.last
mkdir -p "$H/.local/share"
: > "$OUT"
say() { echo "$*" | tee -a "$OUT"; }

# Shell's view: pip2 PipTransitionState mState=undefined while WM has a pinned task = orphaned
shell_state=$(/system/bin/dumpsys activity service SystemUIService WMShell 2>/dev/null \
  | grep -A3 "PipTransitionState" | grep -m1 "mState=" | sed 's/.*mState=//')

# "  * Task{8f77eaa #23884 type=standard A=10488:com.google.android.youtube U=0 ... mode=pinned ..."
pinned=$(/system/bin/dumpsys activity activities 2>/dev/null | grep "mode=pinned" | grep "Task{" \
  | sed -n 's/.*#\([0-9][0-9]*\) .*A=[0-9]*:\([^ ]*\) .*/\1 \2/p' | sort -u)

n=0
if [ -z "$pinned" ]; then
  say "no PiP open: nothing to free"
else
  echo "$pinned" | while read -r id pkg; do
    [ -n "$id" ] || continue
    /system/bin/am stack remove "$id" >/dev/null 2>&1
    sleep 1
    if /system/bin/dumpsys activity activities 2>/dev/null | grep "mode=pinned" | grep -q "#$id "; then
      say "FAIL: $pkg (task $id) still pinned"
    else
      say "freed: $pkg (task $id; SystemUI pip state was '${shell_state:-?}')"
    fi
  done
fi

title="PiP unstick"
grep -q "^freed" "$OUT" && title="PiP unstick: freed $(grep -c '^freed' "$OUT")"
grep -q "^FAIL" "$OUT" && title="PiP unstick: FAILED"
su 1000 -c "export PREFIX=$PREFIX HOME=$H LD_LIBRARY_PATH=$PREFIX/lib PATH=$PREFIX/bin:/system/bin; termux-notification --id pip-unstick --title '$title' --content \"\$(cat $OUT)\"" </dev/null >/dev/null 2>&1 \
  || cmd notification post -S bigtext -t "$title" pip-unstick "$(cat "$OUT")" >/dev/null 2>&1 || true
exit 0
