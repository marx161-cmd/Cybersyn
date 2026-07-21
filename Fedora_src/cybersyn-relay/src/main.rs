//! Cybersyn relay agent — bullet 0 of the Cybersyn attack path.
//!
//! One binary, deployed identically to comrade and comintern. It subscribes to
//! `cybersyn/<node>/action` on the MQTT bus, runs the requested local action,
//! and publishes the outcome to `cybersyn/<node>/event`. Trust boundary is the
//! Tailscale mesh; there is deliberately no auth layer on top (see RECON §1.6).
//!
//! The message schema is intentionally tiny for now (RECON §7.3: grow it against
//! real rules). An action payload is `command` or `command:argument`, e.g.
//!   mosquitto_pub -h 100.108.8.60 -t cybersyn/comrade/action -m 'notify:hi'
//!
//! Add new host capabilities in `dispatch()` — that match arm is the whole
//! extension surface. New capability = new arm, nothing else changes.

use std::process::Command;
use std::time::Duration;

use clap::Parser;
use rumqttc::{Client, Event, MqttOptions, Packet, QoS};

#[derive(Parser, Debug)]
#[command(
    name = "cybersyn-relay",
    about = "Cybersyn relay agent: MQTT -> local action executor"
)]
struct Args {
    /// MQTT broker host. On comrade use 127.0.0.1; on comintern use comrade's
    /// Tailscale IP 100.108.8.60.
    #[arg(long, env = "CYBERSYN_BROKER", default_value = "127.0.0.1")]
    broker: String,

    /// MQTT broker port.
    #[arg(long, env = "CYBERSYN_PORT", default_value_t = 1883)]
    port: u16,

    /// Node name. Defaults to the short hostname; forms the topic namespace
    /// `cybersyn/<node>/{action,event}`.
    #[arg(long, env = "CYBERSYN_NODE")]
    node: Option<String>,

    /// Directory holding this node's capability scripts, invoked as
    /// `script:<name>` (see `dispatch()`). Dropping an executable file in here
    /// is the whole "new capability" workflow — no rebuild, no redeploy.
    #[arg(long, env = "CYBERSYN_SCRIPT_DIR", default_value = "~/.config/cybersyn/scripts")]
    script_dir: String,

    /// Enable the unbounded `shell:<command>` arm. Off by default: with it on,
    /// anything that can publish to this node's action topic runs arbitrary
    /// commands here, including an LLM composing a command string it has never
    /// had reviewed. Use `script:` for normal capabilities; turn this on
    /// deliberately when debugging, not as a standing door.
    #[arg(long, env = "CYBERSYN_ALLOW_SHELL", default_value_t = false)]
    allow_shell: bool,
}

/// Expand a leading `~/` against $HOME; leave everything else alone.
fn expand_home(path: &str) -> std::path::PathBuf {
    match path.strip_prefix("~/") {
        Some(rest) => match std::env::var_os("HOME") {
            Some(home) => std::path::Path::new(&home).join(rest),
            None => std::path::PathBuf::from(path),
        },
        None => std::path::PathBuf::from(path),
    }
}

fn short_hostname() -> String {
    std::fs::read_to_string("/proc/sys/kernel/hostname")
        .ok()
        .map(|h| h.trim().split('.').next().unwrap_or("node").to_string())
        .unwrap_or_else(|| "node".to_string())
}

fn main() {
    let args = Args::parse();
    let node = args.node.clone().unwrap_or_else(short_hostname);
    let script_dir = expand_home(&args.script_dir);
    let action_topic = format!("cybersyn/{node}/action");
    let event_topic = format!("cybersyn/{node}/event");

    let client_id = format!("cybersyn-relay-{node}");
    let mut opts = MqttOptions::new(&client_id, &args.broker, args.port);
    opts.set_keep_alive(Duration::from_secs(30));
    opts.set_clean_session(true);

    let (client, mut connection) = Client::new(opts, 10);
    client
        .subscribe(&action_topic, QoS::AtLeastOnce)
        .expect("initial subscribe should queue");

    println!(
        "cybersyn-relay: node={node} broker={}:{} listening on {action_topic}, results -> {event_topic}",
        args.broker, args.port
    );

    // rumqttc's event loop auto-reconnects; on error we log and keep iterating
    // (with a short backoff) so the relay survives broker restarts / net blips.
    for notification in connection.iter() {
        match notification {
            Ok(Event::Incoming(Packet::Publish(p))) => {
                let payload = String::from_utf8_lossy(&p.payload).to_string();
                println!("recv {} -> {payload:?}", p.topic);
                let result = dispatch(&payload, &script_dir, args.allow_shell);
                if let Err(e) =
                    client.publish(&event_topic, QoS::AtLeastOnce, false, result.into_bytes())
                {
                    eprintln!("failed to publish result: {e}");
                }
            }
            Ok(Event::Incoming(Packet::ConnAck(_))) => {
                // Re-subscribe after every (re)connect so a dropped session recovers.
                if let Err(e) = client.subscribe(&action_topic, QoS::AtLeastOnce) {
                    eprintln!("re-subscribe failed: {e}");
                }
                println!("connected to broker, subscribed to {action_topic}");
            }
            Ok(_) => {}
            Err(e) => {
                eprintln!("mqtt connection error: {e}; retrying");
                std::thread::sleep(Duration::from_secs(2));
            }
        }
    }
}

/// A capability script name is a bare filename: letters, digits, `_`, `-`, `.`.
/// No separators, no `..`, no leading dot. This is what keeps `script:` a
/// closed vocabulary — the caller picks a name, it can never compose a path.
fn valid_script_name(name: &str) -> bool {
    !name.is_empty()
        && !name.starts_with('.')
        && name.len() <= 64
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-' || c == '.')
}

/// Parse `command` or `command:argument` and run the matching host action.
/// Returns a short result string that gets published to the event topic.
fn dispatch(payload: &str, script_dir: &std::path::Path, allow_shell: bool) -> String {
    let (cmd, arg) = match payload.split_once(':') {
        Some((c, a)) => (c.trim(), a.trim()),
        None => (payload.trim(), ""),
    };

    match cmd {
        // Guaranteed-observable round-trip proof; needs no display/dbus.
        "ping" => "pong".to_string(),

        // Desktop notification. Needs a notification daemon + session bus in the
        // relay's environment (a --user unit inherits these; see README).
        "notify" => {
            let body = if arg.is_empty() { "Cybersyn relay" } else { arg };
            match Command::new("notify-send").arg("Cybersyn").arg(body).status() {
                Ok(s) if s.success() => format!("notify:ok:{body}"),
                Ok(s) => format!("notify:exit:{}", s.code().unwrap_or(-1)),
                Err(e) => format!("notify:err:{e}"),
            }
        }

        // --- Cybersyn extension point ---------------------------------------
        // Add new host capabilities here, one match arm per capability. This is
        // the entire surface that grows as rules are added (OVERVIEW: new
        // capability = new rule in the same engine).
        // --------------------------------------------------------------------

        // Named capability script: `script:<name>` or `script:<name> <args>`.
        // This is the normal action surface. The scripts are ordinary shell, so
        // anything expressible via `shell:` is still expressible — the
        // difference is that the operator writes them here ahead of time
        // instead of the caller composing a command string over MQTT. Mirrors
        // the phone side, where the brain invokes named Termux scripts
        // (hidkey.sh, click.sh) rather than arbitrary commands.
        "script" => {
            let (name, script_args) = match arg.split_once(char::is_whitespace) {
                Some((n, a)) => (n.trim(), a.trim()),
                None => (arg, ""),
            };
            if name.is_empty() {
                return "error:script:missing-name".to_string();
            }
            if !valid_script_name(name) {
                return format!("error:script:invalid-name:{name}");
            }
            let path = script_dir.join(name);
            if !path.is_file() {
                return format!("error:script:not-found:{name}");
            }
            let mut cmd = Command::new(&path);
            if !script_args.is_empty() {
                cmd.args(script_args.split_whitespace());
            }
            match cmd.output() {
                Ok(output) => {
                    let stdout = String::from_utf8_lossy(&output.stdout);
                    let stderr = String::from_utf8_lossy(&output.stderr);
                    let code = output.status.code().unwrap_or(-1);
                    if output.status.success() {
                        format!("script:{name}:ok:{}", stdout.trim())
                    } else {
                        format!("script:{name}:exit:{code}:stderr:{}", stderr.trim())
                    }
                }
                Err(e) => format!("script:{name}:err:{e}"),
            }
        }

        // Enumerate this node's capabilities. Lets the MCP catalog be generated
        // from what each node actually has, instead of a hand-kept list that
        // drifts from reality.
        "capabilities" => match std::fs::read_dir(script_dir) {
            Ok(entries) => {
                let mut names: Vec<String> = entries
                    .filter_map(|e| e.ok())
                    .filter(|e| e.path().is_file())
                    .filter_map(|e| e.file_name().into_string().ok())
                    .filter(|n| valid_script_name(n))
                    .collect();
                names.sort();
                format!("capabilities:ping,notify,{}", names.join(","))
            }
            Err(e) => format!("capabilities:ping,notify (script dir unreadable: {e})"),
        },

        // Unbounded shell pass-through — OFF unless --allow-shell is set.
        // With it on, any MQTT publisher (including an LLM composing a string
        // nobody reviewed) runs arbitrary commands here. Prefer `script:`.
        "shell" => {
            if !allow_shell {
                return "error:shell:disabled (relay started without --allow-shell; use script:<name>)"
                    .to_string();
            }
            if arg.is_empty() {
                return "error:shell:missing-command".to_string();
            }
            match Command::new("sh").arg("-c").arg(arg).output() {
                Ok(output) => {
                    let stdout = String::from_utf8_lossy(&output.stdout);
                    let stderr = String::from_utf8_lossy(&output.stderr);
                    let code = output.status.code().unwrap_or(-1);
                    if output.status.success() {
                        format!("shell:ok:{}", stdout.trim())
                    } else {
                        format!("shell:exit:{}:stderr:{}", code, stderr.trim())
                    }
                }
                Err(e) => format!("shell:err:{e}"),
            }
        }

        other => {
            eprintln!("unknown command: {other:?}");
            format!("error:unknown-command:{other}")
        }
    }
}
