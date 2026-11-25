#!/usr/bin/env bash
# build.sh - Convenient root wrapper for DeepSeek APK builder.
# Usage:
#   bash build.sh                         # Build universal release APK (dist/deepseek.apk)
#   bash build.sh --arch arm64-v8a        # Build 64-bit ARMv8-A+ APK (dist/deepseek-arm64-v8a.apk)
#   bash build.sh --arch armeabi-v7a      # Build 32-bit ARMv7-A APK (dist/deepseek-armeabi-v7a.apk)
#   bash build.sh --dev                   # Build dev edition (dist/deepseek-dev.apk)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$SCRIPT_DIR/modules/builder/build_apk.sh" "$@"
