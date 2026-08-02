use std::collections::HashMap;
use std::path::PathBuf;
use std::process::Command;
use std::thread;
use std::time::Duration;

use rumqttc::{Client, QoS};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

const MEDIA_TOPIC: &str = "cybersyn/comrade/media/status";
const ART_CACHE_DIR: &str = "~/.cache/cybersyn/albumart";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlayerStatus {
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub artist: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub album: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub album_art_hash: Option<String>,
    pub album_art_ready: bool,
    pub is_playing: bool,
    pub position_ms: i64,
    pub length_ms: i64,
    pub volume: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub loop_status: Option<String>,
    pub shuffle: bool,
    pub can_play: bool,
    pub can_pause: bool,
    pub can_go_next: bool,
    pub can_go_previous: bool,
    pub can_seek: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct MediaStatus {
    pub players: Vec<PlayerStatus>,
    pub active: Option<String>,
}

pub fn start(mqtt: Client) {
    thread::Builder::new()
        .name("cybersyn-mpris".into())
        .spawn(move || {
            let art_cache_dir = expand_home(ART_CACHE_DIR);
            std::fs::create_dir_all(&art_cache_dir).ok();

            loop {
                match build_status(&art_cache_dir) {
                    Ok(status) => {
                        if let Ok(json) = serde_json::to_string(&status) {
                            if let Err(e) = mqtt.publish(
                                MEDIA_TOPIC,
                                QoS::AtLeastOnce,
                                true,
                                json.as_bytes().to_vec(),
                            ) {
                                eprintln!("mpris: publish failed: {e}");
                            }
                        }
                    }
                    Err(e) => {
                        // No players or playerctl not available — quiet
                        let _ = e;
                    }
                }
                thread::sleep(Duration::from_secs(1));
            }
        })
        .expect("spawn mpris thread");
}

fn build_status(art_cache_dir: &PathBuf) -> Result<MediaStatus, String> {
    let player_names = run_playerctl(&["-l"])?;
    let mut players = Vec::new();
    let mut active = None;

    for name in player_names.lines() {
        let name = name.trim();
        if name.is_empty() {
            continue;
        }

        let metadata = get_metadata(name);
        let status = get_playback_status(name);
        let volume_str = run_playerctl(&["-p", name, "volume"]).unwrap_or_default();
        let position_str = get_position(name);
        let loop_str =
            run_playerctl(&["-p", name, "loop"]).unwrap_or_default();
        let shuffle_str =
            run_playerctl(&["-p", name, "shuffle"]).unwrap_or_default();

        let is_playing = status.contains("Playing");
        if is_playing && active.is_none() {
            active = Some(name.to_string());
        }

        let volume: i32 = volume_str
            .parse::<f64>()
            .map(|v| (v * 100.0).round() as i32)
            .unwrap_or(100);

        let position_ms: i64 = position_str
            .parse::<f64>()
            .map(|p| (p * 1000.0) as i64)
            .unwrap_or(0);

        let length_ms: i64 = metadata
            .get("mpris:length")
            .and_then(|v| v.parse::<f64>().ok())
            .map(|l| (l / 1000.0) as i64)
            .unwrap_or(0);

        let loop_status = match loop_str.trim() {
            "None" | "Track" | "Playlist" => Some(loop_str.trim().to_string()),
            _ => Some("None".to_string()),
        };

        let shuffle = shuffle_str.trim().to_lowercase() == "on";

        let can_play = !status.is_empty();
        let can_pause = !status.is_empty();
        let can_go_next = true;
        let can_go_previous = true;
        let can_seek = length_ms > 0;

        let (album_art_hash, album_art_ready) =
            extract_album_art(name, &metadata, art_cache_dir);

        players.push(PlayerStatus {
            name: name.to_string(),
            title: metadata.get("xesam:title").cloned(),
            artist: metadata.get("xesam:artist").cloned(),
            album: metadata.get("xesam:album").cloned(),
            album_art_hash,
            album_art_ready,
            is_playing,
            position_ms,
            length_ms,
            volume,
            loop_status,
            shuffle,
            can_play,
            can_pause,
            can_go_next,
            can_go_previous,
            can_seek,
        });
    }

    if active.is_none() {
        active = players.first().map(|p| p.name.clone());
    }

    Ok(MediaStatus { players, active })
}

fn get_metadata(player: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    let fields = [
        "xesam:title",
        "xesam:artist",
        "xesam:album",
        "mpris:artUrl",
        "mpris:length",
    ];

    let format_str = fields
        .iter()
        .map(|f| format!("{{{{ {} }}}}", f))
        .collect::<Vec<_>>()
        .join("|");

    let output = match run_playerctl(&["-p", player, "metadata", "-f", &format_str]) {
        Ok(o) => o,
        Err(_) => return map,
    };

    let parts: Vec<&str> = output.split('|').collect();
    for (i, field) in fields.iter().enumerate() {
        let val = parts.get(i).copied().unwrap_or("").trim().to_string();
        if !val.is_empty() {
            map.insert(field.to_string(), val);
        }
    }

    map
}

fn get_playback_status(player: &str) -> String {
    run_playerctl(&["-p", player, "status"]).unwrap_or_default()
}

fn get_position(player: &str) -> String {
    run_playerctl(&["-p", player, "position"]).unwrap_or_default()
}

fn extract_album_art(
    player: &str,
    metadata: &HashMap<String, String>,
    cache_dir: &PathBuf,
) -> (Option<String>, bool) {
    let art_url = match metadata.get("mpris:artUrl") {
        Some(url) => url,
        None => return (None, false),
    };

    let local_path = match art_url.strip_prefix("file://") {
        Some(p) => std::path::Path::new(p),
        None => {
            // HTTP URL or other — try playerctl open to get local path
            return (None, false);
        }
    };

    if !local_path.is_file() {
        return (None, false);
    }

    match cache_album_art(local_path, cache_dir) {
        Ok(hash) => (Some(hash), true),
        Err(e) => {
            eprintln!("mpris: album art cache failed for {player}: {e}");
            (None, false)
        }
    }
}

fn cache_album_art(source: &std::path::Path, cache_dir: &PathBuf) -> Result<String, String> {
    let data =
        std::fs::read(source).map_err(|e| format!("read {source:?}: {e}"))?;
    let mut hasher = Sha256::new();
    hasher.update(&data);
    let hash = format!("{:x}", hasher.finalize());
    let dest = cache_dir.join(format!("{hash}.jpg"));
    if !dest.exists() {
        std::fs::write(&dest, &data)
            .map_err(|e| format!("write {dest:?}: {e}"))?;
    }
    Ok(hash)
}

pub fn get_art_path(hash: &str) -> Option<PathBuf> {
    let dir = expand_home(ART_CACHE_DIR);
    let path = dir.join(format!("{hash}.jpg"));
    if path.is_file() {
        Some(path)
    } else {
        None
    }
}

pub fn get_players() -> Result<Vec<String>, String> {
    let output = run_playerctl(&["-l"])?;
    Ok(output.lines().map(|l| l.trim().to_string()).filter(|l| !l.is_empty()).collect())
}

pub fn get_active_player() -> Option<String> {
    let players = get_players().ok()?;
    for p in &players {
        let status = run_playerctl(&["-p", p, "status"]).unwrap_or_default();
        if status.contains("Playing") {
            return Some(p.clone());
        }
    }
    players.first().cloned()
}

fn run_playerctl(args: &[&str]) -> Result<String, String> {
    let output = Command::new("playerctl")
        .args(args)
        .output()
        .map_err(|e| format!("playerctl: {e}"))?;
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
    }
}

fn expand_home(path: &str) -> PathBuf {
    if let Some(rest) = path.strip_prefix("~/") {
        match std::env::var_os("HOME") {
            Some(home) => std::path::Path::new(&home).join(rest),
            None => PathBuf::from(path),
        }
    } else {
        PathBuf::from(path)
    }
}
