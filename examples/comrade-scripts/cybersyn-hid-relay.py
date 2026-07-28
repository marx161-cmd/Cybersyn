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
* gain, and written via a second, ABSOLUTE-positioning uinput device. There is no
accumulation across samples, so there is nothing to drift - every hold recalibrates
from scratch. Release stops writing to the absolute device; the cursor just stays
wherever it last was, and the relative device (click/scroll/touch-trackpad) takes
over from there for fine positioning.

Topics consumed:
  android/sensor          gyro quaternion → pointer motion (legacy p30control)
  android/clutch          gyro hold start/stop (ON/OFF from a hold key, not a toggle)
  android/click           left button hold/release
  android/back            back button (BTN_SIDE)
  android/forward         forward button (BTN_EXTRA)
  cybersyn/hid/mouse      "dx,dy" from TrackpadActivity touch deltas
  cybersyn/hid/click      "left"|"right"|"middle"|"hold"|"release"|"double"
  cybersyn/hid/scroll     scroll amount (negative = up)
"""

import argparse
import json
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
parser.add_argument("--sensor-topic", default="android/sensor")
parser.add_argument("--clutch-topic", default="android/clutch")
parser.add_argument("--click-topic", default="android/click")
parser.add_argument("--back-topic", default="android/back")
parser.add_argument("--forward-topic", default="android/forward")
parser.add_argument("--mouse-topic", default="cybersyn/hid/mouse")
parser.add_argument("--hid-click-topic", default="cybersyn/hid/click")
parser.add_argument("--scroll-topic", default="cybersyn/hid/scroll")
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


def get_screen_size():
    """Combined virtual-desktop size, e.g. "Screen 0: ... current 4280 x 2416"."""
    out = subprocess.run(["xrandr", "--query"], capture_output=True, text=True, timeout=5).stdout
    m = re.search(r"current\s+(\d+)\s*x\s*(\d+)", out)
    if not m:
        raise RuntimeError("could not parse combined screen size from xrandr --query")
    return int(m.group(1)), int(m.group(2))


def get_cursor_position():
    """Actual current X cursor position, for hold-start calibration."""
    out = subprocess.run(["xdotool", "getmouselocation", "--shell"],
                         capture_output=True, text=True, timeout=5).stdout
    x = int(re.search(r"^X=(\d+)$", out, re.MULTILINE).group(1))
    y = int(re.search(r"^Y=(\d+)$", out, re.MULTILINE).group(1))
    return x, y


SCREEN_W, SCREEN_H = get_screen_size()
print(f">>> Combined desktop: {SCREEN_W}x{SCREEN_H} <<<")

point_device = uinput.Device([
    uinput.ABS_X + (0, SCREEN_W - 1, 0, 0),
    uinput.ABS_Y + (0, SCREEN_H - 1, 0, 0),
], name="cybersyn-laser-pointer")

clutch_active = False
mouse_held = False
last_event_ts = 0.0
last_clutch_toggle_ts = 0.0
# Hold-start ("laser pointer") calibration: captured fresh every time clutch engages.
calib_yaw = None
calib_vertical = None
calib_cursor_x = 0
calib_cursor_y = 0
awaiting_calibration = False

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
    global awaiting_calibration
    clutch_active = enabled
    if enabled:
        # Point the phone at the current cursor before engaging - that's the zero
        # reference. Actual orientation gets captured off the next sensor sample
        # (arrives independently over MQTT, can't assume timing with this message).
        try:
            calib_cursor_x, calib_cursor_y = get_cursor_position()
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


def on_message(client, userdata, msg):
    global clutch_active, mouse_held
    global last_event_ts, last_clutch_toggle_ts
    global calib_yaw, calib_vertical, awaiting_calibration

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
                awaiting_calibration = False
                return

            if calib_yaw is None:
                return

            yaw_offset = unwrap_diff(curr_yaw, calib_yaw)
            vertical_offset = unwrap_diff(curr_vertical, calib_vertical)

            # Same left/right convention as before: positive yaw -> cursor moves left.
            target_x = calib_cursor_x - (yaw_offset * args.point_gain_x)
            if args.invert_y:
                target_y = calib_cursor_y - (vertical_offset * args.point_gain_y)
            else:
                target_y = calib_cursor_y + (vertical_offset * args.point_gain_y)

            out_x = int(clamp(target_x, 0, SCREEN_W - 1))
            out_y = int(clamp(target_y, 0, SCREEN_H - 1))
            point_device.emit(uinput.ABS_X, out_x, syn=False)
            point_device.emit(uinput.ABS_Y, out_y)

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
