# Cybersyn Audit — 2026-08-02 — Work Queue

Full audit of the Cybersyn stack (Android app + Rust relay + phone-side scripts + live
state on blazer and comrade). Every finding below was **verified live**, not inferred from
reading code alone. Nothing was changed during the audit — this file is the work queue.

**Intended workflow:** an implementing agent (DeepSeek) works through these tasks; a
reviewing pass audits the result afterwards. Each task is self-contained: it states the
evidence, the file, the change, and how to verify. Do not re-derive the diagnosis — it was
already done against live systems and the evidence is recorded inline.

Legend: `[ ]` todo · `[x]` done · **P0** breaks something today · **P1** real bug, not
currently biting · **P2** efficiency/hygiene.

---

## 0. Ground rules — read before touching anything

These are hard constraints from the homelab, not preferences. Violating them costs real
uptime or data.

- [ ] **Never** run `btrfs`/`snapper`/`timeshift` or any snapshot/subvolume command.
- [ ] **Never** `am force-stop` any `com.termux.*` package. They share UID 1000, so it kills
      sibling daemons (npud, sshd) as collateral. A bare `kill -9` on Cybersyn's PID has also
      been observed to take npud down. There is currently **no** fully-safe way to force a
      config reload — batch DB edits and accept one restart; do not iterate.
- [ ] **Reinstalling Cybersyn force-stops the whole UID-1000 family**, killing npud and sshd.
      Expect it on every `adb install -r`. Plan reinstalls, don't scatter them.
- [ ] Android builds happen **on comrade**, never on the phone. `./gradlew :app:assembleRelease`
      from the project root. Signing reads `TERMUX_*` properties from `~/.gradle/gradle.properties`.
      **Never print or log the password values.**
- [ ] Reach the phone over Tailscale only: `adb connect 100.69.13.12:5555`, or
      `ssh system@100.69.13.12 -p 8022`. Never a LAN IP.
- [ ] The phone's logcat ring buffer rotates in under ~90s. Use the comrade-side mirror at
      `~/pixel_logs/logcat_*.log.gz` (ships every 5 min) instead of ad-hoc `adb logcat -d`.
- [ ] `sqlite3` exists at `/data/data/com.termux/files/usr/bin/sqlite3` on the phone (Termux/SSH
      only — `adb shell`'s `/system/bin/sh` has no such binary). Generate any JSON with Python's
      `json.dumps` and only SQL-escape single quotes after; hand-escaping JSON through
      SQL-through-shell reliably introduces subtle bugs.
- [ ] Do **not** test GPU decode / hwaccel changes live on comrade's desktop session.

---

## 1. P0 — Volume daemon MQTT client-id fight (breaking the power button today)

**Evidence.** `journalctl -u mosquitto` on comrade shows **3,542 reconnects in 2 hours**, one
every ~2 seconds, each `Client volume-daemon-ime already connected, closing old connection`.
Two live helper processes share the id: pid 5489 (PPID 1, orphaned) and 13445 (child of
`volume_daemon.py`). Both run
`libcybersyn-mqtt.so sub --broker 100.108.8.60 --port 1883 --topic spectreboard/ime_shown --id volume-daemon-ime`.

**Impact.** The ~2s connection gap drops `spectreboard/ime_shown` transitions.
`/data/local/tmp/volume_daemon.log` shows `event0` grabbed around 00:55 with no release until
02:06 — it missed an OFF. For that entire hour the physical power button ran through the
`input keyevent 26` fallback, i.e. **no long-press power menu**. Plus a phone radio wakeup
every 2 seconds, 24/7.

File: `~/.termux/tasker/volume_daemon.py` on the phone (canonical copy should also land in
`examples/phone-scripts/`).

- [ ] **1.1** In `ImeWatcher._spawn()`, kill the previous child before spawning a new one.
      Current code assigns `self.proc = subprocess.Popen(...)` and drops the old handle;
      `poll_line()`'s `self.proc.wait(timeout=1)` is best-effort and does not guarantee death.
      Add an explicit `terminate()` → short wait → `kill()` on the old process, and close its
      stdout, before the new `Popen`.
- [ ] **1.2** Make the MQTT client id unique per process instead of the hardcoded
      `"volume-daemon-ime"`. Use something like `f"volume-daemon-ime-{os.getpid()}"`, or append a
      short random suffix regenerated on each `_spawn()`. This is the same fix already applied
      Kotlin-side in commit `17dbe30`; mirror that reasoning.
- [ ] **1.3** Add a startup guard so two daemon instances can't coexist: take an exclusive
      `flock` on the pidfile (`/data/local/tmp/volume_daemon.pid`) and exit immediately if it's
      already held. The current pidfile is written but not locked, so a watchdog can start a
      second daemon whose predecessor's children become orphans (this is how pid 5489 got
      orphaned).
- [ ] **1.4** Reap orphans on startup: before spawning, find and kill any stray
      `libcybersyn-mqtt.so sub ... --topic spectreboard/ime_shown` processes not owned by this
      daemon.
- [ ] **1.5 (separate fuse, same file)** `resolve_mqtt_bin()` is called **once at daemon start**
      and the result cached for the process lifetime. The two live helpers exec from
      `/data/app/~~dy37L1qSLFbLqz5jZUPDfQ==/com.termux.cybersyn-9rej13uGG1qOaXMaS7Ycsw==/`,
      which **no longer exists on disk** — the current install is `~~WZtGjqLKaSObf1TWeh-ihQ==`.
      They survive only because the binary is already mapped; the next respawn will fail with
      ENOENT and IME gating will die permanently and silently. Call `resolve_mqtt_bin()` inside
      `_spawn()` (fresh every spawn), and log loudly if it returns `None` or the path doesn't
      exist.

**Verify.**
```bash
# on comrade — should drop to near zero over a 10-minute window
sudo journalctl -u mosquitto --since "-10min" | grep -c "already connected"
# on the phone — expect exactly ONE sub process for this topic
adb -s 100.69.13.12:5555 shell "su -c 'ps -A -o PID,PPID,NAME' | grep libcybersyn-mqtt"
```
Then open/close the SpectreBoard keyboard a few times and confirm
`/data/local/tmp/volume_daemon.log` shows matching `ime_shown -> True/False` and
`GRABBED`/`released` pairs with no unpaired grab.

---

## 2. P0 — 64 leaked root `logcat` child processes

**Evidence.** Cybersyn (pid 28318) has **68 children**: 64 `logcat` owned by **root**, 4 mqtt
helpers owned by **system**. ~4 MB RSS each (~260 MB total), each running
`logcat -v threadtime *:E *:S` forever. The app itself: 194 threads, 366 open fds.

**Cause.** `app/src/main/java/com/termux/cybersyn/core/contexts/LogcatContextSource.kt` — the
same TOCTOU shape already fixed in `MqttBridge` (commit `57ca6cb`). `readerProcess` and
`readerPid` are assigned *inside* the launched coroutine after `pb.start()` (lines ~72-73), and
`readerPid` only after the first line is read (line ~83). A `stopReader()` landing in either
window kills nothing, and the orphan is a root process that `Process.destroy()` cannot signal.

- [ ] **2.1** Apply the `MqttBridge` remedy: a `procLock` making "record the process" and
      "destroy the process" one critical section, plus a `running` flag checked under that lock
      so a process started after teardown is killed immediately rather than leaked. Read
      `core/mqtt/MqttBridge.kt`'s `rawSubscribe()` (lines ~129-211) and mirror its structure —
      the reasoning is already written out in comments there.
- [ ] **2.2** Close the "pid not yet captured" hole. If teardown happens before the
      `READER_PID:` line is read, `readerPid` is `null` forever and the root process is
      permanently unkillable. Either capture the pid synchronously before returning from
      `startReader`, or have the spawned shell write its pid to a known file
      (`context.cacheDir`) that teardown can read regardless of stream timing.
- [ ] **2.3** Reap existing orphans at startup. On `AutomationService` start, `su -c` enumerate
      root-owned `logcat` processes whose ppid is this app and kill any beyond the intended one.
      Without this, the 64 already alive stay alive until reboot.
- [ ] **2.4** Consider whether the reader needs `su` at all. Cybersyn holds AID_LOG (1007) — if
      an unprivileged `logcat` can read the buffers it needs, dropping `su` makes the child
      same-UID, which `Process.destroy()` *can* kill and which the audit in §3 *can* see. This
      would eliminate the whole bug class rather than patch it. Test before committing to it.

**Verify.**
```bash
adb -s 100.69.13.12:5555 shell "su -c 'ps -A -o PPID,USER,NAME' | grep -c \"28318 root .*logcat\""
# expect 1 (or 0 when no LOGCAT profile is active), never 64
```
Then toggle LOGCAT-type profiles (17/19/23/24) off and on ~10 times and re-count — the number
must not grow.

---

## 3. P0 — `ChildProcessAudit` is blind to exactly the bug it was built for

**Evidence.** It logs `4 child process(es), 4 distinct cmdline(s)` every 15 minutes while 68
children exist. `/proc` on blazer is mounted `hidepid=invisible,gid=3009`, and Cybersyn's
supplementary groups are `1000 1007 1065 1077 1079 1096 3001 3002 3003 3007 3013 9997` —
**no 3009 (AID_READPROC)**. So `liveChildren()` can only ever see same-UID children. The 64
root logcats are structurally invisible. The class's own doc comment names the su-exec'd logcat
reader as its motivating case.

File: `app/src/main/java/com/termux/cybersyn/core/engine/ChildProcessAudit.kt`.

- [ ] **3.1** Make enumeration see other UIDs. Simplest: shell out `su -c 'ls /proc'` (or
      `su -c 'cat /proc/*/stat'`) and parse that, falling back to the current direct-read path
      if `su` is unavailable. Keep the existing ppid/cmdline grouping logic — only the
      enumeration source needs to change.
- [ ] **3.2** Correct the class doc comment. It currently claims the /proc walk "stays accurate
      even for the su-exec'd case." That is the precise case it cannot see. Record why
      (hidepid=invisible + no AID_READPROC) so this isn't rediscovered a third time — the same
      restricted-`/proc` gotcha already bit the legacy npud shell watchdog.
- [ ] **3.3** Make the finding reachable. Right now a duplicate only produces an `AppLogger.warn`
      that goes nowhere the operator looks. Have `check()` also write a run_log entry or a
      notification when it finds duplicates, so a leak surfaces without someone grepping logcat.
- [ ] **3.4 (optional, decide explicitly)** Consider adding AID_READPROC (3009) to the app's
      groups via the ROM. Note `ro.control_privapp_permissions=log` means privileged perms are
      auto-granted, but a supplementary **gid** is a different mechanism and likely needs an OTA
      — so 3.1 is the cheap path. Document the decision either way.

**Verify.** After 2.3 and 3.1, the 15-minute audit line in `~/pixel_logs` should report a child
count matching `su -c ps` (68 before the fix, small single digits after), and should warn about
any duplicate it finds.

---

## 4. P0 — HID relay bakes in the wrong screen size (root cause of the gyro "open bug")

**Evidence.** The live service logged `>>> Combined desktop: 1080x2416 <<<` at 00:08:06.
Re-running the same regex now returns `4280x2416` (`xrandr --query` → `current 4280 x 2416`;
connected outputs: `Virtual-2-3 1080x2416+0+0 primary`, `Virtual-3-2 1080x1920+1280+0`,
`Virtual-4-1 1920x1080+2360+0`). `journalctl --user -u cybersyn-hid-relay` shows **restart
counter 139**, all `RuntimeError: could not parse combined screen size from xrandr --query` →
exit 1 → restart, until X came up mid-configuration and the relay caught a partial state.

**This supersedes the previous diagnosis.** The old notes concluded X was mapping a touchscreen
device onto a single 1080px output and that `xinput map-to-output` / a Coordinate Transformation
Matrix was needed. That is **wrong** — do not spend time there. `get_screen_size()` runs once at
import (`~/homelab/cybersyn-hid-relay.py` line ~71-78), `SCREEN_W/SCREEN_H` are module globals set
at line ~91, and `point_device` is built with `ABS_X` range `0..SCREEN_W-1` at line ~93. The
uinput device's axis maximum literally was 1079 — exactly where the cursor stopped.

- [ ] **4.1 (preferred fix — do this first)** Drop the ABS/`point_device` entirely and emit the
      computed absolute target as a **relative delta from the previous sample's target** via the
      existing REL_X/REL_Y device, which already spans the full multi-monitor desktop correctly
      and is used for click/scroll/trackpad today. Compute
      `delta = this_sample_target - last_sample_target`, handling the first sample after
      calibration the same way the current code does. Behaviour is mathematically equivalent (no
      drift, recalibrates on every engage) and it sidesteps the entire
      joystick/tablet/touchscreen/coordinate-mapping problem class *and* this screen-size bug.
- [ ] **4.2** Make `get_screen_size()` robust regardless of 4.1: retry with backoff until xrandr
      succeeds **and** returns a plausible size, instead of raising and exiting 1. Re-read it at
      each clutch engage (cheap — once per hold) so the clamp bounds track a display
      reconfiguration instead of being frozen at process start.
- [ ] **4.3** Fix the systemd unit ordering. `~/.config/systemd/user/cybersyn-hid-relay.service`
      has `After=network-online.target` and `After=mosquitto.service`, but it is a **--user**
      unit — those name system units the user manager cannot resolve, so the ordering is a silent
      no-op. Order it after the graphical session is actually up (e.g. `After=` the user-scope
      target that `comrade-desktop.service` satisfies, or add an `ExecStartPre` that blocks until
      `xrandr --query` reports a stable size). This also explains the boot-time "Connection
      refused" retry spam from `cybersyn-relay.service`, which has the same defect — fix both.
- [ ] **4.4** After the fix, restart the service and confirm the startup line reports the real
      combined desktop, then re-run the original failing test: engage gyro-point and drive the
      cursor toward (3000, 1500) and confirm it actually lands there rather than clamping at
      x=1079.

**Verify.**
```bash
systemctl --user restart cybersyn-hid-relay
journalctl --user -u cybersyn-hid-relay -n 5   # expect "Combined desktop: 4280x2416"
systemctl --user show cybersyn-hid-relay -p NRestarts   # should stop climbing
```

---

## 5. P1 — Watchdog tasks: one edit retires three documented bugs

**Evidence.** `run_logs` holds 1028 rows and **1026 are "Ship device logs"** — zero for tasks
116/135/139. That is *not* because the watchdogs are dead: `executeAndLogTask`
(`core/engine/TaskExecutionHelper.kt`) writes the log only **after** `runner.run(task)` returns,
and those tasks are infinite self-recursive loops that never return. Shipped logcat shows
`ProfileMatcher[NpudWatchdog]: Profile activated` and `ProfileMatcher[SshdPollWatchdog]: Profile
activated` **every minute**, and with `automationMode=RESTART` + `cooldownSec=0`,
`dispatchTask` (`core/engine/AutomationService.kt` lines 452-462) cancels and relaunches each
minute. So the health checks *do* run about once a minute. The `RESTART` fix worked.

The `flow.wait` + self-`task.run` actions therefore never complete and are pure dead weight —
and they are the sole reason for the SINGLE-mode deadlock, the RUN_TASK ANR that `d656619` had
to work around with a timeout, and the run_log invisibility.

- [ ] **5.1** Edit tasks **116** (`npud: watchdog check`), **135** (`sshd: poll watchdog check`)
      and **139** (`Volume Daemon: watchdog check`) in the DB: delete action id 2 (`flow.wait`)
      and action id 3 (`task.run` self-reference), leaving only the health-check action. The
      profile's TIME `00:00–23:59` context already pulses once a minute and drives them. Use
      Python `json.dumps` to build the new `actionsJson` and verify it round-trips through
      `json.loads` after writing.
- [ ] **5.2** With the loop gone, set those profiles back to `automationMode='SINGLE'`
      (`RESTART` was only ever a workaround for the infinite-loop deadlock) and give them a
      sensible `cooldownSec` if you want them slower than one minute.
- [ ] **5.3** Task 135 currently calls `Restore SSHD` **unconditionally** with a `flow.wait
      300000` that never elapses — so it effectively runs every minute, not every five. After
      5.1 it will run every minute by design. Confirm `restore_sshd.sh` is genuinely idempotent
      (sshd has been up 1h50m, which suggests it is) or add an explicit health check first.
- [ ] **5.4** Task 116's health check runs `pgrep -x npud` with `useRoot:false`, which hits the
      same `hidepid=invisible` blindness as §3 — it can read a perfectly healthy npud as dead
      and respawn it, which **silently orphans the running instance** because npud unconditionally
      unlinks and rebinds its own socket on startup. Either set `useRoot:true` or drop `pgrep`
      entirely and rely only on the `STATUS`-over-socket check (`nc` on the phone is Ncat 7.99,
      so `-U` works fine). The socket check is the authoritative one anyway.
- [ ] **5.5** Task **139 has no profile at all** — nothing triggers it, so nothing supervises the
      volume daemon. Create a profile for it mirroring profile 12's shape (TIME `00:00–23:59`,
      `enterTaskId=139`).
- [ ] **5.6** Delete the legacy `~/.termux/boot/30-npud-watchdog` from the phone. Cybersyn owns
      supervision now; running both was already established as actively harmful (the non-root
      `pgrep` there produces a convincing fake crash-loop by respawning npud every ~60s, each
      respawn orphaning the previous instance). Pick one supervisor. Keep `20-npud` (the boot
      start script) — only the watchdog goes.
- [ ] **5.7** Make infinite/long-running tasks visible regardless. Have the runner write a
      "started" run_log row *before* `runner.run(task)` and update it on completion, so a task
      that never returns is still observable. Without this the operator has no way to tell a
      working watchdog from a dead one — which is exactly the ambiguity that cost the previous
      session.

**Verify.** After 5.1/5.2, `run_logs` should start showing rows for `npud: watchdog check` and
`sshd: poll watchdog check`. Then the real test: `kill -9` npud and confirm it comes back
unassisted within ~2 minutes, with a matching run_log row. Do this **once**, deliberately — do
not iterate on live-process kills.

---

## 6. P1 — EXPLAIN diagnostic endpoint is silently broken

**Evidence.** `~/.cybersyn/reports/latest.json` and `latest.txt` are dated **2026-07-27 21:45**.
A fresh `adb shell am broadcast -a com.termux.cybersyn.action.EXPLAIN --es scope all` returns
`result=0` (RESULT_CANCELED) and does **not** rewrite them. Per
`core/external/ExplainReceiver.kt` lines 33-56, CANCELED means `buildReport()` or the file write
threw, with the error going only to `AppLogger`.

This is the project's designated self-diagnosis surface. Fixing it first would have surfaced
most of this audit.

- [ ] **6.1** Reproduce and capture the actual exception. Trigger the broadcast and read the
      `AppLogger.error(TAG, "Explain request failed", error)` line from the shipped logcat
      (`~/pixel_logs`, not `adb logcat -d`). Prime suspects: one of `buildReport()`'s collectors
      (`EngineHealthReader.read`, `TermuxScriptBackend.inspect`, `ShizukuPowerBackend.inspect`,
      or a `toDomainDecodeResult()` on a corrupt row) throwing, or the write to `TERMUX_HOME`
      failing.
- [ ] **6.2** Make report generation fault-tolerant: wrap each section in `runCatching` and emit
      a per-section error string into the report instead of failing the whole thing. A partial
      diagnostic report is vastly more useful than none.
- [ ] **6.3** Return the error to the caller. `am broadcast` already prints result extras — put
      the exception message in `EXTRA_ERROR` so a failure is visible from the shell rather than
      requiring a logcat hunt.
- [ ] **6.4** Make the report readable without root. The parent directory is mode 700
      (`drwx------ system system`), so `setReadable(true, false)` on the files alone doesn't help
      — an unprivileged reader can't traverse in. Either grant traversal on
      `.cybersyn/reports/` or write to a location plain `adb shell` can read.

**Verify.**
```bash
adb shell am broadcast -a com.termux.cybersyn.action.EXPLAIN --es scope all
adb shell "su -c 'stat -c %y /data/data/com.termux/files/home/.cybersyn/reports/latest.txt'"
# timestamp must be now, and the broadcast must return result=-1 (RESULT_OK)
```

---

## 7. P1 — Rust relay correctness bugs

Directory: `Fedora_src/cybersyn-relay/`.

- [ ] **7.1 `mpris.rs:108-111` — position is wrong by a factor of 1e6.** The code does
      `position_str.parse::<f64>() / 1000.0`, but `playerctl position` prints **seconds**, not
      microseconds — so `position_ms` is always 0 and the phone's media progress has never
      worked. Change to `* 1000.0`. Note `mpris:length` genuinely *is* microseconds per the MPRIS
      spec, so the `/1000.0` at lines 113-117 is correct — do not "fix" that one.
- [ ] **7.2 `clipboard.rs:102-109` — hand-rolled JSON emits invalid JSON.** `build_payload`
      escapes only `\`, `"`, `\n`, `\r`, leaving raw control characters (a TAB, most commonly)
      inside a JSON string, which RFC 8259 forbids — so copying tab-indented code can silently
      fail to sync. Replace with `serde_json::json!({"content": content}).to_string()`
      (`serde_json` is already a dependency).
- [ ] **7.3 `clipboard.rs:111-119` — `parse_payload` double-unescapes.** `serde_json::from_str`
      already resolves `\n`, `\r`, `\"`, `\\`; the code then re-replaces those literal
      two-character sequences, so a literal `\n` inside copied text (e.g. a code snippet) becomes
      a real newline. Delete the manual replacements and just return `v["content"].as_str()`.
- [ ] **7.4 `main.rs:109-112` — clipboard payloads get trimmed.** The top-level
      `payload.split_once(':')` maps both halves through `.trim()`, so `clipboard:set:` loses
      leading/trailing whitespace. Preserve the payload verbatim for `clipboard set` (split off
      the command prefix without trimming the remainder).
- [ ] **7.5 `main.rs:679` — `dispatch()` blocks the MQTT event loop.** It runs synchronously
      inside `connection.iter()`, and the `script` (line ~363), `shell` (line ~410) and `notify`
      (line ~124) arms all block on `cmd.output()`/`.status()`. A slow script stalls clipboard,
      mpris and every other action, and can outlast the 30s keepalive and drop the connection.
      Move them to spawned threads that publish their result to the event topic when done —
      the same pattern already used for the archive path at lines 222-264.
- [ ] **7.6 `read_clipboard()` publishes garbage for non-text clipboards.** If the X clipboard
      holds an image, `xclip -o` returns binary and `String::from_utf8_lossy` publishes mojibake
      to the phone. Check the available TARGETS (or validate UTF-8 strictly) and skip non-text
      selections.

**Verify.** `cargo build --release` clean, then:
```bash
mosquitto_pub -h 127.0.0.1 -t cybersyn/comrade/action -m 'mpris:play'
mosquitto_sub -h 127.0.0.1 -t 'cybersyn/comrade/media/status' -C 1   # position_ms must be non-zero while playing
```
For clipboard: copy a tab-indented snippet containing a literal `\n` on comrade and confirm the
phone receives it byte-identical.

---

## 8. P1 — Transfer path exposure and robustness

- [ ] **8.1 `file:serve:<path>` is an unauthenticated arbitrary-file read.**
      (`Fedora_src/cybersyn-relay/src/main.rs` lines 207-298.) Anyone able to publish to
      `cybersyn/comrade/action` can request any file the relay's user can read — `~/.ssh/id_ed25519`,
      `~/.gradle/gradle.properties` (keystore passwords) — and the offer is broadcast on
      `cybersyn/file/offer`. Mosquitto listens on `127.0.0.1:1883` and `100.108.8.60:1883` with no
      observed auth, so the tailnet ACL is currently the *entire* authorization model. **Decide
      explicitly**: either accept that and write it down as the security model, or constrain
      `serve` to an allowlist of roots (e.g. `~/Downloads`, `~/Sync`) the way
      `valid_dest_name`/`valid_script_name`/`valid_hash` already constrain the other verbs. Do
      not leave it undecided.
- [ ] **8.2 Phone-side `serveStream` binds all interfaces.**
      (`core/external/ShareReceiverActivity.kt` line 190.) `ServerSocket(0)` with no bind address
      offers the file on WiFi, Tailscale and anything else, first-come-first-served, for a
      30-second window. This contradicts the relay's own deliberate policy — `file_transfer.rs`
      `prepare_serve` (lines 27-51) binds explicit Tailscale/LAN addresses and documents "never
      0.0.0.0". Make the phone side match.
- [ ] **8.3 Hardcoded Tailscale IP.** `ShareReceiverActivity.kt` line 194 hardcodes
      `100.69.13.12`. Resolve the device's own Tailscale address at runtime (the relay does this
      in `file_transfer.rs::tailscale_ip()` via a UDP connect trick — mirror it).
- [ ] **8.4 No size cap on `downloadToFile`.** `core/transfer/CybersynFileTransfer.kt` lines
      26-54 stream to disk unbounded, while `downloadToBytes` (line 60) correctly caps at 20 MB.
      A malformed or hostile offer can fill the phone's cache. Cap it against the offer's `size`
      field with a sane ceiling.
- [ ] **8.5 `serveMultiple` fails silently.** `ShareReceiverActivity.kt` line 101 returns with no
      toast or notification when `tar` exits non-zero, while `serveLocalPath` correctly calls
      `notifyShareFailed` in the same situation. Make them consistent.
- [ ] **8.6 Stale failure advice.** The toast in `notifyShareFailed` (line 238) still says
      "if it's a folder, select the files inside and share those instead" — advice already
      established as wrong (a nested subfolder hits the same EISDIR wall via `ACTION_SEND_MULTIPLE`).
      Point users at the path-text share that actually works, per commit `cb43272`.
- [ ] **8.7 Dead fallback.** `MqttBridge`'s mosquitto fallback (`core/mqtt/MqttBridge.kt` lines
      89-97, 225-232) targets `$PREFIX/bin/mosquitto_{pub,sub}`, which were **deliberately removed
      from this phone**. If the bundled helper ever goes missing the "fallback" just fails
      silently. Either reinstate the packages or delete the branch and fail loudly with a clear
      message naming the missing binary.

---

## 9. P2 — Efficiency

- [ ] **9.1 mpris polling is extremely wasteful.** `mpris.rs::build_status` spawns roughly
      **seven `playerctl` processes per player per second, forever**, and re-reads plus SHA-256s
      the album-art file every second (`extract_album_art` → `cache_album_art`, lines 203-246).
      It also publishes a **retained** message at 1 Hz regardless of whether anything changed.
      Currently `playerctl -l` is failing (no players) so nothing publishes — but the phone holds
      a live subscription to `cybersyn/comrade/media/status`, so the moment media plays it's one
      message per second over Tailscale to a battery-powered device. Fix: one
      `playerctl metadata --format` call covering all fields, cache the art hash keyed by
      artUrl+mtime, publish only on change, and drop the poll to ~2s.
- [ ] **9.2 Clipboard polling spawns one `xclip` per second forever** (`clipboard.rs:18-62`).
      Make it event-driven via `clipnotify` or an XFixes selection-change listener.
- [ ] **9.3 `LogShipTimer` (profile 20) wastes 4 of every 5 triggers.** Its TIME context pulses
      every minute against `cooldownSec=300`, producing ~4 "Skipped/Cooldown" run_log rows per 5
      minutes forever — which is exactly what buries everything else in the table (1026 of 1028
      rows). Give it a 5-minute trigger, or drop the cooldown and let the trigger set the rate.
- [ ] **9.4 WAL is not checkpointing.** `opentasker.db-wal` is **469 KB** against a **311 KB**
      database. Add a periodic `PRAGMA wal_checkpoint(TRUNCATE)` (the existing 15-minute
      `EngineWatchdogWorker` is a natural home).
- [ ] **9.5 Orphan file.** `/data/data/com.termux.cybersyn/databases/cybersyn.db` is 0 bytes,
      dated 2026-07-25. Confirm nothing references it, then remove.

---

## 10. P2 — Repo hygiene

- [ ] **10.1** `cybersyn-stub1/`, `cybersyn-stub2/` and `quicktap-stub/` are **tracked in git**
      (40 files, ~17 MB working tree) in what is now a public repo. Confirm they're dead
      scaffolding, then remove them from tracking.
- [ ] **10.2** `Source_APKs/` is **213 MB** and correctly untracked — but it is **not in
      `.gitignore`**, so it is one `git add .` away from being committed to a public repo. Add it.
- [ ] **10.3** Remove the PowerShell orphans inherited from upstream OpenTasker — there is no
      Windows anywhere in this setup: `tools/verify-local-release.ps1`,
      `tools/verify-fdroid-release.ps1`, `tools/validate-locale-plugin.ps1`,
      `tools/collect-calendar-sun-evidence.ps1`, `tools/collect-location-evidence.ps1`.
- [ ] **10.4** `gradle/verification-metadata.xml.disabled` (276 KB) is retired F-Droid
      supply-chain ballast from the fork. Delete it.
- [ ] **10.5** Rewrite `OVERVIEW.md`. It still contains the pre-decision fork/merge evaluation
      scaffolding — the `> KDE` / `> OpenTasker` bullet lists with dangling empty items — and
      presents the "likely split" as an open question when it was settled long ago. It also
      states "no phone-side MCP/server add-on is planned unless a real gap appears" while
      `mcp-server/` exists in-tree with a systemd unit. Make it describe what Cybersyn *is*, not
      how it was chosen.
- [ ] **10.6** `ROADMAP.md` (58 KB) and `CHANGELOG.md` (83 KB) at the repo root are large enough
      that nobody reads them. Consider archiving the historical bulk under `docs/archive/` and
      keeping a short current-state ROADMAP.

---

## 11. Carried over — unchanged since July, decide or drop

- [ ] **11.1** `charge-cool` is **not running**, **not** in `~/.termux/boot/`, and was never
      applied to the Cybersyn DB — still the "STILL PENDING (2)" item from 2026-07-23. Decide
      persistence: Termux:Boot (`~/.termux/boot/NN-charge-cool` → `charge-cool.sh start`) vs a
      Cybersyn task (`cybersynctl apply examples/charge-cool.yaml`). Pick one, or close the item
      as intentionally manual.
- [ ] **11.2** The KDE mousepad control-surface port (trackpad/softkeyboard/text-send over MQTT
      → relay-side uinput) is still open. Recon is complete and recorded; the persistent `pub`
      pipe is ready for the high-rate stream.

---

## Definition of done for the reviewing pass

For each completed task, the reviewer should be able to confirm:

1. The stated **verify** command produces the stated result on live hardware, not just a clean build.
2. No new orphaned child processes appear after ~30 minutes of normal use
   (`su -c 'ps -A -o PPID,USER,NAME' | grep 28318 | wc -l` stays small and stable).
3. `sudo journalctl -u mosquitto --since "-1h" | grep -c "already connected"` is 0.
4. `run_logs` contains entries for the watchdog tasks, not only "Ship device logs".
5. The EXPLAIN report regenerates on demand with a current timestamp.
6. Nothing in §0 was violated — in particular, no `am force-stop` on a `com.termux.*` package
   and no snapshot tooling anywhere in the change set.
