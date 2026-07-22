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
```

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
