#!/usr/bin/env bash
# build_zstd.sh - Cross-compile minimal PIE zstd decompression binary (libdsh_zstd.so)
# for Android arm64-v8a and armeabi-v7a using NDK, placed under app/src/main/jniLibs/<abi>/.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

TARGET_ARCH="all"
ZSTD_VER="1.5.7"

while [ $# -gt 0 ]; do
    case "$1" in
        --arch)
            TARGET_ARCH="$2"
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

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "[*] Fetching zstd v${ZSTD_VER} source..."
curl -fsSL "https://github.com/facebook/zstd/releases/download/v${ZSTD_VER}/zstd-${ZSTD_VER}.tar.gz" | tar -xz -C "$WORK_DIR"
SRC_DIR="$WORK_DIR/zstd-${ZSTD_VER}"

build_for_abi() {
    local abi="$1"
    local cc="$2"
    local arch_flags="$3"
    local expected_class="$4"
    local expected_machine="$5"

    if [ ! -x "$cc" ]; then
        echo "[ERROR] NDK compiler missing: $cc" >&2
        exit 1
    fi

    local out_dir="$PROJECT_ROOT/app/src/main/jniLibs/$abi"
    local out_bin="$out_dir/libdsh_zstd.so"
    mkdir -p "$out_dir"

    echo "[*] Compiling $out_bin ($abi)..."
    make -C "$SRC_DIR/programs" clean >/dev/null 2>&1 || true
    make -C "$SRC_DIR/programs" -j"$(nproc 2>/dev/null || echo 4)" zstd-decompress \
        CC="$cc" \
        CFLAGS="-O2 -fPIE -fPIC $arch_flags -DZSTD_LEGACY_SUPPORT=0 -DZSTD_MULTITHREAD=0" \
        LDFLAGS="-pie"

    cp -f "$SRC_DIR/programs/zstd-decompress" "$out_bin"
    if [ -x "$LLVM_BIN/llvm-strip" ]; then
        "$LLVM_BIN/llvm-strip" --strip-all "$out_bin"
    fi
    chmod 755 "$out_bin"

    readelf -h "$out_bin" | grep -E "Class:|Machine:"
    readelf -h "$out_bin" | grep -q "$expected_class"
    readelf -h "$out_bin" | grep -qi "$expected_machine"
    echo "[OK] Built $out_bin ($(stat -c%s "$out_bin") bytes)"
}

case "$TARGET_ARCH" in
    all|universal)
        build_for_abi "arm64-v8a" "$LLVM_BIN/aarch64-linux-android24-clang" "-march=armv8-a" "ELF64" "AArch64"
        build_for_abi "armeabi-v7a" "$LLVM_BIN/armv7a-linux-androideabi24-clang" "-march=armv7-a -mfpu=neon -mfloat-abi=softfp" "ELF32" "ARM"
        ;;
    arm64-v8a)
        build_for_abi "arm64-v8a" "$LLVM_BIN/aarch64-linux-android24-clang" "-march=armv8-a" "ELF64" "AArch64"
        ;;
    armeabi-v7a)
        build_for_abi "armeabi-v7a" "$LLVM_BIN/armv7a-linux-androideabi24-clang" "-march=armv7-a -mfpu=neon -mfloat-abi=softfp" "ELF32" "ARM"
        ;;
    *)
        echo "[ERROR] Unsupported --arch: $TARGET_ARCH" >&2
        exit 1
        ;;
esac
