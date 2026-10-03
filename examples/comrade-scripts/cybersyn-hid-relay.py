#!/usr/bin/env python3
"""Cybersyn HID relay — unified MQTT→uinput bridge for gyro, trackpad, clicks, scroll.

Consumes both the legacy p30control sensor pipeline and the Cybersyn trackpad's
MQTT topics, driving shared uinput devices. One relay, one service — no
competing control surfaces.

Gyro pointing is a "laser pointer" model, not dead-reckoning: on hold-start (clutch
ON) we capture the phone's current orientation AND the cursor's actual current
position (queried from X - point the phone at the current cursor before engaging,
same idea as a real laser pointer). While held, cursor position is recomputed fresh
each sample as calibration-position + (current-orientation - calibration-orientation)
* gain, and the delta from the previous sample's target is written via REL_X/REL_Y
on the main uinput device. There is no accumulation across samples, so there is
nothing to drift - every hold recalibrates from scratch. Release stops writing; the
cursor just stays wherever it last was, and normal relative movement (click/scroll/
touch-trackpad) takes over from there for fine positioning.

This avoids the entire ABS/joystick/tablet/touchscreen/coordinate-mapping problem
class — no absolute device, no axis range bounds baked at process start, no
coordinate transformation matrix. The REL approach naturally spans any multi-monitor
desktop configuration.

Topics consumed:
  android/sensor          gyro quaternion → pointer motion (legacy p30control)
  android/clutch          gyro hold start/stop (ON/OFF from a hold key, not a toggle)
  android/click           left button hold/release
  android/back            back button (BTN_SIDE)
  android/forward         forward button (BTN_EXTRA)
  cybersyn/hid/mouse      "dx,dy" from TrackpadActivity touch deltas
  cybersyn/hid/click      "left"|"right"|"middle"|"hold"|"release"|"double"
  cybersyn/hid/scroll     scroll amount (negative = up)
  cybersyn/hid/text              UTF-8 string (typed/glided word) → xdotool type
  cybersyn/hid/key               xdotool key spec ("Return", "ctrl+c", "Up") → xdotool key
  cybersyn/hid/keyraw            "<linuxCode> <value>" from the Pixel's RAPOO BT keyboard,
                                 replayed raw into a real uinput keyboard (no xdotool
                                 translation, so comrade's own X layout applies).
  cybersyn/hid/i3                an i3 command ("fullscreen toggle", "workspace 1") → i3-msg
  cybersyn/hid/keyboard_visible  "1"|"0" → spawn/kill a spacer window on the Pixel output
                                 so tiled windows auto-shove up like Android adjustResize.
  cybersyn/hid/oppo_volume       "0"-"100" from Cybersyn's AppVolumeMirror (crDroid's
                                 App-volume panel) → pactl set-sink-volume on the sink
                                 EasyEffects feeds the AMD->Oppo audio relay from.
"""

import argparse
import json
import os
import math
import re
import signal
import subprocess
import sys
import time

import paho.mqtt.client as mqtt
import uinput

parser = argparse.ArgumentParser()
parser.add_argument("--host", default="127.0.0.1")
parser.add_argument("--port", type=int, default=1883)
parser.add_argument("--gyro-target", choices=("shader", "pointer"), default="shader",
                    help="What the gyro drives while the GYRO toggle is on. "
                         "'shader' steers the shader-backdrop camera (default); "
                         "'pointer' restores the old uinput laser-pointer.")
parser.add_argument("--camera-file",
                    default=os.path.expanduser(
                        "~/builds/amd/shader-backdrop/camera_state.txt"))
parser.add_argument("--gyro-file",
                    default=os.path.expanduser(
                        "~/builds/amd/shader-backdrop/gyro_state.txt"))
parser.add_argument("--shader-gain-x", type=float, default=1.0)
parser.add_argument("--shader-gain-y", type=float, default=1.0)
parser.add_argument("--twist-gain", type=float, default=800.0,
                    help="shader gyro: px of Ctrl-drag (zoom / forward) per radian of twist "
                         "about the pointing axis; negate to flip the direction")
parser.add_argument("--sensor-topic", default="android/sensor")
parser.add_argument("--clutch-topic", default="android/clutch")
parser.add_argument("--click-topic", default="android/click")
parser.add_argument("--back-topic", default="android/back")
parser.add_argument("--forward-topic", default="android/forward")
parser.add_argument("--mouse-topic", default="cybersyn/hid/mouse")
parser.add_argument("--hid-click-topic", default="cybersyn/hid/click")
parser.add_argument("--scroll-topic", default="cybersyn/hid/scroll")
parser.add_argument("--text-topic", default="cybersyn/hid/text")
parser.add_argument("--key-topic", default="cybersyn/hid/key")
parser.add_argument("--keyraw-topic", default="cybersyn/hid/keyraw")
parser.add_argument("--i3-topic", default="cybersyn/hid/i3")
parser.add_argument("--keyboard-visible-topic", default="cybersyn/hid/keyboard_visible")
parser.add_argument("--oppo-volume-topic", default="cybersyn/hid/oppo_volume")
parser.add_argument("--oppo-volume-sink", default="ee_processed_sink",
                    help="PulseAudio sink name that EasyEffects feeds the AMD->Oppo relay from")
parser.add_argument("--pip-mode-topic", default="cybersyn/hid/pip_mode")
parser.add_argument("--device-orientation-topic", default="cybersyn/hid/device_orientation")
parser.add_argument("--keyboard-spacer-height", type=int, default=885,
                    help="fallback spacer height in px, used only if the live output height "
                         "can't be read (normally --keyboard-spacer-ratio drives it instead)")
parser.add_argument("--keyboard-spacer-ratio", type=float, default=0.30,
                    help="spacer height as a fraction of the primary output's CURRENT live "
                         "height, so it tracks the Pixel's now-dynamic stream resolution "
                         "instead of a fixed pixel count tuned for one resolution. Adjusted "
                         "down from the original fixed-height-derived 0.366 (visibly ~1/3 too "
                         "tall on real device testing 2026-08-21) -- may need further tuning.")
parser.add_argument("--idle-release", type=float, default=2.0)
parser.add_argument("--invert-y", action="store_true", default=False)
parser.add_argument("--vertical-axis", choices=("pitch", "roll", "auto"), default="pitch")
parser.add_argument("--clutch-cooldown-ms", type=int, default=350)
parser.add_argument("--scroll-scale", type=float, default=1.0,
                    help="multiplier for scroll deltas from cybersyn/hid/scroll")
parser.add_argument("--point-gain-x", type=float, default=2500.0,
                    help="pixels per radian of yaw offset from the hold-start calibration")
parser.add_argument("--point-gain-y", type=float, default=2500.0,
                    help="pixels per radian of vertical (pitch/roll) offset from calibration")
args = parser.parse_args()

device = uinput.Device([
    uinput.REL_X, uinput.REL_Y,
    uinput.REL_WHEEL,
    uinput.BTN_LEFT, uinput.BTN_RIGHT, uinput.BTN_MIDDLE,
    uinput.BTN_SIDE, uinput.BTN_EXTRA,
])

# Second uinput device: the Pixel's RAPOO BT keyboard replayed as a real keyboard.
# python-uinput accepts bare (ev_type, ev_code) tuples, so declaring every key code and
# emitting raw codes needs no per-key translation table. 1 == EV_KEY, 0x300 == KEY_MAX.
keyboard_device = uinput.Device(
    [(1, code) for code in range(1, 0x300)],
    name="cybersyn-remote-kbd",
)


def get_screen_size():
    """Combined virtual-desktop size, retrying until xrandr succeeds.
    Returns (0, 0) if it can't parse after all retries — callers must handle that."""
    max_retries = 12
    for attempt in range(max_retries):
        try:
            out = subprocess.run(
                ["xrandr", "--query"],
                capture_output=True, text=True, timeout=5,
            ).stdout
        except Exception:
            time.sleep(2)
            continue
        m = re.search(r"current\s+(\d+)\s*x\s*(\d+)", out)
        if m:
            w, h = int(m.group(1)), int(m.group(2))
            if w > 0 and h > 0:
                return w, h
        time.sleep(2)
    return 0, 0


SCREEN_W, SCREEN_H = get_screen_size()
if SCREEN_W == 0:
    print(f">>> WARNING: could not parse combined screen size; gyro bounds will be unlimited <<<")
else:
    print(f">>> Combined desktop: {SCREEN_W}x{SCREEN_H} <<<")


# ---------------------------------------------------------------- shader cam
# The shader-backdrop camera is steered by writing camera_state.txt, which
# renderer.py hot-reads on mtime -- the same indirection ~/bin/orrery-cam uses.
# No shader change is needed for any of this: the shader already treats a change
# in u_cam_elev/u_cam_yaw as a DELTA and applies it straight to its orientation
# quaternion, unclamped and with no inertia. That is exactly gyro semantics.
#
# Why this exists: steering by dragging a cursor runs out of screen. On a 3-head
# VKMS canvas, and especially through Sunshine where the pointer is confined to
# the streamed window, you hit an edge and the camera silently stops turning
# with no feedback. Angles have no edges.
CAM_MIN_INTERVAL = 1.0 / 60.0      # don't thrash the file above frame rate
CAM_MIN_DELTA = 0.0005             # radians; ignore sensor jitter

_cam_last_write = 0.0
_gyro_last = [0.0, 0.0, 0.0]        # latest shader-gyro offset (canvas px), written on release too
calib_cam_elev = 0.0
calib_cam_yaw = 0.0
calib_cam_zoom = 1.0


def read_camera_state():
    """(elev, yaw, zoom) from camera_state.txt; defaults match renderer.py."""
    try:
        with open(args.camera_file) as fh:
            parts = fh.read().split()
        return float(parts[0]), float(parts[1]), float(parts[2])
    except Exception:
        return 0.58, 0.35, 1.0


def write_camera_state(elev, yaw, zoom):
    """Atomic, so renderer.py can never read a half-written line."""
    tmp = args.camera_file + ".tmp"
    with open(tmp, "w") as fh:
        fh.write(f"{elev:.6f} {yaw:.6f} {zoom:.6f}\n")
    os.replace(tmp, args.camera_file)


def write_gyro_state(active, gx, gy, gz=0.0):
    """2026-09-28: shader steering goes through renderer.py's Super+mouse path now
    (every mouse-steered shader gets the gyro, not only the orreries that read
    camera_state.txt). "<active> <gx> <gy> <gz>": pointing offset since the press in
    canvas px, x right / y up; gz = the twist about the pointing axis (like a
    doorknob) as px of Super+Ctrl vertical drag = zoom / forward. Atomic."""
    tmp = args.gyro_file + ".tmp"
    with open(tmp, "w") as fh:
        fh.write(f"{1 if active else 0} {gx:.2f} {gy:.2f} {gz:.2f}\n")
    os.replace(tmp, args.gyro_file)


def get_cursor_position():
    """Actual current X cursor position, for hold-start calibration."""
    out = subprocess.run(["xdotool", "getmouselocation", "--shell"],
                         capture_output=True, text=True, timeout=5).stdout
    x = int(re.search(r"^X=(\d+)$", out, re.MULTILINE).group(1))
    y = int(re.search(r"^Y=(\d+)$", out, re.MULTILINE).group(1))
    return x, y


clutch_active = False
mouse_held = False
last_event_ts = 0.0
last_clutch_toggle_ts = 0.0
# Hold-start ("laser pointer") calibration: captured fresh every time clutch engages.
calib_yaw = None
calib_vertical = None
calib_twist = None             # shader gyro: the remaining Euler angle = twist about the pointing axis
calib_cursor_x = 0
calib_cursor_y = 0
awaiting_calibration = False
last_target_x = 0
last_target_y = 0
targets_valid = False  # True once we have at least 2 samples to compute a delta

BUTTON_MAP = {
    "left": uinput.BTN_LEFT,
    "right": uinput.BTN_RIGHT,
    "middle": uinput.BTN_MIDDLE,
}


def get_angles(q):
    x, y, z, w = q[0], q[1], q[2], q[3]
    t0 = 2.0 * (w * x + y * z)
    t1r = 1.0 - 2.0 * (x * x + y * y)
    roll = math.atan2(t0, t1r)
    t3 = 2.0 * (w * z + x * y)
    t4 = 1.0 - 2.0 * (y * y + z * z)
    yaw = math.atan2(t3, t4)
    t2 = 2.0 * (w * y - z * x)
    t2 = max(-1.0, min(1.0, t2))
    pitch = math.asin(t2)
    return yaw, pitch, roll


def clamp(v, vmin, vmax):
    return max(vmin, min(vmax, v))


def unwrap_diff(curr, last):
    d = curr - last
    if d > math.pi:
        d -= 2 * math.pi
    if d < -math.pi:
        d += 2 * math.pi
    return d


def tap_button(button_code):
    device.emit(button_code, 1)
    device.emit(button_code, 0)


def release_left():
    global mouse_held
    if mouse_held:
        mouse_held = False
        device.emit(uinput.BTN_LEFT, 0)


def shutdown(*_):
    _kill_spacer()
    release_left()
    print("Shutting down cybersyn-hid-relay.")
    sys.exit(0)


def set_click_state(enabled):
    global mouse_held
    if enabled and not mouse_held:
        mouse_held = True
        device.emit(uinput.BTN_LEFT, 1)
        print(">>> HOLDING (Drag Mode) <<<")
    elif not enabled and mouse_held:
        mouse_held = False
        device.emit(uinput.BTN_LEFT, 0)
        print(">>> RELEASED (Drop) <<<")


def set_clutch_state(enabled):
    global clutch_active, calib_yaw, calib_vertical, calib_cursor_x, calib_cursor_y
    global awaiting_calibration, last_target_x, last_target_y, targets_valid
    global calib_cam_elev, calib_cam_yaw, calib_cam_zoom
    clutch_active = enabled
    if args.gyro_target == "shader":
        # The renderer applies only the CHANGE of the offset, so every press
        # starts from wherever the view is -- nothing to baseline here.
        global calib_twist
        calib_twist = None
        calib_yaw = None
        calib_vertical = None
        awaiting_calibration = enabled
        # release carries the LAST offset (not 0,0): a sample the renderer missed
        # (throttle below, or a stalled frame) still lands instead of being lost
        if enabled:
            _gyro_last[:] = [0.0, 0.0, 0.0]
        write_gyro_state(enabled, *_gyro_last)
        print("State: ACTIVE (shader gyro)" if enabled else "State: SAFE (shader gyro)")
        return
    if enabled:
        try:
            calib_cursor_x, calib_cursor_y = get_cursor_position()
            # Seed the delta tracker so the first computed target after calibration
            # emits a delta from the initial cursor position.
            last_target_x = calib_cursor_x
            last_target_y = calib_cursor_y
            targets_valid = False
        except Exception:
            import traceback
            traceback.print_exc()
        calib_yaw = None
        calib_vertical = None
        awaiting_calibration = True
        print(f"State: ACTIVE (calibrating at cursor {calib_cursor_x},{calib_cursor_y})")
    else:
        if mouse_held:
            release_left()
            print(">>> CLUTCH SAFE (Auto-Release) <<<")
        else:
            print("State: SAFE")


# ----------------------------------------------------------------
# Cybersyn trackpad handlers
# ----------------------------------------------------------------

def handle_hid_mouse(payload_str):
    """cybersyn/hid/mouse: "dx,dy" → uinput REL_X, REL_Y"""
    parts = payload_str.strip().split(",")
    if len(parts) < 2:
        return
    try:
        dx = int(float(parts[0]))
        dy = int(float(parts[1]))
    except ValueError:
        return
    if dx == 0 and dy == 0:
        return
    # TrackpadActivity already applies acceleration profiles; clamp to sanity.
    dx = clamp(dx, -200, 200)
    dy = clamp(dy, -200, 200)
    device.emit(uinput.REL_X, dx)
    device.emit(uinput.REL_Y, dy)


def handle_hid_click(payload_str):
    """cybersyn/hid/click: "left"|"right"|"middle"|"hold"|"release"|"double" """
    cmd = payload_str.strip().lower()
    if cmd in ("left", "right", "middle"):
        btn = BUTTON_MAP[cmd]
        tap_button(btn)
        print(f">>> CLICK {cmd} <<<")
    elif cmd == "double":
        tap_button(uinput.BTN_LEFT)
        time.sleep(0.05)
        tap_button(uinput.BTN_LEFT)
        print(">>> DOUBLE CLICK <<<")
    elif cmd == "hold":
        set_click_state(True)
    elif cmd == "release":
        release_left()
        print(">>> RELEASED <<<")


def handle_hid_scroll(payload_str):
    """cybersyn/hid/scroll: scroll amount → uinput REL_WHEEL"""
    try:
        dy = float(payload_str.strip()) * args.scroll_scale
    except ValueError:
        return
    if abs(dy) < 0.5:
        return
    steps = int(clamp(dy, -20, 20))
    if steps != 0:
        device.emit(uinput.REL_WHEEL, steps)


# ----------------------------------------------------------------
# Remote keyboard / i3 lanes (SpectreBoard AMD-mode input forwarding)
# ----------------------------------------------------------------
# xdotool key tokens are letters/digits plus + _ - (e.g. "Return", "ctrl+c", "Up").
_KEY_TOKEN_RE = re.compile(r"^[A-Za-z0-9_+-]+$")


def _run_desktop(cmd):
    """Run an xdotool/i3-msg command in the service's X env; log non-zero exits.
    Synchronous like get_cursor_position() above — these complete in well under a
    frame, and keeping them ordered on the MQTT thread preserves keystroke order."""
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        if r.returncode != 0:
            print(f">>> {cmd[0]} rc={r.returncode}: {r.stderr.strip()} <<<")
    except Exception as e:
        print(f">>> {cmd[0]} failed: {e} <<<")


def handle_hid_text(payload_str):
    """cybersyn/hid/text: a typed or glided word → type into the focused window."""
    if not payload_str:
        return
    # Log length only, never content (privacy + noise).
    print(f">>> TEXT ({len(payload_str)} chars) <<<")
    _run_desktop(["xdotool", "type", "--clearmodifiers", "--", payload_str])


def handle_hid_key(payload_str):
    """cybersyn/hid/key: xdotool key spec(s), e.g. "Return", "ctrl+c", "Up"."""
    tokens = payload_str.split()
    if not tokens or not all(_KEY_TOKEN_RE.match(t) for t in tokens):
        print(f">>> KEY rejected (bad token): {payload_str!r} <<<")
        return
    print(f">>> KEY {' '.join(tokens)} <<<")
    _run_desktop(["xdotool", "key", "--clearmodifiers", *tokens])


def handle_keyraw(payload_str):
    """cybersyn/hid/keyraw: "<linuxCode> <value>" from the Pixel's RAPOO BT keyboard.

    Raw Linux evdev code, replayed into the dedicated uinput keyboard so comrade's own X
    layout decides the glyph — no evdev->xdotool table to drift, and held modifiers work.
    value is 1 (press) or 0 (release); the Pixel already drops autorepeat (2), so X owns
    repeat timing."""
    parts = payload_str.split()
    if len(parts) != 2:
        return
    try:
        code = int(parts[0])
        value = int(parts[1])
    except ValueError:
        return
    if not (1 <= code < 0x300) or value not in (0, 1):
        return
    keyboard_device.emit((1, code), value)  # 1 == EV_KEY


def handle_hid_i3(payload_str):
    """cybersyn/hid/i3: an i3 command → i3-msg (exec, workspace, fullscreen, kill…)."""
    cmd = payload_str.strip()
    if not cmd or cmd.startswith("-"):  # i3 commands never start with '-'
        return
    print(f">>> I3: {cmd} <<<")
    _run_desktop(["i3-msg", cmd])


# ----------------------------------------------------------------
# Keyboard spacer — pushes tiled windows up like Android adjustResize
# ----------------------------------------------------------------

def _get_primary_output_name():
    """xrandr name of the 'primary' output (the Pixel mobile head). None if undetermined."""
    try:
        r = subprocess.run(["xrandr", "--query"], capture_output=True, text=True, timeout=5)
    except Exception:
        return None
    for line in r.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[1] == "connected" and "primary" in parts:
            return parts[0]
    return None


def _set_vkms_landscape(landscape: bool):
    """Rotate the primary (Pixel) output 90 degrees for landscape, back to normal for
    portrait. Pure xrandr --rotate transform -- works on whatever mode is currently
    active (native-portrait modes from tonight's dynamic resolution work, or an old
    landscape-base rotated-to-portrait preset) without needing to know or switch modes."""
    primary = _get_primary_output_name()
    if not primary:
        print(">>> VKMS ORIENTATION: no primary output found <<<")
        return
    rotate = "right" if landscape else "normal"
    _run_desktop(["xrandr", "--output", primary, "--rotate", rotate])
    print(f">>> VKMS ORIENTATION: {primary} -> {rotate} <<<")


def handle_pip_mode(payload_str):
    """cybersyn/hid/pip_mode: "1" (entering PiP) -> landscape, "0" (exiting) -> portrait.
    Diana doesn't need to know anything changed -- purely a comrade-side VKMS rotate."""
    state = payload_str.strip()
    if state == "1":
        _set_vkms_landscape(True)
    elif state == "0":
        _set_vkms_landscape(False)
    else:
        print(f">>> PIP MODE unexpected payload: {payload_str!r} <<<")


def handle_device_orientation(payload_str):
    """cybersyn/hid/device_orientation: "landscape"|"portrait" -- physical phone
    rotation, published by Diana's OrientationEventListener."""
    state = payload_str.strip()
    if state == "landscape":
        _set_vkms_landscape(True)
    elif state == "portrait":
        _set_vkms_landscape(False)
    else:
        print(f">>> DEVICE ORIENTATION unexpected payload: {payload_str!r} <<<")


def _get_primary_output_height():
    """Live height of the 'primary' xrandr output (the Pixel mobile head), accounting
    for rotation -- xrandr's geometry field already reflects on-screen (post-rotation)
    size. Returns None if it can't be determined (caller falls back to a fixed height)."""
    try:
        r = subprocess.run(["xrandr", "--query"], capture_output=True, text=True, timeout=5)
    except Exception:
        return None
    for line in r.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[1] == "connected" and "primary" in parts:
            for tok in parts:
                m = re.match(r"^\d+x(\d+)\+\d+\+\d+$", tok)
                if m:
                    return int(m.group(1))
    return None


def _find_spacer_window_id(node):
    """Walk an `i3-msg -t get_tree` JSON node for the keyboard-spacer leaf and return
    its raw X11 window id (the tree's own "window" field) -- avoids needing xdotool
    to look it up separately."""
    if node.get("window_properties", {}).get("instance") == "keyboard-spacer":
        return node.get("window")
    for child in node.get("nodes", []) + node.get("floating_nodes", []):
        found = _find_spacer_window_id(child)
        if found:
            return found
    return None


_spacer_process = None  # Popen handle for the urxvt spacer window


_SPACER_COLLAPSED_HEIGHT = 2  # px; as close to invisible as i3/urxvt will allow

def _ensure_spacer_exists():
    """Create the spacer window if it doesn't already exist. Only pays the real
    creation cost (spawn + wait for i3 to register it) once per service lifetime --
    subsequent show/hide just resizes the same window, which is instant. Recreating
    it on every keyboard toggle was the actual source of the >1s show latency."""
    global _spacer_process
    if _spacer_process is not None and _spacer_process.poll() is None:
        return  # still alive
    try:
        _run_desktop(["i3-msg", "focus output primary"])
        time.sleep(0.1)
        _run_desktop(["i3-msg", "split v"])
        time.sleep(0.1)
        # Reverted from a tkinter window back to urxvt (2026-08-21): the tkinter
        # replacement's window took unpredictably longer to map, and worse, xdotool
        # itself (both "--sync" and even a plain "search") consistently hung/timed out
        # when invoked from inside this service's real MQTT callback path -- reproduced
        # repeatedly, not just a one-off race -- despite working fine when run manually
        # from an interactive shell or via systemd-run. urxvt never had this problem.
        # -bg/-fg black as a guaranteed-simple fallback in case opacity ever fails again
        # -- blends with typically-dark stream content instead of a stark white bar.
        _spacer_process = subprocess.Popen(
            ["urxvt", "-name", "keyboard-spacer", "-b", "0", "-w", "0",
             "-bg", "black", "-fg", "black", "-sb",
             "-e", "sleep", "infinity"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        # Poll i3's own tree instead of a fixed sleep -- resize/border criteria-match
        # commands were silently no-op-ing when sent before i3 finished registering the
        # new window (confirmed: the same "resize set height" command works instantly
        # when run standalone against an already-registered window). This also gets us
        # the window's raw X11 id directly from the tree JSON's "window" field -- no
        # xdotool needed at all, sidestepping its unexplained hang entirely. This whole
        # block only runs once now, not per toggle.
        win_id = None
        for _ in range(20):  # up to ~1s, one-time cost only
            tree = subprocess.run(["i3-msg", "-t", "get_tree"],
                                   capture_output=True, text=True, timeout=2)
            try:
                win_id = _find_spacer_window_id(json.loads(tree.stdout))
            except Exception:
                win_id = None
            if win_id:
                break
            time.sleep(0.05)
        if win_id is None:
            print(">>> KEYBOARD SPACER: window never registered in i3 tree <<<")
        _run_desktop(["i3-msg", '[instance="keyboard-spacer"] border none'])
        _run_desktop(["i3-msg",
                      f'[instance="keyboard-spacer"] resize set height {_SPACER_COLLAPSED_HEIGHT} px'])
        # Fully transparent via picom's xrender backend, so the reserved tile never
        # visually shows at all -- belt-and-braces on top of the black background.
        if win_id:
            _run_desktop(["xprop", "-id", str(win_id), "-f", "_NET_WM_WINDOW_OPACITY", "32c",
                          "-set", "_NET_WM_WINDOW_OPACITY", "0"])
        print(">>> KEYBOARD SPACER created <<<")
    except Exception as e:
        print(f">>> KEYBOARD SPACER creation failed: {e} <<<")
        _spacer_process = None


def _move_spacer_to_focused_workspace():
    """Bring the persistent spacer to wherever you're actually looking right now.
    Being persistent means it no longer gets recreated fresh each time (which used
    to be what made it always land on your current workspace, as a side effect of
    "focus output primary" running right before every spawn) -- so a switched-away
    workspace would otherwise never see it again. Idempotent if already there."""
    try:
        wss = json.loads(subprocess.run(["i3-msg", "-t", "get_workspaces"],
                                         capture_output=True, text=True, timeout=2).stdout)
        target = next((w["name"] for w in wss if w.get("focused")), None)
    except Exception:
        target = None
    if not target:
        return
    _run_desktop(["i3-msg", f'[instance="keyboard-spacer"] move to workspace {target}'])
    _run_desktop(["i3-msg", '[instance="keyboard-spacer"] move down'])


def _show_spacer(fallback_height: int, ratio: float):
    """Resize the (already-existing) spacer up to its real height. Height is still
    computed fresh each time -- resolution can change between toggles -- but no
    process spawn or window-registration wait is needed once it already exists."""
    _ensure_spacer_exists()
    _move_spacer_to_focused_workspace()
    live_h = _get_primary_output_height()
    height = int(live_h * ratio) if live_h else fallback_height
    _run_desktop(["i3-msg", f'[instance="keyboard-spacer"] resize set height {height} px'])
    print(f">>> KEYBOARD SPACER shown ({height}px) <<<")


def _hide_spacer():
    """Collapse the spacer instead of destroying it, so the next show is instant."""
    if _spacer_process is None:
        return  # never created yet -- nothing to collapse
    _run_desktop(["i3-msg",
                  f'[instance="keyboard-spacer"] resize set height {_SPACER_COLLAPSED_HEIGHT} px'])
    print(">>> KEYBOARD SPACER hidden <<<")


def _kill_spacer():
    """Kill the spacer window if it exists."""
    global _spacer_process
    if _spacer_process is not None:
        try:
            _spacer_process.terminate()
            _spacer_process.wait(timeout=2)
        except Exception:
            try:
                _spacer_process.kill()
            except Exception:
                pass
        _spacer_process = None
    # Also belt-and-braces via i3 — catches leaked windows from previous relay runs.
    _run_desktop(["i3-msg", '[instance="keyboard-spacer"] kill'])
    print(">>> KEYBOARD SPACER killed <<<")


def handle_oppo_volume(payload_str):
    """cybersyn/hid/oppo_volume: "0"-"100" -> pactl set-sink-volume on the EasyEffects
    sink that feeds the AMD->Oppo relay. Payload is already a clamped 0-100 int from
    Cybersyn's AppVolumeMirror; still re-clamp here since this is untrusted network input."""
    try:
        pct = int(float(payload_str.strip()))
    except ValueError:
        print(f">>> OPPO VOLUME rejected (not a number): {payload_str!r} <<<")
        return
    pct = int(clamp(pct, 0, 100))
    _run_desktop(["pactl", "set-sink-volume", args.oppo_volume_sink, f"{pct}%"])
    print(f">>> OPPO VOLUME {pct}% ({args.oppo_volume_sink}) <<<")


def handle_keyboard_visible(payload_str):
    """cybersyn/hid/keyboard_visible: \"1\" → spawn spacer, \"0\" → kill it."""
    state = payload_str.strip()
    if state == "1":
        _show_spacer(args.keyboard_spacer_height, args.keyboard_spacer_ratio)
    elif state == "0":
        _hide_spacer()
    else:
        print(f">>> KEYBOARD VISIBLE unexpected payload: {payload_str!r} <<<")


# ----------------------------------------------------------------
# MQTT callbacks
# ----------------------------------------------------------------

def on_connect(client, userdata, flags, reason_code, properties):
    print(">>> CYBERSYN HID RELAY ONLINE <<<")
    print("Topics: gyro + trackpad → single uinput device")
    client.subscribe(args.sensor_topic)
    client.subscribe(args.clutch_topic)
    client.subscribe(args.click_topic)
    client.subscribe(args.back_topic)
    client.subscribe(args.forward_topic)
    client.subscribe(args.mouse_topic)
    client.subscribe(args.hid_click_topic)
    client.subscribe(args.scroll_topic)
    client.subscribe(args.text_topic)
    client.subscribe(args.key_topic)
    client.subscribe(args.keyraw_topic)
    client.subscribe(args.i3_topic)
    client.subscribe(args.keyboard_visible_topic)
    client.subscribe(args.oppo_volume_topic)
    client.subscribe(args.pip_mode_topic)
    client.subscribe(args.device_orientation_topic)


def on_message(client, userdata, msg):
    global clutch_active, mouse_held
    global last_event_ts, last_clutch_toggle_ts
    global calib_yaw, calib_vertical, calib_twist, awaiting_calibration
    global last_target_x, last_target_y, targets_valid

    last_event_ts = time.time()
    topic = msg.topic

    # ---- Cybersyn trackpad topics ----
    if topic == args.mouse_topic:
        handle_hid_mouse(msg.payload.decode(errors="ignore"))
        return

    if topic == args.hid_click_topic:
        handle_hid_click(msg.payload.decode(errors="ignore"))
        return

    if topic == args.scroll_topic:
        handle_hid_scroll(msg.payload.decode(errors="ignore"))
        return

    # ---- Remote keyboard / i3 lanes ----
    if topic == args.text_topic:
        handle_hid_text(msg.payload.decode(errors="ignore"))
        return

    if topic == args.key_topic:
        handle_hid_key(msg.payload.decode(errors="ignore"))
        return

    if topic == args.keyraw_topic:
        handle_keyraw(msg.payload.decode(errors="ignore"))
        return

    if topic == args.i3_topic:
        handle_hid_i3(msg.payload.decode(errors="ignore"))
        return

    if topic == args.keyboard_visible_topic:
        handle_keyboard_visible(msg.payload.decode(errors="ignore"))
        return

    if topic == args.oppo_volume_topic:
        handle_oppo_volume(msg.payload.decode(errors="ignore"))
        return

    if topic == args.pip_mode_topic:
        handle_pip_mode(msg.payload.decode(errors="ignore"))
        return

    if topic == args.device_orientation_topic:
        handle_device_orientation(msg.payload.decode(errors="ignore"))
        return

    # ---- Legacy p30control topics ----
    if topic == args.click_topic:
        command = msg.payload.decode(errors="ignore").strip().upper()
        if command == "ON":
            set_click_state(True)
        elif command == "OFF":
            set_click_state(False)
        else:
            set_click_state(not mouse_held)
        return

    if topic == args.back_topic:
        tap_button(uinput.BTN_SIDE)
        print(">>> BACK BUTTON <<<")
        return

    if topic == args.forward_topic:
        tap_button(uinput.BTN_EXTRA)
        print(">>> FORWARD BUTTON <<<")
        return

    if topic == args.clutch_topic:
        command = msg.payload.decode(errors="ignore").strip().upper()
        now = time.time()
        if command == "ON":
            set_clutch_state(True)
            last_clutch_toggle_ts = now
            return
        if command == "OFF":
            set_clutch_state(False)
            last_clutch_toggle_ts = now
            return
        if last_clutch_toggle_ts > 0 and (now - last_clutch_toggle_ts) * 1000.0 < args.clutch_cooldown_ms:
            return
        last_clutch_toggle_ts = now
        set_clutch_state(not clutch_active)
        return

    if topic == args.sensor_topic:
        try:
            if not clutch_active:
                return

            payload = json.loads(msg.payload.decode())
            sensor_type = payload.get("type")
            values = payload.get("values", [])
            if not isinstance(values, list):
                return

            if sensor_type == "android.sensor.game_rotation_vector":
                if len(values) < 4:
                    return
                curr_yaw, curr_pitch, curr_roll = get_angles(values[:4])
            elif sensor_type == "android.sensor.head_tracker":
                if len(values) < 3:
                    return
                curr_yaw = float(values[0])
                curr_pitch = float(values[1])
                curr_roll = float(values[2])
            else:
                return

            if args.vertical_axis == "roll":
                curr_vertical = curr_roll
            elif args.vertical_axis == "auto":
                curr_vertical = curr_roll if abs(curr_roll) > abs(curr_pitch) else curr_pitch
            else:
                curr_vertical = curr_pitch

            if awaiting_calibration:
                # First sample after a hold-start: this IS the "phone points at the
                # current cursor" zero reference, not a real reading to act on yet.
                calib_yaw = curr_yaw
                calib_vertical = curr_vertical
                calib_twist = curr_pitch if args.vertical_axis == "roll" else curr_roll
                awaiting_calibration = False
                return

            if calib_yaw is None:
                return

            yaw_offset = unwrap_diff(curr_yaw, calib_yaw)
            vertical_offset = unwrap_diff(curr_vertical, calib_vertical)

            if args.gyro_target == "shader":
                # Angle in, angle out. The offsets are already radians and
                # camera_state.txt is radians, so nothing passes through pixels
                # and there is no screen geometry to run out of.
                # Same pointing maths as the pointer below (same gains, same
                # signs), handed to the renderer as a virtual cursor, y up.
                global _cam_last_write
                now = time.time()
                _gyro_last[0] = -yaw_offset * args.point_gain_x * args.shader_gain_x
                _gyro_last[1] = (vertical_offset if args.invert_y else -vertical_offset) \
                    * args.point_gain_y * args.shader_gain_y
                if calib_twist is not None:
                    curr_twist = curr_pitch if args.vertical_axis == "roll" else curr_roll
                    _gyro_last[2] = unwrap_diff(curr_twist, calib_twist) * args.twist_gain
                if (now - _cam_last_write) >= CAM_MIN_INTERVAL:
                    write_gyro_state(True, *_gyro_last)
                    _cam_last_write = now
                return

            target_x = calib_cursor_x - (yaw_offset * args.point_gain_x)
            if args.invert_y:
                target_y = calib_cursor_y - (vertical_offset * args.point_gain_y)
            else:
                target_y = calib_cursor_y + (vertical_offset * args.point_gain_y)

            if not targets_valid:
                # First sample after calibration — set the baseline, emit no delta yet.
                last_target_x = target_x
                last_target_y = target_y
                targets_valid = True
                return

            dx = int(clamp(target_x - last_target_x, -300, 300))
            dy = int(clamp(target_y - last_target_y, -300, 300))
            last_target_x = target_x
            last_target_y = target_y

            if dx != 0:
                device.emit(uinput.REL_X, dx, syn=False)
            if dy != 0:
                device.emit(uinput.REL_Y, dy)

        except Exception:
            import traceback
            traceback.print_exc()


client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
client.on_connect = on_connect
client.on_message = on_message
client.connect(args.host, args.port, 60)

signal.signal(signal.SIGINT, shutdown)
signal.signal(signal.SIGTERM, shutdown)

client.loop_start()
try:
    while True:
        if mouse_held and args.idle_release > 0 and last_event_ts > 0:
            if (time.time() - last_event_ts) > args.idle_release:
                release_left()
                print(">>> IDLE SAFE (Auto-Release) <<<")
                last_event_ts = time.time()
        time.sleep(0.2)
finally:
    release_left()
    client.loop_stop()
    client.disconnect()
