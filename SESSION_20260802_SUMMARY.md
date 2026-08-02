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

### EXPLAIN endpoint fault tolerance (§6)
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
