# Key-hijack consolidation — move volume_daemon into Cybersyn

**Status:** plan, nothing implemented. Written 2026-08-02 after a session where the
external daemon's accidental complexity caused the actual outage (gyro + click/clutch
silently dead), not the feature being added.

## Why

`volume_daemon.py` is a root python process that grabs `event0`, decides what each key
means, and publishes the result. Every part of that except the grab itself is something
Cybersyn is already better positioned to do. The split costs:

- **Three MQTT connections** (`sub spectreboard/ime_shown`, `sub volume_daemon/browser_mode`,
  `pub`), respawned on every daemon restart, running parallel to `MqttBridge`'s single
  persistent connection.
- **A round trip through the broker to talk to ourselves.** `ForegroundAccessibilityService`
  detects the browser *inside Cybersyn*, publishes `ON`/`OFF` to mosquitto on comrade, so a
  root script on the same phone can read it back and decide what a key does.
- **The `ime_shown` desync bug class.** Routing lives in a process with no direct access to
  IME state, learning it from a non-retained transition-only topic. Every daemon restart
  starts out wrong. Under always-grab that silently routes gyro/hold to the volume slider.
  Currently patched by seeding from `dumpsys input_method` at startup — a workaround for a
  problem that only exists because of the split.
- **~315ms dispatch latency** (bash + `dumpsys` lookup + fresh MQTT connect per keypress),
  which showed up as the cursor tracking for a third of a second after releasing the key.
  Now patched with a private persistent `pub` — i.e. a second copy of what `MqttBridge`
  already does.
- **Supervision scaffolding**: pidfile, `flock`, orphan reaping, plus a Cybersyn task and
  profile whose only job is restarting it. None of it needed for code inside a foreground
  service that's already supervised by `EngineWatchdogWorker`.

## What actually needs root

Only two things: `EVIOCGRAB` on `/dev/input/event0`, and writes to `/dev/uinput`. Both must
live in a root process. Everything else is policy and belongs in Kotlin.

Cybersyn already runs root children — `LogcatContextSource` (`su -c`, with the TOCTOU
lifecycle fix from `57ca6cb`), `ChildProcessAudit`, `runRootCommand`.

## Design

### 1. Root input shim (small, dumb, no policy)

One `su -c` helper owning both privileged fds, speaking a line protocol:

- **stdout**: `EV <code> <value>` per grabbed key event (value `0`=up, `1`=down, `2`=repeat).
- **stdin**: `EMIT <code> <value>` to re-emit through uinput.
- Emits `READY_PID:<pid>` first, same trick `LogcatContextSource` uses so teardown can
  root-kill it regardless of stream timing.

It never decides anything. It grabs, forwards, and emits on command. If it dies the kernel
releases the grab and keys return to stock behaviour — the same safe failure mode as today.

### 2. `KeyHijackController` (Kotlin, in-app)

Owns the shim's lifecycle using the `procLock` + `running`-flag pattern already established
in `MqttBridge.rawSubscribe` and `LogcatContextSource`. Holds all routing:

- **IME state**: `MqttBridge.subscribe("spectreboard/ime_shown")` — already shared and
  ref-counted per (broker, port, topic), so it costs no new connection. State can also be
  seeded directly in-process rather than inferred.
- **Browser state**: direct call from `ForegroundAccessibilityService`. **The accessibility
  service itself does not change** — it is the correct mechanism, event-driven and free.
  It simply stops publishing `volume_daemon/browser_mode` to a broker and hands the state to
  the controller in-process. The topic disappears entirely.
- **Publishing**: `MqttBridge.publish` for `android/clutch` / `android/click`. One
  already-warm connection, no per-event process spawn, no `dispatch_input.sh` hop.
- **Routing rules carry over unchanged** — latched per press on key-down, power never
  remapped, power-held hands the volume keys to Android so the native screenshot chord
  fires. That logic is correct; it just moves.

### 3. What gets deleted

- `examples/phone-scripts/volume_daemon.py`, `volume_daemon.sh`
- The watchdog task + profile (`examples/volume-daemon-watchdog.yaml`) and its DB rows
- `dispatch_input.sh`'s `GYRO_*` / `HOLD_*` arms (keep the rest; Task 121 still uses
  `CLUTCH`/`CLICK`)
- The `volume_daemon/browser_mode` topic and its publisher
- pidfile / flock / orphan reaping / `ime_shown` seeding workaround / the private `MqttPub`

## Risks and open questions

- **Reinstalling Cybersyn drops the grab** until the service restarts. That is strictly
  better than today: keys fall back to stock instead of being held by a process that
  survived with stale state.
- **Root helper lifecycle is the known-dangerous part.** This is the exact bug class from
  `57ca6cb` and audit §2 (64 leaked root `logcat` children). Reuse the fixed pattern; do not
  re-derive it. `ChildProcessAudit` should be able to see the shim.
- **`su -c` availability at boot** — the current daemon has the same dependency, so no new
  exposure, but the controller must retry rather than give up.
- **Does the shim need to be a separate binary?** A shell one-liner can't do `EVIOCGRAB` or
  the uinput ioctls. Options: keep a small python script invoked via `su -c` (simplest, same
  as today, but still an external file), or add the ioctls to the existing native helper.
  **Decide before implementing.**

## Explicit reversal note

On 2026-07-28 the daemon-not-a-service split was a deliberate instruction ("it can be a
daemon script just as npud is, just cybersyn should do the starting and restarting logic"),
and a Kotlin foreground service was rejected as over-building. That was correct at the time:
the daemon read two keycodes and shelled out.

What changed is that the daemon grew always-grab, mode routing, uinput emission, its own MQTT
client set, and its own supervision — while the app independently grew the accessibility
service that the daemon now depends on. The app is already doing half the job and publishing
state outward so the other half can read it back.
