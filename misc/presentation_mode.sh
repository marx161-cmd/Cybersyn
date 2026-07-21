#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

TERMUX_HOME="/data/data/com.termux/files/home"
STATE_DIR="${TERMUX_HOME}/.termux/tasker/.presentation-mode"
STATE_FILE="${STATE_DIR}/state.env"

EDGE_COMPONENT="com.microsoft.emmx/com.microsoft.ruby.Main"
ARTEMIS_COMPONENT="com.limelight.root.noirdebug/com.limelight.PcView"

require_root() {
    if [[ "$(id -u)" -ne 0 ]]; then
        echo "presentation_mode.sh must run as root" >&2
        exit 1
    fi
}

escape_squotes() {
    printf "%s" "$1" | sed "s/'/'\\\\''/g"
}

current_policy_control() {
    settings get global policy_control 2>/dev/null || true
}

store_state() {
    local target="$1"
    local component="$2"
    local previous_policy
    previous_policy="$(current_policy_control)"

    mkdir -p "$STATE_DIR"
    cat >"$STATE_FILE" <<EOF
TARGET='$(escape_squotes "$target")'
COMPONENT='$(escape_squotes "$component")'
PREVIOUS_POLICY_CONTROL='$(escape_squotes "$previous_policy")'
EOF
}

apply_statusbar_restrictions() {
    cmd statusbar collapse || true
    cmd statusbar send-disable-flag home recents statusbar-expansion notification-peek quick-settings
}

clear_statusbar_restrictions() {
    cmd statusbar send-disable-flag none
}

apply_policy_control() {
    local pkg="$1"
    settings put global policy_control "immersive.full=${pkg}"
}

restore_policy_control() {
    local previous="null"
    if [[ -f "$STATE_FILE" ]]; then
        source "$STATE_FILE"
        previous="${PREVIOUS_POLICY_CONTROL:-null}"
    fi

    if [[ -z "$previous" || "$previous" == "null" ]]; then
        settings delete global policy_control || true
    else
        settings put global policy_control "$previous"
    fi
}

launch_component() {
    local component="$1"
    am start-activity -W -n "$component"
}

component_to_package() {
    printf "%s" "${1%%/*}"
}

resolve_component() {
    case "$1" in
        edge) printf "%s" "$EDGE_COMPONENT" ;;
        artemis) printf "%s" "$ARTEMIS_COMPONENT" ;;
        */*) printf "%s" "$1" ;;
        *)
            echo "Unknown target '$1'." >&2
            exit 1
            ;;
    esac
}

enter_mode() {
    local target="$1"
    local component pkg
    component="$(resolve_component "$target")"
    pkg="$(component_to_package "$component")"

    store_state "$target" "$component"
    apply_policy_control "$pkg"
    apply_statusbar_restrictions
    launch_component "$component"
}

exit_mode() {
    clear_statusbar_restrictions
    restore_policy_control
}

case "${1:-}" in
    enter) enter_mode "$2" ;;
    exit) exit_mode ;;
    *) exit 1 ;;
esac
