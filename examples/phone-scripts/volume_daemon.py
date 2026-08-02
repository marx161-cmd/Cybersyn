#!/data/data/com.termux/files/usr/bin/python3
"""Key hijack daemon for Cybersyn — permanently grabs gpio_keys (event0).

Grabs /dev/input/event0 (VOLUME_DOWN, VOLUME_UP, POWER) exclusively and never
releases. On daemon crash the kernel closes the fd, releasing the grab, so
normal key behaviour returns.

Modes (routing is latched per press, on key-down — see KeyGrab._route_for):
  - Power held:                          vol keys pass through, so Android's own
    power+vol_down screenshot fires (and suppresses the power action itself).
  - Gyro mode (keyboard SHOWN):          vol_down/up → GYRO/HOLD signals.
  - Browser mode (keyboard HIDDEN + browser foreground): vol keys → media prev/next.
    (Cybersyn publishes ON/OFF to volume_daemon/browser_mode via a profile.)
  - Normal (keyboard HIDDEN, no browser): all keys forwarded to Android as usual.

The power key is never remapped — it always passes through.

Keys we don't remap are re-emitted verbatim through a uinput virtual keyboard, NOT
via `input keyevent`. This matters: the grab takes the physical keys away from
Android entirely, and `input keyevent` produces a synthetic event that SystemUI's
power-key policy ignores — verified on blazer, `input keyevent --longpress
KEYCODE_POWER` does not open the power menu. Re-emitting through uinput produces a
real hardware-path key with real press/hold/release timing, so Android's own
handling (long-press power menu, power+vol_down screenshot, volume long-press
repeat) works unmodified, and no subprocess is spawned per keypress.
"""

import fcntl
import os
import select
import struct
import subprocess
import sys
import time

DEV = "/dev/input/event0"
EVIOCGRAB = 0x40044590
EVENT_FMT = "qqHHi"
EVENT_SIZE = struct.calcsize(EVENT_FMT)

DISPATCH = "/data/data/com.termux/files/home/.termux/tasker/dispatch_input.sh"
IME_TOPIC = "spectreboard/ime_shown"
BROKER = "100.108.8.60"
PORT = "1883"
PIDFILE = "/data/local/tmp/volume_daemon.pid"

KEY_VOLUMEDOWN = 114
KEY_VOLUMEUP = 115
KEY_POWER = 116

BROWSER_MODE_TOPIC = "volume_daemon/browser_mode"

# uinput, for re-emitting grabbed keys as real hardware keys.
UINPUT_DEV = "/dev/uinput"
UI_SET_EVBIT = 0x40045564
UI_SET_KEYBIT = 0x40045565
UI_DEV_CREATE = 0x5501
UI_DEV_DESTROY = 0x5502
EV_SYN = 0
EV_KEY = 1
SYN_REPORT = 0
UINPUT_NAME = b"cybersyn-keys"

KEYCODE_MAP = {
    KEY_VOLUMEDOWN: "KEYCODE_VOLUME_DOWN",
    KEY_VOLUMEUP: "KEYCODE_VOLUME_UP",
    KEY_POWER: "KEYCODE_POWER",
}


def log(msg):
    print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}", flush=True)


def resolve_mqtt_bin():
    out = subprocess.run(
        ["dumpsys", "package", "com.termux.cybersyn"],
        capture_output=True, text=True, timeout=15,
    ).stdout
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("legacyNativeLibraryDir="):
            libdir = line.split("=", 1)[1]
            return f"{libdir}/arm64/libcybersyn-mqtt.so"
    return None


# Hot-path actions, published directly instead of shelling out to dispatch_input.sh.
# Mapping mirrors that script exactly -- keep them in sync.
DISPATCH_TOPICS = {
    "GYRO_START": ("android/clutch", "ON"),
    "GYRO_STOP": ("android/clutch", "OFF"),
    "HOLD_START": ("android/click", "ON"),
    "HOLD_STOP": ("android/click", "OFF"),
}


class MqttPub:
    """Persistent publisher, mirroring MqttBridge.kt's one-process-reused design.

    `dispatch_input.sh` costs ~315ms per call (bash startup + a `dumpsys package`
    lookup + a fresh MQTT connect), and dispatch() runs inline in the input loop, so
    GYRO_STOP only reached the relay ~315ms after the key was physically released --
    the cursor kept tracking for a third of a second after letting go. A long-lived
    `pub` process turns each dispatch into a pipe write.
    """

    def __init__(self):
        self.proc = None

    def _ensure(self):
        if self.proc is not None and self.proc.poll() is None:
            return True
        mqtt_bin = resolve_mqtt_bin()
        if not mqtt_bin or not os.access(mqtt_bin, os.X_OK):
            return False
        try:
            self.proc = subprocess.Popen(
                [mqtt_bin, "pub", "--broker", BROKER, "--port", PORT],
                stdin=subprocess.PIPE,
            )
            log("mqtt pub process started")
            return True
        except Exception as e:
            log(f"mqtt pub spawn failed: {e}")
            self.proc = None
            return False

    def publish(self, topic, payload):
        if not self._ensure():
            return False
        try:
            self.proc.stdin.write(f"{topic}\t{payload}\n".encode())
            self.proc.stdin.flush()
            return True
        except Exception as e:
            log(f"mqtt pub write failed ({e}); respawning next time")
            try:
                self.proc.kill()
            except Exception:
                pass
            self.proc = None
            return False

    def close(self):
        if self.proc is None:
            return
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        try:
            self.proc.terminate()
        except Exception:
            pass
        self.proc = None


def dispatch(action, pub=None):
    mapped = DISPATCH_TOPICS.get(action)
    if mapped is not None and pub is not None and pub.publish(*mapped):
        log(f"dispatch {action}")
        return
    # Fallback for everything else, and if the persistent publisher is unavailable.
    try:
        subprocess.run([DISPATCH, action], timeout=10)
        log(f"dispatch {action} (via script)")
    except Exception as e:
        log(f"dispatch {action} FAILED: {e}")


def reinject_key(code):
    keycode = KEYCODE_MAP.get(code)
    if not keycode:
        return
    try:
        subprocess.run(
            ["input", "keyevent", keycode],
            timeout=5, capture_output=True,
        )
    except Exception:
        pass


class UinputKeyboard:
    """Virtual keyboard for re-emitting grabbed keys as genuine hardware events.

    Falls back to `input keyevent` if /dev/uinput can't be opened — degraded (no
    long-press power menu, no volume repeat) but not dead.
    """

    def __init__(self):
        self.fd = None
        try:
            fd = os.open(UINPUT_DEV, os.O_WRONLY | os.O_NONBLOCK)
            fcntl.ioctl(fd, UI_SET_EVBIT, EV_KEY)
            for code in KEYCODE_MAP:
                fcntl.ioctl(fd, UI_SET_KEYBIT, code)
            # Legacy uinput_user_dev setup: name[80], input_id(4x u16),
            # ff_effects_max, then absmin/absmax/absfuzz/absflat (4 x ABS_CNT s32).
            dev = struct.pack(
                "<80sHHHHI" + "i" * 256,
                UINPUT_NAME, 0x0003, 0x1209, 0xC5B0, 1, 0, *([0] * 256),
            )
            os.write(fd, dev)
            fcntl.ioctl(fd, UI_DEV_CREATE)
            self.fd = fd
            log("uinput virtual keyboard created")
        except Exception as e:
            log(f"uinput unavailable ({e}); falling back to `input keyevent`")

    def emit(self, code, value):
        """Forward one key event verbatim, preserving value (0=up, 1=down, 2=repeat)."""
        if self.fd is not None:
            try:
                now = time.time()
                sec, usec = int(now), int((now % 1) * 1_000_000)
                os.write(self.fd, struct.pack(EVENT_FMT, sec, usec, EV_KEY, code, value))
                os.write(self.fd, struct.pack(EVENT_FMT, sec, usec, EV_SYN, SYN_REPORT, 0))
                return
            except Exception as e:
                log(f"uinput emit failed ({e}); falling back to `input keyevent`")
                self.close()
        # Fallback can only express a discrete press.
        if value == 1:
            reinject_key(code)

    def close(self):
        if self.fd is None:
            return
        try:
            fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        except Exception:
            pass
        try:
            os.close(self.fd)
        except Exception:
            pass
        self.fd = None


def reap_orphans():
    """Kill helper subs left behind by a previous daemon generation.

    Matches on the `--id daemon-...` marker this daemon stamps on every helper it
    spawns, so it covers every topic we subscribe to (matching a single topic name
    is what let browser_mode helpers survive as root orphans re-parented to init),
    while never touching Cybersyn's own subs, which use `cybersyn-sub-*` ids.
    Runs before any of our own children exist, so nothing live can be caught.
    """
    try:
        out = subprocess.run(
            ["su", "-c", "ps -A -o PID,ARGS"],
            capture_output=True, text=True, timeout=15,
        ).stdout
    except Exception:
        return
    my_pid = os.getpid()
    for line in out.splitlines():
        if "--id daemon-" not in line:
            continue
        parts = line.strip().split(None, 1)
        if not parts:
            continue
        try:
            orphan_pid = int(parts[0])
        except ValueError:
            continue
        if orphan_pid == my_pid:
            continue
        try:
            subprocess.run(
                ["su", "-c", f"kill -9 {orphan_pid}"],
                timeout=5, capture_output=True,
            )
            log(f"reaped orphan mqtt helper pid {orphan_pid}")
        except Exception:
            pass


class MqttSub:
    """Runs Cybersyn's mqtt helper in `sub` mode for a single topic, respawning on drop."""

    def __init__(self, topic, on_message):
        self.topic = topic
        self.on_message = on_message
        self.proc = None
        self._spawn()

    def _spawn(self):
        self._kill_child()
        mqtt_bin = resolve_mqtt_bin()
        if not mqtt_bin or not os.access(mqtt_bin, os.X_OK):
            log(f"FATAL: mqtt binary not found ({mqtt_bin}) — {self.topic} subscription failed")
            self.proc = None
            return
        sub_id = f"daemon-{self.topic.replace('/', '-')}-{os.getpid()}"
        self.proc = subprocess.Popen(
            [mqtt_bin, "sub", "--broker", BROKER, "--port", PORT,
             "--topic", self.topic, "--id", sub_id],
            stdout=subprocess.PIPE, text=True, bufsize=1,
        )
        log(f"mqtt sub spawned: {self.topic} (id={sub_id})")

    def _kill_child(self):
        if self.proc is None:
            return
        try:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=2)
        except Exception:
            pass
        try:
            self.proc.stdout.close()
        except Exception:
            pass
        self.proc = None

    def fileno(self):
        if self.proc is None:
            return None
        return self.proc.stdout.fileno()

    def poll_line(self):
        if self.proc is None:
            return
        line = self.proc.stdout.readline()
        if not line:
            log(f"mqtt sub EOF for {self.topic}, respawning in 2s")
            self._kill_child()
            time.sleep(2)
            self._spawn()
            return
        line = line.strip()
        if "\t" not in line:
            return
        topic, payload = line.split("\t", 1)
        if topic == self.topic:
            self.on_message(payload.strip())


def query_ime_shown():
    """Current IME visibility straight from the system.

    SpectreBoard publishes spectreboard/ime_shown on transitions only and not
    retained, so a daemon that starts (or is restarted by the watchdog) while the
    keyboard is already up would believe it is hidden until the next show/hide --
    routing gyro/hold keys to the volume slider for the whole window in between.
    Seed from the system instead of assuming hidden.
    """
    try:
        out = subprocess.run(
            ["dumpsys", "input_method"],
            capture_output=True, text=True, timeout=10,
        ).stdout
    except Exception as e:
        log(f"ime seed query failed ({e}); assuming hidden")
        return False
    for line in out.splitlines():
        stripped = line.strip()
        if stripped.startswith("mInputShown="):
            return stripped.split("=", 1)[1].strip().lower() == "true"
    return False


class ImeWatcher:
    def __init__(self):
        self.shown = query_ime_shown()
        log(f"ime_shown seeded from system: {self.shown}")
        self._sub = MqttSub(IME_TOPIC, self._on_ime)

    def _on_ime(self, payload):
        new_shown = payload.upper() == "ON"
        if new_shown != self.shown:
            self.shown = new_shown
            log(f"ime_shown -> {self.shown}")

    def fileno(self):
        return self._sub.fileno()

    def poll_line(self):
        self._sub.poll_line()

    def respawn_if_dead(self):
        if self._sub.proc is None:
            self._sub._spawn()


class BrowserModeWatcher:
    def __init__(self):
        self.browser_mode = False
        self._sub = MqttSub(BROWSER_MODE_TOPIC, self._on_mode)

    def _on_mode(self, payload):
        mode = payload.upper() == "ON"
        if mode != self.browser_mode:
            self.browser_mode = mode
            log(f"browser_mode -> {self.browser_mode}")

    def fileno(self):
        return self._sub.fileno()

    def poll_line(self):
        self._sub.poll_line()

    def respawn_if_dead(self):
        if self._sub.proc is None:
            self._sub._spawn()


class KeyGrab:
    def __init__(self, keyboard, pub):
        self.f = None
        self.pub = pub
        self._pressed = {}
        # code -> where this press was routed, latched on key-down. Re-deciding per
        # event would desynchronise Android's key state whenever a mode flips mid-hold:
        # a down we passed through followed by an up we swallowed leaves the key stuck
        # down on the virtual device forever.
        self._routing = {}
        self.keyboard = keyboard
        self._grab()

    def _route_for(self, code, ime_shown, browser_active):
        """Decide where a press goes. Called once per press, on key-down."""
        if code not in (KEY_VOLUMEDOWN, KEY_VOLUMEUP):
            return "pass"
        # Power held: hand the volume keys to Android so its own power+vol_down
        # screenshot fires. Re-implementing the combo here can't work now that power
        # passes through — Android would still resolve the power hold as a real press
        # on release, firing the assistant on top of the screenshot.
        if self._pressed.get(KEY_POWER, False):
            return "pass"
        if ime_shown:
            return "gyro"
        if browser_active:
            return "browser"
        return "pass"

    def _grab(self):
        try:
            self.f = open(DEV, "rb", buffering=0)
            fcntl.ioctl(self.f.fileno(), EVIOCGRAB, struct.pack("i", 1))
            log("event0 GRABBED (vol_down, vol_up, power)")
        except Exception as e:
            log(f"grab FAILED: {e}")
            self.f = None

    def fileno(self):
        return self.f.fileno() if self.f else None

    def poll_event(self, ime_shown, browser_active):
        if self.f is None:
            return
        data = os.read(self.f.fileno(), EVENT_SIZE)
        if not data or len(data) != EVENT_SIZE:
            return
        _sec, _usec, type_, code, value = struct.unpack(EVENT_FMT, data)
        if type_ != 1:
            return

        is_down = (value == 1)
        is_up = (value == 0)
        if is_down:
            self._pressed[code] = True
            route = self._route_for(code, ime_shown, browser_active)
            self._routing[code] = route
        else:
            # Autorepeat and release follow wherever the press went, whatever the
            # mode is now. Unknown key (down missed) falls through to Android.
            route = self._routing.get(code, "pass")
            if is_up:
                self._pressed[code] = False
                self._routing.pop(code, None)

        if route == "gyro":
            if code == KEY_VOLUMEDOWN:
                if is_down:
                    dispatch("GYRO_START", self.pub)
                elif is_up:
                    dispatch("GYRO_STOP", self.pub)
            else:
                if is_down:
                    dispatch("HOLD_START", self.pub)
                elif is_up:
                    dispatch("HOLD_STOP", self.pub)
            return

        if route == "browser":
            # Fires on the press; the release is swallowed so Android never sees a
            # half key. Autorepeat is ignored — one skip per press, not a stream.
            if is_down:
                media_key = (
                    "KEYCODE_MEDIA_PREVIOUS" if code == KEY_VOLUMEDOWN else "KEYCODE_MEDIA_NEXT"
                )
                subprocess.run(
                    ["input", "keyevent", media_key],
                    timeout=5, capture_output=True,
                )
            return

        # Re-emit verbatim so Android sees a real key with real timing — this is what
        # gives back long-press power behaviour and the native screenshot combo.
        self.keyboard.emit(code, value)


def main():
    pid_fd = os.open(PIDFILE, os.O_CREAT | os.O_RDWR, 0o644)
    try:
        fcntl.lockf(pid_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        log(f"another volume_daemon holds {PIDFILE} — exiting")
        os.close(pid_fd)
        sys.exit(0)
    # Truncate first: a shorter pid than the previous generation's would otherwise
    # leave trailing digits behind and `kill $(cat pidfile)` would target a stranger.
    os.ftruncate(pid_fd, 0)
    os.write(pid_fd, f"{os.getpid()}\n".encode())
    os.fsync(pid_fd)

    reap_orphans()

    ime = ImeWatcher()
    browser_mode = BrowserModeWatcher()
    keyboard = UinputKeyboard()
    pub = MqttPub()
    keys = KeyGrab(keyboard, pub)

    log("volume_daemon started (always-grab)")
    while True:
        fds = []
        for watcher in (ime, browser_mode):
            fd = watcher.fileno()
            if fd is not None:
                fds.append(fd)
        key_fd = keys.fileno()
        if key_fd is not None:
            fds.append(key_fd)

        # No hold timers to service any more — Android does its own long-press timing
        # off the re-emitted events, so this can idle.
        r, _, _ = select.select(fds, [], [], 2.0)

        for watcher in (ime, browser_mode):
            fd = watcher.fileno()
            if fd is not None and fd in r:
                watcher.poll_line()
        key_fd = keys.fileno()
        if key_fd is not None and key_fd in r:
            keys.poll_event(ime.shown, browser_mode.browser_mode)

        ime.respawn_if_dead()
        browser_mode.respawn_if_dead()
        if key_fd is None:
            keys._grab()


if __name__ == "__main__":
    main()
