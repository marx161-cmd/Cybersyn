from typing import Any

import uinput


class UInputOutput:
    def __init__(self, name: str | None = None) -> None:
        self.device = uinput.Device(
            [uinput.REL_X, uinput.REL_Y, uinput.BTN_LEFT, uinput.BTN_RIGHT],
            name=name or "sensor-control-uinput",
        )

    def apply_actions(self, actions: list[dict[str, Any]]) -> None:
        for action in actions:
            if action["type"] == "mouse_move":
                self.device.emit(uinput.REL_X, int(action["x"]))
                self.device.emit(uinput.REL_Y, int(action["y"]))
                continue

            if action["type"] == "mouse_button" and action.get("button") == "left":
                self.device.emit(uinput.BTN_LEFT, int(action["value"]))
