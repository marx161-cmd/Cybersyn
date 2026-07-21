#!/system/bin/sh
# /data/adb/service.d/00-setenforce-permissive.sh  (APatch late_start service)
#
# Boots the ROM SELinux-permissive by default so it no longer has to be flipped
# by hand every boot. Enforcing is DEFERRED until the AVC captures are triaged
# into a tested sepolicy — custom sepolicy previously bricked the boot (static G,
# 2026-07-13), so we automate the manual `setenforce 0` instead of touching the
# ROM boot/policy path. Survives OTAs (lives in /data/adb).
#
# APatch runs service.d as root after boot; retry a bit in case we fire before
# the policy is fully loaded.
(
  i=0
  while [ "$i" -lt 20 ]; do
    if [ "$(getenforce 2>/dev/null)" = "Permissive" ]; then
      break
    fi
    setenforce 0 2>/dev/null || true
    i=$((i + 1))
    sleep 3
  done
) &
