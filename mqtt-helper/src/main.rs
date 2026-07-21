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

use std::io::{BufRead, Write};
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
