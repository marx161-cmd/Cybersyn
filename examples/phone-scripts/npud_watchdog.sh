#!/data/data/com.termux/files/usr/bin/sh
# npud health check for the Cybersyn watchdog task.
#
# Lives here rather than as an inline `sh -c` because TermuxScriptPolicy only accepts
# executables under ~/.termux/tasker -- an inline /usr/bin/sh invocation is rejected
# before it runs.
#
# Deliberately does NOT gate on `pgrep -x npud`: /proc is mounted hidepid=invisible and
# Cybersyn lacks AID_READPROC(3009), so a non-root pgrep reads a perfectly healthy npud
# as dead and respawns it -- and npud unlinks and rebinds its own socket on startup, so
# every respawn orphans the previous one. That is exactly how 130 npud processes
# accumulated on 2026-08-02. The socket STATUS check is authoritative anyway: if it
# answers, npud is up AND serving, which is what we actually care about.
SOCK=/data/data/com.termux/files/usr/tmp/npud.sock
BOOTLOG=/data/data/com.termux/files/home/npud-boot.log

if echo STATUS | nc -w 2 -U "$SOCK" >/dev/null 2>&1; then
  exit 0
fi

printf '[%s] watchdog: npud unresponsive, restarting\n' "$(date -Iseconds)" >> "$BOOTLOG"
sh /data/data/com.termux/files/home/.termux/boot/20-npud
