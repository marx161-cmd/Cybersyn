# Credits

Cybersyn is GPL-3.0 (see [`LICENSE`](LICENSE)). Third-party code vendored into this
repository, with the licence it arrived under:

## OpenTasker — MIT

<https://github.com/SysAdminDoc/OpenTasker> — the core rule engine Cybersyn forked from:
profiles, contexts, tasks, actions, Tasker XML import, Room persistence.
Copyright © 2026 SysAdminDoc. Original notice preserved in
[`LICENSE.OpenTasker`](LICENSE.OpenTasker). MIT permits redistribution under GPL-3.0.

## Key Mapper — GPL-3.0

<https://github.com/keymapperorg/KeyMapper> — the evdev key-grabbing layer:

- `app/src/main/rust/evdev/` — Rust bindings over libevdev
- `app/src/main/rust/evdev_manager/` — grab targets, event loop, device watcher,
  Android keylayout mapping, JNI bridge

JNI symbols were repointed from `io.github.sds100.keymapper` to `com.termux.cybersyn`;
`build.rs` was pointed at this machine's NDK. Otherwise unmodified. Copyright © the Key
Mapper authors, GPL-3.0 — the same licence this project uses.

## libevdev — MIT

<https://gitlab.freedesktop.org/libevdev/libevdev> — `app/src/main/cpp/libevdev/`,
vendored via Key Mapper. Copyright © 2013 Red Hat, Inc. and others; per-file
`SPDX-License-Identifier: MIT` headers are intact and authoritative.

`event-names.h` is **generated**, not upstream source — produced by
`make-event-names.py` from the NDK's `linux/input.h` and `linux/input-event-codes.h`.
Regenerate it rather than editing it by hand.

## KDE Connect — conceptual

<https://invent.kde.org/network/kdeconnect-android> — no code taken; device pairing,
encrypted transport and plugin-architecture concepts informed the relay design.
