from __future__ import annotations

import argparse
import json
import signal
import time
from typing import Any

import paho.mqtt.client as mqtt

from .adapters.uinput_output import UInputOutput
from .config import load_config
from .engine import SensorEngine
from .events import Event


def build_event(topic_key: str, payload: bytes, cfg: dict[str, Any]) -> Event | None:
    event_name = cfg["mapping"]["events"].get(topic_key)
    if not event_name:
        return None

    data: dict[str, Any] = {}
    timestamp = time.time()
    if topic_key == "sensor":
        try:
            parsed = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None
        if isinstance(parsed, dict):
            data = parsed
            timestamp = float(parsed.get("timestamp", timestamp))

    return Event(name=event_name, timestamp=timestamp, data=data)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="MQTT phone sensor control runtime")
    parser.add_argument("--config", required=True, help="Path to config JSON")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print actions instead of emitting through Linux uinput",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    cfg = load_config(args.config)
    engine = SensorEngine(cfg["engine"])
    output = None if args.dry_run else UInputOutput(cfg["output"].get("device_name"))
    running = True

    topic_to_key = {
        cfg["mqtt"]["topics"]["sensor"]: "sensor",
        cfg["mqtt"]["topics"]["clutch"]: "clutch",
        cfg["mqtt"]["topics"]["click"]: "click",
    }

    def apply(actions: list[dict[str, Any]]) -> None:
        if not actions:
            return
        if args.dry_run:
            for action in actions:
                print(json.dumps(action, sort_keys=True), flush=True)
            return
        if output is not None:
            output.apply_actions(actions)

    def on_message(_client: mqtt.Client, _userdata: Any, msg: mqtt.MQTTMessage) -> None:
        topic_key = topic_to_key.get(msg.topic)
        if topic_key is None:
            return
        event = build_event(topic_key, msg.payload, cfg)
        if event is not None:
            apply(engine.on_event(event))

    def stop(_signum: int, _frame: Any) -> None:
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    client = mqtt.Client()
    client.on_message = on_message
    client.connect(
        cfg["mqtt"]["host"],
        int(cfg["mqtt"]["port"]),
        int(cfg["mqtt"]["keepalive"]),
    )
    for topic in topic_to_key:
        client.subscribe(topic)
    client.loop_start()

    try:
        tick_seconds = float(cfg["engine"].get("tick_seconds", 0.2))
        while running:
            apply(engine.on_tick(time.time()))
            time.sleep(tick_seconds)
    finally:
        client.loop_stop()
        client.disconnect()
        apply(engine.on_shutdown())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
