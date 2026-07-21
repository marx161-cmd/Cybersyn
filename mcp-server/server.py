#!/usr/bin/env python3
"""Cybersyn MCP server — LLM-drivable automation tools.

Exposes the Cybersyn rule engine, relays, and device state as MCP tools so
an LLM (via Agora, Claude Desktop, or any MCP client) can build automation
rules, query state, and execute actions across the homelab.

Transport: stdio (Claude Desktop) or SSE (Agora connects over Tailscale).
MQTT bus: Mosquitto on localhost:1883 (comrade) — relays and phone brain app
are reached through the existing cybersyn/* topic namespace.

Design axiom (same as the project): new tool = new function, drop it in,
zero redesign of anything existing.
"""

from __future__ import annotations

import json
import os
import queue
import signal
import sys
import threading
import time
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

import paho.mqtt.client as mqtt
from mcp.server.fastmcp import FastMCP

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

BROKER_HOST = os.environ.get("CYBERSYN_BROKER", "127.0.0.1")
BROKER_PORT = int(os.environ.get("CYBERSYN_PORT", "1883"))
RULES_DIR = Path(
    os.environ.get(
        "CYBERSYN_RULES_DIR",
        os.path.expanduser("~/.config/cybersyn/rules"),
    )
)
RPC_TIMEOUT = float(os.environ.get("CYBERSYN_RPC_TIMEOUT", "5.0"))

# ---------------------------------------------------------------------------
# MQTT bus — thin wrapper around paho-mqtt with an RPC helper
# ---------------------------------------------------------------------------


class MqttBus:
    """Thread-safe MQTT client with a simple publish-and-wait-for-response
    pattern (rpc).  One persistent connection, shared by all tools."""

    def __init__(self, host: str, port: int) -> None:
        self.client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2, client_id="cybersyn-mcp"
        )
        self.client.on_message = self._on_message
        self.client.connect(host, port)
        self.client.loop_start()
        self._queues: dict[str, list[queue.Queue[str]]] = defaultdict(list)
        self._lock = threading.Lock()

    def _on_message(
        self, _client: mqtt.Client, _userdata: Any, msg: mqtt.MQTTMessage
    ) -> None:
        payload = msg.payload.decode()
        with self._lock:
            listeners = list(self._queues.get(msg.topic, []))
        for q in listeners:
            q.put(payload)

    def _ensure_subscribed(self, topic: str) -> None:
        self.client.subscribe(topic, qos=0)

    def publish(self, topic: str, payload: str) -> None:
        self.client.publish(topic, payload, qos=0)

    def rpc(self, pub_topic: str, payload: str, resp_topic: str) -> str | None:
        """Publish a request and wait for ONE response on resp_topic.

        Returns the response payload, or None on timeout.
        """
        self._ensure_subscribed(resp_topic)
        q: queue.Queue[str] = queue.Queue()
        with self._lock:
            self._queues[resp_topic].append(q)
        try:
            self.publish(pub_topic, payload)
            return q.get(timeout=RPC_TIMEOUT)
        except queue.Empty:
            return None
        finally:
            with self._lock:
                self._queues[resp_topic].remove(q)


# ---------------------------------------------------------------------------
# Static catalogs — what triggers and actions exist
# ---------------------------------------------------------------------------

# Cybersyn is a system-signed priv-app sharing the Termux UID, with full root
# access.  Nearly every Android action collapses into termux_shell — settings
# changes, HID injection, adbd/sshd lifecycle, /proc|/sys reads, pm grant,
# appops, am intents, Java reflection, clipboard read/write, swapon, everything.
# The catalog reflects this: a small set of triggers, one universal action, and
# a couple of cross-node bridges.

TRIGGERS: list[dict[str, Any]] = [
    {
        "id": "app_foreground",
        "name": "App Foreground",
        "description": "Trigger when a specified app enters/exits the foreground",
        "params": ["package_name", "event (enter|exit)"],
        "node": "phone",
    },
    {
        "id": "time_window",
        "name": "Time Window",
        "description": "Trigger at a specific time (HH:MM) or day-of-week schedule",
        "params": ["start_time", "end_time", "days_of_week"],
        "node": "phone",
    },
    {
        "id": "time_repeating",
        "name": "Repeating Timer",
        "description": "Trigger at a fixed interval (e.g. every 2 min, every 30 min)",
        "params": ["repeat_minutes"],
        "node": "phone",
    },
    {
        "id": "boot_completed",
        "name": "Device Boot",
        "description": "Trigger when the phone finishes booting",
        "params": [],
        "node": "phone",
    },
    {
        "id": "battery_level",
        "name": "Battery Level",
        "description": "Trigger when battery crosses a threshold (e.g. below 15%)",
        "params": ["percent", "direction (above|below)"],
        "node": "phone",
    },
    {
        "id": "charging_state",
        "name": "Charging State",
        "description": "Trigger when the phone is plugged in or unplugged",
        "params": ["state (charging|discharging)"],
        "node": "phone",
    },
    {
        "id": "screen_state",
        "name": "Screen On/Off",
        "description": "Trigger when the screen turns on or off",
        "params": ["state (on|off)"],
        "node": "phone",
    },
    {
        "id": "clipboard_change",
        "name": "Clipboard Change",
        "description": "Trigger when clipboard content changes.  Passes the new text as %clipboard.",
        "params": ["content_pattern (optional regex filter)"],
        "node": "phone",
    },
    {
        "id": "variable_set",
        "name": "Variable Set",
        "description": "Trigger when a named Cybersyn variable changes value.  Used for chaining: one rule sets a variable, another rule reacts to it (the Tasker %Logger_Trigger pattern).",
        "params": ["variable_name", "value_pattern (optional)"],
        "node": "phone",
    },
    {
        "id": "wifi_network",
        "name": "WiFi Network",
        "description": "Trigger when connected to a specific WiFi SSID",
        "params": ["ssid"],
        "node": "phone",
    },
    {
        "id": "bluetooth_device",
        "name": "Bluetooth Device",
        "description": "Trigger when a BT device connects or disconnects",
        "params": ["device_address", "event (connect|disconnect)"],
        "node": "phone",
    },
    {
        "id": "notification",
        "name": "Notification Received",
        "description": "Trigger on a notification matching package/title/text",
        "params": ["package", "title_pattern", "text_pattern"],
        "node": "phone",
    },
    {
        "id": "headphones",
        "name": "Headphones / BT Audio",
        "description": "Trigger when audio device is connected or disconnected",
        "params": ["device_type", "event (connect|disconnect)"],
        "node": "phone",
    },
    {
        "id": "nfc_tag",
        "name": "NFC Tag Scanned",
        "description": "Trigger when a specific NFC tag is detected",
        "params": ["tag_id"],
        "node": "phone",
    },
    {
        "id": "mqtt_topic",
        "name": "MQTT Topic",
        "description": "Trigger on any MQTT topic match — relay events, external sensors, Tasmota state, etc.",
        "params": ["topic_filter", "payload_pattern"],
        "node": "any",
    },
    {
        "id": "node_presence",
        "name": "Node Presence",
        "description": "Trigger when a relay node comes online or goes offline",
        "params": ["node_name"],
        "node": "any",
    },
]

ACTIONS: list[dict[str, Any]] = [
    {
        "id": "termux_shell",
        "name": "Run in Termux",
        "description": "Execute a shell command in Termux on the phone.  Cybersyn is a system priv-app with the same UID as Termux and full root access, so this single action replaces everything: settings put/get, /proc|/sys reads, adbd/sshd lifecycle, pm grant, appops, am intents, swapon, /system/bin/hid keyboard injection, clipboard read/write, Java reflection, write file, wait/delay, if/else logic — it's all just shell.  Use su -c for root.",
        "params": ["command", "timeout_seconds"],
        "node": "phone",
    },
    {
        "id": "launch_app",
        "name": "Launch App",
        "description": "Launch an Android app by package name (shorthand for 'am start' via termux_shell)",
        "params": ["package_name"],
        "node": "phone",
    },
    {
        "id": "android_notify",
        "name": "Phone Notification",
        "description": "Post a notification on the phone",
        "params": ["title", "body"],
        "node": "phone",
    },
    {
        "id": "variable_operation",
        "name": "Variable Set/Clear",
        "description": "Set or clear a Cybersyn variable.  Use this to chain rules: one rule sets %Logger_Trigger=1, another triggers on variable_set for %Logger_Trigger.",
        "params": ["variable_name", "value (empty to clear)"],
        "node": "phone",
    },
    {
        "id": "desktop_notify",
        "name": "Desktop Notification",
        "description": "Send a desktop notification to a relay node (notify-send)",
        "params": ["node", "title", "body"],
        "node": "relay",
    },
    {
        "id": "mqtt_publish",
        "name": "MQTT Publish",
        "description": "Publish an arbitrary MQTT message — universal bridge to relays, Tasmota, Zigbee, any MQTT-speaking device",
        "params": ["topic", "payload"],
        "node": "any",
    },
    {
        "id": "hid_mouse",
        "name": "HID Mouse Move",
        "description": "Move the mouse cursor on the connected host via MQTT.  Pairs with the Cybersyn TrackpadActivity or gyro pipeline.  Topic: cybersyn/hid/mouse, payload: 'dx,dy' (e.g. '12.0,-5.0')",
        "params": ["dx", "dy"],
        "node": "phone",
    },
    {
        "id": "hid_click",
        "name": "HID Mouse Click",
        "description": "Send a mouse click event via MQTT.  Topic: cybersyn/hid/click, payload: 'left'|'right'|'middle'|'hold'|'release'|'double'",
        "params": ["button"],
        "node": "phone",
    },
    {
        "id": "hid_scroll",
        "name": "HID Mouse Scroll",
        "description": "Send a scroll event via MQTT.  Topic: cybersyn/hid/scroll, payload: scroll amount (negative = scroll up)",
        "params": ["dy"],
        "node": "phone",
    },
]

# Known relay nodes
NODES: list[dict[str, Any]] = [
    {
        "name": "comrade",
        "description": "Main workstation. Fedora 43, RX 9060 XT, Mosquitto host. "
        "Capabilities: notify, any future dispatch() arm.",
        "topics": "cybersyn/comrade/{action,event}",
    },
    {
        "name": "comintern",
        "description": "Display appliance, bare i3. "
        "Capabilities: notify, any future dispatch() arm.",
        "topics": "cybersyn/comintern/{action,event}",
    },
    {
        "name": "phone",
        "description": "Pixel 10 Pro (blazer). Android brain app + Termux. "
        "Capabilities: all Android triggers/actions, Termux shell, sensors.",
        "topics": "cybersyn/phone/{action,event,state}",
    },
]

# ---------------------------------------------------------------------------
# Rule store — one YAML/JSON file per rule in a directory
# ---------------------------------------------------------------------------

RULE_EXTENSIONS = {".yaml", ".yml", ".json"}


@dataclass
class Rule:
    name: str
    trigger: str  # trigger id
    trigger_params: dict[str, Any] = field(default_factory=dict)
    conditions: list[dict[str, Any]] = field(default_factory=list)
    action: str = ""  # action id
    action_params: dict[str, Any] = field(default_factory=dict)
    exit_action: str = ""  # action when trigger deactivates (e.g. app leaves foreground)
    exit_action_params: dict[str, Any] = field(default_factory=dict)
    enabled: bool = True
    cooldown_seconds: int = 0
    delay_seconds: int = 0  # wait before executing
    rule_file: Path | None = None  # source file on disk


def _parse_rule_file(path: Path) -> Rule | None:
    """Parse a YAML or JSON rule file into a Rule."""
    try:
        text = path.read_text()
        if path.suffix in (".yaml", ".yml"):
            data: dict[str, Any] = yaml.safe_load(text)
        else:
            data = json.loads(text)
        if not isinstance(data, dict):
            return None
        r = Rule(
            name=data.get("name", path.stem),
            trigger=data.get("trigger", ""),
            trigger_params=data.get("trigger_params", data.get("triggerParams", {})),
            conditions=data.get("conditions", []),
            action=data.get("action", ""),
            action_params=data.get("action_params", data.get("actionParams", {})),
            exit_action=data.get("exit_action", data.get("exitAction", "")),
            exit_action_params=data.get("exit_action_params", data.get("exitActionParams", {})),
            enabled=data.get("enabled", True),
            cooldown_seconds=data.get("cooldown", data.get("cooldown_seconds", 0)),
            delay_seconds=data.get("delay", data.get("delay_seconds", 0)),
            rule_file=path,
        )
        return r
    except Exception:
        return None


def _rule_to_dict(rule: Rule) -> dict[str, Any]:
    d: dict[str, Any] = {
        "name": rule.name,
        "trigger": rule.trigger,
        "trigger_params": rule.trigger_params,
        "conditions": rule.conditions,
        "action": rule.action,
        "action_params": rule.action_params,
        "enabled": rule.enabled,
        "cooldown": rule.cooldown_seconds,
    }
    if rule.exit_action:
        d["exit_action"] = rule.exit_action
        if rule.exit_action_params:
            d["exit_action_params"] = rule.exit_action_params
    if rule.delay_seconds:
        d["delay"] = rule.delay_seconds
    return d


class RuleStore:
    """Directory-backed rule store.  Each rule is one .yaml or .json file."""

    def __init__(self, rules_dir: Path) -> None:
        self.dir = rules_dir
        self.rules: dict[str, Rule] = {}
        self._load()

    def _load(self) -> None:
        if not self.dir.exists():
            return
        for path in sorted(self.dir.iterdir()):
            if path.suffix.lower() in RULE_EXTENSIONS:
                rule = _parse_rule_file(path)
                if rule is not None:
                    self.rules[rule.name] = rule

    def _save_rule(self, rule: Rule) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        # Use the existing filename if the rule was loaded from disk, else
        # derive a safe filename from the rule name.
        if rule.rule_file is not None:
            path = rule.rule_file
        else:
            safe = "".join(c if c.isalnum() or c in "_-" else "_" for c in rule.name)
            path = self.dir / f"{safe}.yaml"
            rule.rule_file = path
        path.write_text(yaml.dump(_rule_to_dict(rule), default_flow_style=False, sort_keys=False))

    def _delete_file(self, rule: Rule) -> None:
        if rule.rule_file is not None and rule.rule_file.exists():
            rule.rule_file.unlink()

    def add(self, rule: Rule) -> None:
        # If a rule with the same name exists from a different file, delete the old file
        existing = self.rules.get(rule.name)
        if existing is not None and existing.rule_file != rule.rule_file:
            self._delete_file(existing)
        self._save_rule(rule)
        self.rules[rule.name] = rule

    def remove(self, name: str) -> bool:
        rule = self.rules.pop(name, None)
        if rule is not None:
            self._delete_file(rule)
            return True
        return False

    def get(self, name: str) -> Rule | None:
        return self.rules.get(name)

    def list(self) -> list[Rule]:
        return list(self.rules.values())

    def reload(self) -> None:
        self.rules.clear()
        self._load()


# ---------------------------------------------------------------------------
# Globals — initialized in main()
# ---------------------------------------------------------------------------

bus: MqttBus
store: RuleStore
mcp = FastMCP(
    "Cybersyn Automation",
    instructions="LLM-drivable home-lab automation: triggers, actions, node control, and rule assembly for the Cybersyn engine.",
    dependencies=["paho-mqtt"],
)

# ---------------------------------------------------------------------------
# Discovery tools
# ---------------------------------------------------------------------------


@mcp.tool()
def cybersyn_list_triggers() -> str:
    """List all available trigger/context sources.  Each trigger has an id,
    a human-readable name, a description, and the parameters it accepts.
    Use this before creating a rule so you know what trigger ids exist."""
    return json.dumps(TRIGGERS, indent=2)


@mcp.tool()
def cybersyn_list_actions() -> str:
    """List all available actions.  Each action has an id, a name, a
    description, required parameters, and which node type can execute it
    (phone / relay / any)."""
    return json.dumps(ACTIONS, indent=2)


@mcp.tool()
def cybersyn_list_nodes() -> str:
    """List all known Cybersyn nodes (relay boxes, phone) with their
    capabilities and MQTT topic namespaces."""
    return json.dumps(NODES, indent=2)


# ---------------------------------------------------------------------------
# State / query tools
# ---------------------------------------------------------------------------


@mcp.tool()
def cybersyn_ping(node: str) -> str:
    """Ping a relay node.  Returns 'pong' if the node is online and responding.
    Also works as a general liveness check."""
    resp = bus.rpc(
        f"cybersyn/{node}/action",
        "ping",
        f"cybersyn/{node}/event",
    )
    if resp is None:
        return f"node '{node}' did not respond within {RPC_TIMEOUT}s — offline or unreachable"
    return resp


@mcp.tool()
def cybersyn_desktop_notify(node: str, title: str, body: str) -> str:
    """Send a desktop notification to a relay node (notify-send).
    The node must be running the cybersyn-relay service with a session bus."""
    payload = f"notify:{body}" if title == "Cybersyn" else f"notify:{title} — {body}"
    resp = bus.rpc(
        f"cybersyn/{node}/action",
        payload,
        f"cybersyn/{node}/event",
    )
    if resp is None:
        return f"node '{node}' did not respond — is cybersyn-relay running on it?"
    return resp


@mcp.tool()
def cybersyn_relay_shell(node: str, command: str) -> str:
    """Run a shell command on a relay node and return the output.
    The node must be running cybersyn-relay with the 'shell' dispatch arm
    (rebuild from Fedora_src/cybersyn-relay if the binary predates this)."""
    resp = bus.rpc(
        f"cybersyn/{node}/action",
        f"shell:{command}",
        f"cybersyn/{node}/event",
    )
    if resp is None:
        return f"node '{node}' did not respond within {RPC_TIMEOUT}s — offline or unreachable"
    return resp


# ---------------------------------------------------------------------------
# Rule management tools
# ---------------------------------------------------------------------------


@mcp.tool()
def cybersyn_create_rule(
    name: str,
    trigger: str,
    action: str,
    trigger_params: str = "{}",
    action_params: str = "{}",
    cooldown_seconds: int = 0,
) -> str:
    """Create or update an automation rule.

    name: unique name for this rule (e.g. 'bedtime-lights-off')
    trigger: trigger id from cybersyn_list_triggers (e.g. 'time_window')
    action: action id from cybersyn_list_actions (e.g. 'mqtt_publish')
    trigger_params: JSON dict of trigger parameters (e.g. '{"start_time":"23:00","end_time":"06:00"}')
    action_params: JSON dict of action parameters (e.g. '{"topic":"cmnd/tasmota/POWER","payload":"OFF"}')
    cooldown_seconds: minimum seconds between re-triggers (0 = no cooldown)

    The rule is stored locally and can be pushed to the phone brain app.
    """
    valid_triggers = {t["id"] for t in TRIGGERS}
    valid_actions = {a["id"] for a in ACTIONS}

    if trigger not in valid_triggers:
        return f"unknown trigger '{trigger}'. Use cybersyn_list_triggers to see available triggers."

    if action not in valid_actions:
        return f"unknown action '{action}'. Use cybersyn_list_actions to see available actions."

    try:
        tp = json.loads(trigger_params)
        ap = json.loads(action_params)
    except json.JSONDecodeError as e:
        return f"invalid JSON in trigger_params or action_params: {e}"

    existing = store.get(name)
    is_update = existing is not None

    rule = Rule(
        name=name,
        trigger=trigger,
        trigger_params=tp,
        action=action,
        action_params=ap,
        cooldown_seconds=cooldown_seconds,
        enabled=True,
    )
    store.add(rule)

    verb = "Updated" if is_update else "Created"
    return f"{verb} rule '{name}': when [{trigger}] → [{action}]{' (cooldown ' + str(cooldown_seconds) + 's)' if cooldown_seconds else ''}. Rules are stored locally; push to phone brain app with cybersyn_push_rules."


@mcp.tool()
def cybersyn_list_rules() -> str:
    """List all locally stored automation rules with their trigger, action,
    and enabled status."""
    rules = store.list()
    if not rules:
        return "No rules defined yet. Use cybersyn_create_rule to create one, and cybersyn_list_triggers + cybersyn_list_actions to explore options."

    out: list[dict[str, Any]] = []
    for r in rules:
        out.append(
            {
                "name": r.name,
                "trigger": r.trigger,
                "trigger_params": r.trigger_params,
                "action": r.action,
                "action_params": r.action_params,
                "enabled": r.enabled,
                "cooldown_seconds": r.cooldown_seconds,
            }
        )
    return json.dumps(out, indent=2)


@mcp.tool()
def cybersyn_enable_rule(name: str) -> str:
    """Enable a rule so it becomes active."""
    rule = store.get(name)
    if rule is None:
        return f"no rule named '{name}'. Use cybersyn_list_rules to see existing rules."
    rule.enabled = True
    store.add(rule)
    return f"rule '{name}' enabled"


@mcp.tool()
def cybersyn_disable_rule(name: str) -> str:
    """Disable a rule without deleting it."""
    rule = store.get(name)
    if rule is None:
        return f"no rule named '{name}'"
    rule.enabled = False
    store.add(rule)
    return f"rule '{name}' disabled"


@mcp.tool()
def cybersyn_delete_rule(name: str) -> str:
    """Permanently delete a rule."""
    if store.remove(name):
        return f"rule '{name}' deleted"
    return f"no rule named '{name}'"


# ---------------------------------------------------------------------------
# Direct execution
# ---------------------------------------------------------------------------


@mcp.tool()
def cybersyn_mqtt_publish(topic: str, payload: str) -> str:
    """Publish a raw MQTT message.  This is the universal bridge — you can
    trigger any relay action, talk to Tasmota/Zigbee devices, or fire
    MQTT-topic context triggers on the phone.

    Common topics:
      cybersyn/<node>/action — send command to a relay node
      cybersyn/<node>/event   — subscribe to see results
      cmnd/tasmota/POWER      — example: Tasmota smart plug toggle
    """
    bus.publish(topic, payload)
    return f"published to '{topic}': {payload}"


# ---------------------------------------------------------------------------
# Push rules to phone brain app (MQTT transport)
# ---------------------------------------------------------------------------


@mcp.tool()
def cybersyn_push_rules() -> str:
    """Push all locally stored rules to the phone's Cybersyn brain app via MQTT.
    The brain app subscribes to cybersyn/phone/config and applies rules on receipt."""
    rules = store.list()
    if not rules:
        return "no rules to push"

    payload = json.dumps(
        {"type": "rules_sync", "rules": [_rule_to_dict(r) for r in rules if r.enabled]}
    )
    bus.publish("cybersyn/phone/config", payload)
    return f"pushed {len(rules)} rules to phone (topic: cybersyn/phone/config)"


# ---------------------------------------------------------------------------
# One-shot rule deployment — the "stupidly easy" path
# ---------------------------------------------------------------------------


@mcp.tool()
def cybersyn_deploy_rule(yaml_or_json: str) -> str:
    """Deploy a rule in ONE shot.  Pass a YAML or JSON rule definition as a
    string literal.  The rule is validated, saved to disk, and pushed to the
    phone brain app via MQTT — everything in one tool call.

    YAML format (paste this pattern, change the values):

    name: bedtime-lights-off
    trigger: time_window
    trigger_params:
      start_time: "23:00"
      end_time: "06:00"
    action: mqtt_publish
    action_params:
      topic: cmnd/tasmota/POWER
      payload: "OFF"
    cooldown: 60

    JSON format works too:

    {"name":"bedtime-lights-off","trigger":"time_window","trigger_params":{"start_time":"23:00","end_time":"06:00"},"action":"mqtt_publish","action_params":{"topic":"cmnd/tasmota/POWER","payload":"OFF"},"cooldown":60}

    TRIGGER_IDS: app_foreground, time_window, time_repeating, boot_completed,
    battery_level, charging_state, screen_state, clipboard_change, variable_set,
    wifi_network, bluetooth_device, notification, headphones, nfc_tag,
    mqtt_topic, node_presence

    ACTION_IDS: termux_shell, launch_app, android_notify, variable_operation,
    desktop_notify, mqtt_publish

    termux_shell is the universal action — Cybersyn is a system priv-app with
    the same UID as Termux and full root access, so shell commands cover
    settings changes, HID injection, adbd/sshd, /proc|/sys, pm grant, am
    intents, clipboard ops, and anything else you can do in bash.  Use 'su -c'
    for root commands.

    Use cybersyn_list_triggers and cybersyn_list_actions for full details.
    """
    valid_triggers = {t["id"] for t in TRIGGERS}
    valid_actions = {a["id"] for a in ACTIONS}

    # Parse — try YAML first (JSON is valid YAML)
    try:
        data: dict[str, Any] = yaml.safe_load(yaml_or_json)
    except yaml.YAMLError as e:
        return f"parse error: {e}"

    if not isinstance(data, dict):
        return "rule must be a YAML/JSON object (dict), not a list or scalar"

    if "name" not in data or not data["name"]:
        return "rule must have a 'name' field"

    trigger = data.get("trigger", "")
    action = data.get("action", "")

    if trigger not in valid_triggers:
        return f"unknown trigger '{trigger}'. Valid: {', '.join(sorted(valid_triggers))}"

    if action not in valid_actions:
        return f"unknown action '{action}'. Valid: {', '.join(sorted(valid_actions))}"

    # Also validate exit_action if present
    exit_action = data.get("exit_action", data.get("exitAction", ""))
    if exit_action and exit_action not in valid_actions:
        return f"unknown exit_action '{exit_action}'. Valid: {', '.join(sorted(valid_actions))}"

    rule = Rule(
        name=data["name"],
        trigger=trigger,
        trigger_params=data.get("trigger_params", data.get("triggerParams", {})),
        conditions=data.get("conditions", []),
        action=action,
        action_params=data.get("action_params", data.get("actionParams", {})),
        exit_action=exit_action,
        exit_action_params=data.get("exit_action_params", data.get("exitActionParams", {})),
        enabled=data.get("enabled", True),
        cooldown_seconds=data.get("cooldown", data.get("cooldown_seconds", 0)),
        delay_seconds=data.get("delay", data.get("delay_seconds", 0)),
    )
    store.add(rule)

    # Push immediately to phone
    mqtt_payload = json.dumps(
        {"type": "rules_sync", "rules": [_rule_to_dict(rule)]}
    )
    bus.publish("cybersyn/phone/config", mqtt_payload)

    return (
        f"Deployed rule '{rule.name}': when [{rule.trigger}] → [{rule.action}]. "
        f"Saved to {rule.rule_file}, pushed to phone via MQTT."
    )


@mcp.tool()
def cybersyn_deploy_rules() -> str:
    """Bulk-deploy ALL rule files (.yaml/.json) from the rules directory to
    the phone brain app via MQTT.  Use this after editing rule files directly
    on disk to sync everything at once."""
    store.reload()
    rules = store.list()
    if not rules:
        return "no rules found in rules directory"

    payload = json.dumps(
        {"type": "rules_sync", "rules": [_rule_to_dict(r) for r in rules if r.enabled]}
    )
    bus.publish("cybersyn/phone/config", payload)
    return f"deployed {len(rules)} rules from {store.dir}"


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    global bus, store

    bus = MqttBus(BROKER_HOST, BROKER_PORT)
    store = RuleStore(RULES_DIR)

    transport = sys.argv[1] if len(sys.argv) > 1 else "stdio"

    if transport == "sse":
        host = os.environ.get("CYBERSYN_MCP_HOST", "0.0.0.0")
        port = int(os.environ.get("CYBERSYN_MCP_PORT", "8080"))
        # Host/port are set on the FastMCP instance, not passed to run()
        mcp.settings.host = host
        mcp.settings.port = port
        mcp.run(transport="sse")
    else:
        mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
