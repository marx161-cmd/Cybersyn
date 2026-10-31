#!/data/data/com.termux/files/usr/bin/sh
# JDSP desk-send FUNCTIONAL watchdog (Cybersyn JdspTapSendWatchdog, minute tick).
# 2026-10-30 version only kept the Termux sender alive. 2026-11-01: a liveness
# check passed every minute for 1.5 days while the desk was silent -- two
# faults it could not see:
#   1. audioserver crashed (21:05) -> the HAL effect was re-created with default
#      state (desk send OFF, mute OFF) and the JDSP app never re-sent its config.
#      Visible in tap.bin's header: sampleRate 0 / flags bit0 clear while audio
#      plays. Fix = re-open effect session 0 in the app (same intent a player
#      sends), which builds a fresh effect + full preference sync.
#   2. the sender's UDP socket was pinned to Wi-Fi (opened before Tailscale's
#      tun0) -- fixed inside jdsp-tap-send itself (socket re-created every 30 s).
# Both halves stay idempotent: nothing happens while things are healthy.
H=/data/data/com.termux/files/home
TAP=/data/data/com.termux.jdsp/files/tap.bin
PREF=/data/data/com.termux.jdsp/shared_prefs/dsp_desksend.xml
STATE=$H/.local/share/jdsp-desksend-reattach.epoch
LOG=$H/.local/share/jdsp-desksend-watchdog.log
REATTACH_COOLDOWN=180

# 1. sender process (idempotent boot hook: pidfile + kill -0).
sh "$H/.termux/boot/70-jdsp-tap-send"

# 2. engine side. Only judged while something plays: the header fields are
#    written from the HAL process callback, so with no audio they are stale.
grep -q 'desksend_enable" value="true"' "$PREF" 2>/dev/null || exit 0
playing=$(dumpsys audio 2>/dev/null | grep -c 'state:started')
[ "${playing:-0}" -gt 0 ] || exit 0
[ -r "$TAP" ] || exit 0
rate=$(od -An -t u4 -j 20 -N 4 "$TAP" 2>/dev/null | tr -d ' ')
flags=$(od -An -t u4 -j 24 -N 4 "$TAP" 2>/dev/null | tr -d ' ')
: "${rate:=0}" "${flags:=0}"
if [ "$rate" -ne 0 ] && [ $((flags & 1)) -eq 1 ]; then
  exit 0   # engine publishing with desk send on: healthy
fi
now=$(date +%s)
last=$(cat "$STATE" 2>/dev/null || echo 0)
if [ $((now - last)) -lt $REATTACH_COOLDOWN ]; then
  exit 0   # gave it a chance recently; the next tick re-checks
fi
echo "$now" > "$STATE"
printf '[%s] engine lost desk send (rate=%s flags=%s, audio playing): re-opening JDSP session 0\n' \
  "$(date -Iseconds)" "$rate" "$flags" >> "$LOG"
am broadcast -n com.termux.jdsp/me.timschneeberger.rootlessjamesdsp.receiver.SessionReceiver \
  -a android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION \
  --ei android.media.extra.AUDIO_SESSION 0 \
  --es android.media.extra.PACKAGE_NAME com.termux.jdsp >/dev/null 2>&1
exit 0
