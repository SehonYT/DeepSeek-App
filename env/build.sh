#!/usr/bin/env bash
# build.sh -- pack the env workspace into APK assets (host build).
#   build.sh bootstrap   bootstrap-aarch64.zip -> bootstrap.tar.gz (symlinks+modes)
#   build.sh assets      stage pkg/overlay/bootstrap into dist/env (1:1)
#   build.sh all
set -eo pipefail

ENV_DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS="${ASSETS:-$ENV_DIR/dist}"
TARGET_ARCH="${TARGET_ARCH:-universal}"

log() { printf 'build: %s\n' "$*" >&2; }

normalize_arch() {
    case "$1" in
        universal|all|fat) echo "universal" ;;
        arm64-v8a|arm64|aarch64|v8a|r8a) echo "arm64-v8a" ;;
        armeabi-v7a|armeabi|arm|v7a|7a) echo "armeabi-v7a" ;;
        *) log "unsupported TARGET_ARCH: $1"; exit 1 ;;
    esac
}

build_bootstrap_tar() {
    local zip="$ENV_DIR/bootstrap/bootstrap-aarch64.zip"
    local out="$ENV_DIR/bootstrap/bootstrap.tar.gz"
    if [ ! -f "$zip" ]; then
        log "Downloading Termux bootstrap-aarch64.zip..."
        curl -sSL "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.15-r1%2Bapt.android-7/bootstrap-aarch64.zip" -o "$zip" || true
    fi
    [ -f "$zip" ] || { log "missing $zip"; return 1; }
    local extra_args=()
    for deb in "$ENV_DIR/packages"/*.deb; do
        [ -f "$deb" ] && extra_args+=("--extra-deb" "$deb")
    done
    python3 "$ENV_DIR/scripts/pack_bootstrap.py" --zip "$zip" --out "$out" "${extra_args[@]}"
    log "bootstrap.tar.gz: $(du -h "$out" | cut -f1)"
}

build_bootstrap_arm_tar() {
    local zip="$ENV_DIR/bootstrap/bootstrap-arm.zip"
    local out="$ENV_DIR/bootstrap/bootstrap-arm.tar.gz"
    if [ ! -f "$zip" ]; then
        log "Downloading Termux bootstrap-arm.zip..."
        curl -sSL "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.15-r1%2Bapt.android-7/bootstrap-arm.zip" -o "$zip" || true
    fi
    [ -f "$zip" ] || { log "missing $zip"; return 1; }
    python3 "$ENV_DIR/scripts/pack_bootstrap.py" --zip "$zip" --out "$out"
    log "bootstrap-arm.tar.gz: $(du -h "$out" | cut -f1)"
}

pack_assets() {
    local arch
    arch="$(normalize_arch "$TARGET_ARCH")"
    rm -rf "$ASSETS"; mkdir -p "$ASSETS/env/bootstrap"
    cp -a "$ENV_DIR/pkg"     "$ASSETS/env/pkg"
    cp -a "$ENV_DIR/overlay" "$ASSETS/env/overlay"

    if [ "$arch" = "universal" ] || [ "$arch" = "arm64-v8a" ]; then
        [ -f "$ENV_DIR/bootstrap/bootstrap.tar.gz" ] || build_bootstrap_tar
        cp -f "$ENV_DIR/bootstrap/bootstrap.tar.gz" "$ASSETS/env/bootstrap/"
    fi
    if [ "$arch" = "universal" ]; then
        [ -f "$ENV_DIR/bootstrap/bootstrap-arm.tar.gz" ] || build_bootstrap_arm_tar || true
        [ -f "$ENV_DIR/bootstrap/bootstrap-arm.tar.gz" ] && cp -f "$ENV_DIR/bootstrap/bootstrap-arm.tar.gz" "$ASSETS/env/bootstrap/" || true
    elif [ "$arch" = "armeabi-v7a" ]; then
        [ -f "$ENV_DIR/bootstrap/bootstrap-arm.tar.gz" ] || build_bootstrap_arm_tar
        cp -f "$ENV_DIR/bootstrap/bootstrap-arm.tar.gz" "$ASSETS/env/bootstrap/"
    fi

    [ -f "$ENV_DIR/seed-packages.txt" ] && cp -f "$ENV_DIR/seed-packages.txt" "$ASSETS/env/seed-packages.txt" || true
    printf '%s\n' "$arch" > "$ASSETS/env/.target_arch"
    python3 "$ENV_DIR/scripts/make_manifest.py" "$ASSETS/env"
    log "assets ($arch) -> $ASSETS"
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
    bootstrap) build_bootstrap_tar; build_bootstrap_arm_tar ;;
    assets)    pack_assets ;;
    all)       pack_assets ;;
esac
