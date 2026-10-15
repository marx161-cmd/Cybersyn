//! cybersyn-mqtt — the self-contained MQTT transport helper bundled inside the
//! Cybersyn Android app (shipped as `jniLibs/arm64-v8a/libcybersyn-mqtt.so`, exec'd
//! from the app's nativeLibraryDir). It links only against bionic, so it needs no
//! Termux packages and survives a fresh install / Termux reset.
//!
//! Same `rumqttc` stack as the Fedora relay, so all three layers speak identical MQTT
//! — if this binary ever fails, the app can fall back to Termux's mosquitto clients
//! with no protocol change.
//!
//! Wire protocol with the app is line-based over stdio (control messages are short and
//! newline-free):
//!   sub  -> prints "<topic>\t<payload>" per inbound message on stdout
//!   pub  -> reads "<topic>\t<payload>" lines on stdin, publishes each on ONE
//!           persistent connection (arbitrary topics — no mosquitto_pub -l limit)
//!   hub  -> ONE connection for everything (what the app uses): stdin lines
//!           "S\t<filter>" subscribe, "U\t<filter>" unsubscribe,
//!           "P\t<topic>\t<payload>" publish; stdout "<topic>\t<payload>" per
//!           inbound message. One TCP connection + one keepalive instead of one per
//!           topic, which matters because every wake-up costs cellular radio energy.

use std::collections::HashSet;
use std::io::{BufRead, Write};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use clap::{Parser, Subcommand};
use rumqttc::{Client, Event, MqttOptions, Outgoing, Packet, QoS};

#[derive(Parser)]
#[command(name = "cybersyn-mqtt", about = "Cybersyn self-contained MQTT helper")]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Subscribe to a topic filter; print "topic\tpayload" per inbound message.
    Sub {
        #[arg(long)]
        broker: String,
        #[arg(long, default_value_t = 1883)]
        port: u16,
        #[arg(long)]
        topic: String,
        #[arg(long, default_value = "cybersyn-helper-sub")]
        id: String,
    },
    /// Publish "topic\tpayload" lines read from stdin over one persistent connection.
    Pub {
        #[arg(long)]
        broker: String,
        #[arg(long, default_value_t = 1883)]
        port: u16,
        #[arg(long, default_value = "cybersyn-helper-pub")]
        id: String,
    },
    /// One connection carrying every subscription and publish, driven over stdio.
    Hub {
        #[arg(long)]
        broker: String,
        #[arg(long, default_value_t = 1883)]
        port: u16,
        #[arg(long, default_value = "cybersyn-hub")]
        id: String,
        /// MQTT keepalive seconds (each ping wakes the radio; dead-link detection is ~2x this).
        #[arg(long, default_value_t = 60)]
        keepalive: u64,
    },
}

fn options(id: &str, broker: &str, port: u16) -> MqttOptions {
    let mut opts = MqttOptions::new(id, broker, port);
    opts.set_keep_alive(Duration::from_secs(30));
    opts.set_clean_session(true);
    opts
}

fn main() {
    match Cli::parse().cmd {
        Cmd::Sub { broker, port, topic, id } => run_sub(&id, &broker, port, &topic),
        Cmd::Pub { broker, port, id } => run_pub(&id, &broker, port),
        Cmd::Hub { broker, port, id, keepalive } => run_hub(&id, &broker, port, keepalive),
    }
}

fn run_sub(id: &str, broker: &str, port: u16, topic: &str) {
    let (client, mut connection) = Client::new(options(id, broker, port), 10);
    let stdout = std::io::stdout();
    for event in connection.iter() {
        match event {
            // (Re)subscribe on every connect so a reconnect recovers the subscription.
            Ok(Event::Incoming(Packet::ConnAck(_))) => {
                let _ = client.subscribe(topic, QoS::AtMostOnce);
            }
            Ok(Event::Incoming(Packet::Publish(p))) => {
                let payload = String::from_utf8_lossy(&p.payload);
                let mut handle = stdout.lock();
                let _ = writeln!(handle, "{}\t{}", p.topic, payload);
                let _ = handle.flush();
            }
            Ok(_) => {}
            // rumqttc's event loop auto-reconnects; back off so we don't spin on errors.
            Err(_) => thread::sleep(Duration::from_secs(2)),
        }
    }
}

fn run_pub(id: &str, broker: &str, port: u16) {
    let (client, mut connection) = Client::new(options(id, broker, port), 10);

    // Reader thread publishes each stdin line. In normal app use stdin stays open for
    // the process lifetime (e.g. the mousepad stream), so this runs indefinitely. On
    // EOF it requests a clean disconnect so the event loop below flushes every queued
    // publish before the process exits (QoS 0 is enqueue-then-send).
    thread::spawn(move || {
        let stdin = std::io::stdin();
        for line in stdin.lock().lines() {
            let line = match line {
                Ok(l) => l,
                Err(_) => break,
            };
            if line.is_empty() {
                continue;
            }
            let (topic, payload) = line.split_once('\t').unwrap_or((line.as_str(), ""));
            let _ = client.publish(topic, QoS::AtMostOnce, false, payload.as_bytes());
        }
        let _ = client.disconnect();
    });

    // Main drives the connection: this is where queued publishes actually go out and
    // where reconnects happen. Exit once our disconnect has been sent.
    for event in connection.iter() {
        match event {
            Ok(Event::Outgoing(Outgoing::Disconnect)) => break,
            Err(_) => thread::sleep(Duration::from_secs(2)),
            _ => {}
        }
    }
}

fn run_hub(id: &str, broker: &str, port: u16, keepalive: u64) {
    let mut opts = options(id, broker, port);
    opts.set_keep_alive(Duration::from_secs(keepalive.max(5)));
    let (client, mut connection) = Client::new(opts, 64);
    let filters: Arc<Mutex<HashSet<String>>> = Arc::new(Mutex::new(HashSet::new()));

    // Command reader. EOF means the app is gone: disconnect cleanly so this process exits
    // instead of lingering as an orphan.
    {
        let client = client.clone();
        let filters = Arc::clone(&filters);
        thread::spawn(move || {
            let stdin = std::io::stdin();
            for line in stdin.lock().lines() {
                let line = match line {
                    Ok(l) => l,
                    Err(_) => break,
                };
                let mut parts = line.splitn(3, '\t');
                match (parts.next(), parts.next(), parts.next()) {
                    (Some("S"), Some(f), _) if !f.is_empty() => {
                        filters.lock().unwrap().insert(f.to_string());
                        let _ = client.subscribe(f, QoS::AtMostOnce);
                    }
                    (Some("U"), Some(f), _) if !f.is_empty() => {
                        filters.lock().unwrap().remove(f);
                        let _ = client.unsubscribe(f);
                    }
                    // try_publish: if we are offline and the queue is full, drop — stale
                    // realtime input (mouse/gyro) must not replay after a reconnect.
                    (Some("P"), Some(t), p) if !t.is_empty() => {
                        let _ = client.try_publish(t, QoS::AtMostOnce, false, p.unwrap_or("").as_bytes());
                    }
                    _ => {}
                }
            }
            let _ = client.disconnect();
        });
    }

    let stdout = std::io::stdout();
    for event in connection.iter() {
        match event {
            // (Re)subscribe everything on every connect so a network drop recovers here.
            // try_subscribe: this thread drains the request queue, so it must never block on it.
            Ok(Event::Incoming(Packet::ConnAck(_))) => {
                for f in filters.lock().unwrap().iter() {
                    let _ = client.try_subscribe(f.as_str(), QoS::AtMostOnce);
                }
            }
            Ok(Event::Incoming(Packet::Publish(p))) => {
                let payload = String::from_utf8_lossy(&p.payload);
                let mut handle = stdout.lock();
                let _ = writeln!(handle, "{}\t{}", p.topic, payload);
                let _ = handle.flush();
            }
            Ok(Event::Outgoing(Outgoing::Disconnect)) => break,
            Ok(_) => {}
            Err(_) => thread::sleep(Duration::from_secs(2)),
        }
    }
}
