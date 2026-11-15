#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

IMAGE_NAME="${IMAGE_NAME:-onyx-yandex-books-emulator:latest}"
AVD_NAME="${AVD_NAME:-onyx_darwin5_api23}"
TAG_PREFIX="${TAG_PREFIX:-${AVD_NAME}}"
APK_PATH="${1:-}"

if [[ -z "${APK_PATH}" ]]; then
    APK_PATH="$(find "${PROJECT_DIR}" -maxdepth 1 -name 'yandex-books-lite-v*.apk' -printf '%T@ %p\n' | sort -nr | head -n1 | awk '{print $2}')"
fi

if [[ -z "${APK_PATH}" ]] || [[ ! -f "${APK_PATH}" ]]; then
    echo "ERROR: APK file not found in ${PROJECT_DIR}. Pass path as arg 1 or run build_apk.sh first." >&2
    exit 1
fi

APK_BASENAME="$(basename "${APK_PATH}")"
ARTIFACTS_DIR="${ARTIFACTS_DIR:-${PROJECT_DIR}/docs/emulator_verify/${TAG_PREFIX}}"
mkdir -p "${ARTIFACTS_DIR}"

echo "=== Running isolated Docker Android Emulator E2E Test ==="
echo "  AVD:       ${AVD_NAME}"
echo "  APK:       ${APK_BASENAME}"
echo "  Artifacts: ${ARTIFACTS_DIR}"

timeout "${TEST_TIMEOUT:-240}" docker run --rm \
    --network=host \
    --device /dev/kvm:/dev/kvm \
    --shm-size=1g \
    -e "AVD_NAME=${AVD_NAME}" \
    -e "BOOT_TIMEOUT=${BOOT_TIMEOUT:-180}" \
    -v "${PROJECT_DIR}:/workspace:ro" \
    -v "${ARTIFACTS_DIR}:/artifacts" \
    "${IMAGE_NAME}" \
    python3 -u /workspace/tools/emulator/e2e_verify.py \
        --apk "/workspace/${APK_BASENAME}" \
        --artifacts "/artifacts" \
        --tag "${TAG_PREFIX}"
