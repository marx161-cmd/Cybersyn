mod clipboard;
mod file_transfer;
mod mpris;

use std::path::PathBuf;
use std::process::Command;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use clap::Parser;
use rumqttc::{Client, Event, MqttOptions, Packet, QoS};

const CLIPBOARD_TOPIC_PIXEL: &str = "cybersyn/clipboard/pixel";
const FILE_OFFER_TOPIC: &str = "cybersyn/file/offer";
const DOWNLOADS_DIR: &str = "~/Downloads/cybersyn";

#[derive(Parser, Debug)]
#[command(
    name = "cybersyn-relay",
    about = "Cybersyn relay agent: MQTT -> local action executor + MPRIS/clipboard/file-transfer"
)]
struct Args {
    #[arg(long, env = "CYBERSYN_BROKER", default_value = "127.0.0.1")]
    broker: String,

    #[arg(long, env = "CYBERSYN_PORT", default_value_t = 1883)]
    port: u16,

    #[arg(long, env = "CYBERSYN_NODE")]
    node: Option<String>,

    #[arg(long, env = "CYBERSYN_SCRIPT_DIR", default_value = "~/.config/cybersyn/scripts")]
    script_dir: String,

    #[arg(long, env = "CYBERSYN_ALLOW_SHELL", default_value_t = false)]
    allow_shell: bool,

    #[arg(long, default_value_t = true)]
    enable_mpris: bool,

    #[arg(long, default_value_t = true)]
    enable_clipboard: bool,
}

fn expand_home(path: &str) -> PathBuf {
    match path.strip_prefix("~/") {
        Some(rest) => match std::env::var_os("HOME") {
            Some(home) => std::path::Path::new(&home).join(rest),
            None => PathBuf::from(path),
        },
        None => PathBuf::from(path),
    }
}

fn short_hostname() -> String {
    std::fs::read_to_string("/proc/sys/kernel/hostname")
        .ok()
        .map(|h| h.trim().split('.').next().unwrap_or("node").to_string())
        .unwrap_or_else(|| "node".to_string())
}

fn valid_script_name(name: &str) -> bool {
    !name.is_empty()
        && !name.starts_with('.')
        && name.len() <= 64
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-' || c == '.')
}

/// Bare filename only — no path separators or traversal, so a `file:receive`
/// destination can never land outside DOWNLOADS_DIR.
fn valid_dest_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 255
        && name != "."
        && name != ".."
        && !name.contains('/')
        && !name.contains('\\')
}

/// SHA-256 hex digest only, matching what mpris::cache_album_art actually writes.
fn valid_hash(hash: &str) -> bool {
    hash.len() == 64 && hash.chars().all(|c| c.is_ascii_hexdigit())
}

fn run_playerctl(args: &[&str]) -> Result<String, String> {
    let output = Command::new("playerctl")
        .args(args)
        .output()
        .map_err(|e| format!("playerctl spawn: {e}"))?;
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    if output.status.success() {
        Ok(stdout)
    } else {
        Err(if stderr.is_empty() { stdout } else { stderr })
    }
}

fn dispatch(
    payload: &str,
    script_dir: &std::path::Path,
    allow_shell: bool,
    mqtt: &Client,
    clipboard_state: &Arc<Mutex<clipboard::ClipboardState>>,
    file_offer_topic: &str,
    event_topic: &str,
) -> String {
    let (cmd, arg) = match payload.split_once(':') {
        Some((c, a)) => (c.trim(), a.trim()),
        None => (payload.trim(), ""),
    };

    // Handle dot-separated mpris commands (e.g., "mpris.players")
    if let Some(sub) = cmd.strip_prefix("mpris.") {
        return dispatch_mpris(sub);
    }

    match cmd {
        "ping" => "pong".to_string(),

        "notify" => {
            let body = if arg.is_empty() { "Cybersyn relay" } else { arg };
            let mqtt_clone = mqtt.clone();
            let event_topic_clone = event_topic.to_string();
            let notify_body = body.to_string();
            std::thread::spawn(move || {
                let result = match Command::new("notify-send")
                    .arg("Cybersyn")
                    .arg(&notify_body)
                    .status()
                {
                    Ok(s) if s.success() => format!("notify:ok:{notify_body}"),
                    Ok(s) => format!("notify:exit:{}", s.code().unwrap_or(-1)),
                    Err(e) => format!("notify:err:{e}"),
                };
                let _ = mqtt_clone.publish(
                    &event_topic_clone,
                    QoS::AtLeastOnce,
                    false,
                    result.into_bytes(),
                );
            });
            format!("notify:dispatched:{body}")
        }

        // --- Clipboard ---
        "clipboard" if arg.starts_with("get") => {
            match Command::new("xclip")
                .args(["-selection", "clipboard", "-o"])
                .output()
            {
                Ok(o) if o.status.success() => {
                    let text = String::from_utf8_lossy(&o.stdout).to_string();
                    format!("clipboard:ok:{text}")
                }
                Ok(o) => format!(
                    "clipboard:exit:{}",
                    o.status.code().unwrap_or(-1)
                ),
                Err(e) => format!("clipboard:err:{e}"),
            }
        }

        "clipboard" if arg.starts_with("set:") => {
            // Re-split the raw payload rather than indexing a fixed prefix length: cmd/arg
            // above are trimmed, so "clipboard: set:x" reaches this arm while a fixed
            // 14-byte slice would cut mid-content. Everything after the second ':' is the
            // clipboard body and must survive verbatim, whitespace included.
            let text = payload
                .splitn(3, ':')
                .nth(2)
                .unwrap_or("");
            clipboard::set_content(text, clipboard_state);
            format!("clipboard:set:ok")
        }

        // --- MPRIS control (via playerctl) ---
        "mpris" => dispatch_mpris(arg),

        // --- Album art (via file-transfer channel) ---
        "albumart" => {
            let hash = arg.trim();
            if hash.is_empty() {
                return "error:albumart:missing-hash".to_string();
            }
            if !valid_hash(hash) {
                return format!("error:albumart:invalid-hash:{hash}");
            }
            match mpris::get_art_path(hash) {
                Some(path) => {
                    match file_transfer::prepare_serve(&path) {
                        Ok((offer, ts_listener, lan_listener)) => {
                            let json = file_transfer::build_file_offer_json(
                                &offer, "albumart",
                            );
                            let _ = mqtt.publish(
                                file_offer_topic,
                                QoS::AtLeastOnce,
                                false,
                                json,
                            );
                            let path_clone = path.clone();
                            std::thread::spawn(move || {
                                match file_transfer::accept_and_send(ts_listener, lan_listener, &path_clone) {
                                    Ok(elapsed) => {
                                        eprintln!("albumart served: {} ({:.1}s)", offer.name, elapsed.as_secs_f64());
                                    }
                                    Err(e) => {
                                        eprintln!("albumart serve failed: {e}");
                                    }
                                }
                            });
                        }
                        Err(e) => {
                            return format!("error:albumart:prepare:{e}");
                        }
                    }
                    format!("albumart:serving:{hash}")
                }
                None => format!("error:albumart:not-cached:{hash}"),
            }
        }

        // --- File transfer ---
        "file" if arg.starts_with("serve:") => {
            let path_str = &arg[6..];
            let path = expand_home(path_str);
            if !path.exists() {
                return format!("error:file:not-found:{path_str}");
            }

            if path.is_dir() {
                // Archiving can take a while for a large tree — do the whole
                // build-then-offer-then-send sequence off the MQTT event-loop
                // thread (dispatch() runs synchronously in that loop) so a big
                // folder doesn't stall clipboard/mpris/other actions meanwhile.
                let path_owned = path.clone();
                let mqtt_owned = mqtt.clone();
                let file_offer_topic_owned = file_offer_topic.to_string();
                std::thread::spawn(move || {
                    let name = path_owned
                        .file_name()
                        .and_then(|n| n.to_str())
                        .unwrap_or("archive")
                        .to_string();
                    let archive_path = file_transfer::temp_archive_path(&name, "tar.zst");
                    if let Err(e) = file_transfer::build_archive_zst(&path_owned, &archive_path) {
                        eprintln!("archive build failed: {e}");
                        return;
                    }
                    match file_transfer::prepare_serve(&archive_path) {
                        Ok((mut offer, ts_listener, lan_listener)) => {
                            // prepare_serve derived `name` from the temp archive's unique
                            // filename — swap in the clean source-directory-derived name
                            // that's actually meant to reach the phone.
                            offer.name = format!("{name}.tar.zst");
                            let json =
                                file_transfer::build_file_offer_json(&offer, "archive-zst");
                            let _ = mqtt_owned.publish(
                                &file_offer_topic_owned,
                                QoS::AtLeastOnce,
                                false,
                                json,
                            );
                            match file_transfer::accept_and_send(ts_listener, lan_listener, &archive_path) {
                                Ok(elapsed) => {
                                    eprintln!(
                                        "archive served: {} ({} bytes, {:.1}s)",
                                        offer.name, offer.size, elapsed.as_secs_f64()
                                    );
                                }
                                Err(e) => {
                                    eprintln!("archive serve failed: {e}");
                                }
                            }
                        }
                        Err(e) => {
                            eprintln!("archive prepare failed: {e}");
                        }
                    }
                    let _ = std::fs::remove_file(&archive_path);
                });
                return format!("file:archiving:{path_str}");
            }

            match file_transfer::prepare_serve(&path) {
                Ok((offer, ts_listener, lan_listener)) => {
                    let json =
                        file_transfer::build_file_offer_json(&offer, "file");
                    let _ = mqtt.publish(
                        file_offer_topic,
                        QoS::AtLeastOnce,
                        false,
                        json,
                    );
                    let path_clone = path.clone();
                    std::thread::spawn(move || {
                        match file_transfer::accept_and_send(ts_listener, lan_listener, &path_clone) {
                            Ok(elapsed) => {
                                eprintln!(
                                    "file served: {} ({} bytes, {:.1}s)",
                                    offer.name, offer.size, elapsed.as_secs_f64()
                                );
                            }
                            Err(e) => {
                                eprintln!("file serve failed: {e}");
                            }
                        }
                    });
                }
                Err(e) => {
                    return format!("error:file:prepare:{e}");
                }
            }
            format!("file:serving:{path_str}")
        }

        "file" if arg.starts_with("receive:") => {
            if arg.len() <= 8 {
                return "error:file:receive:missing-address".to_string();
            }
            let addr_and_path = &arg[8..];
            let parts: Vec<&str> = addr_and_path.splitn(2, ' ').collect();
            if parts.len() < 2 {
                return "error:file:receive:need <addr:port> <dest-name>".to_string();
            }
            let addr = parts[0].to_string();
            let dest_name = parts[1].to_string();
            if !valid_dest_name(&dest_name) {
                return format!("error:file:receive:invalid-name:{dest_name}");
            }
            let dest = expand_home(DOWNLOADS_DIR).join(&dest_name);
            let dest_clone = dest.clone();
            std::thread::spawn(move || {
                match file_transfer::receive_file(&addr, &dest_clone) {
                    Ok(n) => {
                        eprintln!("file received: {dest_clone:?} ({n} bytes)");
                        // Upload leg is intentionally uncompressed (see file_transfer.rs
                        // comment) — a `.tar` suffix means the phone bundled multiple
                        // shared files/a folder, so unpack it into a same-named directory.
                        if let Some(stem) = dest_clone
                            .file_name()
                            .and_then(|n| n.to_str())
                            .and_then(|n| n.strip_suffix(".tar"))
                        {
                            let extract_dir = dest_clone.with_file_name(stem);
                            match file_transfer::extract_tar(&dest_clone, &extract_dir) {
                                Ok(()) => eprintln!("archive extracted: {extract_dir:?}"),
                                Err(e) => eprintln!("archive extract failed: {e}"),
                            }
                        }
                    }
                    Err(e) => {
                        eprintln!("file receive failed: {e}");
                    }
                }
            });
            format!("file:receiving:{dest_name}")
        }

        // --- Script dispatch (existing) ---
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
            let mqtt_clone = mqtt.clone();
            let event_topic_clone = event_topic.to_string();
            let path_clone = path.clone();
            let script_args_clone = script_args.to_string();
            let name_owned = name.to_string();
            std::thread::spawn(move || {
                let mut cmd = Command::new(&path_clone);
                if !script_args_clone.is_empty() {
                    cmd.args(script_args_clone.split_whitespace());
                }
                let result = match cmd.output() {
                    Ok(output) => {
                        let stdout = String::from_utf8_lossy(&output.stdout);
                        let stderr = String::from_utf8_lossy(&output.stderr);
                        let code = output.status.code().unwrap_or(-1);
                        if output.status.success() {
                            format!("script:{name_owned}:ok:{}", stdout.trim())
                        } else {
                            format!(
                                "script:{name_owned}:exit:{code}:stderr:{}",
                                stderr.trim()
                            )
                        }
                    }
                    Err(e) => format!("script:{name_owned}:err:{e}"),
                };
                let _ = mqtt_clone.publish(
                    &event_topic_clone,
                    QoS::AtLeastOnce,
                    false,
                    result.into_bytes(),
                );
            });
            format!("script:dispatched:{name}")
        }

        "capabilities" => match std::fs::read_dir(script_dir) {
            Ok(entries) => {
                let mut names: Vec<String> = entries
                    .filter_map(|e| e.ok())
                    .filter(|e| e.path().is_file())
                    .filter_map(|e| e.file_name().into_string().ok())
                    .filter(|n| valid_script_name(n))
                    .collect();
                names.sort();
                format!(
                    "capabilities:ping,notify,clipboard,mpris,albumart,file,{}",
                    names.join(",")
                )
            }
            Err(e) => {
                format!(
                    "capabilities:ping,notify,clipboard,mpris,albumart,file (script dir unreadable: {e})"
                )
            }
        },

        "shell" => {
            if !allow_shell {
                return "error:shell:disabled (relay started without --allow-shell; use script:<name>)"
                    .to_string();
            }
            if arg.is_empty() {
                return "error:shell:missing-command".to_string();
            }
            let mqtt_clone = mqtt.clone();
            let event_topic_clone = event_topic.to_string();
            let shell_cmd = arg.to_string();
            std::thread::spawn(move || {
                let result = match Command::new("sh").arg("-c").arg(&shell_cmd).output() {
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
                };
                let _ = mqtt_clone.publish(
                    &event_topic_clone,
                    QoS::AtLeastOnce,
                    false,
                    result.into_bytes(),
                );
            });
            format!("shell:dispatched")
        }

        other => {
            eprintln!("unknown command: {other:?}");
            format!("error:unknown-command:{other}")
        }
    }
}

fn dispatch_mpris(arg: &str) -> String {
    if arg.is_empty() {
        return "error:mpris:missing-command".to_string();
    }

    let (subcmd, subarg) = match arg.split_once(':') {
        Some((c, a)) => (c.trim(), a.trim()),
        None => (arg, ""),
    };

    match subcmd {
        "play" => {
            let active = mpris::get_active_player();
            let player = if subarg.is_empty() {
                active.as_deref().unwrap_or("")
            } else {
                subarg
            };
            if player.is_empty() {
                return "error:mpris:no-player".to_string();
            }
            match run_playerctl(&["--player", player, "play"]) {
                Ok(_) => format!("mpris:play:ok:{player}"),
                Err(e) => format!("mpris:play:err:{e}"),
            }
        }

        "pause" => {
            let active = mpris::get_active_player();
            let player = if subarg.is_empty() {
                active.as_deref().unwrap_or("")
            } else {
                subarg
            };
            if player.is_empty() {
                return "error:mpris:no-player".to_string();
            }
            match run_playerctl(&["--player", player, "pause"]) {
                Ok(_) => format!("mpris:pause:ok:{player}"),
                Err(e) => format!("mpris:pause:err:{e}"),
            }
        }

        "play-pause" => {
            let active = mpris::get_active_player();
            let player = if subarg.is_empty() {
                active.as_deref().unwrap_or("")
            } else {
                subarg
            };
            if player.is_empty() {
                return "error:mpris:no-player".to_string();
            }
            match run_playerctl(&["--player", player, "play-pause"]) {
                Ok(_) => format!("mpris:play-pause:ok:{player}"),
                Err(e) => format!("mpris:play-pause:err:{e}"),
            }
        }

        "next" | "prev" | "previous" | "stop" => {
            let active = mpris::get_active_player();
            let player = if subarg.is_empty() {
                active.as_deref().unwrap_or("")
            } else {
                subarg
            };
            if player.is_empty() {
                return "error:mpris:no-player".to_string();
            }
            let cmd = if subcmd == "previous" { "previous" } else { subcmd };
            match run_playerctl(&["--player", player, cmd]) {
                Ok(_) => format!("mpris:{subcmd}:ok:{player}"),
                Err(e) => format!("mpris:{subcmd}:err:{e}"),
            }
        }

        "seek" => {
            let active = mpris::get_active_player();
            let (player, offset_secs) = if let Some((p, o)) = subarg.split_once(' ') {
                (p, o)
            } else {
                (active.as_deref().unwrap_or(""), subarg)
            };
            if player.is_empty() {
                return "error:mpris:no-player".to_string();
            }
            match run_playerctl(&[
                "--player",
                player,
                "position",
                &format!("{offset_secs}+"),
            ]) {
                Ok(_) => format!("mpris:seek:ok:{player}:{offset_secs}"),
                Err(e) => format!("mpris:seek:err:{e}"),
            }
        }

        "position" => {
            let active = mpris::get_active_player();
            let (player, pos_secs) = if let Some((p, o)) = subarg.split_once(' ') {
                (p, o)
            } else {
                (active.as_deref().unwrap_or(""), subarg)
            };
            if player.is_empty() {
                return "error:mpris:no-player".to_string();
            }
            match run_playerctl(&["--player", player, "position", pos_secs]) {
                Ok(_) => {
                    format!("mpris:position:ok:{player}:{pos_secs}")
                }
                Err(e) => format!("mpris:position:err:{e}"),
            }
        }

        "volume" => {
            let active = mpris::get_active_player();
            let (player, vol) = if let Some((p, v)) = subarg.split_once(' ') {
                (p, v)
            } else {
                (active.as_deref().unwrap_or(""), subarg)
            };
            if player.is_empty() {
                return "error:mpris:no-player".to_string();
            }
            let vol_f: f64 = vol.parse().unwrap_or(100.0) / 100.0;
            match run_playerctl(&[
                "--player",
                player,
                "volume",
                &format!("{vol_f}"),
            ]) {
                Ok(_) => format!("mpris:volume:ok:{player}:{vol}"),
                Err(e) => format!("mpris:volume:err:{e}"),
            }
        }

        "loop" => {
            let active = mpris::get_active_player();
            let (player, mode) = if let Some((p, m)) = subarg.split_once(' ') {
                (p, m)
            } else {
                (active.as_deref().unwrap_or(""), subarg)
            };
            if player.is_empty() {
                return "error:mpris:no-player".to_string();
            }
            match run_playerctl(&["--player", player, "loop", mode]) {
                Ok(_) => format!("mpris:loop:ok:{player}:{mode}"),
                Err(e) => format!("mpris:loop:err:{e}"),
            }
        }

        "shuffle" => {
            let active = mpris::get_active_player();
            let (player, val) = if let Some((p, v)) = subarg.split_once(' ') {
                (p, v)
            } else {
                (active.as_deref().unwrap_or(""), subarg)
            };
            if player.is_empty() {
                return "error:mpris:no-player".to_string();
            }
            match run_playerctl(&["--player", player, "shuffle", val]) {
                Ok(_) => format!("mpris:shuffle:ok:{player}:{val}"),
                Err(e) => format!("mpris:shuffle:err:{e}"),
            }
        }

        "players" => match mpris::get_players() {
            Ok(list) => format!("mpris:players:{}", list.join(",")),
            Err(e) => format!("mpris:players:err:{e}"),
        },

        "active" => match mpris::get_active_player() {
            Some(player) => format!("mpris:active:{player}"),
            None => "mpris:active:none".to_string(),
        },

        other => format!("error:mpris:unknown-command:{other}"),
    }
}

fn main() {
    let args = Args::parse();
    let node = args.node.unwrap_or_else(short_hostname);
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
        .expect("subscribe action topic");

    // Also subscribe to incoming clipboard from phone
    client
        .subscribe(CLIPBOARD_TOPIC_PIXEL, QoS::AtLeastOnce)
        .expect("subscribe clipboard topic");

    println!(
        "cybersyn-relay: node={node} broker={}:{} listening on {action_topic} + {CLIPBOARD_TOPIC_PIXEL}, results -> {event_topic}",
        args.broker, args.port
    );

    let clipboard_state = Arc::new(Mutex::new(clipboard::ClipboardState {
        last_content: String::new(),
        last_published: String::new(),
    }));

    if args.enable_mpris {
        let mqtt_clone = client.clone();
        println!("mpris-monitor: starting D-Bus MPRIS watcher");
        mpris::start(mqtt_clone);
    }

    if args.enable_clipboard {
        let mqtt_clone = client.clone();
        let state = clipboard_state.clone();
        println!("clipboard-monitor: starting 1s poll");
        clipboard::start_monitor(mqtt_clone, state);
    }

    std::fs::create_dir_all(expand_home(DOWNLOADS_DIR)).ok();
    std::fs::create_dir_all(expand_home("~/.cache/cybersyn/albumart")).ok();

    for notification in connection.iter() {
        match notification {
            Ok(Event::Incoming(Packet::Publish(p))) => {
                let payload_str =
                    String::from_utf8_lossy(&p.payload).to_string();

                if p.topic == CLIPBOARD_TOPIC_PIXEL {
                    if let Some(content) = clipboard::parse_payload(&payload_str) {
                        clipboard::set_content(&content, &clipboard_state);
                        println!("clipboard: received from pixel: {} chars", content.len());
                    }
                    continue;
                }

                println!("recv {} -> {payload_str:?}", p.topic);
                let result = dispatch(
                    &payload_str,
                    &script_dir,
                    args.allow_shell,
                    &client,
                    &clipboard_state,
                    FILE_OFFER_TOPIC,
                    &event_topic,
                );
                if let Err(e) = client.publish(
                    &event_topic,
                    QoS::AtLeastOnce,
                    false,
                    result.into_bytes(),
                ) {
                    eprintln!("failed to publish result: {e}");
                }
            }
            Ok(Event::Incoming(Packet::ConnAck(_))) => {
                if let Err(e) = client
                    .subscribe(&action_topic, QoS::AtLeastOnce)
                    .and_then(|_| {
                        client.subscribe(
                            CLIPBOARD_TOPIC_PIXEL,
                            QoS::AtLeastOnce,
                        )
                    })
                {
                    eprintln!("re-subscribe failed: {e}");
                }
                println!(
                    "connected, subscribed to {action_topic} + {CLIPBOARD_TOPIC_PIXEL}"
                );
            }
            Ok(_) => {}
            Err(e) => {
                eprintln!("mqtt connection error: {e}; retrying");
                std::thread::sleep(Duration::from_secs(2));
            }
        }
    }
}
