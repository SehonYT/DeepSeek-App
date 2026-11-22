#!/usr/bin/env bash
# build.sh - Convenient root wrapper for DeepSeek APK builder.
# Usage:
#   bash build.sh         # Build standard release edition (dist/deepseek.apk)
#   bash build.sh --dev   # Build dev edition (dist/deepseek-dev.apk)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$SCRIPT_DIR/modules/builder/build_apk.sh" "$@"
