# Cybersyn

An open-source, Tasker-grade automation brain — Android-native, root/system-signed, with Linux relay nodes across the homelab. Named after Project Cybersyn (Chile, 1971–73): one central control point, fed by distributed nodes.

## Why this exists

Tasker is the most convenient automation tool there is, but it's closed source and its "hard parts" (accessibility-service abuse detection, OEM background-execution whack-a-mole, per-app notification scraping) are specifically the tax non-rooted, Play-Store-constrained developers pay. None of that applies here: crDroid, full root, system-key-signed brain app. That removes almost the entire category of things that make Tasker hard to reimplement, leaving the actually interesting part: a clean, composable rule engine.

## Core design principle

Triggers and actions are independent, composable rules (`trigger → condition (optional) → action`) that never block each other by default. This is the whole point of building this instead of hand-rolling scripts — new capability = new rule, dropped into the same engine, zero redesign of anything existing.

## Architecture

- **Brain — Android, Kotlin, system-signed.** Rule engine, scripting sandbox, trigger UI, and direct sensor access (gyro/accel/magnetometer/barometer/proximity/UWB — no throttling, no permission dance, because it's a system app). Lives on the phone because the phone has comparable uptime to the homelab boxes *and* it's the one device always on Chris's person, so location/context-aware rules actually mean something here.
- **Relay agents — Rust, deployed identically to `comrade` and `comintern`** (both Fedora 43, so one binary runs on both with zero porting). Watch local dbus/systemd/inotify/udev events, forward to the brain, execute actions the brain sends back. Dumb and stateless by design — adding a third node later (another Fedora box, or a local Termux relay) is just "build once, deploy everywhere."
- **Transport:** Mosquitto already runs on `comrade` (100.108.8.60:1883) and is the existing, working path — `p30control.py`/`p30control.service` already prove phone→MQTT→`comrade` works today. Whether this stays MQTT or gets replaced by KDE Connect's pairing/transport layer is still an open decision (see below).

## Build strategy: fork-and-merge, not from scratch

Same approach as the Agora project: clone multiple candidate codebases, evaluate which has less to strip out, graft the other's useful parts into the more solid base.

- **KDE Connect** (`kdeconnect-android` + `kdeconnect-kde`, GPL, invent.kde.org) — contributes device pairing/discovery, encrypted transport, and a plugin-loading skeleton that already runs on both Android and Linux (desktop side is C++/Qt with a D-Bus interface). This is real, tedious infrastructure Chris doesn't want to re-solve. It does **not** contribute a rule engine — its plugins are hardcoded features, not composable trigger/action rules.
- **OpenTasker** (`SysAdminDoc/OpenTasker`, FOSS, actively developed) — contributes an actual existing rule-engine attempt: profiles/contexts/tasks/actions, flow graphs, Tasker-XML import. Kotlin, Android-only, no desktop/relay component.
- **Likely split:** OpenTasker's core is probably the stronger base for the Android rule engine half; KDE Connect is probably perfect for the desktop/relay/transport half, since OpenTasker has nothing to compete with KDE Connect's Linux daemon.
Basically:

> KDE
- dbus interaction
- working touchpad, softkeyboard, clipboard
- file access
- notifications
- 

> OpenTasker:
- task/action logic
- Tasker xml support
- 

> older Projects:
- mqtt stream to improve KDE latency for control options
- better sensor handling for improved gyro mouse
- 
- No Play Store compliance considerations anywhere in this project.