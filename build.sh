#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# build.sh — Build the StorageFiller APK
#
# Usage:
#   ./build.sh              # debug build
#   ./build.sh release      # release build (unsigned)
#   ./build.sh clean        # clean build outputs
#
# All configuration is read from .env in this directory.
# ---------------------------------------------------------------------------
set -euo pipefail

# ── Load .env ───────────────────────────────────────────────────────────────
if [[ -f .env ]]; then
    # Export every non-comment, non-blank line
    set -o allexport
    # shellcheck disable=SC1091
    source <(grep -v '^\s*#' .env | grep -v '^\s*$')
    set +o allexport
else
    echo "Warning: .env not found — using defaults"
fi

APK_NAME="${APK_NAME:-StorageFiller}"
BUILD_TYPE="${1:-debug}"

echo "============================================"
echo " StorageFiller Builder"
echo " APK name    : ${APK_NAME}"
echo " Build type  : ${BUILD_TYPE}"
echo "============================================"

case "${BUILD_TYPE}" in
    clean)
        echo "Cleaning..."
        gradle clean
        echo "Done."
        ;;
    release)
        echo "Building release APK..."
        gradle assembleRelease
        OUTPUT_DIR="app/build/outputs/apk/release"
        APK_FILE="${OUTPUT_DIR}/${APK_NAME}-release.apk"
        echo ""
        if [[ -f "${APK_FILE}" ]]; then
            echo "✓ APK ready: ${APK_FILE}"
        else
            echo "Build finished. Check ${OUTPUT_DIR}/ for the APK."
        fi
        ;;
    debug|*)
        echo "Building debug APK..."
        gradle assembleDebug
        OUTPUT_DIR="app/build/outputs/apk/debug"
        APK_FILE="${OUTPUT_DIR}/${APK_NAME}-debug.apk"
        echo ""
        if [[ -f "${APK_FILE}" ]]; then
            echo "✓ APK ready: ${APK_FILE}"
        else
            echo "Build finished. Check ${OUTPUT_DIR}/ for the APK."
        fi
        ;;
esac
