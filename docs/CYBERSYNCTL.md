# cybersynctl

`tools/cybersynctl` is the Cybersyn authoring/control surface. It deliberately
uses the existing Tailscale paths instead of a resident phone-side MCP/server:

- ADB to blazer: `100.69.13.12:5555`
- Termux SSH/SCP to blazer: `u0_a464@100.69.13.12:8022`
- Cybersyn app receiver: DUMP-protected broadcast actions, callable by `adb shell`

## Commands

```sh
tools/cybersynctl list-tasks
tools/cybersynctl list-profiles
tools/cybersynctl export [bundle.json]
tools/cybersynctl apply examples/quicktap-sidebar.yaml
tools/cybersynctl profile enable QuickTapSidebarTrigger
tools/cybersynctl profile disable QuickTapSidebarTrigger
tools/cybersynctl run "Some Non-UI Task"
tools/cybersynctl explain [out.json]
```

`explain` is the one-shot manifest call — run this first, before authoring anything.
It returns the real, live vocabulary (not this doc): every trigger `type` string
registered in `ContextSourceRegistry`, every `automationMode` enum value, every
action id with its args (`fields`: key/type/required) and whether it's actually
usable on this device right now (`capability`/`can_add`/`reason`), plus current
tasks/profiles, the last 30 run-log entries (so recent failures are visible without
a separate query), and a live memory/CPU/process/battery/thermal snapshot. With no
path argument it prints the JSON to stdout; with a path it writes there instead.
Internally it broadcasts to `ExplainReceiver` and reads back the JSON it mirrors to
`/data/local/tmp/cybersyn_explain.json` — no scp needed, unlike `export`.

The per-type `config` shape for triggers isn't structured anywhere in source (a
context source only declares its `type` string), so that part still comes from the
YAML Shape example below, not from `explain`.

`apply` converts simple YAML to Cybersyn bundle JSON on the host, then imports
it through ADB. It uses replace-by-name semantics for tasks/profiles in the
incoming file, so reapplying the same YAML updates that automation rather than
creating another enabled duplicate.

`run` is useful for non-UI tasks. Android background-activity-launch limits can
still block tasks whose only job is starting another app/activity; event/profile
paths such as the Quick Tap Relay remain the right path for those.

## YAML Shape

```yaml
name: Example
tasks:
  - name: Notify Example
    actions:
      - type: notify.show
        args:
          title: Cybersyn
          text: Hello
profiles:
  - name: ExampleTrigger
    enabled: true
    automationMode: RESTART
    enterTask: Notify Example
    contexts:
      - type: EVENT
        config:
          event: external_trigger
          trigger: example
```

For a proven real example, see `examples/quicktap-sidebar.yaml`.
