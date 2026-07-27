use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream, SocketAddr, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

const CHUNK_SIZE: usize = 65536;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const LAN_CONNECT_TIMEOUT: Duration = Duration::from_secs(3);
const PORT_RANGE_START: u16 = 1740;
const PORT_RANGE_END: u16 = 1745;

pub struct FileOffer {
    pub name: String,
    pub size: u64,
    pub port: u16,
    pub ip: String,
    pub lan_ip: Option<String>,
}

/// Phase 1: bind port(s), compute metadata, return offer + listener(s).
/// Binds explicitly on the Tailscale IP (always) and comrade's LAN IP (best-effort,
/// when detectable) — never 0.0.0.0. The control plane (MQTT action/offer topics)
/// stays Tailscale-only regardless; this only lets the bulk transfer itself take
/// the LAN shortcut when both ends happen to share one, since that transfer only
/// ever starts because a Tailscale-authenticated action already asked for it.
pub fn prepare_serve(path: &Path) -> Result<(FileOffer, TcpListener, Option<TcpListener>), String> {
    let metadata =
        std::fs::metadata(path).map_err(|e| format!("stat {path:?}: {e}"))?;
    let size = metadata.len();
    let name = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("unknown")
        .to_string();

    let ip = tailscale_ip();
    let lan = lan_ip();

    let (ts_listener, lan_listener, port) = bind_free_port(&ip, lan.as_deref())?;

    let offer = FileOffer {
        name,
        size,
        port,
        ip,
        lan_ip: lan_listener.as_ref().and(lan),
    };

    Ok((offer, ts_listener, lan_listener))
}

/// Phase 2: accept on whichever listener connects first (LAN or Tailscale) and
/// stream the file. Blocks until connected or timeout.
pub fn accept_and_send(
    ts_listener: TcpListener,
    lan_listener: Option<TcpListener>,
    path: &Path,
) -> Result<Duration, String> {
    let start = Instant::now();
    let timeout = Duration::from_secs(30);

    ts_listener
        .set_nonblocking(true)
        .map_err(|e| format!("set_nonblocking: {e}"))?;
    if let Some(l) = &lan_listener {
        l.set_nonblocking(true).map_err(|e| format!("set_nonblocking: {e}"))?;
    }

    let (mut stream, _addr) = wait_for_accept_either(&ts_listener, lan_listener.as_ref(), timeout)?;
    stream
        .set_nonblocking(false)
        .map_err(|e| format!("set_nonblocking: {e}"))?;
    stream
        .set_write_timeout(Some(Duration::from_secs(30)))
        .ok();

    let mut file =
        std::fs::File::open(path).map_err(|e| format!("open {path:?}: {e}"))?;
    let mut buf = [0u8; CHUNK_SIZE];

    loop {
        let n = file
            .read(&mut buf)
            .map_err(|e| format!("read: {e}"))?;
        if n == 0 {
            break;
        }
        stream
            .write_all(&buf[..n])
            .map_err(|e| format!("write: {e}"))?;
    }

    stream
        .flush()
        .map_err(|e| format!("flush: {e}"))?;
    drop(stream);

    Ok(start.elapsed())
}

fn wait_for_accept_either(
    ts: &TcpListener,
    lan: Option<&TcpListener>,
    timeout: Duration,
) -> Result<(TcpStream, SocketAddr), String> {
    let deadline = Instant::now() + timeout;
    loop {
        match ts.accept() {
            Ok(conn) => return Ok(conn),
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
            Err(e) => return Err(format!("accept: {e}")),
        }
        if let Some(l) = lan {
            match l.accept() {
                Ok(conn) => return Ok(conn),
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
                Err(e) => return Err(format!("accept: {e}")),
            }
        }
        if Instant::now() >= deadline {
            return Err("accept timeout (30s)".to_string());
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// Connect to a sender and receive file bytes, writing to dest_path. `addrs` is a
/// comma-separated candidate list (e.g. "192.168.0.47:9,100.69.13.12:9") tried in
/// order — a short timeout on all but the last so a LAN-first candidate that isn't
/// reachable doesn't stall the guaranteed Tailscale fallback for long.
pub fn receive_file(addrs: &str, dest_path: &Path) -> Result<u64, String> {
    let candidates: Vec<&str> = addrs
        .split(',')
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect();
    if candidates.is_empty() {
        return Err("no candidate addresses".to_string());
    }

    let mut stream = None;
    let mut last_err = String::new();
    for (i, addr) in candidates.iter().enumerate() {
        let timeout = if i + 1 < candidates.len() { LAN_CONNECT_TIMEOUT } else { CONNECT_TIMEOUT };
        match connect_one(addr, timeout) {
            Ok(s) => {
                stream = Some(s);
                break;
            }
            Err(e) => last_err = e,
        }
    }
    let mut stream = stream.ok_or_else(|| format!("all candidates failed: {last_err}"))?;

    stream
        .set_read_timeout(Some(Duration::from_secs(30)))
        .ok();

    if let Some(parent) = dest_path.parent() {
        std::fs::create_dir_all(parent).ok();
    }

    let mut file =
        std::fs::File::create(dest_path)
            .map_err(|e| format!("create {dest_path:?}: {e}"))?;

    let mut buf = [0u8; CHUNK_SIZE];
    let mut total = 0u64;

    loop {
        let n = stream
            .read(&mut buf)
            .map_err(|e| format!("read: {e}"))?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n])
            .map_err(|e| format!("write: {e}"))?;
        total += n as u64;
    }

    file.flush().map_err(|e| format!("flush: {e}"))?;

    Ok(total)
}

fn connect_one(addr: &str, timeout: Duration) -> Result<TcpStream, String> {
    let socket_addr: SocketAddr = addr
        .to_socket_addrs()
        .map_err(|e| format!("resolve {addr}: {e}"))?
        .next()
        .ok_or_else(|| format!("no address resolved for {addr}"))?;
    TcpStream::connect_timeout(&socket_addr, timeout).map_err(|e| format!("connect {addr}: {e}"))
}

/// Bind the same port on the Tailscale IP (required) and, best-effort, comrade's
/// LAN IP too. Both are explicit addresses — never 0.0.0.0. If the LAN bind fails
/// for a given port (in use for something else), we proceed Tailscale-only rather
/// than skipping to another port, since Tailscale reachability is the guarantee.
fn bind_free_port(ts_ip: &str, lan_ip: Option<&str>) -> Result<(TcpListener, Option<TcpListener>, u16), String> {
    for port in PORT_RANGE_START..=PORT_RANGE_END {
        let ts_addr = format!("{ts_ip}:{port}");
        let ts_listener = match TcpListener::bind(&ts_addr) {
            Ok(l) => l,
            Err(_) => continue,
        };
        ts_listener.set_nonblocking(false).ok();

        let lan_listener = lan_ip.and_then(|ip| {
            let lan_addr = format!("{ip}:{port}");
            TcpListener::bind(&lan_addr).ok().map(|l| {
                l.set_nonblocking(false).ok();
                l
            })
        });

        return Ok((ts_listener, lan_listener, port));
    }
    Err(format!(
        "no free port in range {PORT_RANGE_START}-{PORT_RANGE_END} on {ts_ip}"
    ))
}

pub fn tailscale_ip() -> String {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| {
            s.connect("100.100.100.100:0")?;
            s.local_addr()
        })
        .map(|a| a.ip().to_string())
        .unwrap_or_else(|_| "127.0.0.1".to_string())
}

/// comrade's LAN-facing IPv4 address — whatever interface actually carries the
/// default route, so it's right even with docker bridges/other virtual interfaces
/// present. Best-effort: None if it can't be determined (e.g. ethernet unplugged).
pub fn lan_ip() -> Option<String> {
    let output = Command::new("ip")
        .args(["-4", "route", "get", "8.8.8.8"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&output.stdout);
    let mut tokens = text.split_whitespace();
    while let Some(tok) = tokens.next() {
        if tok == "src" {
            return tokens.next().map(|s| s.to_string());
        }
    }
    None
}

/// A fresh path under the system temp dir for a one-shot archive, so concurrent
/// transfers (the port range already allows a handful in parallel) don't collide.
pub fn temp_archive_path(name: &str, ext: &str) -> PathBuf {
    let unique = format!(
        "{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0)
    );
    std::env::temp_dir().join(format!("cybersyn-{name}-{unique}.{ext}"))
}

/// Stream `tar -C <dir's parent> <dir's basename>` through `zstd -T0` into `out_path`,
/// without ever holding the whole archive in memory. Level 12 + all-cores threading is
/// chosen so compression stays comfortably ahead of realistic LAN/Tailscale throughput
/// on this box (Ryzen 5 8600G, 12 threads) rather than becoming the bottleneck itself —
/// see the phone-side counterpart in CybersynFileTransfer for why the upload direction
/// (weaker, thermally-constrained phone CPU as sender) deliberately skips compression.
pub fn build_archive_zst(dir: &Path, out_path: &Path) -> Result<(), String> {
    let parent = dir.parent().ok_or_else(|| format!("{dir:?}: no parent dir"))?;
    let base = dir.file_name().ok_or_else(|| format!("{dir:?}: no dir name"))?;

    let mut tar_child = Command::new("tar")
        .arg("-cf")
        .arg("-")
        .arg("-C")
        .arg(parent)
        .arg(base)
        .stdout(Stdio::piped())
        .spawn()
        .map_err(|e| format!("tar spawn: {e}"))?;
    let tar_stdout = tar_child.stdout.take().ok_or("tar: no stdout")?;

    let zstd_status = Command::new("zstd")
        .args(["-T0", "-12", "-q", "-f", "-o"])
        .arg(out_path)
        .stdin(Stdio::from(tar_stdout))
        .status()
        .map_err(|e| format!("zstd spawn: {e}"))?;

    let tar_status = tar_child.wait().map_err(|e| format!("tar wait: {e}"))?;
    if !tar_status.success() {
        return Err(format!("tar exited: {tar_status}"));
    }
    if !zstd_status.success() {
        return Err(format!("zstd exited: {zstd_status}"));
    }
    Ok(())
}

/// Extraction for the upload leg (phone -> comrade), which is deliberately plain
/// (no zstd) since compression would otherwise run on the phone's weaker,
/// thermally-constrained CPU as the sender. Unpacks the `.tar` and removes it.
pub fn extract_tar(archive_path: &Path, dest_dir: &Path) -> Result<(), String> {
    std::fs::create_dir_all(dest_dir)
        .map_err(|e| format!("mkdir {dest_dir:?}: {e}"))?;

    let status = Command::new("tar")
        .arg("-xf")
        .arg(archive_path)
        .arg("-C")
        .arg(dest_dir)
        .status()
        .map_err(|e| format!("tar spawn: {e}"))?;

    let _ = std::fs::remove_file(archive_path);

    if !status.success() {
        return Err(format!("tar exited: {status}"));
    }
    Ok(())
}

pub fn build_file_offer_json(offer: &FileOffer, file_type: &str) -> String {
    serde_json::json!({
        "name": offer.name,
        "size": offer.size,
        "port": offer.port,
        "ip": offer.ip,
        "lan_ip": offer.lan_ip,
        "type": file_type,
    })
    .to_string()
}
