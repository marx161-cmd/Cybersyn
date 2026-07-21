# cybersyn-relay

Bullet 0 of the Cybersyn attack path: the Linux relay agent. One binary,
deployed identically to **comrade** and **comintern**. It subscribes to
`cybersyn/<node>/action` on the MQTT bus (Mosquitto on comrade,
`100.108.8.60:1883`), runs the requested local action, and publishes the
outcome to `cybersyn/<node>/event`.

Trust boundary is the always-on Tailscale mesh; there is deliberately no auth
layer on top (RECON §1.6).

## Message schema (intentionally tiny — grows per rule, RECON §7.3)

An action payload is `command` or `command:argument`:

| payload                  | effect                                                  |
|--------------------------|---------------------------------------------------------|
| `ping`                   | publishes `pong` to the event topic (no display needed)  |
| `notify:hello`           | `notify-send "Cybersyn" "hello"`                         |
| `capabilities`           | lists this node's script names — the catalog source      |
| `script:<name>`          | runs `<script-dir>/<name>`                               |
| `script:<name> a b`      | same, with whitespace-split arguments                    |
| `shell:<command>`        | arbitrary `sh -c`. **Disabled unless `--allow-shell`**   |

Everything else publishes `error:unknown-command:<cmd>`.

## Adding a capability

Drop an executable file in the script dir
(`--script-dir`, `$CYBERSYN_SCRIPT_DIR`, default `~/.config/cybersyn/scripts`).
That is the whole workflow — no rebuild, no redeploy, and `capabilities`
picks it up immediately so the MCP catalog can be generated from what the
node actually has.

This mirrors the phone side, where the brain invokes named Termux scripts
(`hidkey.sh`, `click.sh`) rather than composing shell over the wire. The
scripts are ordinary shell, so nothing is less expressive than `shell:` —
the difference is that *you* write the command here, ahead of time, instead
of the caller composing a string that was never reviewed.

`script:` names are bare filenames only (alphanumeric, `_`, `-`, `.`; no
separators, no `..`, no leading dot). A name that fails validation or does
not exist fails closed and executes nothing.

### On `shell:` being off by default

Anything able to publish to this node's action topic can reach this arm —
including an LLM composing a command string through the MCP bridge, and any
rule you wrote months ago and no longer remember. `script:` bounds that to a
vocabulary you curated; `shell:` does not. Turn it on deliberately while
debugging (`--allow-shell` / `CYBERSYN_ALLOW_SHELL=1`), not as a standing door.

## Build

```sh
cd ~/builds/crossplatform/Cybersyn/Fedora_src/cybersyn-relay
cargo build --release
# binary: target/release/cybersyn-relay
```

## Run (foreground, for testing)

```sh
./target/release/cybersyn-relay --broker 127.0.0.1 --port 1883
# node defaults to the short hostname; override with --node or CYBERSYN_NODE
```

## Prove it end-to-end (simulating the phone with mosquitto_pub)

```sh
# terminal 1: watch results
mosquitto_sub -h 127.0.0.1 -t 'cybersyn/comrade/event' -v
# terminal 2: fire an action (this is what the phone's OpenTasker shell action does)
mosquitto_pub -h 127.0.0.1 -t 'cybersyn/comrade/action' -m ping      # -> pong
mosquitto_pub -h 127.0.0.1 -t 'cybersyn/comrade/action' -m 'notify:hi from phone'
```

On the phone, the equivalent trigger is an OpenTasker/Cybersyn shell action
(same-UID with Termux, so it runs `mosquitto_pub` directly):

```sh
mosquitto_pub -h 100.108.8.60 -t cybersyn/comrade/action -m 'notify:hi from phone'
```

## Install as a service

See `cybersyn-relay.service` (a `--user` unit, so desktop actions inherit the
session bus / DISPLAY). On comintern, set `CYBERSYN_BROKER=100.108.8.60`.

## History

- The old phone→host control surface (`p30control.service`, gyro→uinput on
  `android/*` topics) was disabled 2026-07-19 to avoid two competing control
  surfaces. Re-enable with `systemctl --user enable --now p30control.service`.
  Cybersyn uses the `cybersyn/*` topic namespace, so there is no collision if
  both ever run.
