#!/usr/bin/env bash
# build.sh -- pack the env workspace into APK assets (host build).
#   build.sh bootstrap   bootstrap-aarch64.zip -> bootstrap.tar.gz (symlinks+modes)
#   build.sh assets      stage pkg/overlay/bootstrap into dist/env (1:1)
#   build.sh all
set -eo pipefail

ENV_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$ENV_DIR/.." && pwd)"
ASSETS="${ASSETS:-$ENV_DIR/dist}"
TARGET_ARCH="${TARGET_ARCH:-universal}"
PREPATCH_PKG="${PREPATCH_PKG:-com.dsh}"

log() { printf 'build: %s\n' "$*" >&2; }

can_use_zstd_for_arch() {
    local arch="$1"
    command -v zstd >/dev/null 2>&1 || return 1
    case "$arch" in
        arm64-v8a)
            [ -f "$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a/libdsh_zstd.so" ]
            ;;
        armeabi-v7a)
            [ -f "$PROJECT_ROOT/app/src/main/jniLibs/armeabi-v7a/libdsh_zstd.so" ]
            ;;
        universal)
            [ -f "$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a/libdsh_zstd.so" ] \
                && [ -f "$PROJECT_ROOT/app/src/main/jniLibs/armeabi-v7a/libdsh_zstd.so" ]
            ;;
        *) return 1 ;;
    esac
}

normalize_arch() {
    case "$1" in
        universal|all|fat) echo "universal" ;;
        arm64-v8a|arm64|aarch64|v8a|r8a) echo "arm64-v8a" ;;
        armeabi-v7a|armeabi|arm|v7a|7a) echo "armeabi-v7a" ;;
        x86) echo "x86" ;;
        x86_64|x64|amd64) echo "x86_64" ;;
        *) log "unsupported TARGET_ARCH: $1"; exit 1 ;;
    esac
}

build_bootstrap_tar() {
    local ext="${1:-tar.gz}"
    local zip="$ENV_DIR/bootstrap/bootstrap-aarch64.zip"
    local out="$ENV_DIR/bootstrap/bootstrap.${ext}"
    local deb_dir="$ENV_DIR/packages/aarch64"
    if [ ! -f "$zip" ]; then
        log "Downloading Termux bootstrap-aarch64.zip..."
        curl -sSL "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.15-r1%2Bapt.android-7/bootstrap-aarch64.zip" -o "$zip" || true
    fi
    [ -f "$zip" ] || { log "missing $zip"; return 1; }
    log "Resolving 64-bit ARM nodejs, sqlite, and git packages..."
    python3 "$ENV_DIR/scripts/fetch_termux_debs.py" \
        --arch aarch64 \
        --bootstrap-zip "$zip" \
        --out-dir "$deb_dir" \
        nodejs sqlite git || true
    local extra_args=()
    for deb in "$ENV_DIR/packages"/*.deb "$deb_dir"/*.deb; do
        [ -f "$deb" ] && extra_args+=("--extra-deb" "$deb")
    done
    python3 "$ENV_DIR/scripts/pack_bootstrap.py" --zip "$zip" --out "$out" --prepatch-pkg "$PREPATCH_PKG" "${extra_args[@]}"
    log "bootstrap.${ext}: $(du -h "$out" | cut -f1)"
}

build_bootstrap_arm_tar() {
    local ext="${1:-tar.gz}"
    local zip="$ENV_DIR/bootstrap/bootstrap-arm.zip"
    local out="$ENV_DIR/bootstrap/bootstrap-arm.${ext}"
    local deb_dir="$ENV_DIR/packages/arm"
    if [ ! -f "$zip" ]; then
        log "Downloading Termux bootstrap-arm.zip..."
        curl -sSL "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.15-r1%2Bapt.android-7/bootstrap-arm.zip" -o "$zip" || true
    fi
    [ -f "$zip" ] || { log "missing $zip"; return 1; }
    log "Resolving 32-bit ARM nodejs, sqlite, and git packages..."
    python3 "$ENV_DIR/scripts/fetch_termux_debs.py" \
        --arch arm \
        --bootstrap-zip "$zip" \
        --out-dir "$deb_dir" \
        nodejs sqlite git
    local extra_args=()
    for deb in "$deb_dir"/*.deb; do
        [ -f "$deb" ] && extra_args+=("--extra-deb" "$deb")
    done
    python3 "$ENV_DIR/scripts/pack_bootstrap.py" --zip "$zip" --out "$out" --prepatch-pkg "$PREPATCH_PKG" "${extra_args[@]}"
    log "bootstrap-arm.${ext}: $(du -h "$out" | cut -f1)"
}

# build_bootstrap_x86_tar <termux-arch> <zip> <template> <out> <deb-arch>
# Builds an i686 (x86) or x86_64 userspace with its matching Node.js stack.
# These ABIs do not use zstd (no vendored libdsh_zstd for them yet).
build_bootstrap_x86_tar() {
    local termux_arch="$1" zipname="$2" template="$3" outname="$4" deb_arch="$5"
    local zip="$ENV_DIR/bootstrap/$zipname"
    local out="$ENV_DIR/bootstrap/$outname"
    local deb_dir="$ENV_DIR/packages/$deb_arch"
    if [ ! -f "$zip" ]; then
        log "Downloading Termux $zipname..."
        curl -sSL "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.15-r1%2Bapt.android-7/$zipname" -o "$zip" || true
    fi
    [ -f "$zip" ] || { log "missing $zip"; return 1; }
    log "Resolving $termux_arch nodejs, sqlite, and git packages..."
    python3 "$ENV_DIR/scripts/fetch_termux_debs.py"         --arch "$termux_arch"         --bootstrap-zip "$zip"         --out-dir "$deb_dir"         nodejs sqlite git || true
    local extra_args=()
    for deb in "$ENV_DIR/packages"/*.deb "$deb_dir"/*.deb; do
        [ -f "$deb" ] && extra_args+=("--extra-deb" "$deb")
    done
    python3 "$ENV_DIR/scripts/pack_bootstrap.py" --zip "$zip" --out "$out"         --prepatch-pkg "$PREPATCH_PKG" "${extra_args[@]}"
    log "$outname: $(du -h "$out" | cut -f1)"
}

bootstrap_arm_has_node() {
    local ext="${1:-tar.gz}"
    local out="$ENV_DIR/bootstrap/bootstrap-arm.${ext}"
    if [ "$ext" = "tar.zst" ]; then
        [ -f "$out" ] && command -v zstd >/dev/null 2>&1 && (zstd -d -c "$out" 2>/dev/null | tar -tf - ./bin/node >/dev/null 2>&1 || zstd -d -c "$out" 2>/dev/null | tar -tf - bin/node >/dev/null 2>&1)
    else
        [ -f "$out" ] && (tar -tzf "$out" ./bin/node >/dev/null 2>&1 || tar -tzf "$out" bin/node >/dev/null 2>&1)
    fi
}

pack_assets() {
    local arch ext="tar.gz"
    arch="$(normalize_arch "$TARGET_ARCH")"
    if can_use_zstd_for_arch "$arch"; then
        ext="tar.zst"
    fi
    rm -rf "$ASSETS"; mkdir -p "$ASSETS/env/bootstrap"
    cp -a "$ENV_DIR/pkg"     "$ASSETS/env/pkg"
    cp -a "$ENV_DIR/overlay" "$ASSETS/env/overlay"

    if [ "$arch" = "universal" ] || [ "$arch" = "arm64-v8a" ]; then
        [ -f "$ENV_DIR/bootstrap/bootstrap.${ext}" ] || build_bootstrap_tar "$ext"
        cp -f "$ENV_DIR/bootstrap/bootstrap.${ext}" "$ASSETS/env/bootstrap/"
    fi
    if [ "$arch" = "universal" ]; then
        bootstrap_arm_has_node "$ext" || build_bootstrap_arm_tar "$ext" || true
        [ -f "$ENV_DIR/bootstrap/bootstrap-arm.${ext}" ] && cp -f "$ENV_DIR/bootstrap/bootstrap-arm.${ext}" "$ASSETS/env/bootstrap/" || true
        # x86 ABIs: gzip only (no vendored zstd for them), needed so a universal
        # APK installs on Intel/ChromeOS/emulator devices too.
        [ -f "$ENV_DIR/bootstrap/bootstrap-x86.tar.gz" ] || build_bootstrap_x86_tar i686 bootstrap-i686.zip x86 bootstrap-x86.tar.gz x86 || true
        [ -f "$ENV_DIR/bootstrap/bootstrap-x86_64.tar.gz" ] || build_bootstrap_x86_tar x86_64 bootstrap-x86_64.zip x86_64 bootstrap-x86_64.tar.gz x86_64 || true
        for f in bootstrap-x86.tar.gz bootstrap-x86_64.tar.gz; do
            [ -f "$ENV_DIR/bootstrap/$f" ] && cp -f "$ENV_DIR/bootstrap/$f" "$ASSETS/env/bootstrap/" || true
        done
    elif [ "$arch" = "armeabi-v7a" ]; then
        bootstrap_arm_has_node "$ext" || build_bootstrap_arm_tar "$ext"
        cp -f "$ENV_DIR/bootstrap/bootstrap-arm.${ext}" "$ASSETS/env/bootstrap/"
    elif [ "$arch" = "x86" ]; then
        [ -f "$ENV_DIR/bootstrap/bootstrap-x86.tar.gz" ] || build_bootstrap_x86_tar i686 bootstrap-i686.zip x86 bootstrap-x86.tar.gz x86
        cp -f "$ENV_DIR/bootstrap/bootstrap-x86.tar.gz" "$ASSETS/env/bootstrap/"
    elif [ "$arch" = "x86_64" ]; then
        [ -f "$ENV_DIR/bootstrap/bootstrap-x86_64.tar.gz" ] || build_bootstrap_x86_tar x86_64 bootstrap-x86_64.zip x86_64 bootstrap-x86_64.tar.gz x86_64
        cp -f "$ENV_DIR/bootstrap/bootstrap-x86_64.tar.gz" "$ASSETS/env/bootstrap/"
    fi

    [ -f "$ENV_DIR/seed-packages.txt" ] && cp -f "$ENV_DIR/seed-packages.txt" "$ASSETS/env/seed-packages.txt" || true
    printf '%s\n' "$arch" > "$ASSETS/env/.target_arch"
    python3 "$ENV_DIR/scripts/make_manifest.py" "$ASSETS/env"
    log "assets ($arch, $ext) -> $ASSETS"
    find "$ASSETS" -maxdepth 3 | sed 's/^/  /' >&2
}

ACTION="all"
while [ $# -gt 0 ]; do
    case "$1" in
        --arch|--abi)
            TARGET_ARCH="$(normalize_arch "$2")"
            shift 2
            ;;
        --arch=*|--abi=*)
            TARGET_ARCH="$(normalize_arch "${1#*=}")"
            shift
            ;;
        bootstrap|assets|all)
            ACTION="$1"
            shift
            ;;
        *)
            printf 'usage: build.sh [--arch universal|arm64-v8a|armeabi-v7a] [bootstrap|assets|all]\n' >&2
            exit 1
            ;;
    esac
done

case "$ACTION" in
    bootstrap)
        arch="$(normalize_arch "$TARGET_ARCH")"
        ext="tar.gz"
        if can_use_zstd_for_arch "$arch"; then
            ext="tar.zst"
        fi
        if [ "$arch" = "universal" ] || [ "$arch" = "arm64-v8a" ]; then
            build_bootstrap_tar "$ext"
        fi
        if [ "$arch" = "universal" ] || [ "$arch" = "armeabi-v7a" ]; then
            build_bootstrap_arm_tar "$ext"
        fi
        if [ "$arch" = "universal" ] || [ "$arch" = "x86" ]; then
            build_bootstrap_x86_tar i686 bootstrap-i686.zip x86 bootstrap-x86.tar.gz x86
        fi
        if [ "$arch" = "universal" ] || [ "$arch" = "x86_64" ]; then
            build_bootstrap_x86_tar x86_64 bootstrap-x86_64.zip x86_64 bootstrap-x86_64.tar.gz x86_64
        fi
        ;;
    assets)    pack_assets ;;
    all)       pack_assets ;;
esac
