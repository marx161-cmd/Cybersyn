#!/system/bin/sh
# Cybersyn AVC denial logger — APatch boot service (/data/adb/service.d/).
# Starts pixel-avc-once-logger on every boot so SELinux avc:denied capture is
# always-on while the device is still Permissive — this is the ruleset-gathering
# needed BEFORE flipping to enforcing. Uses a fresh per-boot output dir (the
# logger clobbers its processed files if re-run into the same dir).
LOGGER=/data/adb/cybersyn-avc-logger/pixel-avc-once-logger.sh
[ -x "$LOGGER" ] || exit 0

# Wait for boot to settle so the logcat/auditd buffers are up.
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 20

OUT="/data/local/tmp/avc-watch-boot-$(date +%Y%m%d-%H%M%S)"
# Detach so the service.d runner returns; logger blocks on its logcat pipe.
setsid sh "$LOGGER" "$OUT" </dev/null >/dev/null 2>&1 &
