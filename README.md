# Cybersyn

Android-native, system-signed automation brain — trigger/action engine with logcat context source, Termux scripting, MQTT relay, and root-level device control.

> **DISCLAIMER: This is a personal homelab project tailored to one specific device ecosystem — a rooted Pixel 10 Pro running crDroid with a full Termux-suite system-app stack. Do not install this on your own device as-is. It assumes SELinux permissive, shared UID sign keys, specific hardware (Tensor G5 NPU, EdgeTPU), and a companion MQTT broker. It will not work on a normal Android device and may break things if you try.** This repo exists for transparency, auditability, and reference — not as a general-purpose app.

## Attribution

Cybersyn is built on the shoulders of:

- **[OpenTasker](https://github.com/SysAdminDoc/OpenTasker)** — the core rule engine: profiles, contexts, tasks, actions, Tasker XML import, and the Room persistence layer. Cybersyn forked from OpenTasker and retains its GPL-3.0 license.
- **[KDE Connect](https://invent.kde.org/network/kdeconnect-android)** — device pairing, encrypted transport, and plugin architecture concepts that informed the relay design.

## What it does

- **Trigger engine**: TIME, DAY, APPLICATION, STATE, EVENT, LOGCAT, LOCATION, PLUGIN contexts
- **Logcat source**: one blocking `logcat` process (`su` on Android 16+) watching ERROR-level system logs — any profile can register a tag + regex filter and fire on match
- **Termux scripting**: `script.termux.run` action type executes arbitrary Termux commands with bounded timeouts and output capture
- **MQTT relay**: `mqtt.publish` action talks to a homelab MQTT broker for cross-device coordination
- **Root actions**: reboot, screen on/off, shell commands via `su`
- **CLI management**: `tools/cybersynctl` — host-side YAML authoring over ADB/Tailscale. List profiles, export bundles, apply YAML, run tasks, toggle profiles.

## Context engine

```
ContextType → ContextSource → ProfileMatcher → AutomationService → TaskRunner
```

New in this fork: `LOGCAT` context type. A single `logcat -v threadtime *:E *:S` process (running as root to bypass Android 16's READ_LOGS dialog) emits log lines via a SharedFlow. Any profile can match on `tag` and/or `regex`. This replaces ~80% of individual context monitors — app crashes, package installs, notification events, connectivity changes — all visible in logcat without dedicated receivers.

## Build

```
cd Android_src
./gradlew assembleRelease
```

Signs with the Termux platform key (not included). The `app/build.gradle.kts` reads signing config from environment variables or falls back to debug signing.

## Deploy (crDroid / system app)

```
blazer-sysapp-update install com.termux.cybersyn app/build/outputs/apk/release/app-release.apk
```

This overlays the APK as a temporary system-app update. Roll back with:
```
blazer-sysapp-update rollback com.termux.cybersyn
```

## License

Cybersyn retains OpenTasker's GPL-3.0 license. Original copyright belongs to the OpenTasker authors. Modifications in this fork are provided under the same license.
