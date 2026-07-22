# Cybersyn — Recon & Working Decisions

Date: 2026-07-18, revised same day after discussion. Companion to
`OVERVIEW.md` (scope). Neither doc automatically wins: if the two contradict,
that's a flag to stop and resolve it in discussion, then update both.

## 1. Decisions so far

1. **App identity: `com.termux.cybersyn`**, `sharedUserId="com.termux"`,
   signed with the platform key. Verified: the crDroid fork's platform key
   and the Termux-family key are the **same key** — cert SHA-256
   `CE:89:47:9E:…:B1:24`, on-device signature hash `[e12e830d]` matches the
   `android` package. Joining the family gets Termux UID (10253) + SELinux
   domain `platform_app` + auto-granted signature perms (INJECT_EVENTS,
   WRITE_SECURE_SETTINGS, READ_LOGS, …) simultaneously. Install as priv-app
   like the rest of the family. Note: `sharedUserId` is fixed at first
   install.
2. **Cybersyn is "Tasker in open source", not "KDE Connect plus some".**
   The OpenTasker tree is copied in as the starting skeleton of the brain
   app and renamed from day one. KDE Connect (both repos) is a **parts bin**:
   we copy the bits we need, when we need them.
3. **No forks to maintain.** `Source_APKs/` clones stay read-only reference
   trees with upstream remotes (can pull for fresh parts). Work happens in
   `Android_src/` (brain) and `Fedora_src/` (relays). Bits are copied/rsynced
   over piecemeal; nothing tracks upstream.
4. **Desktop side: Rust relay daemons on comrade + comintern**, as in
   OVERVIEW.md — one binary, both boxes, watch dbus/systemd/inotify/udev,
   execute actions locally. KDE's C++ is not run there; its value on this
   side is **logic and code to learn/port from** (connection keeping,
   reconnect-after-loss behavior, file transfer flow), not the KDE protocol
   itself.
5. **Transport: MQTT is the default bus for everything** — triggers, events,
   actions, presence. Mosquitto already runs on comrade
   (`100.108.8.60:1883`, also `127.0.0.1:1883`) and the phone→MQTT→uinput
   path is proven live (`p30control.service` active today). Encryption is
   the always-on zero-trust Tailscale mesh — no extra crypto layer needed.
   Exception: **file sending reuses KDE Connect's transfer code/approach**
   (its payload side-channel is good, MQTT is wrong for bulk data).
6. **Keep what's already there and working from reused KDE code** — e.g.
   broadcast discovery and KDE's built-in pairing/verification come along
   with the code that uses them. No stripping of working behavior for
   theoretical cleanliness, and **no makeshift auth layer on top** — KDE
   Connect already has verification built in where its code is reused.
7. **Stock KDE Connect is fully retired at cutover** — Cybersyn's feature
   set strictly contains it (KDE circle entirely inside the Cybersyn circle).
   Retirement list when the time comes: `KDEConnect` priv-app out of the
   crDroid tree, `kdeconnectd.service` (+ offscreen drop-in) off comrade,
   nothing on comintern (never installed). Until then both keep running
   normally.
8. **License: GPL-2/3 for the brain app if ever distributed** (KDE code
   copied into the MIT OpenTasker base ⇒ combined work is GPL on
   distribution; private use obligates nothing). Operator explicitly fine
   with this.
9. **No phone-side MCP/server add-on for authoring.** The trust boundary is the
   existing Tailscale path to blazer, and `tools/cybersynctl` uses ADB plus
   DUMP-protected Cybersyn broadcasts to query/export/import/run. YAML is
   converted to bundle JSON on the host and pushed over the CLI path. A server
   can be revisited only if the CLI proves insufficient.

## 2. Open items (not decided, revisit when relevant)

- MQTT topic schema / message format for triggers, actions, presence.
  (Implementation note: MQTT LWT + retained messages would give presence
  "node is on/off" for free — suggestion, not decided.)
- How the KDE-style file transfer channel and the MQTT bus hand off to each
  other (e.g. action message on MQTT carries an offer, transfer runs on the
  side channel).
- How much of KDE's link-supervision logic the Rust relays actually need
  once the broker handles session state — port judiciously, not wholesale.
- Tasker XML migration: OpenTasker's importer maps only ~5 action types;
  real migrations will be partly manual. Scope unknown until tried.
- YAML authoring ergonomics live in `tools/cybersynctl`, not in a resident
  phone-side MCP service.

## 3. What's in this tree

- `OVERVIEW.md` — scope doc.
- `RECON.md` — this file: recon findings + running decisions.
- `Source_APKs/` — clean read-only reference clones, upstream remotes intact:
  - `kdeconnect-android` (HEAD 2026-07-10) — Android parts bin.
  - `kdeconnect-kde` (HEAD 2026-07-17) — logic reference for the Rust
    relays; C++ parts bin if ever needed.
  - `OpenTasker` (HEAD 2026-07-17) — brain skeleton source.
- `Android_src/` — will hold the `com.termux.cybersyn` app tree.
- `Fedora_src/` — will hold the Rust relay crate.
- `tools/cybersynctl` — host-side ADB/Tailscale CLI for list/export/apply/run/profile operations.
- `examples/quicktap-sidebar.yaml` — minimal YAML import example.
- `misc/` — borrowables from older projects (§6).

## 4. Repo assessments (recon 2026-07-18)

### OpenTasker — the skeleton; solid, genuinely

MIT. Single-author, ~2.5 months old, 431 commits, ~32k LOC Kotlin + 522
passing JVM tests. Kotlin 2.3.21, AGP 9.2.1, compileSdk 37, minSdk 26, no
Hilt. **Real engine, not scaffolding** (verified in code):

- Engine: `core/engine/AutomationService.kt` (698 l), `TaskRunner.kt` (603 l;
  if/else/foreach/stop, sub-tasks, per-action timeouts),
  `ProfileMatcherImpl.kt` (OR groups), `FlowStructure.kt`.
- ~59 actions (`core/actions/`, registered in `core/RuntimeRegistries.kt`),
  ~20 trigger sources (`core/contexts/`, `automation/`).
- Tasker XML import: `core/transfer/TaskerXmlImport.kt` — real but thin
  (§2 open item).
- **Cybersyn extension points:** `ContextSourceRegistry` (MQTT/relay-event
  triggers slot in as just another source), `ActionRegistry` (root/shell/
  desktop actions), `AutomationTargetReceiver` (signature-scoped external
  intent API).
- Same-UID payoff: `core/scripting/TermuxScriptBackend`'s RUN_COMMAND +
  SHA-256-allowlist dance can be deleted → direct in-process exec of
  `$PREFIX` binaries.
- Risks: bus factor 1, young code, known edge cases in its own ROADMAP
  (sub-task variable leak, QUEUED-cooldown interaction, scene sliders).

### kdeconnect-android — Android parts bin

GPL-2/3. v1.35.9, Kotlin 2.4.0 / AGP 9.2.1 / compileSdk 37, ~35k LOC.
Candidate parts (pull only when a rule/feature needs them):

- File sending / payload transfer: `NetworkPacket.kt` payload handling +
  the side-channel transfer sockets in `backends/lan/LanLink.java` /
  `LanLinkProvider.java`; SFTP server if wanted: `plugins/sftp/`
  (Apache SSHD embedded, incl. SAF layer).
- Connection keeping / reconnect logic: `backends/lan/*` link lifecycle,
  `BackgroundService`'s NetworkCallback → `onNetworkChange()` redial path,
  broadcast + custom-host identity announce (both already work — keep as-is
  where reused).
- Pairing/verification (built-in, reuse as-is where its code is copied):
  `PairingHandler.kt`, `helpers/security/SslHelper.kt`, `RsaHelper.kt`,
  `helpers/TrustedDevices.kt`.
- Plugin internals worth mining later: `plugins/notifications/` (612 l,
  listener + reply/actions), `plugins/mousepad/` (input handling),
  `plugins/clipboard/`, `plugins/runcommand/`, `plugins/systemvolume/`.
- Plugin scaffolding if useful: `plugins/Plugin.kt` +
  `plugins/PluginFactory.kt` (KSP `@LoadablePlugin` auto-discovery).
- Known Play-Store-only ballast (skip when copying): foreground-service
  dance, READ_LOGS clipboard hack, SAF gymnastics, permission gating — a
  system-signed priv-app needs none of it.

### kdeconnect-kde — logic reference for the Rust relays

GPL-2/3, C++/Qt6 + KF6. Not run and not forked; read it to port behavior:

- Wire/payload format: `core/networkpacket.cpp` (~96 lines total
  serialize/unserialize, protocol v8) — relevant for the file-transfer
  channel the relays must speak with the phone.
- Link supervision / reconnect: `core/backends/lan/` (provider + link),
  `Daemon::forceOnNetworkChange()`, custom devices handling.
- Pairing flow (if ported): `core/backends/pairinghandler.cpp`,
  `core/sslhelper.cpp`.
- Plugin surface for feature ideas: `plugins/<name>/` each ~5 files;
  desktop capabilities catalog (mousepad, remotekeyboard, clipboard, sftp,
  notifications, runcommand).
- FYI: stock `kdeconnectd` 26.04 currently runs headless on comrade
  (`QT_QPA_PLATFORM=offscreen`, user unit + drop-in) — useful live
  reference/test peer until cutover.

## 5. Signing / device facts (verified live 2026-07-18)

- ROM platform cert = Termux family cert: SHA-256
  `CE:89:47:9E:28:58:B6:5A:A2:13:18:36:AB:4C:2D:16:AA:09:28:4F:2B:40:FB:00:26:20:AD:1A:EC:91:B1:24`
  (`crDroid/device/google/blazer-secur/keys/platform.x509.pem`; same
  fingerprint ships in the ROM as `termux_platform.x509.pem`). On-device:
  `com.termux` sig `[e12e830d]` == `android` package sig `[e12e830d]`.
- Gradle signing keystore: `~/builds/android/termux_build/signing/
  termux_platform.p12`. The `.jks`/`WRONG_KEY_*.bak` beside it is the old
  dev key (fingerprint `E1:52:A0:40:…`) — do not use.
- Termux family: shared UID **10253**, SELinux `platform_app`; members seen
  live: com.termux + shadereditor, widget, kdeconnect, jdsp, spectreboard.
- `com.termux.kdeconnect` 1.35.9 = `/system/priv-app/KDEConnect`, source
  tree `~/builds/android/kdeconnect-android` (stock KDE Connect renamed —
  the daily driver until cutover; proves the sign-and-priv-app pipeline;
  not a Cybersyn work tree).

## 6. misc/ inventory (older projects to borrow from)

- `amd-control/` — Flask + token action API (`control-api/api.py`) +
  `p30control.service` unit. Pattern donor for host-side action endpoints.
  **⚠ contains a real secret: `control-api/ACTION_TOKEN` — never publish
  this directory.**
- `sensor-control/` — published MIT gyro/sensor pipeline (adapters → state
  machine → outputs, systemd unit, tests). Structure donor for the relay's
  stream handling; license-clean.
- HID scripts (`hidrunner.sh`, `hidkey.sh`, `dispatch_input.sh`, `click.sh`,
  `clutch.sh`, `back.sh`, `forward.sh`) — Termux root HID keyboard/mouse
  injection via `/data/local/tmp` fifo. Future Cybersyn root actions.
- `presentation_mode.sh`, `diana_stream_kiosk.sh`, `start-gyro-control.sh` —
  phone-triggered host mode-switch orchestration examples.
- `homelab-health/healthd.py` — health daemon pattern (live original runs
  from `~/homelab-health/`). `__pycache__/` here is junk, safe to delete.
- Live-verified today: mosquitto on `100.108.8.60:1883` + `127.0.0.1:1883`;
  `p30control.service` active (gyro→uinput, `--invert-y --vertical-axis
  roll`); healthd active. The MQTT lane is not hypothetical.

## 7. Next steps

1. Keep expanding `tools/cybersynctl` as the authoring surface: validation,
   better diffs, and richer YAML shorthands before considering any server.
2. Draft the MQTT topic/message schema (§2) small and concrete against the
   first real relay rules; extend per rule after that.
3. First relay rule through the engine: event → MQTT → relay action → result.
4. Copy in KDE file-transfer parts when the first file-moving rule needs them
   (shopping list in §4).
5. At cutover: retire stock KDE per §1.7 and reconcile `OVERVIEW.md` with
   this doc.
