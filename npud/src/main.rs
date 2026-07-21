//! npud — resident NPU model daemon for the Tensor G5.
//!
//! # Why this exists
//!
//! Every NPU consumer on this device re-initialises its model per request.
//! Initialisation is slow, and on the in-process JNI path it is outright fatal:
//! `nativeCreateEngine` SIGABRTs the calling app (Agora chat/title generation,
//! and SpectreBoard whisper before it). Everything that runs the same models
//! *out of process* works reliably — SubAgent and the embedder have done so for
//! months.
//!
//! npud promotes that working pattern to a service: one long-lived worker
//! process per model, spawned once and kept warm, fronted by a Unix socket that
//! any client can speak to. Clients stop linking model runtimes altogether —
//! they write a line and read a line.
//!
//! Residency here is NOT an mlock problem. Compiled weights never enter the
//! worker's address space (a loaded 1B model shows ~3.7 MB RSS and zero
//! `.litertlm` mappings — they live behind the FMQ/edgetpu interface in
//! firmware-managed memory). "Keep it in RAM" really means "keep the session
//! handle alive", which is precisely what a resident process buys.
//!
//! # Backends are configuration, not code
//!
//! The G5 workers (`libpoll_e_worker.so`, `libwhisper_g5_worker.so`,
//! `kokoro_g5_worker`, `vibevoice_g5_worker`, the embedder) are siblings: all
//! line-oriented over stdin/stdout, differing only in their token vocabulary
//! (`POLL_E_READY` vs `KOKORO_G5_READY` vs `WHISPER_G5_OK`, ...). So a backend
//! is a table entry in npud.conf, and adding a new worker type needs no rebuild.
//!
//! # Adding a model
//!
//! Drop the file in the backend's directory: `<model-dir>/<kind>/<name>.<ext>`.
//! Discovery rescans on every LIST, so a new file is visible immediately, loads
//! on first use, and stays warm.
//!
//! # Protocol (UTF-8 lines; one connection may carry many requests)
//!
//! ```text
//! LIST                       -> "<kind> <model>" per line, then END
//! STATUS                     -> "<kind> <model> resident|cold" per line, END
//! WARM <kind> <model>        -> OK <model>
//! GEN  <kind> <model> <text> -> BEGIN, output lines, END
//! ```
//! Failures return one `ERR <message>` line. Newlines in a request are escaped
//! as `\n`, matching the worker protocol.

use std::collections::HashMap;
use std::io::{BufRead, BufReader, BufWriter, Read, Write};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const DEFAULT_SOCKET: &str = "/data/data/com.termux/files/usr/tmp/npud.sock";
const DEFAULT_MODEL_DIR: &str = "/data/data/com.termux/files/home/npu_models";
const DEFAULT_CONF: &str = "/data/data/com.termux/files/home/.config/npud/npud.conf";
const DEFAULT_DISPATCH_DIR: &str = "/data/local/tmp/agora";

/// Cold NPU init genuinely takes seconds; be generous rather than flap.
const READY_TIMEOUT: Duration = Duration::from_secs(90);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(180);

/// One worker type. Everything here comes from npud.conf so a new G5 worker is
/// a config edit, not a rebuild.
#[derive(Clone, Debug)]
struct Backend {
    kind: String,
    binary: PathBuf,
    /// Printed by the worker once the model is loaded and it can accept input.
    ready: String,
    /// Marks the start of a response body. Absent => the reply is a single line.
    begin: Option<String>,
    /// Marks the end of a response body (or success, for single-line workers).
    end: String,
    /// Prefix identifying an error reply.
    error: String,
    /// File extension identifying models for this backend.
    ext: String,
    /// How the model path is passed, e.g. `--model_path=`.
    model_flag: String,
    /// Fixed arguments, whitespace-separated in config.
    args: Vec<String>,
}

struct Config {
    socket: PathBuf,
    model_dir: PathBuf,
    dispatch_dir: PathBuf,
    conf_path: PathBuf,
    backends: Vec<Backend>,
    preload: bool,
}

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

/// Parse npud.conf: INI-ish, `[kind]` sections of `key = value`. Hand-rolled so
/// the binary stays dependency-free and links only against bionic.
fn parse_conf(path: &Path) -> Result<Vec<Backend>, String> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    let mut out: Vec<Backend> = Vec::new();
    let mut cur: Option<(String, HashMap<String, String>)> = None;

    let flush = |cur: &mut Option<(String, HashMap<String, String>)>,
                 out: &mut Vec<Backend>|
     -> Result<(), String> {
        if let Some((kind, kv)) = cur.take() {
            let need = |k: &str| -> Result<String, String> {
                kv.get(k)
                    .cloned()
                    .ok_or_else(|| format!("[{kind}] missing required key '{k}'"))
            };
            out.push(Backend {
                binary: PathBuf::from(need("binary")?),
                ready: need("ready")?,
                begin: kv.get("begin").cloned().filter(|s| !s.is_empty()),
                end: need("end")?,
                error: kv.get("error").cloned().unwrap_or_else(|| "ERROR".into()),
                ext: need("ext")?,
                model_flag: kv
                    .get("model_flag")
                    .cloned()
                    .unwrap_or_else(|| "--model_path=".into()),
                args: kv
                    .get("args")
                    .map(|a| a.split_whitespace().map(String::from).collect())
                    .unwrap_or_default(),
                kind,
            });
        }
        Ok(())
    };

    for (n, raw) in text.lines().enumerate() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with(';') {
            continue;
        }
        if line.starts_with('[') && line.ends_with(']') {
            flush(&mut cur, &mut out)?;
            let kind = line[1..line.len() - 1].trim().to_string();
            if kind.is_empty() {
                return Err(format!("line {}: empty section name", n + 1));
            }
            cur = Some((kind, HashMap::new()));
        } else if let Some((k, v)) = line.split_once('=') {
            match cur.as_mut() {
                Some((_, kv)) => {
                    kv.insert(k.trim().to_string(), v.trim().to_string());
                }
                None => return Err(format!("line {}: key outside any [section]", n + 1)),
            }
        } else {
            return Err(format!("line {}: not a section or key=value", n + 1));
        }
    }
    flush(&mut cur, &mut out)?;
    if out.is_empty() {
        return Err("no backends defined".into());
    }
    Ok(out)
}

fn load_config() -> Result<Config, String> {
    let mut socket = PathBuf::from(env_or("NPUD_SOCKET", DEFAULT_SOCKET));
    let mut model_dir = PathBuf::from(env_or("NPUD_MODEL_DIR", DEFAULT_MODEL_DIR));
    let mut conf_path = PathBuf::from(env_or("NPUD_CONF", DEFAULT_CONF));
    let dispatch_dir = PathBuf::from(env_or("NPUD_DISPATCH_DIR", DEFAULT_DISPATCH_DIR));
    let mut preload = env_or("NPUD_PRELOAD", "0") == "1";

    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        let next = args.get(i + 1).cloned();
        match args[i].as_str() {
            "--socket" => {
                if let Some(v) = next {
                    socket = PathBuf::from(v);
                }
            }
            "--model-dir" => {
                if let Some(v) = next {
                    model_dir = PathBuf::from(v);
                }
            }
            "--conf" => {
                if let Some(v) = next {
                    conf_path = PathBuf::from(v);
                }
            }
            "--preload" => {
                preload = true;
                i += 1;
                continue;
            }
            "--help" | "-h" => {
                println!(
                    "npud — resident NPU model daemon\n\n\
                     --socket PATH      unix socket (NPUD_SOCKET)\n\
                     --model-dir PATH   root of <kind>/ model dirs (NPUD_MODEL_DIR)\n\
                     --conf PATH        backend table (NPUD_CONF)\n\
                     --preload          load every model at startup (NPUD_PRELOAD=1)\n\n\
                     Protocol: LIST | STATUS | WARM <kind> <model> | GEN <kind> <model> <text>"
                );
                std::process::exit(0);
            }
            _ => {}
        }
        i += 2;
    }

    let backends = parse_conf(&conf_path)?;
    Ok(Config {
        socket,
        model_dir,
        dispatch_dir,
        conf_path,
        backends,
        preload,
    })
}

struct Worker {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
}

impl Worker {
    fn spawn(cfg: &Config, be: &Backend, model: &Path) -> Result<Worker, String> {
        let worker_dir = be
            .binary
            .parent()
            .map(|p| p.to_string_lossy().to_string())
            .unwrap_or_default();
        // Dispatch dir first: its complete tachyon toolchain must win over any
        // partial copy of the same lib names sitting beside the binary. Then
        // /system/lib64 before /vendor/lib64, or the vendor libbinder (which
        // lacks PermissionCache) shadows the framework one and the worker dies
        // on a missing symbol.
        let ld = format!(
            "{}:{}:/system/lib64:/vendor/lib64",
            cfg.dispatch_dir.to_string_lossy(),
            worker_dir
        );

        let mut cmd = Command::new(&be.binary);
        for a in &be.args {
            cmd.arg(a);
        }
        cmd.arg(format!("{}{}", be.model_flag, model.to_string_lossy()))
            .arg(format!(
                "--litert_dispatch_lib_dir={}",
                cfg.dispatch_dir.to_string_lossy()
            ))
            .env("LD_LIBRARY_PATH", &ld)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null());

        let mut child = cmd.spawn().map_err(|e| format!("spawn failed: {e}"))?;
        let stdin = child.stdin.take().ok_or("no stdin")?;
        let stdout = child.stdout.take().ok_or("no stdout")?;
        let mut reader = BufReader::new(stdout);

        let mut tail: Vec<String> = Vec::new();
        let deadline = Instant::now() + READY_TIMEOUT;
        loop {
            match read_line(&mut reader, deadline) {
                Some(line) => {
                    if line.contains(&be.ready) {
                        return Ok(Worker {
                            child,
                            stdin,
                            stdout: reader,
                        });
                    }
                    if line.contains(&be.error) {
                        let _ = child.kill();
                        return Err(format!("worker error during load: {line}"));
                    }
                    if tail.len() < 4 {
                        tail.push(line);
                    }
                }
                None => {
                    let _ = child.kill();
                    return Err(format!(
                        "worker never printed {}{}",
                        be.ready,
                        if tail.is_empty() {
                            String::new()
                        } else {
                            format!(" (saw: {})", tail.join(" | "))
                        }
                    ));
                }
            }
        }
    }

    fn request(&mut self, be: &Backend, payload: &str) -> Result<String, String> {
        writeln!(self.stdin, "{}", payload.replace('\n', "\\n"))
            .map_err(|e| format!("write failed: {e}"))?;
        self.stdin.flush().map_err(|e| format!("flush failed: {e}"))?;

        let deadline = Instant::now() + REQUEST_TIMEOUT;
        let mut body = String::new();
        // Workers with no begin token reply on a single line terminated by their
        // end/OK token, so start collecting immediately in that case.
        let mut collecting = be.begin.is_none();

        loop {
            let line = match read_line(&mut self.stdout, deadline) {
                Some(l) => l,
                None => return Err("request timed out or worker closed".into()),
            };
            if line.contains(&be.error) {
                return Err(line.trim().to_string());
            }
            if let Some(begin) = &be.begin {
                if line.contains(begin) {
                    collecting = true;
                    body.clear();
                    continue;
                }
            }
            if line.contains(&be.end) {
                return Ok(body);
            }
            if collecting {
                if !body.is_empty() {
                    body.push('\n');
                }
                body.push_str(&line);
            }
        }
    }

    fn alive(&mut self) -> bool {
        matches!(self.child.try_wait(), Ok(None))
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// Read one line, giving up at `deadline`. Pipes have no read timeout, so this
/// polls a byte at a time — latency granularity is irrelevant beside multi-second
/// model inference, and it keeps the binary dependency-free.
fn read_line(reader: &mut BufReader<ChildStdout>, deadline: Instant) -> Option<String> {
    let mut buf: Vec<u8> = Vec::new();
    loop {
        if Instant::now() > deadline {
            return None;
        }
        let mut b = [0u8; 1];
        match reader.read(&mut b) {
            Ok(0) => return None,
            Ok(_) => {
                if b[0] == b'\n' {
                    return Some(String::from_utf8_lossy(&buf).trim_end().to_string());
                }
                buf.push(b[0]);
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => return None,
        }
    }
}

type Slot = Arc<Mutex<Option<Worker>>>;
type Registry = Arc<Mutex<HashMap<String, Slot>>>;

fn backend<'a>(cfg: &'a Config, kind: &str) -> Option<&'a Backend> {
    cfg.backends.iter().find(|b| b.kind == kind)
}

/// Reject anything that could escape the backend's model directory.
fn safe_name(name: &str) -> bool {
    !name.is_empty()
        && !name.starts_with('.')
        && !name.contains('/')
        && !name.contains("..")
        && name.len() <= 128
}

fn model_path(cfg: &Config, be: &Backend, name: &str) -> Option<PathBuf> {
    if !safe_name(name) {
        return None;
    }
    let p = cfg
        .model_dir
        .join(&be.kind)
        .join(format!("{name}.{}", be.ext));
    p.is_file().then_some(p)
}

/// Rescan on every call so a dropped-in model appears without a restart.
fn scan(cfg: &Config) -> Vec<(String, String)> {
    let mut out = Vec::new();
    for be in &cfg.backends {
        let dir = cfg.model_dir.join(&be.kind);
        if let Ok(entries) = std::fs::read_dir(&dir) {
            for e in entries.flatten() {
                let p = e.path();
                if p.is_file() && p.extension().map(|x| x == be.ext.as_str()).unwrap_or(false) {
                    if let Some(stem) = p.file_stem().and_then(|s| s.to_str()) {
                        out.push((be.kind.clone(), stem.to_string()));
                    }
                }
            }
        }
    }
    out.sort();
    out
}

fn ensure(cfg: &Config, reg: &Registry, kind: &str, name: &str) -> Result<Slot, String> {
    let be = backend(cfg, kind).ok_or_else(|| format!("unknown backend '{kind}'"))?;
    let path = model_path(cfg, be, name)
        .ok_or_else(|| format!("unknown model '{name}' for backend '{kind}'"))?;
    let key = format!("{kind}/{name}");

    let slot = {
        let mut r = reg.lock().unwrap();
        r.entry(key.clone())
            .or_insert_with(|| Arc::new(Mutex::new(None)))
            .clone()
    };
    {
        // Only this model's slot is held while loading, so a slow cold start of
        // one model never blocks requests for another.
        let mut g = slot.lock().unwrap();
        let cold = match g.as_mut() {
            None => true,
            Some(w) => !w.alive(),
        };
        if cold {
            eprintln!("npud: loading {key}");
            let started = Instant::now();
            let w = Worker::spawn(cfg, be, &path)?;
            eprintln!(
                "npud: {key} resident ({:.1}s)",
                started.elapsed().as_secs_f32()
            );
            *g = Some(w);
        }
    }
    Ok(slot)
}

fn serve(cfg: Arc<Config>, reg: Registry, stream: UnixStream) {
    let rd = match stream.try_clone() {
        Ok(s) => BufReader::new(s),
        Err(_) => return,
    };
    let mut w = BufWriter::new(stream);

    for line in rd.lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => return,
        };
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let mut parts = line.splitn(2, ' ');
        let cmd = parts.next().unwrap_or("").to_ascii_uppercase();
        let rest = parts.next().unwrap_or("").trim();

        let io: std::io::Result<()> = match cmd.as_str() {
            "LIST" => (|| {
                for (k, m) in scan(&cfg) {
                    writeln!(w, "{k} {m}")?;
                }
                writeln!(w, "END")
            })(),
            "STATUS" => {
                let snapshot: Vec<(String, bool)> = {
                    let r = reg.lock().unwrap();
                    r.iter()
                        .map(|(k, v)| (k.clone(), v.lock().map(|g| g.is_some()).unwrap_or(false)))
                        .collect()
                };
                (|| {
                    for (k, loaded) in snapshot {
                        writeln!(w, "{k} {}", if loaded { "resident" } else { "cold" })?;
                    }
                    writeln!(w, "END")
                })()
            }
            "WARM" => {
                let mut a = rest.splitn(2, ' ');
                let kind = a.next().unwrap_or("");
                let name = a.next().unwrap_or("").trim();
                match ensure(&cfg, &reg, kind, name) {
                    Ok(_) => writeln!(w, "OK {name}"),
                    Err(e) => writeln!(w, "ERR {e}"),
                }
            }
            "GEN" => {
                let mut a = rest.splitn(3, ' ');
                let kind = a.next().unwrap_or("");
                let name = a.next().unwrap_or("");
                let payload = a.next().unwrap_or("").trim();
                if kind.is_empty() || name.is_empty() || payload.is_empty() {
                    writeln!(w, "ERR usage: GEN <kind> <model> <text>")
                } else {
                    match ensure(&cfg, &reg, kind, name) {
                        Err(e) => writeln!(w, "ERR {e}"),
                        Ok(slot) => {
                            let be = backend(&cfg, kind).unwrap().clone();
                            let mut g = slot.lock().unwrap();
                            let res = match g.as_mut() {
                                Some(worker) => worker.request(&be, payload),
                                None => Err("worker vanished".into()),
                            };
                            match res {
                                Ok(text) => (|| {
                                    writeln!(w, "BEGIN")?;
                                    for l in text.lines() {
                                        writeln!(w, "{l}")?;
                                    }
                                    writeln!(w, "END")
                                })(),
                                Err(e) => {
                                    // Drop the corpse so the next request respawns
                                    // rather than inheriting a dead pipe.
                                    *g = None;
                                    writeln!(w, "ERR {e}")
                                }
                            }
                        }
                    }
                }
            }
            other => writeln!(w, "ERR unknown command '{other}'"),
        };

        if io.is_err() || w.flush().is_err() {
            return;
        }
    }
}

fn main() {
    let cfg = match load_config() {
        Ok(c) => Arc::new(c),
        Err(e) => {
            eprintln!("npud: config error: {e}");
            std::process::exit(1);
        }
    };

    let mut usable = 0;
    for be in &cfg.backends {
        if be.binary.is_file() {
            usable += 1;
        } else {
            eprintln!(
                "npud: backend '{}' disabled — binary missing: {}",
                be.kind,
                be.binary.display()
            );
        }
    }
    if usable == 0 {
        eprintln!("npud: no backend has a usable binary; refusing to start");
        std::process::exit(1);
    }

    if let Some(parent) = cfg.socket.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    // A stale socket from an unclean shutdown would block bind().
    let _ = std::fs::remove_file(&cfg.socket);
    let listener = match UnixListener::bind(&cfg.socket) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("npud: cannot bind {}: {e}", cfg.socket.display());
            std::process::exit(1);
        }
    };

    eprintln!(
        "npud: listening on {} | conf {} | models {}",
        cfg.socket.display(),
        cfg.conf_path.display(),
        cfg.model_dir.display()
    );
    for (k, m) in scan(&cfg) {
        eprintln!("npud:   {k}/{m}");
    }

    let reg: Registry = Arc::new(Mutex::new(HashMap::new()));

    if cfg.preload {
        // Sequential deliberately: two cold NPU inits at once is exactly the
        // contention this daemon exists to remove.
        for (kind, name) in scan(&cfg) {
            if let Err(e) = ensure(&cfg, &reg, &kind, &name) {
                eprintln!("npud: preload {kind}/{name} failed: {e}");
            }
        }
    }

    for s in listener.incoming() {
        match s {
            Ok(s) => {
                let cfg = Arc::clone(&cfg);
                let reg = Arc::clone(&reg);
                thread::spawn(move || serve(cfg, reg, s));
            }
            Err(e) => eprintln!("npud: accept failed: {e}"),
        }
    }
}
