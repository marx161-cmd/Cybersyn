import math
from typing import Any

from .events import Event


class SensorEngine:
    def __init__(self, cfg: dict[str, Any]) -> None:
        self.cfg = cfg
        self.clutch_active = False
        self.mouse_held = False
        self.last_yaw: float | None = None
        self.last_pitch: float | None = None
        self.remainder_x = 0.0
        self.remainder_y = 0.0
        self.last_event_ts = 0.0

    def _quat_to_angles(self, q: list[float]) -> tuple[float, float]:
        x, y, z, w = q[0], q[1], q[2], q[3]
        t3 = 2.0 * (w * z + x * y)
        t4 = 1.0 - 2.0 * (y * y + z * z)
        yaw = math.atan2(t3, t4)
        t1 = 2.0 * (w * x - y * z)
        t1 = max(-1.0, min(1.0, t1))
        pitch = math.asin(t1)
        return yaw, pitch

    @staticmethod
    def _unwrap(delta: float) -> float:
        if delta > math.pi:
            return delta - 2 * math.pi
        if delta < -math.pi:
            return delta + 2 * math.pi
        return delta

    def _target_value(self, diff: float, sens: float) -> float:
        dead = float(self.cfg["dead"])
        curve = float(self.cfg["curve"])
        if abs(diff) <= dead:
            return 0.0
        direction = -1 if diff > 0 else 1
        return direction * (abs(diff) ** curve) * sens * 1000

    def on_event(self, event: Event) -> list[dict[str, Any]]:
        self.last_event_ts = event.timestamp
        actions: list[dict[str, Any]] = []

        if event.name == "button.click_toggle":
            self.mouse_held = not self.mouse_held
            actions.append(
                {
                    "type": "mouse_button",
                    "button": "left",
                    "value": 1 if self.mouse_held else 0,
                    "reason": "toggle_click",
                }
            )
            return actions

        if event.name == "button.clutch_toggle":
            self.clutch_active = not self.clutch_active
            self.last_yaw = None
            self.last_pitch = None
            if not self.clutch_active and self.mouse_held:
                self.mouse_held = False
                actions.append(
                    {
                        "type": "mouse_button",
                        "button": "left",
                        "value": 0,
                        "reason": "clutch_off_release",
                    }
                )
            return actions

        if event.name != "sensor.rotation_vector" or not self.clutch_active:
            return actions

        sensor_type = self.cfg["sensor_type"]
        if event.data.get("type") != sensor_type:
            return actions

        values = event.data.get("values", [])
        if len(values) < 4:
            return actions

        curr_yaw, curr_pitch = self._quat_to_angles(values[:4])
        if self.last_yaw is None or self.last_pitch is None:
            self.last_yaw = curr_yaw
            self.last_pitch = curr_pitch
            return actions

        diff_yaw = self._unwrap(curr_yaw - self.last_yaw)
        diff_pitch = self._unwrap(curr_pitch - self.last_pitch)
        self.last_yaw = curr_yaw
        self.last_pitch = curr_pitch

        target_x = self._target_value(diff_yaw, float(self.cfg["sens_x"]))
        target_y = self._target_value(diff_pitch, float(self.cfg["sens_y"]))

        smooth = float(self.cfg["smooth"])
        final_x = (target_x * (1.0 - smooth)) + (self.remainder_x * smooth)
        final_y = (target_y * (1.0 - smooth)) + (self.remainder_y * smooth)
        self.remainder_x = final_x
        self.remainder_y = final_y

        move_x = int(final_x)
        move_y = int(final_y)
        if move_x != 0 or move_y != 0:
            actions.append({"type": "mouse_move", "x": move_x, "y": move_y})
        return actions

    def on_tick(self, now_ts: float) -> list[dict[str, Any]]:
        actions: list[dict[str, Any]] = []
        timeout = float(self.cfg["idle_release_seconds"])
        if timeout <= 0:
            return actions
        if not self.clutch_active or not self.mouse_held:
            return actions
        if self.last_event_ts <= 0:
            return actions
        if now_ts - self.last_event_ts > timeout:
            self.mouse_held = False
            self.last_event_ts = now_ts
            actions.append(
                {
                    "type": "mouse_button",
                    "button": "left",
                    "value": 0,
                    "reason": "idle_release",
                }
            )
        return actions

    def on_shutdown(self) -> list[dict[str, Any]]:
        if self.mouse_held:
            self.mouse_held = False
            return [
                {
                    "type": "mouse_button",
                    "button": "left",
                    "value": 0,
                    "reason": "shutdown_release",
                }
            ]
        return []
