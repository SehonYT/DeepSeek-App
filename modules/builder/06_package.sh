#!/usr/bin/env bash
# 06_package.sh - Packages Android resources, assets, and classes.dex into unaligned.apk.
set -euo pipefail

export ANDROID_DATA="${ANDROID_DATA:-/data}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${PROJECT_ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
APP_DIR="${APP_DIR:-$PROJECT_ROOT/app}"
UNALIGNED_APK="${1:-$BUILD_DIR/unaligned.apk}"
MANIFEST="${2:-$APP_DIR/src/main/AndroidManifest.xml}"
RES_DIR="${3:-$APP_DIR/src/main/res}"
ANDROID_JAR="${4:-$TOOLS_DIR/android.jar}"
STAGE_ASSETS="${5:-$BUILD_DIR/staging/assets}"
DEX_FILE="${6:-$BUILD_DIR/dex/classes.dex}"
BUILD_RES_DIR="${7:-${BUILD_DIR:-}/res}"

RES_ARGS=()
if [ -n "${BUILD_RES_DIR:-}" ] && [ -d "$BUILD_RES_DIR" ]; then
    RES_ARGS+=(-S "$BUILD_RES_DIR")
fi
RES_ARGS+=(-S "$RES_DIR" --auto-add-overlay)

echo "[*] [06_package] Packaging base resources into unaligned APK..."
aapt package -f \
    -M "$MANIFEST" \
    "${RES_ARGS[@]}" \
    -I "$ANDROID_JAR" \
    -F "$UNALIGNED_APK"

echo "[*] [06_package] Injecting classes.dex, jniLibs, and assets into APK..."
python3 - "$UNALIGNED_APK" "$DEX_FILE" "$STAGE_ASSETS" "$APP_DIR/src/main/jniLibs" "${TARGET_ARCH:-universal}" <<'PY'
import sys, zipfile, os

apk_path = sys.argv[1]
dex_path = sys.argv[2]
assets_dir = sys.argv[3]
jni_dir = sys.argv[4]
target_arch = sys.argv[5]

allowed_abis = {"arm64-v8a", "armeabi-v7a"} if target_arch == "universal" else {target_arch}

with zipfile.ZipFile(apk_path, "a", compression=zipfile.ZIP_DEFLATED) as z:
    z.write(dex_path, "classes.dex")
    if os.path.isdir(jni_dir):
        for abi in sorted(os.listdir(jni_dir)):
            if abi not in allowed_abis:
                continue
            abi_path = os.path.join(jni_dir, abi)
            if not os.path.isdir(abi_path):
                continue
            for fn in sorted(os.listdir(abi_path)):
                if fn.endswith(".so"):
                    full = os.path.join(abi_path, fn)
                    z.write(full, f"lib/{abi}/{fn}", compress_type=zipfile.ZIP_STORED)
    if os.path.isdir(assets_dir):
        staging_parent = os.path.dirname(assets_dir)
        for root, dirs, files in os.walk(assets_dir):
            for f in files:
                full = os.path.join(root, f)
                if not os.path.exists(full) and os.path.islink(full):
                    continue
                rel = os.path.relpath(full, staging_parent)
                # Avoid recompressing already-compressed archives so AssetManager can stream/mmap directly
                comp = zipfile.ZIP_STORED if f.endswith(('.gz', '.tgz', '.zst', '.tzst', '.zip', '.png', '.jpg', '.blob')) else zipfile.ZIP_DEFLATED
                try:
                    z.write(full, rel, compress_type=comp)
                except Exception as e:
                    print(f"[!] Warning: skipped {full}: {e}")
PY

echo "[OK] [06_package] Created $(stat -c%s "$UNALIGNED_APK") bytes unaligned.apk"
