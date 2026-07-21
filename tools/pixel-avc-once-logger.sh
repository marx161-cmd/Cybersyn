#!/system/bin/sh
set -eu

OUT_DIR="${1:-/data/local/tmp/avc-watch-$(date +%Y%m%d-%H%M%S)}"
RAW="$OUT_DIR/avc-raw.log"
UNIQUE="$OUT_DIR/avc-unique.txt"
ALLOW="$OUT_DIR/avc-suggested-allow-review.te"
PIDFILE="$OUT_DIR/logger.pid"
SEEN="$OUT_DIR/.seen"
# Skip-list of KNOWN-OURS denial signatures (grep -E patterns matched against the
# dedup key). Keeps already-triaged Termux-family denials out of the unique/review
# outputs so each session surfaces only NEW denials. Everything still lands in RAW.
SKIP="${SKIP_FILE:-/data/adb/cybersyn-avc-logger/skip-known-ours.txt}"

mkdir -p "$OUT_DIR"
: > "$SEEN"
printf '%s\n' "$$" > "$PIDFILE"

{
  printf '# started=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '# out_dir=%s\n' "$OUT_DIR"
  printf '# getenforce=%s\n' "$(getenforce 2>/dev/null || true)"
} >> "$RAW"
cp "$RAW" "$UNIQUE"
cp "$RAW" "$ALLOW"

logcat -b all -c || true

logcat -b all -v threadtime auditd:I '*:S' | while IFS= read -r line; do
  case "$line" in
    *'avc:  denied'*|*'avc: denied'*) ;;
    *) continue ;;
  esac

  printf '%s\n' "$line" >> "$RAW"

  perms="$(printf '%s\n' "$line" | sed -n 's/.*avc: *denied *{ *\([^}]*\) *}.*/\1/p' | tr -s ' ' | sed 's/^ //;s/ $//')"
  sctx="$(printf '%s\n' "$line" | sed -n 's/.*scontext=\([^ ]*\).*/\1/p')"
  tctx="$(printf '%s\n' "$line" | sed -n 's/.*tcontext=\([^ ]*\).*/\1/p')"
  tclass="$(printf '%s\n' "$line" | sed -n 's/.*tclass=\([^ ]*\).*/\1/p')"
  comm="$(printf '%s\n' "$line" | sed -n 's/.*comm="\([^"]*\)".*/\1/p')"
  app="$(printf '%s\n' "$line" | sed -n 's/.* app=\([^ ]*\).*/\1/p')"
  name="$(printf '%s\n' "$line" | sed -n 's/.* name="\([^"]*\)".*/\1/p')"
  path="$(printf '%s\n' "$line" | sed -n 's/.* path="\([^"]*\)".*/\1/p')"

  stype="$(printf '%s\n' "$sctx" | cut -d: -f3)"
  ttype="$(printf '%s\n' "$tctx" | cut -d: -f3)"
  target="$path"
  [ -n "$target" ] || target="$name"
  [ -n "$target" ] || target="-"

  key="perms=$perms|s=$stype|t=$ttype|class=$tclass|comm=$comm|app=$app|target=$target"

  # skip already-triaged known-ours signatures (still recorded in RAW above)
  if [ -f "$SKIP" ] && printf '%s\n' "$key" | grep -Eqf "$SKIP" 2>/dev/null; then
    continue
  fi

  if grep -Fqx "$key" "$SEEN" 2>/dev/null; then
    continue
  fi
  printf '%s\n' "$key" >> "$SEEN"

  timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '%s %s\n  raw: %s\n' "$timestamp" "$key" "$line" >> "$UNIQUE"
  printf 'allow %s %s:%s { %s }; # REVIEW comm=%s app=%s target=%s\n' \
    "$stype" "$ttype" "$tclass" "$perms" "$comm" "$app" "$target" >> "$ALLOW"
done
