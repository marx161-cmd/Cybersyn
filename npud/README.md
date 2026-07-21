# npud — resident NPU model daemon

Keeps Tensor G5 model workers warm so requests skip per-call initialisation, and
exposes them over a Unix socket to any client.

## Why

Two problems, one cause. Every NPU consumer re-initialises its model per request,
and on the **in-process JNI path** that init is not just slow but fatal —
`nativeCreateEngine` SIGABRTs the calling app (Agora chat/title generation;
SpectreBoard whisper before it). Everything running the same models
**out of process** has been reliable for months (SubAgent, the embedder).

npud makes that pattern a service: one long-lived worker per model, spawned once,
kept warm. Clients stop linking model runtimes — they write a line, read a line.

Measured on blazer, Gemma 3 1B ekv4096:

| | latency |
|---|---|
| cold (includes model load) | 6.6 s |
| warm | **1.2 s** |

## Backends are config, not code

The G5 workers are siblings — all line-oriented stdin/stdout — but their token
vocabularies differ (`POLL_E_READY`, `KOKORO_G5_READY`, `WHISPER_G5_OK`,
`VIBEVOICE_G5_READY`). So a backend is a section in `npud.conf`; adding a worker
type is an edit plus a restart, never a rebuild. Verify tokens against the
worker's own source in `~/builds/android/LiteRT/litert/tools/*_g5_worker.cc`.

Backends whose binary is missing are logged and skipped, so a partial deployment
still serves whatever is present.

## Adding a model

Drop it in `<model-dir>/<kind>/<name>.<ext>`. `LIST` rescans every call, so it is
visible immediately, loads on first use, and stays warm.

## Protocol

```
LIST                       -> "<kind> <model>" per line, then END
STATUS                     -> "<kind> <model> resident|cold" per line, END
WARM <kind> <model>        -> OK <model>
GEN  <kind> <model> <text> -> BEGIN, output lines, END
```
Errors return one `ERR <message>`. Newlines in requests are escaped `\n`.

## Build and deploy

```sh
cargo build --release --target aarch64-linux-android
adb push target/aarch64-linux-android/release/npud /data/local/tmp/npud
```
Install as a Termux runit service with `service/run` — see the header comment in
that file. runit is used rather than Android init because it runs natively under
the Termux UID and needs no new SELinux domain (this ROM builds with
`PRODUCT_PRIVATE_SEPOLICY_DIRS` disabled).

## Memory

Model weights **are** file-backed mmaps in the worker's address space — Gemma 1B
shows ~3.7 GB RSS with five `.litertlm` mappings. They are clean pages, so the
kernel can evict them under pressure and the next request pays a re-read.
`ulimit -l unlimited` in the service wrapper allows pinning them; the 64 KB
default is not enough for anything.

Consequence for `NPUD_PRELOAD=1`: each resident LLM costs roughly its file size
in RSS, so preloading several models will not fit in 16 GB. Preload deliberately,
not reflexively.
