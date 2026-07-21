#!/system/bin/sh
# Reapply the custom status bar colour (#FF0050) after boot.
#
# Install to /data/adb/service.d/50-statusbar-red.sh (chmod 755). Runs as root
# via APatch, which is required: pm install, cmd overlay and restarting SystemUI
# all need it, and Termux:Boot scripts run as the Termux UID.
#
# Why this is needed
# ------------------
# Iconify applies the custom status bar colour by GENERATING and installing an
# overlay APK, IconifyComponentDynamic2.overlay, targeting com.android.systemui.
# Verified 2026-07-21: applying the colour in the UI rewrote exactly that one
# package (mtime jumped to the moment of the tap) while every other enabled
# Iconify overlay stayed days old, so this single package is the whole applied
# set for the colour. Its stored values live in Iconify's Room DB
# (dynamic_resource_database, fabricated_resource_table) as
# status_bar_icon_color -> com.android.systemui -> #FF0050 across seven
# resources (clock, single/dual tone, light/dark, QS variants).
#
# After a reboot the package is still present and still ENABLED, but the colour
# is back to default — so Iconify regenerates it during its own boot service
# with the wrong value rather than losing the registration. Re-enabling alone is
# therefore useless; the known-good APK has to be put back.
#
# Strategy: keep a copy of the APK captured while the colour was correct, and at
# boot reinstall it only if what is live differs from that copy.
#
# Timing: Iconify's own module (/data/adb/modules/Iconify/service.sh) waits for
# sys.boot_completed, sleeps ~8s, kills SystemUI, sleeps 6, then fixes up its
# accent overlays. Running before that finishes would get our copy overwritten
# and our SystemUI restart wasted, hence the deliberate late start below.

MODDIR=${0%/*}
SAVED=/data/adb/statusbar-red/IconifyComponentDynamic2.overlay.apk
PKG=IconifyComponentDynamic2.overlay
LOG=/data/adb/statusbar-red/apply.log

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }

[ -f "$SAVED" ] || exit 0

while [ "$(getprop sys.boot_completed | tr -d '\r')" != "1" ]; do
  sleep 2
done

# Let Iconify's module finish its own restart/fixup pass before touching this.
sleep 45

live_path=$(pm path "$PKG" 2>/dev/null | head -1 | sed 's/package://')

if [ -n "$live_path" ] && [ -f "$live_path" ]; then
  live_sum=$(sha256sum "$live_path" 2>/dev/null | cut -d' ' -f1)
  saved_sum=$(sha256sum "$SAVED" 2>/dev/null | cut -d' ' -f1)
  if [ "$live_sum" = "$saved_sum" ]; then
    log "already correct ($live_sum), nothing to do"
    exit 0
  fi
  log "live overlay differs (live=$live_sum saved=$saved_sum); reinstalling"
else
  log "overlay not installed; installing saved copy"
fi

if pm install -r "$SAVED" >/dev/null 2>&1; then
  log "installed saved overlay"
else
  log "pm install FAILED — leaving current state alone"
  exit 1
fi

# Enable and win over the other Dynamic overlays, which all sit at max priority.
cmd overlay enable --user current "$PKG" >/dev/null 2>&1
cmd overlay set-priority "$PKG" highest >/dev/null 2>&1

# SystemUI has to be restarted to pick up a changed overlay. Iconify does the
# same thing at boot; this is the second and final one.
killall com.android.systemui 2>/dev/null

log "reapplied and restarted SystemUI"
exit 0
