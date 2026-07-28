#!/data/data/com.termux/files/usr/bin/python3
"""Volume-key hijack daemon for Cybersyn's gyro-point/click-hold controls.

Grabs /dev/input/event0 (gpio_keys: VOLUME_DOWN/VOLUME_UP) exclusively, but ONLY
while SpectreBoard's keyboard is actually shown on screen (tracked in real time via
the spectreboard/ime_shown MQTT topic SpectreBoard itself publishes from
LatinIME.onWindowShown/onWindowHidden - no polling). Whenever the keyboard isn't
shown, the device is released (closed, not EVIOCGRAB(0) - that call reliably threw
EBUSY when tested live on this kernel; closing the fd is the standard way an evdev
grab is released and was verified live to restore normal volume handling cleanly).

While grabbed:
  KEY_VOLUMEDOWN down/up -> dispatch_input.sh GYRO_START/GYRO_STOP (tilt-cursor gate)
  KEY_VOLUMEUP   down/up -> dispatch_input.sh HOLD_START/HOLD_STOP (click-and-hold/drag)

Meant to be started detached by volume_daemon.sh and supervised by a Cybersyn
self-recursive watchdog task, same pattern as npud.
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

KEY_VOLUMEDOWN = 114
KEY_VOLUMEUP = 115


def log(msg):
    print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}", flush=True)


def resolve_mqtt_bin():
    out = subprocess.run(
        ["dumpsys", "package", "com.termux.cybersyn"],
        capture_output=True, text=True,
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


class ImeWatcher:
    """Runs Cybersyn's own mqtt helper in `sub` mode, respawning on drop."""

    def __init__(self, mqtt_bin):
        self.mqtt_bin = mqtt_bin
        self.proc = None
        self.shown = False
        self._spawn()

    def _spawn(self):
        self.proc = subprocess.Popen(
            [self.mqtt_bin, "sub", "--broker", BROKER, "--port", PORT,
             "--topic", IME_TOPIC, "--id", "volume-daemon-ime"],
            stdout=subprocess.PIPE, text=True, bufsize=1,
        )
        log("ime watcher (re)spawned")

    def fileno(self):
        return self.proc.stdout.fileno()

    def poll_line(self):
        """Call when select() says the sub process's stdout is readable."""
        line = self.proc.stdout.readline()
        if not line:
            # Process died/EOF - respawn after a short backoff.
            log("ime watcher subprocess EOF, respawning in 2s")
            try:
                self.proc.wait(timeout=1)
            except Exception:
                pass
            time.sleep(2)
            self._spawn()
            return
        line = line.strip()
        if "\t" not in line:
            return
        topic, payload = line.split("\t", 1)
        if topic != IME_TOPIC:
            return
        new_shown = payload.strip().upper() == "ON"
        if new_shown != self.shown:
            self.shown = new_shown
            log(f"ime_shown -> {self.shown}")


class VolumeGrab:
    """Owns the (possibly-open) grabbed event0 fd. None when released."""

    def __init__(self):
        self.f = None

    def fileno(self):
        return self.f.fileno() if self.f else None

    def grabbed(self):
        return self.f is not None

    def grab(self):
        if self.f is not None:
            return
        try:
            self.f = open(DEV, "rb", buffering=0)
            fcntl.ioctl(self.f.fileno(), EVIOCGRAB, struct.pack("i", 1))
            log("volume keys GRABBED")
        except Exception as e:
            log(f"grab FAILED: {e}")
            self.f = None

    def release(self):
        if self.f is None:
            return
        try:
            self.f.close()  # releases the grab as a kernel-level side effect
        except Exception:
            pass
        self.f = None
        log("volume keys released")

    def poll_event(self):
        if self.f is None:
            return
        data = os.read(self.f.fileno(), EVENT_SIZE)
        if not data or len(data) != EVENT_SIZE:
            return
        _sec, _usec, type_, code, value = struct.unpack(EVENT_FMT, data)
        if type_ != 1:  # EV_KEY only
            return
        if code == KEY_VOLUMEDOWN:
            dispatch("GYRO_START" if value == 1 else "GYRO_STOP")
        elif code == KEY_VOLUMEUP:
            dispatch("HOLD_START" if value == 1 else "HOLD_STOP")


def main():
    mqtt_bin = resolve_mqtt_bin()
    if not mqtt_bin or not os.access(mqtt_bin, os.X_OK):
        log(f"FATAL: could not resolve Cybersyn mqtt binary ({mqtt_bin})")
        sys.exit(1)

    ime = ImeWatcher(mqtt_bin)
    grab = VolumeGrab()

    log("volume_daemon started")
    while True:
        fds = [ime.fileno()]
        if grab.grabbed():
            fds.append(grab.fileno())

        r, _, _ = select.select(fds, [], [], 2.0)

        if ime.fileno() in r:
            ime.poll_line()

        if grab.fileno() in r:
            grab.poll_event()

        # Reconcile grab state with the latest ime_shown value every iteration -
        # cheap, and self-heals if a state transition was missed mid-read somehow.
        if ime.shown and not grab.grabbed():
            grab.grab()
        elif not ime.shown and grab.grabbed():
            grab.release()


if __name__ == "__main__":
    main()
