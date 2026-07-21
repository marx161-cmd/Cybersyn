#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PKG="${DIANA_PKG:-com.limelight.root.noirdebug}"
ACTION_START="${PKG}.action.START_LOCK_TASK"
ACTION_STOP="${PKG}.action.STOP_LOCK_TASK"
CODEC_DEVFREQ="/sys/class/devfreq/codec_3p_freq"

usage() {
    cat <<EOF
Usage:
  ~/.termux/tasker/diana_stream_kiosk.sh start [package]
  ~/.termux/tasker/diana_stream_kiosk.sh stop [package]

Default package: ${PKG}
EOF
}

set_codec_governor() {
    local governor="$1"

    su -c "
        if [ -d '$CODEC_DEVFREQ' ] && [ -w '$CODEC_DEVFREQ/governor' ]; then
            echo '$governor' > '$CODEC_DEVFREQ/governor'
        fi
    " >/dev/null 2>&1 || true
}

broadcast_action() {
    local action="$1"
    am broadcast -a "$action" -p "$PKG"
}

main() {
    local command="${1:-start}"
    if [[ $# -ge 2 ]]; then
        PKG="$2"
        ACTION_START="${PKG}.action.START_LOCK_TASK"
        ACTION_STOP="${PKG}.action.STOP_LOCK_TASK"
    fi

    case "$command" in
        start|enter)
            set_codec_governor performance
            broadcast_action "$ACTION_START"
            ;;
        stop|exit)
            broadcast_action "$ACTION_STOP"
            set_codec_governor powersave
            ;;
        -h|--help|help)
            usage
            ;;
        *)
            usage >&2
            exit 1
            ;;
    esac
}

main "$@"
