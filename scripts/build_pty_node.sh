#!/usr/bin/env bash
# build_pty_node.sh - Cross-compile node-pty (pty.node) for Android ABIs using NDK.
# Usage:
#   bash scripts/build_pty_node.sh --arch android-arm [--dsh-source archives/dsh-source]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

TARGET_ABI="android-arm"
DSH_SOURCE=""
NODE_PTY_VER="1.2.0-beta.15"
NODE_ADDON_API_VER="8.3.0"
NODE_HEADERS_VER="v22.14.0"

while [ $# -gt 0 ]; do
    case "$1" in
        --arch)
            TARGET_ABI="$2"
            shift 2
            ;;
        --dsh-source)
            DSH_SOURCE="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

find_ndk() {
    for candidate in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" "${NDK_HOME:-}"; do
        if [ -n "$candidate" ] && [ -d "$candidate/toolchains/llvm/prebuilt" ]; then
            echo "$candidate"
            return 0
        fi
    done
    local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
    if [ -d "$sdk_root/ndk" ]; then
        local latest
        latest="$(ls -1d "$sdk_root/ndk"/* 2>/dev/null | sort -V | tail -n 1 || true)"
        if [ -n "$latest" ] && [ -d "$latest/toolchains/llvm/prebuilt" ]; then
            echo "$latest"
            return 0
        fi
    fi
    return 1
}

NDK_DIR="$(find_ndk || true)"
if [ -z "$NDK_DIR" ]; then
    echo "[ERROR] Android NDK not found. Set ANDROID_NDK_HOME or install NDK." >&2
    exit 1
fi

HOST_TAG="$(ls -1 "$NDK_DIR/toolchains/llvm/prebuilt" | head -n 1)"
LLVM_BIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin"

case "$TARGET_ABI" in
    android-arm|armeabi-v7a|arm)
        TARGET_ABI="android-arm"
        CLANGXX="$LLVM_BIN/armv7a-linux-androideabi24-clang++"
        ARCH_FLAGS=(-march=armv7-a -mfpu=neon -mfloat-abi=softfp)
        EXPECTED_CLASS="ELF32"
        EXPECTED_MACHINE="ARM"
        ;;
    android-arm64|arm64-v8a|arm64)
        TARGET_ABI="android-arm64"
        CLANGXX="$LLVM_BIN/aarch64-linux-android24-clang++"
        ARCH_FLAGS=(-march=armv8-a)
        EXPECTED_CLASS="ELF64"
        EXPECTED_MACHINE="AArch64"
        ;;
    *)
        echo "[ERROR] Unsupported TARGET_ABI: $TARGET_ABI" >&2
        exit 1
        ;;
esac

if [ ! -x "$CLANGXX" ]; then
    echo "[ERROR] NDK compiler missing: $CLANGXX" >&2
    exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "[*] Fetching node-pty@${NODE_PTY_VER}, node-addon-api@${NODE_ADDON_API_VER}, and Node N-API headers..."
curl -fsSL "https://registry.npmjs.org/node-pty/-/node-pty-${NODE_PTY_VER}.tgz" | tar -xz -C "$WORK_DIR"
mkdir -p "$WORK_DIR/node_addon_api" "$WORK_DIR/node_headers"
curl -fsSL "https://registry.npmjs.org/node-addon-api/-/node-addon-api-${NODE_ADDON_API_VER}.tgz" | tar -xz -C "$WORK_DIR/node_addon_api"
curl -fsSL "https://nodejs.org/dist/${NODE_HEADERS_VER}/node-${NODE_HEADERS_VER}-headers.tar.gz" | tar -xz -C "$WORK_DIR/node_headers"

PTY_PKG="$WORK_DIR/package"
if [ -n "$DSH_SOURCE" ] && [ -f "$DSH_SOURCE/patches/node-pty@${NODE_PTY_VER}.patch" ]; then
    echo "[*] Applying node-pty mobile patch..."
    patch -p1 -d "$PTY_PKG" -i "$DSH_SOURCE/patches/node-pty@${NODE_PTY_VER}.patch" || true
fi

OUT_DIR="$PROJECT_ROOT/vendor/native/$TARGET_ABI"
OUT_NODE="$OUT_DIR/pty.node"
mkdir -p "$OUT_DIR"

echo "[*] Compiling $OUT_NODE with $CLANGXX..."
"$CLANGXX" \
    -shared -fPIC -O2 -std=c++17 -fexceptions -frtti \
    "${ARCH_FLAGS[@]}" \
    -DNAPI_VERSION=8 \
    -DNODE_ADDON_API_CPP_EXCEPTIONS \
    -D_FILE_OFFSET_BITS=64 \
    -D__ANDROID_API__=24 \
    -I"$WORK_DIR/node_headers/node-${NODE_HEADERS_VER}/include/node" \
    -I"$WORK_DIR/node_addon_api/package" \
    "$PTY_PKG/src/unix/pty.cc" \
    -Wl,-soname,pty.node \
    -Wl,-z,now \
    -Wl,--allow-shlib-undefined \
    -lc++_shared -lm -ldl \
    -o "$OUT_NODE"

if [ -x "$LLVM_BIN/llvm-strip" ]; then
    "$LLVM_BIN/llvm-strip" --strip-unneeded "$OUT_NODE"
fi

echo "[*] Verifying $OUT_NODE ELF header..."
readelf -h "$OUT_NODE" | grep -E "Class:|Machine:"
readelf -h "$OUT_NODE" | grep -q "$EXPECTED_CLASS"
readelf -h "$OUT_NODE" | grep -qi "$EXPECTED_MACHINE"
echo "[OK] Built $OUT_NODE ($(stat -c%s "$OUT_NODE") bytes)"
