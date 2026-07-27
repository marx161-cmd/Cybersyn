use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream, SocketAddr, ToSocketAddrs};
use std::path::Path;
use std::time::Duration;

const CHUNK_SIZE: usize = 4096;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const PORT_RANGE_START: u16 = 1740;
const PORT_RANGE_END: u16 = 1745;

pub struct FileOffer {
    pub name: String,
    pub size: u64,
    pub port: u16,
    pub ip: String,
}

/// Phase 1: bind port, compute metadata, return offer + listener.
/// Non-blocking — caller publishes offer on MQTT before Phase 2.
pub fn prepare_serve(path: &Path) -> Result<(FileOffer, TcpListener), String> {
    let metadata =
        std::fs::metadata(path).map_err(|e| format!("stat {path:?}: {e}"))?;
    let size = metadata.len();
    let name = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("unknown")
        .to_string();

    let ip = tailscale_ip();

    let (listener, port) = bind_free_port()?;
    listener
        .set_nonblocking(false)
        .map_err(|e| format!("set_nonblocking: {e}"))?;

    let offer = FileOffer {
        name,
        size,
        port,
        ip,
    };

    Ok((offer, listener))
}

/// Phase 2: accept one connection and stream the file. Blocks until connected or timeout.
pub fn accept_and_send(listener: TcpListener, path: &Path) -> Result<Duration, String> {
    let start = std::time::Instant::now();

    let timeout = Duration::from_secs(30);
    listener
        .set_nonblocking(true)
        .map_err(|e| format!("set_nonblocking: {e}"))?;

    let (mut stream, _addr) = wait_for_accept(&listener, timeout)?;
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

fn wait_for_accept(
    listener: &TcpListener,
    timeout: Duration,
) -> Result<(TcpStream, std::net::SocketAddr), String> {
    let deadline = std::time::Instant::now() + timeout;
    loop {
        match listener.accept() {
            Ok(conn) => return Ok(conn),
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                if std::time::Instant::now() >= deadline {
                    return Err("accept timeout (30s)".to_string());
                }
                std::thread::sleep(Duration::from_millis(100));
            }
            Err(e) => return Err(format!("accept: {e}")),
        }
    }
}

/// Connect to a sender and receive file bytes, writing to dest_path.
pub fn receive_file(addr: &str, dest_path: &Path) -> Result<u64, String> {
    let socket_addr: SocketAddr = addr
        .to_socket_addrs()
        .map_err(|e| format!("resolve {addr}: {e}"))?
        .next()
        .ok_or_else(|| format!("no address resolved for {addr}"))?;

    let mut stream =
        TcpStream::connect_timeout(&socket_addr, CONNECT_TIMEOUT)
            .map_err(|e| format!("connect {addr}: {e}"))?;

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

fn bind_free_port() -> Result<(TcpListener, u16), String> {
    for port in PORT_RANGE_START..=PORT_RANGE_END {
        let addr = format!("0.0.0.0:{port}");
        match TcpListener::bind(&addr) {
            Ok(l) => {
                l.set_nonblocking(false).ok();
                return Ok((l, port));
            }
            Err(_) => continue,
        }
    }
    Err(format!(
        "no free port in range {PORT_RANGE_START}-{PORT_RANGE_END}"
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

pub fn build_file_offer_json(offer: &FileOffer, file_type: &str) -> String {
    serde_json::json!({
        "name": offer.name,
        "size": offer.size,
        "port": offer.port,
        "ip": offer.ip,
        "type": file_type,
    })
    .to_string()
}
