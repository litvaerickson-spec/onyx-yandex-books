#!/usr/bin/env bash
set -Eeuo pipefail

AVD_NAME="${AVD_NAME:-onyx_darwin5_api23}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-180}"
EMU_MEMORY="${EMU_MEMORY:-1536}"
ADB_PORT="${ADB_PORT:-5555}"

log() {
    printf '[%s] [onyx-emu] %s\n' "$(date -u +%H:%M:%S)" "$*"
}

if [[ ! -r /dev/kvm ]] || [[ ! -w /dev/kvm ]]; then
    log "ERROR: /dev/kvm is missing or not accessible. Start container with --device /dev/kvm."
    exit 1
fi

log "Starting ADB server..."
adb start-server

# Expose emulator ADB over TCP 5555 cleanly when requested by external tooling,
# while also keeping local emulator-5554 accessible inside container.
EMU_PID=""
cleanup() {
    log "Shutting down Android emulator..."
    adb -s emulator-5554 emu kill >/dev/null 2>&1 || true
    if [[ -n "$EMU_PID" ]] && kill -0 "$EMU_PID" 2>/dev/null; then
        kill "$EMU_PID" 2>/dev/null || true
        wait "$EMU_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

log "Booting AVD '${AVD_NAME}' (KVM headless, RAM=${EMU_MEMORY}M)..."
emulator \
    -avd "${AVD_NAME}" \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -gpu swiftshader_indirect \
    -no-snapshot \
    -memory "${EMU_MEMORY}" \
    -ports 5554,5555 \
    -qemu -enable-kvm &
EMU_PID=$!

log "Waiting for device online..."
elapsed=0
until adb -s emulator-5554 get-state 2>/dev/null | grep -q '^device$'; do
    if ! kill -0 "$EMU_PID" 2>/dev/null; then
        log "ERROR: Emulator process exited prematurely!"
        exit 1
    fi
    if (( elapsed >= BOOT_TIMEOUT )); then
        log "ERROR: Timeout (${BOOT_TIMEOUT}s) waiting for emulator-5554."
        exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
done

log "Waiting for Android boot_completed & package/download services..."
while (( elapsed < BOOT_TIMEOUT )); do
    boot="$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    pkg_ready="$(adb -s emulator-5554 shell pm path android 2>/dev/null | tr -d '\r' || true)"
    if [[ "$boot" == "1" && -n "$pkg_ready" ]]; then
        break
    fi
    sleep 2
    elapsed=$((elapsed + 2))
done

if [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" != "1" ]]; then
    log "ERROR: Android did not finish booting within ${BOOT_TIMEOUT}s."
    exit 1
fi

# Unlock keyguard & disable window animations for deterministic UI testing
adb -s emulator-5554 shell input keyevent 82 >/dev/null 2>&1 || true
adb -s emulator-5554 shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb -s emulator-5554 shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb -s emulator-5554 shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
# Keep screen awake during tests
adb -s emulator-5554 shell svc power stayon true >/dev/null 2>&1 || true

log "READY: AVD '${AVD_NAME}' booted in ${elapsed}s."

if [[ $# -gt 0 ]]; then
    log "Executing command inside container: $*"
    exec "$@"
else
    log "Emulator is running. Press Ctrl+C or stop container to exit."
    wait "$EMU_PID"
fi
