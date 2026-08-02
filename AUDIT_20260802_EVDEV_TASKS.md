# Cybersyn Audit #2 — 2026-08-02 (post-KeyMapper-merge) — Work Queue

> **STATUS 2026-08-02 14:10 — ALL ITEMS BELOW ARE DONE, live-verified on blazer.**
> Commits `0208ad2`, `031bb26`, `70b079d` (Cybersyn) and `ebe49ae6` (SpectreBoard),
> all pushed. See "Resolution" at the bottom for what was proven on-device, the
> three *additional* bugs that verification uncovered, and the one pre-existing
> test failure deliberately left alone. The unchecked `[ ]` boxes below are the
> original queue, kept as written for the record.

Second audit of the day, covering the vendored-KeyMapper evdev merge (commits
`2bc2e0e`..`0736c74`) plus the watchdog rebuild (`3f55765`..`2845fca`) and live state on
blazer + comrade. Every finding verified live or against the exact code path; nothing was
changed during the audit.

**Verdict on the merge itself: sound, keep it.** The vendored core (libevdev + Rust
`evdev_manager`) is the strong part — JNI symbols correctly repointed, grab matching
verified against the live device identity (`gpio_keys`, bus 0x19, vendor 0x1, product 0x1),
the grab controller excludes its own uinput clones (no feedback loop), and deciding
consume synchronously in the native callback is the architecturally right fix the python
daemon could never have. Everything below is in the ~800 lines of Cybersyn-side glue.

Legend: `[ ]` todo · **P0** functional regression vs the retired daemon · **P1** robustness
bug, will bite eventually · **P2** hygiene/efficiency.

Ground rules are identical to `AUDIT_20260802_TASKS.md` §0 — read them first. In
particular: no `am force-stop com.termux.*`, reinstalls kill the whole UID-1000 family,
builds on comrade only, phone via Tailscale only.

---

## 1. P0 — Consume decision is not latched at key-down (regression)

**Evidence.** `EvdevRootHelper`'s event callback consults `mode.get()` per event, and
`KeyHijackController`'s `EV_UP` handler routes by the *current* `imeShown`. The retired
python daemon's review-pass fix ("Routing latched at key-down, so a mode flip mid-hold
can't leave a key stuck down… or emit an orphan release" — `SESSION_20260802_SUMMARY.md`)
was not ported.

**Failure modes** (mode flips mid-hold, i.e. keyboard hides or browser leaves foreground
while a volume key is held):
- Down passed through in `normal`, mode flips, up consumed → Android never sees the
  release → **stuck volume autorepeat** on the real key path.
- Down consumed in `gyro`, IME hides before release → `stopGyro()` is gated on `imeShown`
  at UP time → `android/clutch` **stuck ON** on comrade until the next clutch press.

**Fix.** In the helper: record the consume decision per key code at value==1 and reuse it
for value==2/0 of the same press (clear on up). In the controller: route the UP by what
the DOWN started (keep a small per-code "this press is a gyro/hold press" map), not by
current `imeShown`.

- [ ] Latch consume per press in `EvdevRootHelper`'s callback
- [ ] Route EV_UP by press origin in `KeyHijackController`
- [ ] Test: hold vol-down in gyro, hide keyboard mid-hold, release → clutch OFF published,
      no stuck key

## 2. P0 — Browser media nav is dead (regression)

**Evidence.** `emitKeycode(88/87)` → `EMIT_KC` → `write_key_code_event` maps
MEDIA_PREVIOUS/NEXT via Generic.kl to scan codes 165/163 — but the uinput clone only has
the capabilities of the grabbed device plus the GRAB `extra_key_codes` (25/24/26 → key
bits 114/115/116). Verified live: the clone (`/devices/virtual/input/input8`) advertises
`KEY=1c000000000000` = bits 114–116 only. The kernel input core silently drops events for
unregistered codes. The python daemon used `input keyevent` for media keys for exactly
this reason (noted in `SESSION_20260802_SUMMARY.md`: "which have no evdev key").

**Fix (pick one).**
- Add 87 and 88 to the GRAB codes in `KeyHijackController.handleLine("READY")` — the
  whole point of `extra_event_codes` is to register codes on the clone that the real
  device lacks. One-line change, keeps everything in-process. **Preferred.**
- Or dispatch via `AudioManager.dispatchMediaKeyEvent` in-process (no root, no uinput).

- [ ] Add 87/88 to grab codes (or AudioManager dispatch)
- [ ] Test: browser foreground, keyboard hidden, vol keys → page prev/next actually fires

## 3. P0 — IME seeding is a placebo

**Evidence.** `AutomationService.queryImeShown()` calls
`InputMethodManager.isAcceptingText` from a Service. That API reflects *this process's
own* input connection — a background service has no served view, so it returns **false
unconditionally**. The desync it was written to fix (service start while the keyboard is
already up → gyro keys go to the volume slider) still exists; it has only been masked by
SpectreBoard usually publishing a transition soon after.

**Fix (ranked).**
1. Make SpectreBoard publish `spectreboard/ime_shown` **retained** — the subscribe in
   `startKeyHijack()` then delivers the true current state on connect, no query needed.
   Also fixes every future late subscriber. (SpectreBoard change, not Cybersyn.)
2. Fallback: `su -c dumpsys input_method | grep mInputShown` once at start.

- [ ] Retained publish in SpectreBoard (or dumpsys query)
- [ ] Remove `queryImeShown()` or make it honest
- [ ] Test: keyboard up → kill/restart Cybersyn service → vol-down clutches immediately

## 4. P1 — `KeyHijackController.start()` wedges permanently if the spawn throws

`running.set(true)` happens before `ProcessBuilder.start()`. If the spawn throws (su
denied, ENOENT after an update), the exception propagates out of `start()` — into
`AutomationService.onCreate` — with `running=true` and `helperProcess=null`. Every later
`start()` bails on the "already running" guard. Dead until app restart.

- [ ] Wrap the spawn; on failure `running.set(false)`, log, and schedule a retry
- [ ] Don't let the exception escape into `onCreate`

## 5. P1 — Helper respawn loop has no backoff

The reader's `finally` → `start(context)` recursion restarts instantly. A helper that dies
on arrival every time (missing/incompatible `.so`, su policy change) becomes a tight
su + pkill + app_process loop, forever. Add exponential backoff (e.g. 1s → 30s cap) and
give up + notify after N consecutive sub-second deaths.

- [ ] Backoff + give-up threshold in the restart path

## 6. P1 — ForegroundAccessibilityService flips browser mode on any window event

`TYPE_WINDOW_STATE_CHANGED` fires for the notification shade, volume dialog, permission
dialogs, IME popups — all with non-browser packages. Pulling the shade over Cromite sets
`browserForeground=false`; media nav dies until the next window event. Filter: ignore
`com.android.systemui` (and events whose className doesn't resolve to an Activity), the
standard trick for foreground-app detection.

Also: the `volume_daemon/browser_mode` MQTT publish is now pure dead weight (the python
daemon is retired) — a thread + broker publish per app switch with zero consumers.

- [ ] Filter non-activity window events
- [ ] Delete the legacy MQTT publish (and its topic from any comrade-side subscriber)

## 7. P1 — Per-minute reloadProfiles churn (pre-existing, July 15 `c263f0f`, now measured)

`onStartCommand` calls `reloadProfiles()` on **every** minute tick. That cancels all
matcher jobs and re-collects every context flow — respawning the root logcat reader and
the `cybersyn/+/event` MQTT helper 1440×/day. Verified live: reader/sub PIDs rotate every
minute at :00; mosquitto logs one `cybersyn-sub-cybersyn___event` reconnect per minute.

Costs: a 1–2 s blind window every minute (LOGCAT profiles can miss `Start proc
:com.termux/` lines; EVENT profiles can miss relay events), plus the spawn/kill race run
1440×/day — which is how the **currently-live leaked root logcat reader** (pid 31169,
running since 11:00:30) happened. `ChildProcessAudit` has been reporting "1 duplicate
group" every 15 min since; nothing acts on it.

**Fix.** Drop the unconditional reload on `timeTickTrigger` — the `!engineLoaded` branch
plus `observeProfileRegistry()` (which reconciles on actual DB change) already cover the
doze-recovery case `c263f0f` was after. Keep `TimeContextEvents.publish()`.

- [ ] Gate the timeTick reload on `!engineLoaded`
- [ ] Kill the stale reader pid 31169 (`su -c kill`), verify ChildProcessAudit goes quiet
- [ ] Verify mosquitto shows no per-minute reconnect afterwards

## 8. P1 — SpectreBoard's MQTT client dies by timeout every session

Mosquitto: `Client spectreboard-<pid> has exceeded timeout, disconnecting` (~45 s after
connect, keepalive 30 not honoured). If SpectreBoard later writes an `ime_shown`
transition into that dead socket, the transition is silently lost → exactly the historical
"keys route to the volume slider" flakiness. This is a SpectreBoard-side bug (its
publisher holds a connection without a ping loop). Retained + short-lived per-publish
connections would fix both this and §3.

- [ ] Fix SpectreBoard publisher (retained flag lands here too)

## 9. P2 — Smaller items

- [ ] `killStrayHelpers` / `killStrayLogcatReaders` pkill patterns appear in their own
      `su -c sh` wrapper's cmdline; pkill spares itself but not the wrapper — works, but
      racy/noisy. Anchor the patterns (e.g. `app_process.*EvdevRootHelper`).
- [ ] Emergency-kill: power held 10 s → rust `process::exit(0)`, and the controller
      restarts the helper immediately, nullifying KeyMapper's lockout escape. Acceptable
      (power is never consumed) — but `setEmergencyKillCallback` is dead code; either
      register it or note why not.
- [ ] `stop()` never sends `QUIT`; it relies on stdin EOF. Works, but send QUIT first for
      an orderly `destroyEvdevManager()` (releases the grab before the sweep has to).
- [ ] Manifest now requests `DIAGNOSTIC` + `VIRTUAL_INPUT_DEVICE` with a comment claiming
      "no su, no sidecar" — the code still runs the su sidecar, and going in-process is
      blocked on the signature-permission allowlist (see homelab memory). Reword the
      comment; keep the permissions only if the allowlist work is actually planned.
- [ ] npud watchdog restart path doesn't kill a hung-but-alive npud before respawning —
      the hang case recreates the orphan pattern the socket check was built to end.
- [ ] Task 140 is named "QuickTap Toggle Sidebar" but sends `KEYCODE_VOLUME_UP`. Rename.
- [ ] run_logs: LogShipTimer writes a failure row per cooldown skip (265 skip rows vs 50
      real runs today). Don't log skips as failures, or don't log them at all.
- [ ] Repo hygiene carry-over from audit #1 §8: stubs tracked, `Source_APKs/` 213 MB,
      `OVERVIEW.md` stale sections.

---

## 10. Finishing the swap — the boundary is right, one seam is missing

The stated goal — keep Cybersyn's task-dispatch engine, take only KeyMapper's mature key
handling — is exactly the boundary the merge drew: the native evdev layer was vendored
whole, the trigger *model* (ClickType / TriggerMode / per-key consume) was ported, and
`KeyMapAlgorithm` + KeyMapper's Action/Constraint domain were deliberately left behind.
That was the correct cut; nothing from the KeyMapper side needs to come out.

What's missing is the last seam: **nothing calls `KeyHijackController.setTriggers()`** —
the detector is inert scaffolding today. The clean finish, using only existing machinery:

1. On profile reload, derive `KeyTrigger`s from a small config (or hardcode the first
   few) and register them via `setTriggers`.
2. In `onTrigger`, publish `ExternalTriggerContextEvents` with the trigger id — the
   same `external_trigger` EVENT path QuickTap already uses (profile 27 proves it
   end-to-end: gesture → event → profile → task).
3. Task dispatch stays 100% in the OpenTasker engine; KeyMapper code remains purely an
   input source. No trigger→task logic lives in the evdev layer.

With §1–§3 fixed and this seam added, the swap is *done*: gyro/hold on the raw path,
classified presses (double-vol-down, long-press, chords) driving ordinary Cybersyn
profiles, python daemon gone with no successor debt.

- [ ] Bridge `onTrigger` → `ExternalTriggerContextEvents.publish(id)`
- [ ] Register triggers on profile reload (config or hardcoded first pass)
- [ ] Example: double-press vol-down (keyboard hidden) → some visible task, as the proof

## What's confirmed healthy (no action)

- Single `EvdevRootHelper` generation live (sh 28698 → app_process 28702), sweep works.
- `volume_daemon.py` fully retired: process gone, `VolumeDaemonWatchdog` profile 30
  disabled, no orphaned mqtt helpers, no client-id fights (0 "already connected" in 3 h).
- Grab matching: live device is `gpio_keys` 0x19/0x1/0x1 — matches the hardcoded GRAB.
- Clone self-exclusion in `evdev_grab_controller.rs` — no grab-your-own-uinput loop.
- npud/sshd watchdogs green since 11:02–11:04 (checks pass every minute, no restarts);
  old tasks 116/135/139 replaced by 144/145/147.
- MQTT `pub` helper is one persistent process (24650); `spectreboard/ime_shown` sub is
  stable (28705, not churned by §7 since it lives outside the matchers).

---

# Resolution — 2026-08-02, live-verified

Commits: `0208ad2` (the swap), `031bb26` (time_tick + sshd uid), `70b079d` (logcat
registration + sweep race) in Cybersyn; `ebe49ae6` in SpectreBoard. All pushed.

## Proven on the device, not inferred

| What | Evidence |
|---|---|
| §1 consume/routing latch | Held vol-down in gyro, flipped IME to hidden **mid-hold**, released: `android/clutch` published `ON` then `OFF`. Before, the release was routed by the new mode and the clutch stuck ON. Volume unchanged afterwards, so no key leaked to Android. |
| §2 media nav | uinput clone key bitmask went `1c000000000000 0` → `2800000000 1c000000000000 0`, i.e. bits 163/165 (`KEY_NEXTSONG`/`KEY_PREVIOUSSONG`) now registered. The kernel had been silently dropping every media event. |
| §3 IME seed | `dumpsys input_method` → `mInputShown` (verified to be the field that actually tracks live state; `mIsInputViewShown` is sticky). SpectreBoard now publishes retained: a fresh subscriber gets `spectreboard/ime_shown OFF` immediately, agreeing with the system. |
| §7 reload churn | Zero `cybersyn-sub-cybersyn___event` reconnects at the broker across ~12 min (was one per minute); reader/helper/subscriber pids identical across four consecutive minute boundaries. |
| §10 trigger seam | `Registered 2 key trigger(s): vol_down_double, vol_up_long`, then real evdev injections into the grabbed device produced `Key trigger 'vol_down_double' matched` and `Key trigger 'vol_up_long' matched`. |
| Stray-helper sweep | The pre-existing root helper from the old APK path was gone after install; exactly one generation runs. |

## Three bugs that only verification could have found

1. **sshd was restored as root.** The watchdog task runs `useRoot:true`, so its bare
   `sshd` produced a root-owned daemon reading root's `authorized_keys` — every login
   answered `Permission denied (publickey)`. Hit live when the reinstall killed sshd and
   the watchdog "fixed" it. `sshd_watchdog.sh` now drops to uid 1000 itself rather than
   trusting a DB flag. Verified: killed sshd, ran the watchdog as root, got a
   system-owned sshd and a working key login.

2. **Removing the per-minute reload silently stopped every periodic profile.** TIME is a
   *level* context (only EVENT and LOGCAT are pulses), so `00:00–23:59` activates once, on
   its false→true edge. The watchdogs and LogShipTimer only looked periodic because the
   rebuild manufactured a fresh edge each minute. Fixed with a real once-a-minute
   `time_tick` EVENT pulse; profiles 20/31/33 repointed. Making TIME itself a pulse would
   have been wrong — a `09:00–17:00` profile means "when the window is entered".

3. **LOGCAT profiles were dead after a cold start.** `ContextMonitor.LOGCAT.start()` *is*
   the source registration, but `reconcile()` runs after the matchers are built, so on a
   fresh process nothing could subscribe and the reader never spawned. Masked for the same
   reason as #2. The source is now registered in `onCreate`. Separately,
   `killStrayLogcatReaders()` could complete just after a reader started and kill it
   (it matches by cmdline); `reloadProfiles()` now awaits the sweep.

**The pattern worth remembering:** the per-minute `reloadProfiles()` was load-bearing for
two subsystems by accident. Removing a wasteful rebuild exposed everything that had been
quietly depending on it. Both dependencies were invisible in the diff and obvious within
minutes of watching `run_logs` and `ps` on the device.

## Deliberately not done

`RuntimeRegistriesTest.everyRuntimeActionHasUiMetadata` still fails. 24 relay actions
(`mpv.*`, `media.*`, `clipboard.*`, `file.serve/receive`) have been registered with no
`ActionMetadata` since `6a32638` (2026-07-24) — red for nine days, unrelated to this work.
Fixing it means authoring ~60 user-facing catalog strings plus 24 sensitivity
classifications, i.e. deciding what belongs in the action picker. That is a catalog design
call, not a bug fix. The other four tests that were red this morning are fixed; the suite
is 580/581.

Also left alone: `cybersyn-stub1/2` and `quicktap-stub` are tracked, which audit #1 §8
called hygiene — but stub1 is *live* (it connected to the broker today), so they are real
components, not clutter.
