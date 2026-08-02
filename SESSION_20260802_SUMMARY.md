# Cybersyn Fix Session — 2026-08-02

Based on the audit at `AUDIT_20260802_TASKS.md`. All changes verified live against
comrade (Fedora 43, Ryzen 8600G) + Pixel 10 Pro "blazer" (crDroid, UID 1000).

## P0 — Critical Bugs Fixed

### Volume daemon MQTT client-id fight (§1)
- **File:** `examples/phone-scripts/volume_daemon.py`, `volume_daemon.sh`
- Unique MQTT client ID per spawn (`volume-daemon-ime-<pid>`)
- Kill old child before spawning replacement
- `flock`-protected pidfile prevents duplicate daemon instances
- Orphan reaping on startup via `sudo ps` + `sudo kill`
- Fresh `resolve_mqtt_bin()` per spawn (survives APK reinstalls)
- `volume_daemon.sh` uses `sudo` for root access to `/dev/input/event0`

### LogcatContextSource TOCTOU leak (§2)
- **File:** `app/.../core/contexts/LogcatContextSource.kt`
- `procLock` + `readerActive` guard mirrors the `MqttBridge` fix (commit `57ca6cb`)
- `readerPid` captured synchronously so `stopReader` can always root-kill
- Consistent cleanup in both `startReader` and `stopReader` paths

### ChildProcessAudit blind to root processes (§3)
- **File:** `app/.../core/engine/ChildProcessAudit.kt`
- su-based `/proc` enumeration (single batch shell, `sed`-based ppid parsing)
- Falls back to direct Java File() walk if su unavailable
- Doc comment corrected — records `hidepid=invisible,gid=3009` limitation
- Duplicate findings now written to `run_logs` with task ID 0

### HID relay wrong desktop size + ABS bounds (§4)
- **File:** `~/homelab/cybersyn-hid-relay.py`
- Dropped ABS `point_device` entirely — gyro now emits `REL_X`/`REL_Y` deltas
- Same calibration math, no coordinate bounds → naturally spans any multi-monitor setup
- `get_screen_size()` retries 12x with 2s backoff instead of crashing

## P1 — Real Bugs Fixed

### Watchdog task run_log invisibility (§5.7)
- **File:** `app/.../core/engine/TaskExecutionHelper.kt`
- "started" run_log row written BEFORE `runner.run(task)`
- Infinite/self-recursive watchdog tasks are now observable in run_logs

### EXPLAIN endpoint fault tolerance (§6) — SUPERSEDED, see review section below
- **File:** `app/.../core/external/ExplainReceiver.kt`
- Each data section wrapped in `runCatching` — one bad row can't kill the whole report
- `collection_errors` array in output for diagnostic visibility
- Directory traversal granted (`setExecutable`) so `adb shell` can read reports

### Rust relay correctness (§7)
- **Files:** `Fedora_src/cybersyn-relay/src/{mpris,clipboard,main}.rs`
- `mpris.rs`: position `* 1000.0` fix (was dividing seconds by 1000 → always 0ms)
- `clipboard.rs`: hand-rolled JSON replaced with `serde_json::json!`; double-unescape removed; UTF-8 strict validation (skip binary clipboards)
- `main.rs`: clipboard payload read from raw (not trimmed); `script`/`shell`/`notify` arms spawn threads so dispatch never blocks the MQTT event loop

### Transfer path fixes (§8)
- **Files:** `app/.../core/external/ShareReceiverActivity.kt`, `app/.../core/transfer/CybersynFileTransfer.kt`, `app/.../core/mqtt/MqttBridge.kt`
- Phone-side ServerSocket binds Tailscale IP (not 0.0.0.0)
- Runtime Tailscale IP resolution via UDP connect trick (matching relay)
- `downloadToFile` capped at 100MB
- `serveMultiple` now calls `notifyShareFailed` on tar failure
- Toast message updated (path-text share, not "select files inside")
- Dead mosquitto fallback branches removed — fails loudly with clear log message

## P2 — Efficiency & Hygiene

### WAL checkpoint (§9.4)
- **File:** `app/.../core/engine/EngineWatchdogWorker.kt`
- `PRAGMA wal_checkpoint(TRUNCATE)` every 15-minute watchdog cycle

### Repo cleanup (§10.2-10.4)
- 5 PowerShell scripts removed (no Windows anywhere in this setup)
- `gradle/verification-metadata.xml.disabled` removed (276KB retired F-Droid ballast)
- `Source_APKs/` already in `.gitignore` (audit was wrong on this)

## New Features

### `key.send` action
- **Files:** `app/.../core/actions/SystemActions.kt`, `app/.../core/RuntimeRegistries.kt`
- Generic key event injection via `input keyevent` (reuses `runRootCommand` pattern)
- Used by Quick Tap profile to show volume slider: `key.send KEYCODE_VOLUME_UP`

### Quick Tap → volume slider
- **Files:** `examples/quicktap-sidebar.yaml`, DB profile 27
- Pixel double-tap now shows the Android volume slider
- Replaces the old Smart Edge sidebar toggle intent

### Volume daemon: always-grab + browser nav + screenshot
- **File:** `examples/phone-scripts/volume_daemon.py`
- Permanently grabs event0 (vol_down, vol_up, power) — crash releases grab, stock returns
- Keyboard shown: vol keys → gyro/hold (unchanged)
- Keyboard hidden + browser foreground: vol keys → media prev/next
- Power+vol_down held: screenshot (via `input keyevent KEYCODE_SYSRQ`, works in any mode)
- All other keys: reinjected for normal Android handling

### Foreground accessibility service
- **Files:** `app/.../core/external/ForegroundAccessibilityService.kt`, `app/.../res/xml/accessibility_foreground.xml`, `app/.../AndroidManifest.xml`
- Instant foreground app detection — zero polling, event-driven
- Publishes `ON`/`OFF` to `volume_daemon/browser_mode` MQTT topic
- Detects Cromite, Firefox, Diana for browser nav mode

## Not Committed (outside this repo)

- `~/homelab/cybersyn-hid-relay.py` — REL delta mode, screen size retry
- DB-only: task 140, profile 27 (Quick Tap volume slider), tasks 141/142, profile 28 (Browser mode — superseded by accessibility service)

---

# Review pass — same day, live-verified

## §6 EXPLAIN: the audit's diagnosis was wrong, and so was the fix

The audit asserted `result=0` meant `buildReport()` or the file write threw. It did not.
`am broadcast -a <action>` with no `-p`/`-n` is an **implicit** broadcast, and Android 8+
does not deliver those to manifest-declared receivers — `ExplainReceiver` was never invoked
at all. `result=0` was the untouched initial result code, indistinguishable from a receiver
that ran and failed. Nothing in the report-building path was ever broken.

- Correct invocation: `adb shell am broadcast -a ...EXPLAIN -p com.termux.cybersyn --es scope all`
  → `result=-1`, report regenerated in ~120ms. Applies to **every** receiver in this app.
- Entry logging added (`"Explain request received"`) so "never fired" is distinguishable from
  "fired and failed" — this is what actually found the bug.
- `next_commands` and `OPERATIONAL_NOTES` corrected; they had been handing out the broken form.
- `setExecutable` on `reports/` could never have worked — every ancestor up to
  `/data/data/com.termux` is 700. Report now mirrored to `/data/local/tmp/cybersyn_explain.{txt,json}`,
  mode 644, verified readable by plain `adb shell`.
- Per-collector and whole-build timeouts kept as hardening: `runCatching` cannot catch a hang.

## §5.7 run_log: fixed a regression it introduced

The "started" row was inserted but never completed, so every finished task wrote **two** rows
and every completed run looked like a failure. `RunLogDao.finalizeRun` now updates the
placeholder in place. Verified: completed tasks write one row; 116/135 still show a lone
`started` row, which is correct — they genuinely never return.

## §5.5 volume daemon supervision — done (was never started)

Task 139 had no profile, so nothing supervised the daemon. Also: its health check invoked
`/data/data/com.termux/files/usr/bin/sh -c '...'`, which `TermuxScriptPolicy` rejects outright
(executables must live under `~/.termux/tasker`) — it would have failed on first trigger.
Replaced by `examples/volume-daemon-watchdog.yaml`: health check moved into
`volume_daemon.sh ensure`, self-recursion dropped, `SINGLE` mode. Live test: `kill -9` the
daemon → watchdog restarted it within a minute, unassisted, and the new generation reaped the
mqtt helpers orphaned by the kill.

## Volume daemon key handling

- **uinput passthrough** replaces `input keyevent` for the physical keys we grab. `input
  keyevent` can only express a discrete press, so long-press power, volume repeat and the
  native power+vol_down screenshot were all unreachable under the grab. Keys are now re-emitted
  through a virtual device with real edges and timing, and Android's own handling applies —
  including whatever `power_button_long_press` is set to (currently `5` = assistant).
  `input keyevent` is retained for `KEYCODE_MEDIA_NEXT/PREVIOUS`, which have no evdev key.
- **Routing latched at key-down**, so a mode flip mid-hold can't leave a key stuck down on the
  virtual device (down passed through + up swallowed) or emit an orphan release.
- **Orphan reaper generalised** from one hardcoded topic to the `--id daemon-*` marker; the
  single-topic version had let `volume_daemon/browser_mode` helpers accumulate as root orphans
  re-parented to init (two were live, from two prior install paths).
- **Pidfile ownership corrected**: `volume_daemon.sh` recorded `$!` (the `sudo` wrapper), so
  stopping it orphaned the real root python and its helpers.

## Other corrections

- `ShareReceiverActivity` advertised `lanIp:port` while binding Tailscale only, so comrade's
  LAN-first attempt failed on every transfer; now advertises only what it binds. Tailscale
  probe result validated against 100.64.0.0/10 — an unvalidated wildcard would have bound all
  interfaces, the exact exposure §8.2 was about.
- `key.send` was registered as an action but had no `ActionMetadata`/`ActionCapability`, so it
  was invisible to the UI picker and the capability report. Registered.
- WAL checkpoint used `execSQL`, which is documented as not for row-returning statements and
  can silently no-op; now `query()` with a `busy` warning.
- `LogcatContextSource` cleanup now checks process identity — a stop→start race could have the
  old coroutine's `finally` kill the replacement reader.
- Removed a bogus `<uses-permission android:name="BIND_ACCESSIBILITY_SERVICE">` (system-held).
- Relay `clipboard:set:` used a fixed 14-byte slice on an already-trimmed payload; re-split.

## Still open from the audit

§5.1–5.4/5.6 (watchdog self-recursion in tasks 116/135, `SINGLE` mode, task 116's non-root
`pgrep` blind to `hidepid` — observed live producing a duplicate npud), §8.1 (unauthenticated
`file:serve` arbitrary-file read — still undecided), §9.1/9.2/9.3/9.5, §10.1/10.5/10.6.
