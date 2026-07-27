use std::io::Write;
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use rumqttc::{Client, QoS};

const CLIPBOARD_TOPIC_IN: &str = "cybersyn/clipboard/comrade";

pub struct ClipboardState {
    pub last_content: String,
    pub last_published: String,
}

/// Spawn a background thread that polls the local clipboard every second and
/// publishes changes to MQTT. Uses string-equality anti-loop (same as KDE).
pub fn start_monitor(mqtt: Client, state: Arc<Mutex<ClipboardState>>) {
    thread::Builder::new()
        .name("cybersyn-clipboard".into())
        .spawn(move || {
            loop {
                let current = match read_clipboard() {
                    Some(c) => c,
                    None => {
                        thread::sleep(Duration::from_secs(1));
                        continue;
                    }
                };

                let should_publish = {
                    let mut s = state.lock().unwrap();
                    if current == s.last_content {
                        false
                    } else {
                        s.last_content = current.clone();
                        if current == s.last_published {
                            false
                        } else {
                            s.last_published = current.clone();
                            true
                        }
                    }
                };

                if should_publish {
                    let payload = build_payload(&current);
                    if let Err(e) = mqtt.publish(
                        CLIPBOARD_TOPIC_IN,
                        QoS::AtLeastOnce,
                        false,
                        payload.as_bytes().to_vec(),
                    ) {
                        eprintln!("clipboard: publish failed: {e}");
                    }
                }

                thread::sleep(Duration::from_secs(1));
            }
        })
        .expect("spawn clipboard thread");
}

/// Apply incoming clipboard content. Updates the cached value to prevent
/// loopback (the next poll will see this same content and skip publishing).
pub fn set_content(content: &str, state: &Mutex<ClipboardState>) {
    {
        let mut s = state.lock().unwrap();
        s.last_content = content.to_string();
        s.last_published = content.to_string();
    }
    write_clipboard(content);
}

fn read_clipboard() -> Option<String> {
    Command::new("xclip")
        .args(["-selection", "clipboard", "-o"])
        .output()
        .ok()
        .and_then(|o| {
            if o.status.success() {
                Some(String::from_utf8_lossy(&o.stdout).to_string())
            } else {
                None
            }
        })
}

fn write_clipboard(text: &str) {
    if let Ok(mut child) = Command::new("xclip")
        .args(["-selection", "clipboard", "-i"])
        .stdin(Stdio::piped())
        .spawn()
    {
        if let Some(mut stdin) = child.stdin.take() {
            let _ = stdin.write_all(text.as_bytes());
        }
        let _ = child.wait();
    }
}

fn build_payload(content: &str) -> String {
    let escaped = content
        .replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('\n', "\\n")
        .replace('\r', "\\r");
    format!(r#"{{"content":"{escaped}"}}"#)
}

pub fn parse_payload(payload: &str) -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(payload).ok()?;
    v.get("content")?.as_str().map(|s| {
        s.replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    })
}
