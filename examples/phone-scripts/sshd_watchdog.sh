#!/data/data/com.termux/files/usr/bin/sh
# sshd health check for the Cybersyn watchdog task.
#
# The previous task called "Restore SSHD" unconditionally -- no check at all -- and its
# flow.wait(300000) never elapsed because RESTART mode cancelled the task every minute,
# so a "poll every 5 minutes" watchdog actually restarted sshd every single minute.
#
# Checks the listening port rather than pgrep: /proc is hidepid=invisible and Cybersyn
# lacks AID_READPROC(3009), so a non-root pgrep reads a healthy daemon as dead -- the
# exact failure that accumulated 130 npud processes. A port that accepts a connection is
# also a stronger statement than a process that exists.
PORT=8022
BOOTLOG=/data/data/com.termux/files/home/sshd-boot.log

if nc -w 2 -z 127.0.0.1 "$PORT" >/dev/null 2>&1; then
  exit 0
fi

printf '[%s] watchdog: sshd not listening on %s, restoring\n' "$(date -Iseconds)" "$PORT" >> "$BOOTLOG"

# Restore inline rather than as a second task action. A separate action cannot express
# "only if the check failed": continueOnError:false stops on ERROR, so a healthy check
# (exit 0) falls through and restores anyway, while an unhealthy one aborts before it
# can. Same one-script shape as npud_watchdog.sh.
sh /data/data/com.termux/files/home/.termux/tasker/restore_sshd.sh
exit 0
