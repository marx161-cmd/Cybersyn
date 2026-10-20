# Cybersyn — scope.md

Append-only. Each entry is dated; never edit or delete a prior entry — add a
new one if a decision changes.

## 2026-08-24 — Oppo/EasyEffects volume mirror via the Pixel's App-volume panel

**Decided with the user, this session:**

- Goal: control the volume of the AMD→Oppo audio relay (the PulseAudio sink
  `ee_processed_sink` that EasyEffects processes before `oppo-usb-audio.service`
  ships it to the Oppo) **from the Pixel's physical volume keys / the crDroid
  "App volume" panel**, not a new standalone app, not a toggle, not the
  system output-switcher.
- Mechanism confirmed by inspecting crDroid source
  (`frameworks/base/media/java/android/media/AppVolume.java`,
  `AudioManager.listAppVolumes()`/`setAppVolume()` in
  `frameworks/base/media/java/android/media/AudioManager.java`): crDroid's
  "App volume" panel lists any app with an **active playback stream**, and
  the slider calls `AudioManager.setAppVolume(pkg, 0..1f)`, which applies a
  native mixer gain to that package's real audio — there is **no callback**
  telling the app its volume changed; the app must poll
  `AudioManager.listAppVolumes()` for its own entry.
  - Both methods are `@hide`, `@RequiresPermission(MODIFY_AUDIO_ROUTING)` —
    signature|privileged. Confirmed via
    `vendor/google/blazer/proprietary/system_ext/etc/permissions/privapp-permissions-google-se-lineage.xml`
    that only `com.android.systemui` currently holds this grant — any app
    using it needs its own privapp-permissions allowlist entry, platform
    signing alone is not enough (see
    `docs-consolidated` reference_android_signature_permission_allowlist).
- **Decision: build this into Cybersyn, not a new dedicated app.** Cybersyn
  is already `sharedUserId="android.uid.system"`, already privileged, and
  already has the one-persistent-MQTT-connection pattern
  (`MqttBridge.publish` / `MqttIngest`) other apps (SpectreBoard, Artemis)
  publish into. A second standalone app would need its own signing, its own
  install path, and its own privapp-permissions entry for the exact same
  permission — no benefit over adding one more component to the app that
  already has the connection and the UID. The tradeoff: it shows up in the
  App-volume panel as "Cybersyn", not a purpose-named app — accepted.
- **Design:**
  1. `AppVolumeMirror` (new Kotlin object in Cybersyn, modeled 1:1 on
     `KeyHijackController`'s `start(context)`/`stop()` shape, started/stopped
     from `AutomationService.onCreate()`/`onDestroy()` next to
     `startKeyHijack()`): holds a silent looping `AudioTrack` (USAGE_MEDIA)
     so Cybersyn shows up as an *active* entry in `listAppVolumes()`, polls
     its own package's entry every ~250ms, debounces, and on change calls
     `MqttBridge.publish(context, "cybersyn/hid/oppo_volume", "<0-100>")`.
  2. comrade side: `cybersyn-hid-relay.py` gets a new topic handler,
     `pactl set-sink-volume ee_processed_sink <pct>%` — same
     subprocess-call style as the existing `xdotool`/`i3-msg` handlers.
  3. crDroid: `MODIFY_AUDIO_ROUTING` `<uses-permission>` added to Cybersyn's
     manifest, plus a new privapp-permissions allowlist entry for
     `com.termux.cybersyn` granting it.
- **Known cost, stated to the user, not yet done:** the privapp-permissions
  grant lives in the ROM tree, so it can't take effect via a sysapp overlay
  push — it needs an actual crDroid rebuild + OTA + flash before this can be
  live-verified on-device. The Kotlin/Python source changes are written and
  buildable now; the OTA cut is a separate, explicitly-flagged follow-up
  step, not bundled into "done" silently (per the standing "only promote
  sysapp at OTA time" rule — this one goes further, since it's not even
  overlay-pushable, it's a real ROM permission-manifest change).

## 2026-08-24 — Correction: Oppo volume mirror moved to Artemis, no privileged permission needed

The first design above (Cybersyn calling hidden `AudioManager.listAppVolumes()` to read
back the crDroid App-volume panel's per-app gain) was wrong on two counts, both caught
by the user live:

1. **The target app never needed the permission.** `setAppVolume()` is called by SystemUI
   (which already holds `MODIFY_AUDIO_ROUTING`) *onto* the target app's real output — the
   target needs zero special permission to be controlled that way. Proven live: YouTube,
   an ordinary unprivileged third-party app, has its own row and slider in that exact
   panel. So there was never a reason for Cybersyn itself to need the allowlist entry or
   the OTA at all for that half.
2. **Cybersyn was the wrong app regardless.** The user's framing: "Artemis should both
   know how to play audio and how to talk to Termux/comrade" — and it already does both.
   Diana/Artemis is genuinely playing the streamed audio (real `STREAM_MUSIC` playback,
   real audio focus) whenever it's in use, so the phone's volume rocker already targets
   it naturally during normal use — no need to fake a silent keep-alive stream in a
   background service to "steal" a spot in a volume UI. And it already has the
   `publishCybersynFifo()` FIFO-writer built (used for `cybersyn/hid/keyboard_visible`).

**Final design, implemented and built clean:**
- `~/builds/android/artemis-patch` `Game.java`: a `BroadcastReceiver` on the standard
  `android.media.VOLUME_CHANGED_ACTION`, filtered to `STREAM_MUSIC`, reads
  `AudioManager.getStreamVolume()/getStreamMaxVolume()` (both fully public, no
  permission), computes a 0-100 percent, debounces against the last-published value, and
  publishes to `cybersyn/hid/oppo_volume` via the existing FIFO writer. Registered in
  `onCreate()`/unregistered in `onDestroy()`, `RECEIVER_NOT_EXPORTED` on API 33+.
- comrade: `cybersyn-hid-relay.py`'s `cybersyn/hid/oppo_volume` handler (unchanged from
  the first pass) → `pactl set-sink-volume ee_processed_sink <pct>%`. Relay restarted
  live 2026-08-24 to pick it up.
- All crDroid ROM changes (privapp-permissions XML, `device-secur.mk` entries) and the
  Cybersyn manifest/`AutomationService` wiring from the entry above were **reverted** —
  this ships with zero OTA required, no ROM rebuild, nothing pending. Built
  `assembleRootRelease` clean and installed on-device 2026-08-24; live end-to-end test
  (adjust phone media volume while Diana is streaming, confirm Oppo speaker level
  follows) still needs the user to actually run it.

## 2026-08-24 — shader-renderer-ctl.sh: stale PID file broke restart from the i3 menu

Not part of the Pixel<>AMD scope above, fixed same session while the user was testing it.
`~/bin/shader-renderer-ctl.sh` (wired into `i3-screen-menu.sh`'s "Shader wallpaper:
start/stop/status") tracked the renderer solely via `~/.cache/shader-backdrop/renderer.pid`.
That file was missing (renderer had been started some other way at some point), so
`is_running()` always reported false, `stop` silently no-op'd on the real live process, and
the user was stuck with a wallpaper instance the menu couldn't touch. Fixed by falling back
to `pgrep -f "$PYTHON_BIN renderer.py"` whenever the PID file is missing/stale, so state
can't desync from reality like that again. First fix attempt had its own bug: under
`set -e -o pipefail`, `pgrep | head -1` finding nothing exits non-zero, which aborted the
whole script from inside a command-substitution assignment -- fixed with `|| true` on that
pipeline. Killed the orphaned real process and did a clean restart via the fixed script;
confirmed all three output windows (pixel/landscape/dell-portrait) re-registered.

Separate, worse bug found right after: "correct geometry" above was wrong -- `renderer.py`'s
`OUTPUTS` table (window x/y/w/h per output) is a **hardcoded snapshot**, not a live
`xrandr` query despite its own comment claiming otherwise. It had gone stale: landscape's
real live x was 3496, hardcoded said 2360; dell-portrait's real live x/y was 2416/0,
hardcoded said 1280/248. Symptom matched exactly: landscape rendered in the gap between
real outputs (looked "completely off"), dell-portrait only partially overlapped the real
rotated monitor region (showed just the lower third). Updated the three hardcoded values
to match current live `xrandr --query`, restarted, confirmed new log lines match. Pixel's
entry was left untouched (2416 vs live 2410, close enough, user confirmed it already
looked correct) rather than risk changing something that wasn't broken.

**Known caveat, not fixed:** this table will go stale again the next time the VKMS output
layout actually changes (a Sunshine/stream mode switch, a Dell nudge, etc.) since nothing
re-queries or re-applies it live. Flagged to the user, not silently converted to a dynamic
xrandr-query -- that's a real architecture change to this renderer, not a one-line fix,
and wasn't asked for.

## 2026-08-24 — Repacked VKMS layout: portrait stacked under landscape, not beside it

User's idea, confirmed with a mockup: instead of three outputs side by side (mobile |
portrait | landscape), put portrait directly under landscape in a shared column, since
portrait (1080 wide) fits inside landscape's own width (1920). User bumped the Pixel
stream to `pixel-hi` (1280x2856 portrait) first specifically so the stack
(landscape 1080h + portrait 1920h = 3000h) would roughly match mobile's own height,
keeping the combined framebuffer close to square instead of a long strip.

Edited `~/bin/display-vkms-appliance.sh`: replaced the old "portrait gets its own X
column between mobile and landscape, vertically centered" logic with "portrait and
landscape share mobile's right edge as their X, portrait's Y = landscape's height (no
gap)". FB_W/FB_H recomputed from the new packing instead of the old side-by-side sum.
Applied via `stream-output-mode.sh pixel-hi` (the sanctioned entry point, not a raw
xrandr call). Result: combined screen went from 5416x2410 to 3200x3000 -- confirmed live.
Updated `renderer.py`'s `OUTPUTS` table to match (pixel 1280x2856@0,0; landscape
1920x1080@1280,0; dell-portrait 1080x1920@1280,1080) and restarted; log confirms the new
positions.

## 2026-08-24 — Pin the stack's X so switching Pixel stream modes doesn't shove it

Follow-up ask: mobile's on-screen width varies by stream mode (pixel=1080, pixel-hi=1280,
tablet=1200...), and the repack above derived the stack's X directly from `MOBILE_W`, so
switching modes moved the whole landscape+portrait stack sideways every time. Wanted the
stack pinned, with only mobile's own box resizing.

First attempt: keep the stack at a fixed anchor (1280, the widest portrait mode) and make
*mobile* grow/shrink from its own left edge instead (`MOBILE_X = anchor - MOBILE_W`),
so mobile's right edge always touches the anchor. **Live-tested and wrong**: X11/RandR
normalizes the whole screen so the union of all outputs' bounding box starts at (0,0) --
requesting `--pos 200x0` for mobile got silently un-done, snapping mobile back to x=0 and
the stack back to x=1080 (i.e. right back to following `MOBILE_W`), confirmed by direct
`xrandr` calls outside the script too (tried moving `--primary` to rule that out
specifically -- made no difference). This is a hard X-server/RandR behavior around the
screen origin, not something to fight.

Fix: mobile stays flush at x=0 always (never moves), the stack stays pinned at the fixed
x=1280 always -- the gap from a narrower mobile mode lands *between* mobile and the stack,
not at the screen's own origin, so it isn't touched by that normalization. Confirmed live
both directions (`pixel` <-> `pixel-hi`): stack held at x=1280 in both, only mobile's own
box changed size. `renderer.py`'s `OUTPUTS` restarted to match (unchanged values from the
prior entry, since they already matched the pixel-hi/anchor=1280 case).

**Known risk, not addressed, flagged to the user:** the old portrait layout was
deliberately *vertically centered* against mobile specifically to avoid a dead-zone gap,
because X11 clips pointer movement to the union of active output rects -- a real gap
there is a hard wall, not just unseen desktop (see the code comment this replaced). The
new stacked layout reintroduces two such gaps: a ~144px strip below mobile's own column
(2856 tall vs the stack's 3000), and an 840px-wide notch below landscape's right portion
(landscape is 1920 wide, portrait only 1080, so the outer 840px of the stack's width has
nothing under landscape). Not worked around -- needs to be tried live to see whether it
actually causes the same pointer-confinement problem the old code was written to avoid.

## 2026-08-24 — 4-finger tap repurposed: touch-mode toggle instead of virtual keyboard

User's ask: repurpose the 4-finger tap (previously opened the full virtual keyboard --
not needed, their soft keyboard is stacked with its own menu fallback) to toggle between
multi-touch and trackpad mode instead. Framed as "add modes to the custom gesture
recognizer like anchor-drag" -- turned out not to need that: the 4-finger tap is stock
Moonlight gesture code in `Game.java`, unrelated to `AnchorDragGestureRecognizer`, and
there's already a complete runtime mode-switcher (`applyMouseMode(int mode)`, used by the
mouse-mode settings dialog) that correctly rebuilds `touchContextMap` for the new mode.
Added `toggleTouchMode()` (`applyMouseMode(touchscreenTrackpad ? 0 : 2)`, session-only,
not persisted to prefs) and pointed both 4-finger-tap call sites at it instead of
`toggleFullKeyboard()`. Built `assembleRootRelease` clean, installed via
`blazer-sysapp-update` (not a raw adb install). Not yet live-tested by the user.

## 2026-08-24 — Anchor-drag gesture: modes based on anchor position + drag finger count

Extended `AnchorDragGestureRecognizer` (previously single-purpose: any anchor+drag =
window-move) into three actions, confirmed with the user before building:

- Anchor down inside a top-left corner zone (120dp square from origin) + drag ->
  `WINDOW_MOVE` (today's Super+drag -> i3 Mod+drag), regardless of drag finger count.
  Same behavior as before, just now gated to that corner instead of anywhere on screen.
- Anchor down anywhere else + 1 drag finger -> `LEFT_CLICK_DRAG` (BUTTON_LEFT held for
  the gesture's duration, mirroring `conn.sendMouseButtonDown/Up` already used by
  `TrackpadContext`'s tap-based right-click).
- A 2nd drag finger joining mid-gesture upgrades `LEFT_CLICK_DRAG` to `RIGHT_CLICK_DRAG`
  (releases LEFT, presses RIGHT) -- anchor + two fingers as the "hold" version of the
  existing 2-finger-tap-for-right-click mapping elsewhere in the app.
- Releasing either drag finger ends the gesture (matches the pre-existing behavior of
  releasing the anchor finger alone NOT ending it, only unchanged).

`Host` interface changed from three no-arg-ish methods to
`on{Start,Move,End}(Action, ...)`; `Game.java`'s implementation switches on the action to
send Super-hold vs `BUTTON_LEFT`/`BUTTON_RIGHT` hold via the existing `NvConnection`
calls (`sendMouseButtonDown/Up`, same as stock Moonlight input contexts already do).
Built `assembleRootRelease` clean, installed via `blazer-sysapp-update`, ledger-recorded.
Not yet live-tested by the user. User explicitly framed this as the start of an
iteratively-expanding gesture set (anchor position + finger count as the two axes) --
future additions should follow the same pattern rather than one-off special cases.

## 2026-08-24 — Artemis anchor-drag gesture: blocks normal two-finger gestures

Diagnosed (not yet fixed as of this entry): `AnchorDragGestureRecognizer.onTouch()`
in `~/builds/android/artemis-patch` treats *any* second-finger-down as the
start of anchor-drag as soon as the first finger is within `anchorSlopPx` of
its down point — which is true at the instant almost every normal two-finger
gesture (pinch-zoom, two-finger scroll) begins too, since both fingers
naturally land close together in time. Confirmed by the user: "it blocks
half the normal gestures." Fix direction agreed: require the anchor finger
to have been held stationary for a short dwell time (~200ms) before a
second-finger-down is allowed to start anchor-drag, so a normal fast
two-finger gesture passes through untouched. This file is uncommitted
(`git status`: `AnchorDragGestureRecognizer.java` untracked, `Game.java`
modified) as of 2026-08-21 — same uncommitted state going into this fix.

## 2026-08-29 — ChildProcessAudit runaway `tr` burned ~1255 mAh; replaced /proc walk with `ps`

A `tr '\0' ' ' < /proc/<pid>/cmdline` helper spawned by `ChildProcessAudit`'s
`liveChildrenViaSu()` spun at ~96.5% of one core for ~9.5 hours (130 billion
read() syscalls, ~253 KB consumed) after reading a single `/proc/<pid>/cmdline`
that never reached EOF — the process was caught mid-transition (zombie/mid-exec/
PID-recycled), and the raw `tr` redirection has no bound. Root cause + forensics
in the audit trail; the battery hit was ~1255 mAh (UID 0 cpu) out of ~2993 mAh
computed drain.

Fix applied in `app/src/main/java/com/termux/cybersyn/core/engine/ChildProcessAudit.kt`:
replaced the two-step `su -c 'ls /proc'` + per-pid `sed`/`tr` loop with a single
bounded `su -c 'timeout 30 ps -A -o PID,PPID,ARGS'`, filtered to ownPid children
in-process. `ps` reads /proc with bounded buffers (cannot spin forever the way the
raw `tr` redirection did), and the `timeout 30` wrapper is belt-and-suspenders so a
stuck kernel walk can never leave a child at 100% CPU again. Doc comments updated.
Grouping key changes slightly: `ps ARGS` shows argv[0] as basename, whereas the old
`/proc/<pid>/cmdline` read carried the full path — immaterial for duplicate-helper
detection (all helpers launch from one canonical path per binary).

## 2026-09-02 — Extend ExplainReceiver into a single LLM-facing manifest endpoint

**Goal, decided with the user this session:** cut the friction of driving Cybersyn
as an LLM — today that means reading `ROADMAP.md`/`docs/CYBERSYNCTL.md`/grepping
Kotlin source to guess the real trigger/action vocabulary, then hand-testing.
Inspired by looking at a third-party app (`~/builds/android/argus`, GPL-3.0,
license-compatible with Cybersyn's own GPL-3.0) whose `CapabilityManifest` gives
its LLM compiler one live, generated-from-the-real-registry snapshot of what's
buildable right now, instead of hand-maintained docs that drift.

**Decision: do NOT build a new subsystem or MCP server for this.** Investigation
found `core/external/ExplainReceiver.kt` already does ~80% of what's wanted —
it's explicitly written for LLM consumption (see its own comment: "so an LLM
querying this endpoint gets them without re-deriving them from source"), already
dumps real tasks/profiles/run-log/engine-health/action-capability data as JSON,
mirrors to `/data/local/tmp` for plain `adb shell cat` (no scp/root needed), and
already ends with a `next_commands` hint list. It just has no `cybersynctl` verb
calling it, so it was effectively dark. Extending it is far less work and far
safer than a parallel manifest path — it reuses the already-proven per-section
timeout/fault-isolation pattern (one bad collector can't kill the report) instead
of re-solving that.

**What ExplainReceiver's JSON is gaining:**
1. `triggers` — the real list of registered context `type` strings from
   `ContextSourceRegistry.all()`, ground truth (not hand-copied into a doc that
   can drift). Per-type `config` key shapes are NOT structured anywhere in the
   Kotlin source (`ContextSource` only declares `val type: String`), so those
   stay documented in `docs/CYBERSYNCTL.md`'s example rather than fabricated here.
2. `automation_modes` — `AutomationMode.entries` (`SINGLE`/`RESTART`/`QUEUED`/
   `PARALLEL`), ground truth from `core/model/Profile.kt`.
3. Action `fields` (key/type/required) added to each entry in the existing
   `actionCapabilities` JSON, pulled from `ActionMetadataRegistry` — today that
   list has id/name/category/capability/reason but not args, so the args shape
   still required reading `ActionMetadata.kt` source. Also appends
   `RUNTIME_ONLY_ACTION_IDS` (real, dispatchable, deliberately absent from the UI
   catalog — mpv/media/clipboard/file-transfer relay actions) marked
   `"runtime_only": true` so they're not silently missing from the picture.
4. `device_state` — a new `DeviceStateSnapshot` collector: `/proc/meminfo` (direct
   file read, no shell), plus `su -c "timeout 5 ..."` for `dumpsys battery`,
   `dumpsys thermalservice`, and `ps -A -o PID,PPID,RSS,ARGS` — this is the
   "memory/CPU/process overview" half of `~/bin/android-snapshot.sh` the user
   asked for, deliberately excluding the other half (settings dumps, package
   lists, appops, storage/df, mounts, wifi dumpsys, display metrics) as
   diagnostic-dump scope, not live task-authoring context. Uses the exact
   `su -c "timeout N ..."` pattern established above after the runaway-`tr`
   incident — never an unbounded shell call.

**`tools/cybersynctl` gains an `explain` subcommand:** broadcasts
`ACTION_EXPLAIN` to `ExplainReceiver` (`-n
com.termux.cybersyn/.core.external.ExplainReceiver`, DUMP-protected, already
callable by plain `adb shell`), then `adb shell cat
/data/local/tmp/cybersyn_explain.json` to read the mirrored copy straight back —
no scp round trip needed, unlike `export`/`apply`, since ExplainReceiver already
solved the "shell user can't traverse into Termux's 700 home dir" problem.

**Not changed:** no MCP server work (explicitly rejected this session — an
external tool-calling loop is the opposite of what's wanted here), no YAML
authoring path changes (`cybersynctl apply` already accepts raw bundle JSON
directly per `tools/cybersynctl:160`, confirmed working, nothing to port from
Argus there).

**2026-09-02, same day — bug found using it for real, fixed same day:**
`device_state.processes` capped `ps -A -o PID,PPID,RSS,ARGS` at 60 lines with no
sorting. Plain `ps -A` orders by PID, so the first ~50 rows on this device are
zero-RSS kernel threads — the field never reached a real app within the cap. Found
while diagnosing live phone sluggishness (had to fall back to a manual `ps | sort`
over adb instead of `explain` answering it directly). Fixed in
`DeviceStateSnapshot.kt`: the shell command is now `ps -A -o PID,PPID,RSS,ARGS |
sort -k3 -nr` (still inside the same bounded `su -c "timeout N ..."` wrapper),
verified live post-redeploy — top of the list now reads real RSS-heavy apps
  (`ru.tech.imageresizershrinker` 1.2GB, `dev.brgr.outspoke` 875MB, `system_server`
  866MB, ...), kernel threads sorted off the bottom of the 40-line cap for free.

## 2026-10-03 — RAPOO Bluetooth keyboard → comrade keystroke relay (evdev → uinput)

**Goal, decided with the user this session:** the user keeps a RAPOO Bluetooth
keyboard in a different room; the BT link to the Pixel is weak, but the Pixel is
always-on and already has a Tailscale/MQTT path to `comrade`. They want the
keyboard to type/navigate on the AMD box (`comrade`) **whenever the Pixel is
connected to that keyboard**, not on the Pixel.

**Decisions confirmed with the user (all recommended options):**
1. **Automatic while connected** — grab + relay whenever the RAPOO is present,
   ungrab on disconnect. Not gated on the existing `hidmode` AMD toggle.
2. **Exclusively relay** — the RAPOO's keys are consumed on the Pixel and never
   type on the phone (phone touchscreen/other input untouched).
3. **Raw evdev → uinput keyboard on comrade** — replays the raw Linux keycode.
   Layout-correct and works with i3/X directly; no per-key xdotool mapping table,
   modifiers/chords work for free. (`cybersyn/hid/key`'s xdotool-token lane is
   left as-is for SpectreBoard's AMD-mode soft keyboard.)

**Concrete device identity (live-verified 2026-10-03 via `/proc/bus/input/devices`):**
- name `RAPOO Keyboard-1 Keyboard`, Bus `0x0005` (Bluetooth), Vendor `0x245a`,
  Product `0x8276`, `/dev/input/event4`, uniq `d1:06:98:ed:1c:8a`.
- We match on the evdev `(name, bus, vendor, product)` tuple only. **No Android
  Bluetooth event/broadcast is needed** — the Rust `EvdevDevicesWatcher` already
  watches `/dev/input` for `Create`/`Remove` and hot-grabs a registered target
  when it appears, so connect/disconnect (and boot with the keyboard already
  paired) are all handled by the same path with zero new trigger code.

**Why reuse, not build:** `EvdevRootHelper` (root `app_process`, in-APK) already
grabs evdev devices, already emits `EV_RAW <deviceId> <type> <code> <value>
<androidCode>` with the raw Linux code, and `MqttBridge.publish` already gives a
single persistent MQTT connection (the `cybersyn-pub.fifo` / `cybersyn-send`
doorway). The Rust side (`libevdev_manager.so`) already accepts an **array** of
`GrabTargetKeyCode` and supports multiple simultaneous grabs. So this is:
one extra grab target + a consume-all flag for that device + a new MQTT topic +
a keyboard uinput device in the relay. **No Rust/native rebuild, no new ROM
permission, no new app, no BT receiver.**

**Design (implemented):**
1. **Phone — `KeyHijackController.kt`:** register a *second* grab target
   (the RAPOO) alongside `gpio_keys` via a new multi-target stdin protocol
   (`GRAB_RESET` + N× `GRAB <bus> <vendor> <product> <codes> <name…>` +
   `GRAB_APPLY`). Track grabbed device ids from `GRAB_OK` batches (now
   batch-terminated by `GRAB_DONE`) and tell the helper to hard-consume every
   `EV_KEY` from the keyboard ids (`CONSUME_DEVICE <ids>`). For those ids, forward
   `code,value` (down=`1`, up=`0`; drop autorepeat `2` so comrade's X owns
   repeat timing) to MQTT topic `cybersyn/hid/keyraw`, and skip the vol/power/gyro
   routing entirely so a keyboard volume key can't trigger a gyro clutch.
2. **Phone — `EvdevRootHelper.kt`:** new commands `GRAB_RESET`, name-aware
   multi-target `GRAB`, `GRAB_APPLY`, `CONSUME_DEVICE`; new stdout line
   `GRAB_DONE`. Callback consumes all `EV_KEY` from the configured device ids
   (checked before the power/vol logic). Small pure helper
   `RemoteKeyboardRelay.kt` holds the target identity + payload formatting for
   unit testing.
3. **comrade — `cybersyn-hid-relay.py`:** new `--keyraw-topic` (default
   `cybersyn/hid/keyraw`), a second `uinput.Device` declaring all `(EV_KEY, code)`
   capabilities (`python-uinput` accepts raw `(type, code)` tuples, verified),
   and a handler that emits `(uinput.EV_KEY, code)` with the forwarded value.
   Restart the user service to go live.

**Risks / accepted:** while the keyboard is connected it cannot be used on the
Pixel at all (that is the requested behavior); there is no local-escape key for
the BT keyboard (the 10s-power-hold escape only exists on `gpio_keys`). If this
proves annoying, the follow-up is to gate on `hidmode` or add a chord toggle —
not built now.

**Not changed:** the existing `cybersyn/hid/key` xdotool lane, the Android
Bluetooth context source, the crDroid ROM, and the MQTT trust boundary.

## 2026-10-03 — Correction: match ANY RAPOO Keyboard interface, not one exact name

Same session, immediately after the entry above. The user reported the keyboard
"sometimes shows up with a different number" (the trailing HID instance index in
`RAPOO Keyboard-N Keyboard`) and asked to grab **all RAPOO keyboards**; they also
confirmed they only ever connect this one keyboard.

This directly contradicts the exact-`(name,bus,vendor,product)` match and the
"no Rust/native rebuild" line in the entry above, so per the append-only rule this
corrects it rather than editing that entry:

- **Matcher change (requires the one native rebuild the prior entry ruled out):**
  `app/src/main/rust/evdev_manager/core/src/grab_target.rs` now supports name
  wildcards (`"*Keyboard"` = suffix, `"RAPOO*"` = prefix; plain name still exact)
  and an `ANY = 0xFFFF` sentinel for bus/vendor/product. Four unit tests added
  (exact, suffix, prefix, ANY-sentinel) and passing.
- `libevdev_manager.so` rebuilt for arm64 via `cargo ndk -t arm64-v8a build
  --release -p main` and copied into `app/src/main/jniLibs/arm64-v8a/` (build is
  ~5s incremental; NDK resolved from `$ANDROID_SDK_ROOT`).
- Kotlin target is now `name="*Keyboard", bus=0x05, vendor=0x245a, product=ANY`
  (`RemoteKeyboardRelay`). `isRemoteKeyboard()` mirrors it as a case-insensitive
  `endsWith("Keyboard")`; `gpio_keys` is unaffected (plain exact name).
- **Known limitation, accepted:** a native target grabs the *first* matching
  device per target (`get_targets_to_grab` uses `.find`), so if a single RAPOO
  ever exposed two interfaces both named `...Keyboard` simultaneously only one
  would be grabbed. HID keyboards expose one `Keyboard` interface (media keys are
  a separate `Consumer Control`), and the user only connects this keyboard, so
  this is not worked around.
- Everything else from the entry above stands: consume-all, `cybersyn/hid/keyraw`,
  uinput replay, automatic on connect/at boot.

**Not changed:** match still scoped to Bluetooth bus + RAPOO vendor, so an
unrelated BT keyboard would not be grabbed; only the model/product number is
unpinned.

## 2026-10-15 — One MQTT connection for all of Cybersyn (hub helper), to cut cellular-radio drain

**Why (measured on blazer, 2026-10-15):** `dumpsys batterystats` since last
charge: 2560 mAh drained, **mobile radio 1549 mAh (~60%)**, screen only 232. UID
1000 (Termux family) alone is credited 1075 mAh of radio energy for only ~9 MB
of traffic, i.e. the cost is radio wake-ups / high-TX-power time
(`cellular_high_tx_power` on for 164 of 210 min during a screen-off window),
not data volume. The phone was on LTE (wlan0 idle), and Cybersyn held **5
separate TCP connections** to the broker (100.108.8.60:1883, over Tailscale)
with a 30 s keepalive each: `sub` x4 (`cybersyn/clipboard/comrade`,
`cybersyn/file/offer`, `cybersyn/+/event`, `cybersyn/comrade/media/status`) +
`pub` x1. They were verified NOT to be duplicates (distinct topics, one parent
PID) — the 2026-07-31 leak is fixed; this is the design itself.

**Decided with the user ("definitely do that"):**

- Replace the per-topic `sub` helper processes and the separate `pub` helper
  with **one `hub` helper process = one MQTT connection** per (broker, port),
  carrying every subscription and all publishes.
- **Public API of `MqttBridge` does not change** (`publish(...)`,
  `subscribe(...): Flow<Message>`), so no caller is touched.
- Helper stdio protocol (line based, tab separated), new subcommand `hub`:
  stdin `S\t<filter>` subscribe, `U\t<filter>` unsubscribe,
  `P\t<topic>\t<payload>` publish; stdout `<topic>\t<payload>` per inbound
  message (same as `sub`). The helper keeps the active filter set and
  re-subscribes on every ConnAck (network drop recovers inside the process);
  Kotlin re-sends its filter set whenever it respawns the helper.
- Kotlin side routes inbound messages to listeners by MQTT filter matching
  (`+`, `#`); filters are ref-counted so N collectors of one filter = one
  broker subscription. Exactly one helper process per hub, spawned and
  recorded under one lock (no TOCTOU window — the leak shape from 07-31 can't
  reoccur by construction). Helper exits on stdin EOF (parent death).
- Keepalive for the hub: **60 s** (was 30 s per connection => ~10 pings/min
  across five connections, now 1/min). Not longer: after a Wi-Fi<->LTE switch
  the dead TCP is only noticed at the next missed ping, so 60 s keeps
  recovery under ~2 min.
- Old `sub` / `pub` subcommands stay in the binary unchanged
  (`volume_daemon.py` and ad-hoc scripts exec the .so directly).
- **Known limitation, accepted:** two *overlapping* filters held at the same
  time (e.g. `a/+` and `a/b`) could make the broker deliver one message twice.
  None of today's filters overlap.
- Tradeoff: publishes are dropped (not queued unboundedly) if the helper's
  channel is full while offline — QoS 0 realtime streams (mouse, gyro) should
  not replay stale input after a reconnect.

**Not decided / follow-up, measure first:** whether the radio is still held
in high-TX power after this lands. Compare `dumpsys batterystats`
mobile_radio mAh over a comparable idle window before/after. Keeping the phone
on Wi-Fi when home is the other lever and is independent of this change.

## 2026-10-15 — Gyro-mode vol-up: "ALL" (gather workspaces) instead of click-hold

**Decided with the user, this session:**

- In gyro mode (Artemis in front, or the AMD-mode toggle) the two hijacked
  volume keys become exactly:
  - **vol-down, held** = gyro mouse, only for as long as it is held (unchanged:
    `android/clutch` ON on down, OFF on up, rotation vector streamed meanwhile).
  - **vol-up, pressed** = the i3 status-bar **ALL** action: move every
    workspace to the output the mouse pointer is on
    (`~/bin/i3-gather-workspaces-current-output.sh`). One-shot on key-down;
    nothing on key-up.
- **Removed:** vol-up as left-mouse-button hold (`android/click` ON/OFF). The
  user does not want any click-hold on the volume keys.
- Mechanism: phone publishes `script:i3-gather-here` to
  `cybersyn/comrade/action`; cybersyn-relay runs
  `~/.config/cybersyn/scripts/i3-gather-here` (wrapper that sets
  `DISPLAY=:0`/`XAUTHORITY` and execs the existing gather script — the relay
  is a user service with no X session). No relay rebuild.
- Gate: "during Artemis streams" needs Artemis-in-front detection, which is
  `ForegroundAccessibilityService` setting `artemisForeground`. That service
  was **not enabled** on the phone (found 2026-10-15); gyro mode had only been
  reachable through the AMD toggle file, which lives in a volatile tmp dir and
  resets to "android" at every reboot. The accessibility service is therefore
  enabled once (a stored setting) so Artemis detection actually works.
  The AMD toggle is left as is.

## 2026-10-15 — AMD-mode toggle no longer drives the volume keys

**Decided with the user:** now that Artemis-in-front detection is live
(`ForegroundAccessibilityService` enabled, see entry above), the gyro-mode
volume-key routing (vol-down held = gyro mouse, vol-up = ALL) depends **only**
on `artemisForeground`. The AMD-mode toggle (`cybersyn-hidmode` file, SpectreBoard
toolbar) no longer turns gyro mode on; it keeps its other job (SpectreBoard
input routed to comrade). The user had the toggle only to get gyro on the keys
without Artemis detection; it also reset at every reboot. `amdMode` stays
in `KeyHijackController` (still set by AutomationService) but no longer feeds
`gyroMode`.

## 2026-10-15 — Correction: AMD-mode flag removed from the key hijack entirely

Follow-up to the entry above. The user's intent (restated): the AMD toggle was
only ever a stand-in for Artemis detection and the accessibility service is
meant to be ON — "drop the flag and do accessibility instead". So rather than
leave `amdMode` as a dead property, Cybersyn no longer reads or watches
`/data/data/com.termux/files/usr/tmp/cybersyn-hidmode` at all
(`KeyHijackController.amdMode`, the FileObserver and `readHidMode()` in
`AutomationService` are deleted). Gyro-mode volume keys = Artemis in front,
via `ForegroundAccessibilityService`, nothing else. SpectreBoard's own toggle
and the file it writes are untouched (it still routes its input to comrade).

## 2026-10-15 — Gyro mouse + ALL move OUT of Cybersyn and INTO Diana (the stream)

**Decided with the user:** the gyro and the vol-up ALL action do not belong in
Cybersyn's system-wide key hijack + accessibility detection. They belong in
the stream app, which already has a high-quality low-latency mouse path and
receives the volume keys directly while streaming. Reverses the two entries
above (Artemis-detection-driven gyro mode):

- Cybersyn: `KeyHijackController` loses gyro mode entirely (`artemisForeground`,
  `gyroMode`, `PressKind.GYRO/GATHER`, `startGyro/stopGyro/gatherWorkspaces`, the
  "gyro" mode push) and `GyroStreamer.kt` is deleted. `ForegroundAccessibilityService`
  no longer touches `artemisForeground`. The accessibility service is turned
  OFF again on the phone (removed from `enabled_accessibility_services`);
  its class stays only for the dormant browser-nav path. The 08-24
  `i3-gather-here` relay script stays (Diana calls it).
- Diana (`~/builds/android/artemis-patch`, see its scope.md): vol-down held =
  gyro MOUSE CURSOR (target chosen by the user: the desktop cursor, not the shader
  camera) as relative mouse moves over the stream; vol-up = publish
  `script:i3-gather-here`. See Diana scope.md for the math/ownership.

## 2026-10-15 — Phone-side remote layer: pads in Termux, a satellite UI, scavenged sensors + haptics (plan)

**Decided with the user, this session.** This is the *Android* half of the
phone→shader control work. The shader half (pad → `u_pad_*` → the shared `mouse_cam`
accumulator) is already built and recorded in `~/builds/amd/shader-backdrop/scope.md`
(two 2026-10-15 entries), `SHADER_REFERENCE.md` §2.8 and `pads/README.md`. DroidPad is
still the sender today (the dumps in `shader-backdrop/pads/` are its exports), but the
user is not committed to keeping it: "we wouldnt necessarily keep it as this specific
app, im constantly figuring this out … ill likely be throwing over the approach a few
times". The aim the whole entry serves: one **general** remote steering every shader
that already takes the Super+cursor steering, and inputs that *steer* (push/pull,
accelerate/slow, lean) instead of hard-overriding movement — "steer what they do not
swim ourselves".

- **Interaction path is the ingest FIFO, and only the FIFO.** User: "correct place to
  interact still is fifo". `$PREFIX/tmp/cybersyn-pub.fifo`, `topic⇥payload`,
  `$PREFIX/bin/cybersyn-send`. Publishing through it reuses Cybersyn's single hub
  connection (`MqttBridge`, commit `b21c426` this same day), and the hub helper
  hardcodes **QoS 0 / retain=false** on both subscribe and publish, so neither is a
  knob. Nothing on the phone opens its own broker connection — `MqttBridge.kt` and
  `ExplainReceiver` now state it outright ("Don't exec libcybersyn-mqtt.so from new
  code — publish through MqttBridge/cybersyn-send").
- **Pads live inside Termux.** User: "inside termux does make the most sense to me".
  Live copy `~/.cybersyn/pads/`, matching the existing phone-side state convention
  (`~/.cybersyn/*`, and the `$PREFIX/tmp/cybersyn-*` state files the Android side
  already reads, e.g. `cybersyn-hidmode`). It is free because Termux on this phone is
  a **system-UID install** (`id` → `uid=1000(system)`, sshd runs as `system`) and the
  satellites are `android.uid.system` — same UID, so both sides read/write directly,
  no shared-storage export and no `RUN_COMMAND` intent needed (though
  `allow-external-apps = true` is already set if that door is ever wanted). SELinux is
  `Permissive` (`enforce=0`, checked this day), so no domain obstacle either. comrade
  already ssh's into that home, so a pad edit is a file write on both sides with **no
  build at all**. `~/builds/amd/shader-backdrop/pads/` stays the git-tracked mirror
  (Termux has no history); `pads/fetch.py` gains a push direction.
- **The remote UI lives in a satellite, not in `:app`.** Satellites are already
  separate APKs in this repo (`:quicktap-stub`, `:cybersyn-stub1`, `:cybersyn-stub2`),
  every one `android.uid.system`; the user's reason: they "decouple building and
  crashes from main". Concretely, a UI crash there cannot drop the hub connection or
  strand automation/kiosk/key-hijack/widget state, and a build is one module instead
  of the 335-Kotlin-file app. `:cybersyn-stub2` is an **unused placeholder**
  (`PlaceholderStubActivity.java`), already installed and listed in
  `crDroid/…/APPS_MANIFEST.md` — the ready slot for a make/select-remotes menu.
  stub1's trackpad lane stays intact.
- **Better joysticks + haptics: scavenge from our own Artemis.** `~/builds/android/artemis-patch`
  (origin `ClassicOldSong/moonlight-android`, remote `marx161`, HEAD this day = "diana:
  volume keys while streaming = gyro mouse"), GPL-3 — so lifting is unencumbered:
  - on-screen elements in `app/src/main/java/com/limelight/binding/input/virtual_controller/`:
    `AnalogStick` / **`AnalogStickFree`** (free = does not self-centre, i.e. the sticky
    behaviour, as opposed to the self-centring wheel we fought with `PAD_STICK_TTL_S`) /
    Left+Right variants / `LeftTrigger` / `RightTrigger` / `DigitalButton` /
    `DigitalPad`, plus a layout-driven keyboard controller;
  - element configs are **JSON in SharedPreferences** with an in-app editor
    (`VirtualControllerConfigurationLoader`: `element.getConfiguration().toString()` →
    `prefEditor.putString(prefKey, …)`, `element.loadConfiguration(JSONObject)`) — a
    working pad-layout schema and editor to borrow or map to;
  - haptics are modern, not a buzz: `VibrationEffect.createOneShot(ms, amplitude)`,
    and `startComposition()` with `PRIMITIVE_CLICK/TICK/THUD` scaled per action
    (`keyboard/KeyBoardController.java:439-459`); touch haptics on engage
    (`sendControllerInputContext(10, 0x11)`); host rumble → per-vibrator effects
    (`ControllerHandler.java:2034-2095`) — a reference for comrade→phone feedback.
- **Broader sensors: SensorSpot, and the one that matters is dynamic.** Installed
  `com.github.umercodez.sensorspot` (GPL-3, Kotlin/Compose/Hilt) is an MQTT sensor
  publisher: `{"type":String,"values":[Float…],"timestamp":Long}` at a selectable rate,
  from `getSensorList(TYPE_ALL)` **+ `getDynamicSensorList(TYPE_ALL)`** (default rate
  200000 µs). The phone's live dynamic sensor right now: `android.sensor.head_tracker(37)`,
  "Christian's Pixel B" over Bluetooth, continuous 10–100 Hz — the class
  `getDefaultSensor()` cannot reach, which is why DroidPad never exposed it. The user's
  uncommitted fork `~/homelab/tmp/sensorspot-src-20260313-1` (`applicationId
  …sensorspot.ht`, versionName `1.16.0-ht`) already fixes exactly that: a synthetic
  type-37 selector entry, `stringType|type` selection with a legacy-int fallback,
  dynamic matching by `(type, stringType, name)`, dedupe of static+dynamic, refresh
  event. Not installed yet and no broker prefs written. **Topic collision to avoid:**
  its default topic `android/sensor` is the topic stub1 publishes its
  `game_rotation_vector` to.
- **Non-goals:** no new sockets or brokers (1883 via the hub only, QoS 0, retain off);
  no second MQTT connection from a satellite; nothing `adb install`ed counted as
  durable (`docs-consolidated/70-crdroid-rom.md` is the gated promotion flow);
  DroidPad's variant pairing stays as documented (`off` switch swaps `<name>.mp` ⇄
  `ctrl_<name>.mp`, and the general pad has no `off`, so it cannot swap); frenzy stays
  unreachable unless the user switches it on; `slider` / `step_slider` stay
  deliberately unwired ("i just put them there in case we want them for sth").

**Open — needs a decision before building:**

1. **Downlink.** A satellite can publish through the FIFO, but nothing sanctioned gets
   *into* one: `MqttBridge` is an in-process object of `:app`, and inbound messages
   route only to in-process listeners. Needed for comrade→phone haptics and for
   "select remote X". Options: `:app` re-emits (broadcast/intent), a second downlink
   FIFO that `:app` writes and the satellite reads, or a bound service. Touch-only
   haptics need none of this, so this can lag the rest.
2. **Where SensorSpot's reader lands.** Folding the reader into the satellite keeps one
   connection and one UID (both the consolidation rule and the crash/build argument),
   but means maintaining that fork inside Cybersyn; keeping the standalone `.ht` app
   means it opens its own broker connection, which
   `docs-consolidated/86-ipc-consolidation-plan.md` exists to prevent.
3. **Pad schema.** Adopt Artemis's element-config JSON as the on-phone format, keep
   `pads/*.json` (profile + widget payloads) canonical, or hold a mapping. The on-phone
   copy has to be *writable by a UI*, which the app's export format was not designed
   for.
4. **Where steering lives.** Today `Look`/`Move` ride the renderer's shared `mouse_cam`,
   so 22 `u_cam_look` / 10 `u_cam_move` / 30 `u_steer` shaders steer with no shader
   edits. Whether that stays the only steering path once the satellite exists is a
   decision, not a default.
5. **Make/select UX.** What "a remote" is in the UI, whether the satellite writes
   `~/.cybersyn/pads/*.json` directly, and what marks one active for the bridge.

## 2026-10-15 — Correction to the remote-layer plan: no downlink, sensors inside the satellite, DroidPad's UI stays

**Follow-up to the entry above, same day, with the user's answers to its open list.**
Items 1–3 were answered; the two still-open ones are renumbered at the end.

- **Item 1 (downlink): withdrawn — the satellite has no reason to receive.** User:
  "why does thr satellite need to receive stuff?" It doesn't, for anything on this
  page. Button-press haptics are local (the Artemis lift below); choosing a remote is
  an *outbound* action (it publishes the choice and writes the live pad files); which
  pad is active is a file the satellite itself owns; sensors are outbound. The entry
  above justified a downlink with "comrade→phone haptics and select remote X" — that
  was wrong and is retracted. The only thing that would ever want an inbound path is
  feedback driven by comrade (e.g. haptics following the audio-reactive backdrop); if
  that is ever wanted, the decision is a relay in `:app` (broadcast/intent, a second
  downlink FIFO, or a bound service), because a satellite may not open its own hub
  connection. Not needed now, so not a blocker.
- **Item 2 (SensorSpot): decided — the reader goes inside the satellite.** User:
  "definitely inside the satellite thats why i brought it up — i want one place thats
  the remote". So one app *is* the remote: the pad surface, the sensor reader (static
  + dynamic, `android.sensor.head_tracker(37)` included) and the haptics. That also
  keeps the one-MQTT-connection rule for free — the satellite is `android.uid.system`
  and publishes through `cybersyn-send` / the ingest FIFO like everything else.
- **Item 3 (pad schema): decided — DroidPad's UI and format stay.** User: "we keep
  the droidpad ui for sure i just wanted to lift the snappier joystick and the great
  haptic feedback for button presses". So no Artemis layout JSON becomes the on-phone
  format and no editor is rewritten: DroidPad keeps authoring pads, and `pads/*.json`
  (plus the Termux live copy) stays the profile/widget contract. From Artemis we lift
  only *feel*: the analog stick's input handling (`AnalogStick`/`AnalogStickFree` —
  150 ms deadzone lift after touch-down, 350 ms double-click window, free =
  non-self-centring) and the press haptics
  (`VibrationEffect.startComposition()` with `PRIMITIVE_CLICK/TICK/THUD`,
  `keyboard/KeyBoardController.java:439-459`).
  - Read that as: the satellite is the surface you *use* — it renders the pad's
    widgets with that feel and those haptics — while DroidPad remains the *authoring*
    UI whose exports are the pad definitions. If instead DroidPad is meant to keep
    being the thing that sends input, then there is nothing for the joystick feel to be
    lifted *into*, and the satellite is only the menu + sensors; confirm if so.

**Still open:** 4 — whether steering keeps riding the renderer's shared `mouse_cam`
(today: 22 `u_cam_look` / 10 `u_cam_move` / 30 `u_steer` shaders, no shader edits) or
moves into the satellite. 5 — *selecting* a remote (and any per-pad tweaks) in the
satellite's UI, since **making** stayed with DroidPad.

## 2026-10-15 — Plan closed: steering and remote selection both stay exactly as they are

**The last two open items, answered the same way — don't touch either.** This entry
closes the remote-layer plan; nothing else in the two entries above changes.

- **Item 4 (steering): unchanged.** User: "why would we change steering we already
  built 3 remotes working like this". The three are the profiles in
  `~/builds/amd/shader-backdrop/pads/` (`brown_dwarf`, `general`, `mycontrolpad-2` —
  the worms pad), and all of them steer through the renderer's shared `mouse_cam`
  accumulator (`u_cam_look` / `u_cam_move` / `u_steer`). No steering code moves into the
  satellite, and no shader edits.
- **Item 5 (selection): unchanged.** User: "the selection works same as now through the
  ui". No new selector, no new topic, no picker to design — whatever the UI does today
  keeps doing it.
- That also makes the previous entry's "authoring vs sending" footnote moot at this
  level: either way, selection and steering are untouched. What is being *kept* is
  DroidPad's UI and the `pads/*.json` profile/widget contract; what is being *lifted
  from Artemis* is widget-level feel only (the stick's handling) plus press haptics.
- **Status: no open items.** Pad definitions live in Termux with this repo's
  `scope.md` mirror, the satellite is the one place that is the remote (pad surface +
  sensor reader + haptics), publishing goes only through the ingest FIFO (QoS 0, no
  retain, one hub connection), sensors come from the SensorSpot reader inside that
  satellite, haptics and stick feel come from the Artemis lift, steering stays in the
  renderer, and selection stays in the UI.

## 2026-10-15 — Built: `cybersyn-stub2` is the remote (first increment, testable now)

The placeholder slot is gone; the module is the remote surface, installed on the Pixel as a
temporary system-app update. Everything below is verified live, not designed.

**Naming and identity mirror the rest, exactly as asked.** Module `cybersyn-stub2`, package
`com.termux.cybersyn.stub2`, `android:sharedUserId="android.uid.system"`, label
`Cybersyn Stub 2`, activity `.RemoteActivity` replacing `.PlaceholderStubActivity`. The
APPS_MANIFEST row (`CybersynStub2`, priv-app, signer `ce89479e2858b65aa2131836ab4c2d16aa09284f2b40fb002620ad1aec91b124`)
is untouched and the built APK's signer matches it, which is what let
`blazer-sysapp-update install com.termux.cybersyn.stub2 <apk>` verify and go straight in
(`UPDATED_SYSTEM_APP`, old APK still underneath, `rollback` =
`uninstall-system-updates`). **This is an adb-tested overlay, not a ROM promotion** —
promoting means dropping the source into `Android_src` and rebuilding the ROM, a separate
gated flow.

**The wire is DroidPad's, unchanged — nothing on comrade changed.** The satellite writes
`DroidPad/Events<TAB>{"id":…,"type":…,…}` into the ingest FIFO
(`/data/data/com.termux/files/usr/tmp/cybersyn-pub.fifo`), the same topic and the same
payload shapes the app itself publishes, so `droidpad-shader-bridge` → `pad.json` → the
renderer's `u_pad_*` uniforms consume it without a line of change. The fd is opened
`O_RDWR` (the `cybersyn-send` trick, so `open()` never blocks if Cybersyn is respawning)
and reopened once on failure. No socket, no second hub connection, QoS 0 / no retain —
`86-ipc-consolidation-plan.md` holds.

**Pads are files, in Termux, as planned.** The live copy is
`/data/data/com.termux/files/home/.cybersyn/pads/*.json` (the app's own export format,
`controlPad` + `controlPadItems`, `properties` as a nested JSON string, colours as Compose
`Color.toULong()` in the high 32 bits), read at launch and on demand. `pads/fetch.py`
gained the push direction (`--push`, copy-only, default `--live-dir` absolute) so the
git-tracked mirror stays the source of truth and the phone copy stops drifting silently.
An extra `pad` string launches straight into one profile for the test loop:
`am start -n com.termux.cybersyn.stub2/.RemoteActivity --es pad brown_dwarf.json`.

**Widgets driven: JOYSTICK, BUTTON, DPAD, SWITCH. The rest are drawn inert on purpose.**
`SLIDER` / `STEP_SLIDER` publish raw values nothing consumes (the renderer's own contract
says so), `GAUGE` / `LED` are outputs, and `STEERING_WHEEL` (roll) is the next one to wire —
each is drawn where the pad puts it with its type as a caption, so a gap is visible rather
than silent. On the three real pads that is 9/13 (brown Dwarf), 8/12 (MyControlPad), 5/8
(general) driven; the header shows the count.

**The stick is DroidPad's contract with Artemis's handling.** Contract: x/y in −1..1 with
+y UP, and — because the app only ever sent a 0 on drag END — a release *always* publishes
`0,0`, since the renderer's `PAD_STICK_TTL_S` treats a quiet stick as rest. Handling, lifted
from `AnalogStick`: the touch position *is* the deflection (no hunting for the handle, the
"snappier" bit), the deadzone releases after 150 ms of holding so precise movement is
possible, and once active it stays active back through the deadzone. Publishes are capped
at ~66 Hz with a small epsilon — `pad.json` only needs the latest value.

**Press haptics are local and typed** (`VibrationEffect.startComposition()`:
`PRIMITIVE_TICK`→`CLICK` for a button, a 10 ms one-shot when a stick engages, `THUD` pair
for a heavy hit), degrading to a one-shot where primitives are unsupported — the
`KeyBoardController.java:439-459` lift. **A loaded pad publishes nothing until touched**:
switches start off and only explicit intent may latch one. Presses are never gated by any
switch (steer what they do, don't swim ourselves).

**Verified live (2026-10-15, Pixel over adb + the broker).** With a `probe` fixture whose
ids the renderer does not know (so nothing on the shader side could move), a broker capture
saw: switch `true` then `false`; button `PRESS`/`RELEASE`; dpad `UP` `PRESS`/`RELEASE`;
stick taps at +40/+80/+120/+160 px → `0.227/0.467/0.706/0.946` (linear, implied radius
~170 px ≈ the drawn circle at `density 2.5`), +200 px clamping to `0.998`; a centre tap
publishing nothing on press and `0,0` on release; a touch starting away from the stick
producing no stick events. `pad.json` on comrade folded every one of them in. The probe pad
stays on the phone as a permanent wire probe — it is harmless everywhere else.

**Not done yet (no decision needed to continue):** the steering wheel's roll, sliders if
they ever get a consumer, and folding the SensorSpot reader inside the satellite (`~/.ht`
fork, uncommitted) — one place that is the remote still means the sensors come in here.
Selection stays local: the header's chooser plus a remembered last pad, which is the
"selection works same as now through the ui" answer, and nothing new is published for it.

**Tree state:** the module is written, built and installed, but nothing is committed —
`scope.md` plus a modified `cybersyn-stub2` manifest and the new sources are uncommitted in
`~/builds/crossplatform/Cybersyn`, and `pads/fetch.py` is uncommitted in
`~/builds/amd/shader-backdrop`.

## 2026-10-16 — Built: the sensors are in the satellite (second increment, verified live)

The remote now reads the phone's own sensors. One place is still the remote — same module, same
FIFO, no second app and no new socket — and, as decided, **the sensors are publishers for lanes
that already exist rather than new wire**. Nothing on comrade changed for either lane.

**The camera lane reuses the gyromouse exactly.** `SensorLane` registers `TYPE_GAME_ROTATION_VECTOR`
(50 Hz requested, publishes every ~18 ms), reconstructs `w = sqrt(1 - x² - y² - z²)` and publishes
`android/sensor` = `{"type":"android.sensor.game_rotation_vector","values":[x,y,z,w]}` plus
`android/clutch` `ON`/`OFF` — the shape `SensorSpot` used and the shape `cybersyn-hid-relay.py`
already parses. That relay writes `gyro_state.txt`, which `renderer.py`'s existing gyro block turns
into the shared `mouse_cam` yaw/pitch and `u_steer`, so **every mouse-steering shader follows the
phone with no shader edit**. `ON` is the calibration the relay already implements: arming starts
from where the phone is, it does not yank the view. A dynamic head tracker
(`android.sensor.head_tracker`, present only while a BT head-tracking device is connected) is
offered as an alternative source when it exists; it does not on this phone today.

**The tilt lane fills the one widget this remote could not drive.** The phone's roll is published as
the pad's *own* `STEERING_WHEEL` id — `{"id","type":"STEERING_WHEEL","angle"}` in degrees, the same
event a finger would send — and `renderer.py` already applies wheel angles as deltas to
`mouse_cam["roll"]`, an axis no stick can reach. The angle uses the relay's own
`get_angles()` roll formula, so the two ends agree about which way is which; releasing the lane
publishes `0`, which is the unwind the app's own `selfCentering` animation does. Because the lane
speaks for *the pad's* wheel, it needs the pad to carry one: **`general.json` is the only profile of
the three that does**, and on a pad without a wheel the lane reports itself unavailable instead of
quietly publishing some other id.

**Arming is explicit, and dropping out is automatic.** Three ways in: the header's `sensors:` panel
(two truthful rows — orientation → camera, tilt → wheel, plus the source row), reserved pad ids
(`SWITCH` / `gyro` / `tilt`; a reserved switch arms a lane locally and publishes nothing to
`DroidPad/Events`), and `am start … --es arm gyro,tilt` for the sysapp test loop (consumed once, so
a relaunch never silently re-arms). Sensors are registered *only* while a lane is armed, and every
lane is dropped on `onPause` — background, screen off, or another app taking the surface. A
proximity interlock is armed with the lanes but deliberately paranoid: it only trusts the sensor
after it has reported "far" once, so a proximity sensor that only ever says "near" can never mute
the lanes.

**Verified live (2026-10-16 ~00:22, Pixel over adb + the broker).** With the extended `probe` pad
(a `probe_wheel` no shader knows, plus `gyro`/`tilt` switches — pushed to the phone as the permanent
wire probe), `--es arm gyro,tilt` produced: `android/clutch` `ON` → relay journal
`State: ACTIVE (shader gyro)`; `android/sensor` game-rotation samples at ~48 Hz (334 in 7 s, all
four values); `gyro_state.txt` freshly written with `active=1`; `DroidPad/Events`
`{"id":"probe_wheel","type":"STEERING_WHEEL","angle":16.19}` at ~the relay's roll for the same
quaternion (checked by hand: `atan2(2(wx+yz), 1-2(x²+y²))` = 16.19° — the same number to the digit).
Leaving the surface — the user swiped up out of the remote into Recents ~10 s later — produced the
unwind `{"angle":0}` and `android/clutch OFF` → `State: SAFE (shader gyro)`, with
`gyro_state.txt` written `active=0`. That was the pause interlock doing its job, unplanned.

**Not yet exercised, no decision needed:** the reserved-id switches' taps and the wheel's own touch
handling (its self-centring animation, and taking the wheel from the sensor while armed) are built
but were not tapped before the surface was left — they want a finger on the pad. The proximity
interlock is armed but has never fired (the sensor has not reported covered since). `TILT_ROLL_SIGN`
in `SensorLane` is the one place to flip the tilt if a hand test says "right edge down" rolls the
wrong way; the sign chosen is the one that matches the app's own clockwise-positive wheel.

**Not done, deliberately:** ambient light as a mood input and barometer → `mouse_cam["u"]` need
renderer-side consumers, so they stay unwired; `SLIDER`/`STEP_SLIDER` remain unwired by the
renderer's own contract.

**Tree state:** still nothing committed. New in `cybersyn-stub2`: `SensorLane.java`,
`WheelWidget.java`, the reserved-id roles in `SwitchWidget`, the sensor binding + wheel factory in
`PadView`, the header panel / `arm` extra / lifecycle disarm in `RemoteActivity`, a topic
parameter on `PadPublisher.publish`, an invalidate hook on `Widget`, and the extended `probe.json`
(+ `pads/README.md`'s new *Reserved ids* section in `~/builds/amd/shader-backdrop`). Installed as a
temporary system-app update (`blazer-sysapp-update install com.termux.cybersyn.stub2`, ledgered);
**adb-tested overlay, not a ROM promotion** — that stays the separate gated flow.

## 2026-10-16 (later) — cybersyn-stub2 is a full pad SURFACE: arrange mode, one finger per widget, the variant control

Shipped as **versionCode 3**, installed the same day as a temporary system-app update
(`blazer-sysapp-update install com.termux.cybersyn.stub2`, `lastUpdateTime` 2026-10-16 02:07) —
`UPDATED_SYSTEM_APP` overlay, **not** a ROM promotion. The remote is the satellite: it reads the
pad export, draws its own surface, and publishes ONLY through the ingest FIFO.

- **Surface (new files)**: `PadProfile` (the app's exported JSON → items), `PadLayout` (pad coords →
  screen), `PadView` (touch), one class per widget — `StickWidget`, `SliderWidget`, `SwitchWidget`,
  `ButtonWidget`, `DpadWidget`, `WheelWidget`, `InertWidget` — plus `Haptics`, `PadPublisher`,
  `SensorLane`. The old `PlaceholderStubActivity` is deleted.
- **Arrange mode**: a header button. Each drop is written as a per-surface override
  (`PadProfile.overrides`) as it lands, so an arrangement survives a pad reload; a freshly loaded pad
  always starts in driving mode.
- **One finger per widget**: touches are captured by pointer id (`PadView.capture`), so two sticks
  drive at once and a second finger never steals the first one's widget. Arrange mode still places
  one widget at a time.
- **Variant control (the `ctrl_` half)**: the header button OR a reserved `off` widget — a `SWITCH`
  named `off`, or a two-stop `STEP_SLIDER` named `off` whose ends read AUTO/REMOTE under the thumb.
  Both publish the same `{"id":"off","type":"SWITCH","state":bool}` that the renderer already swaps
  `<name>.mp` ⇄ `ctrl_<name>.mp` on; header and pad control mirror each other
  (`Widget.stateListener` / `PadView.setOnVariantChanged`). Verified live 2026-10-16: taps drove
  `ctrl_lagoon_worms_v6.mp` ⇄ `lagoon_worms_v6.mp` with matching bridge log lines.
- **Publish discipline** (unchanged, re-verified): nothing is published when a pad loads; a
  `STEP_SLIDER` goes out as `"type":"SLIDER"` like any other; `GAUGE`/`LED` can never publish.
  Everything else goes to `DroidPad/Events` through `$PREFIX/tmp/cybersyn-pub.fifo`, held open
  `O_RDWR` so a hub respawn never blocks a publish (a replaced FIFO is retried once, then dropped —
  `PadPublisher`).
- **No persistent flag**: the remote takes no foreground service of its own; the sensor lanes disarm
  on `onPause` (entry above), and the pad surface is expected to be the visible app while it drives.
- **Quick tap** now opens this remote — see `60-pixel-blazer.md`, "Quick tap (double-tap back)".

Deliberately NOT written down: a hub-side keep-alive/whitelist change was attributed to the same day
but is not in the committed app tree, so it needs the device (or the user) to say where it lives
before it goes in here.

**Tree state:** still nothing committed in `cybersyn-stub2` — 13 new widget/profile classes plus
`probe.json` untracked, `build.gradle.kts` / `AndroidManifest.xml` / `scope.md` modified.

## 2026-10-18 — PROPOSED (awaiting user OK): home Wi-Fi band steering + mobile-data auto switch

**Why:** Sunshine/Artemis on the Pixel complained "slow connection". Measured 2026-10-18: the
Pixel sat on 2.4 GHz (ch 11) of the home SSID although the
5 GHz radio (5260 MHz / ch 52 DFS, ~-64 dBm) was visible; ping comrade→Pixel
during a 28.5 Mbps stream = 40% loss, 2–4 s RTT; ~1/3 TX retries. Android never roams off a
"good enough" network on its own. User keeps mobile data OFF at home by hand.

**Behaviour as proposed (nothing built yet):**
1. *Home detected* — connected to the home SSID (either band), stable 10 s →
   mobile data OFF.
2. *Home lost* — not connected to it for 60 s continuous → mobile data ON. The 60 s grace
   covers the ~2 s drop a band switch itself causes, so steering never flaps mobile data.
3. *Steer up* — at home, on 2.4 GHz, 5 GHz BSSID seen at ≥ -72 dBm → reconnect pinned to the
   5 GHz BSSID (`cmd wifi connect-network … -b <bssid>`).
4. *Fall back* — on 5 GHz and RSSI < -80 dBm for 20 s, or 5 GHz BSSID gone from scans →
   drop the pin and join 2.4 GHz. Return to 5 GHz only after ≥ -72 dBm for 30 s (hysteresis);
   at most one band switch per 2 min.
5. *Mechanism* — one Termux shell script run as a Cybersyn task (same pattern as the existing
   watchdog profiles), triggered on Wi-Fi state change plus a light poll ONLY while on home
   Wi-Fi. Uses Android's cached scan results; no forced scans except to confirm a fallback.
   Mindful of the open TIME_TICK wakelock cost (see memory project_cybersyn_time_tick_energy_impact).

**Open, for the user:** (a) switch band while a Moonlight/Artemis stream is running, or wait
until it ends? (b) thresholds -72 / -80 dBm OK? (c) any other home SSIDs?

## 2026-10-18 (later) — band-steering mechanism changed: Cybersyn task reads stdout itself, no script

User pointed out Cybersyn is part of the Termux family and can read command stdout directly.
Point 5 above is replaced: **no separate Termux script.** The logic is a Cybersyn task built from
existing actions — shell step (stdout captured into `<prefix>_stdout`, `core/actions/ScriptActions.kt`),
text-match steps, `flow.if/else`, `mobile.toggle`, `var.persist` for hysteresis timers.
Verified live 2026-10-18: `cmd wifi status` / `list-scan-results` return NOTHING when run as
Cybersyn's own UID 1000 (system_app); they must go through `su -c` (root works, same as the
existing `mobile.toggle` root path). Behaviour points 1–4 and the open questions are unchanged.

## 2026-10-18 (decision) — band steering: OPTION 2 (native actions, no root), defaults accepted

User chose **native Cybersyn actions using Android's Wi-Fi/telephony APIs as the UID-1000 system app**
(no `su`, no `cmd wifi`). Rebuild + temporary system-app update on blazer (not a ROM promotion).
User had no opinion on the switch policy ("never thought about it until my stream collapsed") →
the proposed defaults stand: up to 5 GHz at ≥ -72 dBm (allowed mid-stream), fall back below
-80 dBm for 20 s (or 5 GHz gone), 30 s good before returning, ≤ 1 band switch / 2 min,
mobile data OFF after 10 s on home Wi-Fi, ON after 60 s without it.
The home SSID is the **only** home network (single router, no mesh nodes).
Mid-stream detail ("defer fallback until stream ends") dropped for v1: a 5 GHz link that is
genuinely failing is worse for the stream than the 2 s switch, so fallback is not deferred.

## 2026-10-18 (built + verified live) — HomeWifiSteerer, Cybersyn 0.2.80 (versionCode 83)

**Shape as built (one deviation from the entries above, flagged to the user):** the timing policy
(10 s / 20 s / 30 s / 60 s / 2 min windows) lives in a native controller
`core/network/HomeWifiSteerer.kt`, not in task steps — profiles have no delays, and doing the
windows in tasks would have needed the TIME_TICK poll. A task only switches it:
`wifi.home_steer state=on|off|status` (`examples/home-wifi-steer.yaml` — real SSID only in the gitignored `examples/home-wifi-steer.local.yaml` — tasks
"Home Wi-Fi steer: on/off/status"). The on/off choice + ssid/thresholds persist in
SharedPreferences `home_wifi_steer` and `AutomationService.onCreate` restores it.
5 s poll via coroutine delay (no wakelock); own `startScan()` at most once per 60 s, only while
on 2.4 GHz at home. Radios are discovered from scans by SSID (no hard-coded BSSIDs).
Band switch = pin the saved config's BSSID (`updateNetwork` + `enableNetwork`, then
`disconnect`/`reconnect`). Fallback pins the 2.4 GHz BSSID rather than unpinning (clearing a pin
via updateNetwork is not reliable: a null BSSID is "unchanged"); when disconnected and the
pinned radio is not visible, the pin moves to a visible radio so the phone can join at all.
Mobile data only turns back ON if the controller turned it OFF (`mobile_off_by_us`).
Also: `wifi.info` (link → variables), `wifi.connect` (pin ssid→bssid), `mobile.toggle` now tries
`TelephonyManager.setDataEnabledForReason(USER)` before the root `svc data` fallback;
device-state keys `wifi_band` / `wifi_bssid` published on change. All UID 1000, no root.

**Verified live 2026-10-18 on blazer:**
- on → 2.4 GHz (-55 dBm) moved to 5 GHz (-67 dBm) within 10 s.
- 5 GHz under a saturating SSH push: 150 MB in 3.0 s ≈ 397 Mbit/s, ping 0 % loss
  (before, on 2.4 GHz during a 28.5 Mbps stream: 40 % loss, 2–4 s RTT).
- Fallback forced with down_dbm=-40: 5→2.4 GHz ~25 s after crossing; restored -80 → back to
  5 GHz at the 2-min switch limit (21:19:07).
- Wi-Fi off 90 s (`svc wifi disable`): `mobile_data1` 0→1 at +62 s, Wi-Fi back → rejoined
  5 GHz, `mobile_data1` 1→0 10 s later. (Note: the per-SIM key `mobile_data1` is the real one;
  global `mobile_data` did not change.)
- Side effect: each band switch re-randomises the MAC (network uses non-persistent
  randomisation) → new DHCP IP (192.168.0.165 → .6). Tailscale follows it; LAN-IP-pinned
  things would not.
- Leftover: a CLI test task "Home Wi-Fi steer: on (test)" was overwritten to `state=status`
  (no CLI delete exists); delete it in the app UI if wanted.
Not yet tested: walking out of 5 GHz range for real, and a live Moonlight stream on 5 GHz.

Privacy (2026-10-18): the home SSID and router BSSIDs are deliberately kept out of this public
repo (user request) — they were redacted from these entries before the first push. Real values
live only in the gitignored `examples/home-wifi-steer.local.yaml` and in the homelab docs.

## 2026-10-20 — Media card: thumbs up/down + working scrub

Decisions with the user (this session):
- **Scrub fix (DONE, relay only):** the phone's `onSeekTo` publishes `mpris.position:<secs>`; the relay's
  dot-prefix path (`mpris.<sub>`) split at the first ':' and dropped the argument, so every scrub ran
  `playerctl position ""` and failed. Fixed in `Fedora_src/cybersyn-relay/src/main.rs` (re-join `sub:arg`).
- **Thumbs buttons** = two `PlaybackState` custom actions on the mirrored "CybersynMedia" session
  (shown by the SystemUI media card), only for players playing a local `file://` track.
  - 👎 **does NOT skip** (user: so a fat-fingered tap can be undone). It toggles the track's
    `.excluded` marker in `~/.local/share/shader-backdrop/not_music/` (same marker `shader-music exclude`
    writes: never computed, never shuffled). Tapping again removes it.
  - 👍 toggles the track's line in `~/.local/share/shader-backdrop/favs.m3u` (new favourites playlist);
    `shader-music favs` shuffles just that list.
  - Up and down are mutually exclusive (setting one clears the other).
- Phone -> desktop path: `cybersyn/comrade/action` = `script:shader-music thumb-up|thumb-down`
  (relay script dispatch; `shader-music` symlinked into `~/.config/cybersyn/scripts/`).
- Relay publishes `thumb_up` / `thumb_down` booleans per file:// player in the media status so the
  phone can show the current state (filled vs dim icon).
- Deploy of the app is via `blazer-sysapp-update install` (temp tier; promote only at OTA time).

### 2026-10-20 (later) — thumbs + scrub: BUILT and verified live
- Relay `mpris.rs`: `xesam:url` now collected; `thumb_up`/`thumb_down` published for file:// players.
  `cybersyn-relay` redeployed to `~/.local/bin` (backup `cybersyn-relay.bak-20261020`).
- `~/bin/shader-music`: new `thumb-up`, `thumb-down` (toggles, mutually exclusive), `favs` (shuffle favourites).
  Backup `~/bin/shader-music.bak-20261020`; symlinked into `~/.config/cybersyn/scripts/`.
- App 0.2.80 (`MediaContextSource.kt` custom actions + `ic_thumb_{up,down}_{on,off}.xml`) installed via
  `blazer-sysapp-update` (temp tier, NOT promoted). `dumpsys media_session` shows both custom actions;
  `script:shader-music thumb-up` over MQTT toggled the favourite and flipped the phone's icon id on/off.
- Not yet verified: a real finger tap on the card, and a real scrub drag (only the MQTT path was driven).
