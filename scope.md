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
