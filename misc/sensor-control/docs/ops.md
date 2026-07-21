# Ops Runbook

## Host bootstrap

```bash
./scripts/bootstrap-host.sh --enable-now
```

## Service status

```bash
systemctl --user status sensor-control
journalctl --user -u sensor-control -f
```

## Self-heal check

```bash
./scripts/self-heal.sh
```

## Broker validation

```bash
mosquitto_sub -h 127.0.0.1 -t 'android/#' -v
```

## Factory reset recovery checklist

1. Reinstall phone apps: Tasker, Termux, Key Mapper.
2. Recreate phone scripts:
- `~/.termux/tasker/clutch.sh`
- `~/.termux/tasker/click.sh`
3. Recreate Key Mapper actions pointing to those scripts.
4. Re-enable Tasker sensor stream for `android/sensor`.
5. Confirm events arrive with `mosquitto_sub`.
6. Verify `sensor-control` service state and cursor behavior.
