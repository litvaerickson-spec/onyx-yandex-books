#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

readonly IMAGE_NAME="${IMAGE_NAME:-onyx-yandex-books-emulator:latest}"
readonly DEFAULT_AVDS=(
    "onyx_darwin1_api17"
    "onyx_darwin5_api19"
    "onyx_darwin6_api19"
    "onyx_darwin5_api23"
    "onyx_darwin7_api28"
)

log_info() {
    printf '[%s] [INFO] %s\n' "$(date +%H:%M:%S)" "$*"
}

log_err() {
    printf '[%s] [ERROR] %s\n' "$(date +%H:%M:%S)" "$*" >&2
}

usage() {
    cat <<'EOF'
Usage: run_all_emulators.sh [OPTIONS] [APK_PATH]

Sequentially runs isolated Docker Android Emulator E2E verification
across all 5 Onyx Boox AVD profiles (API 17, 19x2, 23, 28).

Options:
  --list            Print configured AVD profiles and exit
  --no-fail-fast    Continue running remaining AVDs even if one fails
  -h, --help        Show this help message

Environment:
  IMAGE_NAME        Docker image (default: onyx-yandex-books-emulator:latest)
  AVDS              Space-separated custom AVD list override
  TEST_TIMEOUT      Per-AVD timeout in seconds (default: 240)
EOF
}

resolve_apk() {
    local requested="${1:-}"
    if [[ -n "${requested}" ]]; then
        if [[ ! -f "${requested}" ]]; then
            log_err "Specified APK does not exist: ${requested}"
            exit 1
        fi
        printf '%s\n' "${requested}"
        return 0
    fi

    local detected=""
    detected="$(find "${PROJECT_DIR}" -maxdepth 1 -name 'yandex-books-lite-v*.apk' -printf '%T@ %p\n' | sort -nr | head -n1 | awk '{print $2}' || true)"
    if [[ -z "${detected}" || ! -f "${detected}" ]]; then
        log_err "APK not found in ${PROJECT_DIR}. Run 'bash build_apk.sh' or pass APK_PATH."
        exit 1
    fi
    printf '%s\n' "${detected}"
}

main() {
    local fail_fast=1
    local list_only=0
    local apk_arg=""

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --list)
                list_only=1
                shift
                ;;
            --no-fail-fast)
                fail_fast=0
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            -*)
                log_err "Unknown option: $1"
                usage >&2
                exit 1
                ;;
            *)
                if [[ -n "${apk_arg}" ]]; then
                    log_err "Unexpected extra argument: $1"
                    exit 1
                fi
                apk_arg="$1"
                shift
                ;;
        esac
    done

    local avds=("${DEFAULT_AVDS[@]}")
    if [[ -n "${AVDS:-}" ]]; then
        read -r -a avds <<< "${AVDS}"
    fi

    if (( list_only == 1 )); then
        printf '%s\n' "${avds[@]}"
        exit 0
    fi

    if ! command -v docker >/dev/null 2>&1; then
        log_err "docker CLI not found in PATH"
        exit 1
    fi

    if [[ ! -c /dev/kvm ]]; then
        log_err "/dev/kvm device node is required for KVM-accelerated Android emulator."
        exit 1
    fi

    if ! docker image inspect "${IMAGE_NAME}" >/dev/null 2>&1; then
        log_err "Docker image '${IMAGE_NAME}' not found. Build it first:"
        log_err "  docker build --network=host -t ${IMAGE_NAME} ${SCRIPT_DIR}"
        exit 1
    fi

    local apk_path=""
    apk_path="$(resolve_apk "${apk_arg}")"

    log_info "Target APK: ${apk_path}"
    log_info "Running strictly sequential E2E across ${#avds[@]} AVD profiles (one container at a time for RAM safety)..."

    local passed=0
    local failed=0
    local results=()
    local avd=""
    local start_ts=0
    local dur=0

    for avd in "${avds[@]}"; do
        printf '\n============================================================\n'
        log_info "Starting AVD '${avd}'..."
        printf '============================================================\n'
        start_ts="$(date +%s)"
        if env "IMAGE_NAME=${IMAGE_NAME}" "AVD_NAME=${avd}" bash "${SCRIPT_DIR}/run_emulator_e2e.sh" "${apk_path}"; then
            dur="$(( $(date +%s) - start_ts ))"
            results+=("PASS | ${avd} (${dur}s)")
            passed=$((passed + 1))
        else
            dur="$(( $(date +%s) - start_ts ))"
            results+=("FAIL | ${avd} (${dur}s)")
            failed=$((failed + 1))
            if (( fail_fast == 1 )); then
                log_err "AVD '${avd}' failed (fail-fast enabled). Aborting remaining AVDs."
                break
            fi
        fi
    done

    printf '\n==================== E2E MATRIX SUMMARY ====================\n'
    printf 'APK: %s\n' "${apk_path}"
    local line=""
    for line in "${results[@]}"; do
        printf '  %s\n' "${line}"
    done
    printf 'Total: passed=%d, failed=%d\n' "${passed}" "${failed}"
    printf 'Screenshots: %s/docs/emulator_verify/\n' "${PROJECT_DIR}"
    printf '============================================================\n'

    (( failed == 0 ))
}

main "$@"
