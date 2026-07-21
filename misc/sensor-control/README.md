# sensor-control

Phone sensor and trigger pipeline for Linux input control.

## Goals

- Keep input adapters, core logic, mapping rules, and output adapters separated.
- Make setup/recovery repeatable after phone factory resets.
- Keep behavior testable with recorded events.

## Layout

- `src/sensor_control/`
- `config/default.json`
- `docs/event-schema.md`
- `docs/state-machine.md`
- `docs/ops.md`
- `scripts/bootstrap-host.sh`
- `scripts/self-heal.sh`
- `systemd/sensor-control.service.in`
- `tests/fixtures/`

## Project Status

This is a public source release of a personal homelab/accessibility tool. Use
it, fork it, adapt it, or strip it for parts. Do not expect maintenance,
support, compatibility guarantees, or a stable roadmap from the original
author.

## Run

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

PYTHONPATH="$PWD/src" \
python3 -m sensor_control.main \
  --config "$PWD/config/default.json"
```

## Install service

```bash
./scripts/bootstrap-host.sh --enable-now
```

## License

MIT. See [LICENSE](LICENSE). Additional project expectations are in
[NOTICE.md](NOTICE.md).
