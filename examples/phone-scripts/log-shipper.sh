#!/data/data/com.termux/files/usr/bin/sh
# log-shipper -- periodic logcat capture + push to comrade.
# Invoked fresh every cycle by Cybersyn (TIME context, cooldownSec=300), so it
# self-heals naturally: no persistent process to keep alive.
#
# Transport is scp with no key configured at all -- comrade runs Tailscale
# SSH (RunSSH: true), which authenticates purely on tailnet identity/ACL, not
# on a key file. Nothing to manage, nothing extra on disk.
#
# Every cycle: snapshot the FULL logcat buffer, unfiltered (*:V) -- measured
# ~100-200MB/day compressed for the whole device, cheap enough that there's
# no reason to filter, and framework-level detail (WindowManager transitions,
# etc.) that looks like noise today may be exactly what's needed later --
# clear the on-device buffer so nothing is double-captured, gzip it into the
# /tmp staging queue.
#
# The push to comrade is backgrounded via setsid+nohup+flock, detached from
# this script's session, and guarded against overlap. Cybersyn's task runner
# kills this script's process group 60s after launch, and scp over a slow or
# contended tailnet link can occasionally take longer than that -- without
# detaching, that used to kill the push mid-transfer and silently lose that
# cycle's capture (run_logs showed ~40% of cycles timing out overnight).
# Capture+gzip alone is a few hundred ms; only the push needs headroom past
# 60s, so it's taken out of the timeout's blast radius entirely. flock -n
# stops two overlapping pushers if one cycle's push is still draining the
# queue (slow link, big backlog) when the next cycle's capture finishes --
# the queue just keeps growing safely until a pusher is free to drain it.

QUEUE_DIR=/tmp/pixel-logs-queue
DEST=comrade@100.108.8.60
DEST_DIR=/home/comrade/pixel_logs
LOCK_FILE=/tmp/pixel-logs-queue/.push.lock
TS=$(date +%Y%m%d_%H%M%S)

mkdir -p "$QUEUE_DIR"

# Snapshot + rotate the buffer
logcat -d *:V > "$QUEUE_DIR/logcat_${TS}.log" 2>/dev/null
logcat -c 2>/dev/null
gzip -f "$QUEUE_DIR/logcat_${TS}.log"

# Push everything currently queued, detached so a task-runner timeout on this
# script can't take a push down with it.
setsid nohup flock -n "$LOCK_FILE" sh -c '
    QUEUE_DIR="$1"; DEST="$2"; DEST_DIR="$3"
    for f in "$QUEUE_DIR"/*.gz; do
        [ -e "$f" ] || continue
        if /data/data/com.termux/files/usr/bin/scp -P 22 -o BatchMode=yes \
            -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 \
            "$f" "${DEST}:${DEST_DIR}/" 2>"$QUEUE_DIR/last_scp_error.log"; then
            rm -f "$f" "$QUEUE_DIR/last_scp_error.log"
        fi
    done
' _ "$QUEUE_DIR" "$DEST" "$DEST_DIR" >>"$QUEUE_DIR/push.log" 2>&1 </dev/null &
