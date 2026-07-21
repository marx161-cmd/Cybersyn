from flask import Flask, request
import subprocess
import os

app = Flask(__name__)

SILLY_UNIT = "sillytavern.service"
COMFY_UNIT = "comfyui.service"
FACEFUSION_UNIT = "facefusion-ui-gpu.service"
ARM_FILE = os.path.expanduser("~/homelab/amd-control/ARMED")
TOKEN_FILE = os.path.expanduser("~/homelab/amd-control/control-api/ACTION_TOKEN")


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def set_unit_state(unit: str, target: str):
    if target == "start":
        run(["systemctl", "--user", "start", unit])
        return f"{unit} started\n", 200
    if target == "stop":
        run(["systemctl", "--user", "stop", unit])
        return f"{unit} stopped\n", 200
    return "invalid target state\n", 400


def set_mode(mode: str):
    """
    Deterministic switch for frequent remote use.
    - comfy mode: comfy on, facefusion off
    - facefusion mode: facefusion on, comfy off
    """
    if mode == "comfy":
        run(["systemctl", "--user", "stop", FACEFUSION_UNIT])
        run(["systemctl", "--user", "start", COMFY_UNIT])
        return (
            f"mode=comfy ({COMFY_UNIT}=started, {FACEFUSION_UNIT}=stopped)\n",
            200,
        )
    if mode == "facefusion":
        run(["systemctl", "--user", "stop", COMFY_UNIT])
        run(["systemctl", "--user", "start", FACEFUSION_UNIT])
        return (
            f"mode=facefusion ({FACEFUSION_UNIT}=started, {COMFY_UNIT}=stopped)\n",
            200,
        )
    return "invalid mode\n", 400


def is_silly_active():
    result = run(["systemctl", "--user", "is-active", SILLY_UNIT])
    return result.returncode == 0


def is_comfy_active():
    result = run(["systemctl", "--user", "is-active", COMFY_UNIT])
    return result.returncode == 0


def is_facefusion_active():
    result = run(["systemctl", "--user", "is-active", FACEFUSION_UNIT])
    return result.returncode == 0


def is_armed():
    return os.path.exists(ARM_FILE)


def remote_addr():
    xff = request.headers.get("X-Forwarded-For", "").split(",")[0].strip()
    if xff:
        return xff
    return request.remote_addr or ""


def read_action_token():
    try:
        with open(TOKEN_FILE, "r", encoding="utf-8") as f:
            return f.read().strip()
    except FileNotFoundError:
        return ""


def action_allowed():
    # Guard mutating routes from status-pollers.
    # Require explicit ?action=1 and auth token for remote callers.
    if request.args.get("action") != "1":
        return False
    addr = remote_addr()
    if addr in ("127.0.0.1", "::1"):
        return True
    supplied = request.args.get("token", "")
    expected = read_action_token()
    return bool(expected) and supplied == expected


@app.get("/toggle/silly")
def toggle_silly():
    if not action_allowed():
        return "noop: add ?action=1 to execute\n", 200
    if is_silly_active():
        run(["systemctl", "--user", "stop", SILLY_UNIT])
        return f"{SILLY_UNIT} stopped\n", 200
    else:
        run(["systemctl", "--user", "start", SILLY_UNIT])
        return f"{SILLY_UNIT} started\n", 200


@app.get("/status/silly")
def status_silly():
    result = run(["systemctl", "--user", "is-active", SILLY_UNIT])
    return f"{SILLY_UNIT}: {result.stdout.strip()}\n", 200


@app.get("/start/silly")
def start_silly():
    if not action_allowed():
        return "noop: add ?action=1 to execute\n", 200
    return set_unit_state(SILLY_UNIT, "start")


@app.get("/stop/silly")
def stop_silly():
    if not action_allowed():
        return "noop: add ?action=1 to execute\n", 200
    return set_unit_state(SILLY_UNIT, "stop")


@app.get("/toggle/comfy")
def toggle_comfy():
    if not action_allowed():
        return "noop: add ?action=1 to execute\n", 200
    # Keep this endpoint idempotent for Dashy polling:
    # user flow is "toggle off comfy".
    run(["systemctl", "--user", "stop", COMFY_UNIT])
    return f"{COMFY_UNIT} stopped\n", 200


@app.get("/status/comfy")
def status_comfy():
    result = run(["systemctl", "--user", "is-active", COMFY_UNIT])
    return f"{COMFY_UNIT}: {result.stdout.strip()}\n", 200


@app.get("/start/comfy")
def start_comfy():
    if not action_allowed():
        return "noop: add ?action=1 to execute\n", 200
    return set_unit_state(COMFY_UNIT, "start")


@app.get("/stop/comfy")
def stop_comfy():
    if not action_allowed():
        return "noop: add ?action=1 to execute\n", 200
    return set_unit_state(COMFY_UNIT, "stop")


@app.get("/toggle/facefusion")
def toggle_facefusion():
    if not action_allowed():
        return "noop: add ?action=1 to execute\n", 200
    # Keep this endpoint idempotent for Dashy polling:
    # user flow is "toggle on facefusion".
    run(["systemctl", "--user", "start", FACEFUSION_UNIT])
    return f"{FACEFUSION_UNIT} started\n", 200


@app.get("/status/facefusion")
def status_facefusion():
    result = run(["systemctl", "--user", "is-active", FACEFUSION_UNIT])
    return f"{FACEFUSION_UNIT}: {result.stdout.strip()}\n", 200


@app.get("/start/facefusion")
def start_facefusion():
    if not action_allowed():
        return "noop: add ?action=1 to execute\n", 200
    return set_unit_state(FACEFUSION_UNIT, "start")


@app.get("/stop/facefusion")
def stop_facefusion():
    if not action_allowed():
        return "noop: add ?action=1 to execute\n", 200
    return set_unit_state(FACEFUSION_UNIT, "stop")


@app.get("/mode/comfy")
def mode_comfy():
    if not action_allowed():
        return "noop: add ?action=1 to execute\n", 200
    return set_mode("comfy")


@app.get("/mode/facefusion")
def mode_facefusion():
    if not action_allowed():
        return "noop: add ?action=1 to execute\n", 200
    return set_mode("facefusion")


@app.get("/mode/status")
def mode_status():
    comfy = "active" if is_comfy_active() else "inactive"
    facefusion = "active" if is_facefusion_active() else "inactive"
    if comfy == "active" and facefusion == "inactive":
        mode = "comfy"
    elif facefusion == "active" and comfy == "inactive":
        mode = "facefusion"
    elif facefusion == "inactive" and comfy == "inactive":
        mode = "idle"
    else:
        mode = "mixed"
    return (
        f"mode={mode} ({COMFY_UNIT}={comfy}, {FACEFUSION_UNIT}={facefusion})\n",
        200,
    )


@app.get("/suspend/hard")
def suspend_hard():
    if not action_allowed():
        return "noop: add ?action=1 to execute\n", 200
    # safety: only suspend if ARMED file exists
    if not is_armed():
        return "suspend DISARMED\n", 200
    run(["systemctl", "suspend", "-i"])
    return "suspending NOW (inhibitors ignored)\n", 200


if __name__ == "__main__":
    host = os.environ.get("CONTROL_API_HOST", "100.108.8.60")
    port = int(os.environ.get("CONTROL_API_PORT", "9000"))
    app.run(host=host, port=port)
