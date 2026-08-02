#!/data/data/com.termux/files/usr/bin/python3
"""Key hijack daemon for Cybersyn — permanently grabs gpio_keys (event0).

Grabs /dev/input/event0 (VOLUME_DOWN, VOLUME_UP, POWER) exclusively and never
releases. On daemon crash the kernel closes the fd, releasing the grab, so
normal key behaviour returns.

Modes:
  - Screenshot: power+vol_down held together → always works, any mode.
  - Gyro mode (keyboard SHOWN):         vol_down/up → GYRO/HOLD signals.
  - Browser mode (keyboard HIDDEN + browser foreground): vol keys → media prev/next.
    (Cybersyn publishes ON/OFF to volume_daemon/browser_mode via a profile.)
  - Normal (keyboard HIDDEN, no browser):  all keys forwarded to Android as usual.
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


def dispatch(action):
    try:
        subprocess.run([DISPATCH, action], timeout=10)
        log(f"dispatch {action}")
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


def screencap():
    try:
        subprocess.run(
            ["input", "keyevent", "KEYCODE_SYSRQ"],
            timeout=10, capture_output=True,
        )
        log("screencap (power+vol_down)")
    except Exception as e:
        log(f"screencap FAILED: {e}")


def reap_orphans():
    try:
        out = subprocess.run(
            ["su", "-c", "ps -A -o PID,ARGS"],
            capture_output=True, text=True, timeout=15,
        ).stdout
    except Exception:
        return
    my_pid = os.getpid()
    for line in out.splitlines():
        if IME_TOPIC not in line:
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


class ImeWatcher:
    def __init__(self):
        self.shown = False
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
    def __init__(self):
        self.f = None
        self._pressed = {}
        self._grab()

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
        elif is_up:
            self._pressed[code] = False

        # Screenshot: power held + vol_down press — works in any mode
        if code == KEY_VOLUMEDOWN and is_down and self._pressed.get(KEY_POWER, False):
            screencap()
            return

        # Gyro mode: keyboard shown, remap volume keys
        if ime_shown and code in (KEY_VOLUMEDOWN, KEY_VOLUMEUP):
            if code == KEY_VOLUMEDOWN:
                if is_down:
                    dispatch("GYRO_START")
                elif is_up:
                    dispatch("GYRO_STOP")
            elif code == KEY_VOLUMEUP:
                if is_down:
                    dispatch("HOLD_START")
                elif is_up:
                    dispatch("HOLD_STOP")
            return

        # Browser nav mode: keyboard hidden + browser_mode ON
        if not ime_shown and code in (KEY_VOLUMEDOWN, KEY_VOLUMEUP) and is_down and browser_active:
            if code == KEY_VOLUMEDOWN:
                subprocess.run(
                    ["input", "keyevent", "KEYCODE_MEDIA_PREVIOUS"],
                    timeout=5, capture_output=True,
                )
            elif code == KEY_VOLUMEUP:
                subprocess.run(
                    ["input", "keyevent", "KEYCODE_MEDIA_NEXT"],
                    timeout=5, capture_output=True,
                )
            return

        # All other cases: forward normally
        if is_down:
            reinject_key(code)


def main():
    pid_fd = os.open(PIDFILE, os.O_CREAT | os.O_RDWR, 0o644)
    try:
        fcntl.lockf(pid_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        log(f"another volume_daemon holds {PIDFILE} — exiting")
        os.close(pid_fd)
        sys.exit(0)
    os.write(pid_fd, f"{os.getpid()}\n".encode())
    os.fsync(pid_fd)

    reap_orphans()

    ime = ImeWatcher()
    browser_mode = BrowserModeWatcher()
    keys = KeyGrab()

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
