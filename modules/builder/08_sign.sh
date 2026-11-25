#!/usr/bin/env bash
# 08_sign.sh - Signs the aligned APK using apksigner and debug.keystore.
set -euo pipefail

ALIGNED_APK="${1:-$BUILD_DIR/aligned.apk}"
OUT_APK="${2:-$DIST_DIR/deepseek.apk}"
KEYSTORE="${3:-$TOOLS_DIR/release.keystore}"
KEY_ALIAS="${4:-salman2005}"
KEY_PASS="${5:-salman2005}"
STORE_PASS="${6:-salman2005}"

if [ ! -f "$KEYSTORE" ] && [ -f "$TOOLS_DIR/debug.keystore" ]; then
    KEYSTORE="$TOOLS_DIR/debug.keystore"
    KEY_ALIAS="salman2005"
fi

echo "[*] [08_sign] Signing APK with $KEYSTORE (alias: $KEY_ALIAS)..."
mkdir -p "$(dirname "$OUT_APK")"

# apksigner only enables the v1 JAR scheme when minSdkVersion < 24; with
# minSdk 24 it silently drops v1. Some vendors/installers (and any tool that
# falls back to JAR verification) then reject the APK. Enable all three
# schemes explicitly so the APK verifies on every supported Android version.
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --min-sdk-version 24 \
    --out "$OUT_APK" \
    "$ALIGNED_APK"

echo "[OK] [08_sign] Signed APK created: $OUT_APK ($(stat -c%s "$OUT_APK") bytes)"
