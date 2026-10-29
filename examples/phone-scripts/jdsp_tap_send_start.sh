#!/data/data/com.termux/files/usr/bin/sh
# Start the JDSP desk-send sender (Pixel tap -> Oppo desk speaker) via its boot hook if it is down.
# The hook is idempotent (pidfile + kill -0), so this is a no-op while the sender runs.
# Wrapper lives here because TermuxScriptPolicy only accepts executables under ~/.termux/tasker.
# Added 2026-10-30 after the sender died in the 22:42 low-memory storm and stayed dead.
sh /data/data/com.termux/files/home/.termux/boot/70-jdsp-tap-send
exit 0
