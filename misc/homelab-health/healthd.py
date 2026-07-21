#!/usr/bin/env python3
import argparse
import json
import os
import shlex
import socket
import subprocess
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Tuple
from urllib.error import URLError
from urllib.request import Request, urlopen


@dataclass
class CheckResult:
    name: str
    ok: bool
    reason: str
    detail: Dict[str, Any]
    latency_ms: int


class HealthEngine:
    def __init__(self, config_path: Path):
        self.config_path = config_path
        self.config = self._load_config(config_path)

    def _load_config(self, path: Path) -> Dict[str, Any]:
        with path.open("r", encoding="utf-8") as f:
            data = json.load(f)
        if "checks" not in data or not isinstance(data["checks"], dict):
            raise ValueError("config must contain object 'checks'")
        return data

    def reload(self) -> None:
        self.config = self._load_config(self.config_path)

    def run_all(self) -> Tuple[Dict[str, CheckResult], bool]:
        checks = self.config.get("checks", {})
        results: Dict[str, CheckResult] = {}

        for name, spec in checks.items():
            results[name] = self._run_one(name, spec)

        # Apply dependency gating after primary checks
        for name, spec in checks.items():
            deps = spec.get("depends_on", [])
            if not deps:
                continue
            missing = [d for d in deps if not results.get(d, CheckResult(d, False, "missing", {}, 0)).ok]
            if missing:
                r = results[name]
                results[name] = CheckResult(
                    name=name,
                    ok=False,
                    reason=f"dependency_failed:{','.join(missing)}",
                    detail={**r.detail, "failed_dependencies": missing},
                    latency_ms=r.latency_ms,
                )

        overall_ok = True
        for name, spec in checks.items():
            required = bool(spec.get("required", True))
            if required and not results[name].ok:
                overall_ok = False
                break

        return results, overall_ok

    def _run_one(self, name: str, spec: Dict[str, Any]) -> CheckResult:
        t0 = time.time()
        ctype = spec.get("type", "")
        timeout = int(spec.get("timeout_sec", 3))
        try:
            if ctype == "user_service":
                ok, reason, detail = self._check_systemd(["systemctl", "--user", "is-active", spec["unit"]])
            elif ctype == "system_service":
                ok, reason, detail = self._check_systemd(["systemctl", "is-active", spec["unit"]])
            elif ctype == "process":
                ok, reason, detail = self._check_process(spec["pattern"], timeout)
            elif ctype == "tcp":
                ok, reason, detail = self._check_tcp(spec.get("host", "127.0.0.1"), int(spec["port"]), timeout)
            elif ctype == "http":
                ok, reason, detail = self._check_http(spec["url"], timeout, spec.get("expect_status"))
            elif ctype == "docker_container":
                ok, reason, detail = self._check_docker(spec["name"], bool(spec.get("require_healthy", False)), timeout)
            elif ctype == "command":
                ok, reason, detail = self._check_command(spec["command"], timeout)
            else:
                ok, reason, detail = False, "unknown_check_type", {"type": ctype}
        except Exception as e:  # defensive so one bad check never kills API
            ok, reason, detail = False, "exception", {"error": str(e), "type": ctype}

        latency_ms = int((time.time() - t0) * 1000)
        return CheckResult(name=name, ok=ok, reason=reason, detail=detail, latency_ms=latency_ms)

    def _check_systemd(self, cmd: List[str]) -> Tuple[bool, str, Dict[str, Any]]:
        p = subprocess.run(cmd, capture_output=True, text=True)
        out = (p.stdout or "").strip()
        err = (p.stderr or "").strip()
        ok = p.returncode == 0 and out == "active"
        return ok, ("active" if ok else "inactive"), {"stdout": out, "stderr": err, "returncode": p.returncode}

    def _check_process(self, pattern: str, timeout: int) -> Tuple[bool, str, Dict[str, Any]]:
        p = subprocess.run(["pgrep", "-f", pattern], capture_output=True, text=True, timeout=timeout)
        pids = [line.strip() for line in (p.stdout or "").splitlines() if line.strip()]
        ok = len(pids) > 0
        return ok, ("running" if ok else "not_running"), {"pids": pids}

    def _check_tcp(self, host: str, port: int, timeout: int) -> Tuple[bool, str, Dict[str, Any]]:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(timeout)
        try:
            s.connect((host, port))
            return True, "open", {"host": host, "port": port}
        except OSError as e:
            return False, "closed", {"host": host, "port": port, "error": str(e)}
        finally:
            s.close()

    def _check_http(self, url: str, timeout: int, expect_status: Any) -> Tuple[bool, str, Dict[str, Any]]:
        req = Request(url, method="GET")
        try:
            with urlopen(req, timeout=timeout) as resp:
                code = int(resp.status)
                body = resp.read(256).decode("utf-8", errors="replace")
        except URLError as e:
            return False, "unreachable", {"url": url, "error": str(e)}

        if expect_status is None:
            ok = 200 <= code < 300
        elif isinstance(expect_status, list):
            ok = code in [int(x) for x in expect_status]
        else:
            ok = code == int(expect_status)

        return ok, ("ok" if ok else "bad_status"), {"url": url, "status": code, "body_head": body}

    def _check_docker(self, name: str, require_healthy: bool, timeout: int) -> Tuple[bool, str, Dict[str, Any]]:
        fmt = "{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}"
        p = subprocess.run(["docker", "inspect", "-f", fmt, name], capture_output=True, text=True, timeout=timeout)
        out = (p.stdout or "").strip()
        if p.returncode != 0:
            return False, "not_found", {"stderr": (p.stderr or "").strip(), "returncode": p.returncode}
        parts = out.split("|", 1)
        status = parts[0] if parts else "unknown"
        health = parts[1] if len(parts) > 1 else "none"
        ok = status == "running" and ((health == "healthy") if require_healthy else True)
        reason = "running" if ok else f"{status}/{health}"
        return ok, reason, {"container": name, "status": status, "health": health}

    def _check_command(self, command: str, timeout: int) -> Tuple[bool, str, Dict[str, Any]]:
        p = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=timeout)
        ok = p.returncode == 0
        return ok, ("ok" if ok else "failed"), {
            "command": command,
            "returncode": p.returncode,
            "stdout": (p.stdout or "").strip()[:300],
            "stderr": (p.stderr or "").strip()[:300],
        }


def build_payload(results: Dict[str, CheckResult], overall_ok: bool, config: Dict[str, Any]) -> Dict[str, Any]:
    checks = {}
    for name, result in results.items():
        spec = config["checks"].get(name, {})
        checks[name] = {
            "ok": result.ok,
            "reason": result.reason,
            "latency_ms": result.latency_ms,
            "required": bool(spec.get("required", True)),
            "description": spec.get("description", ""),
            "detail": result.detail,
        }
    return {
        "ok": overall_ok,
        "timestamp": int(time.time()),
        "host": socket.gethostname(),
        "checks": checks,
    }


class Handler(BaseHTTPRequestHandler):
    engine: HealthEngine = None  # injected

    def log_message(self, fmt: str, *args: Any) -> None:
        return

    def _send_json(self, status: int, data: Dict[str, Any]) -> None:
        body = json.dumps(data, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_head_status(self, status: int) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def _resolve_status_for_path(self, path: str) -> Tuple[int, Dict[str, Any]]:
        if path == "/reload":
            try:
                self.engine.reload()
                return 200, {"ok": True, "reloaded": True}
            except Exception as e:
                return 500, {"ok": False, "error": str(e)}

        results, overall_ok = self.engine.run_all()
        payload = build_payload(results, overall_ok, self.engine.config)

        if path in ["/", "/health", "/status", "/status.json"]:
            return (200 if overall_ok else 503), payload

        if path.startswith("/health/"):
            name = path[len("/health/"):]
            if not name:
                return 404, {"ok": False, "error": "missing_check_name"}
            if name not in payload["checks"]:
                return 404, {"ok": False, "error": "unknown_check", "name": name}
            item = payload["checks"][name]
            return (200 if item["ok"] else 503), {"ok": item["ok"], "name": name, **item}

        return 404, {"ok": False, "error": "not_found", "path": path}

    def do_GET(self) -> None:
        path = self.path.split("?", 1)[0]
        status, payload = self._resolve_status_for_path(path)
        self._send_json(status, payload)

    def do_HEAD(self) -> None:
        path = self.path.split("?", 1)[0]
        status, _payload = self._resolve_status_for_path(path)
        self._send_head_status(status)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="/home/comrade/.config/homelab-health/checks.json")
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18081)
    args = parser.parse_args()

    engine = HealthEngine(Path(args.config))
    Handler.engine = engine

    server = ThreadingHTTPServer((args.listen, args.port), Handler)
    print(f"homelab-healthd listening on http://{args.listen}:{args.port} using {args.config}")
    server.serve_forever()


if __name__ == "__main__":
    main()
