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

| payload            | effect                                              |
|--------------------|-----------------------------------------------------|
| `ping`             | publishes `pong` to the event topic (no display needed) |
| `notify:hello`     | `notify-send "Cybersyn" "hello"`                    |

Everything else publishes `error:unknown-command:<cmd>`.

New host capabilities are added as one match arm in `dispatch()` in
`src/main.rs` — that is the entire extension surface.

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
