#!/usr/bin/env bash
# build.sh -- assemble the bundled Android userspace (Bionic Termux prefix) for each ABI.
#
#   build.sh bootstrap   build bootstrap .tar.gz for every ABI (downloads Termux zips + .debs)
#   build.sh assets      stage pkg/overlay/bootstrap + manifest into dist/env
#   build.sh all         bootstrap + assets
#
# Every ABI ships its own bootstrap because the prefix contains native ELFs
# (bash, node, compiled libs) that a device of another ABI cannot execute.
#
# Each is built the same way: unpack the pinned Termux bootstrap zip, ingest the
# ABI's Node.js stack as extra .deb packages (the plain bootstraps ship no Node,
# yet EnvInstaller requires $PREFIX/bin/node), then repack with symlinks+modes.
set -eo pipefail

ENV_DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS="${ASSETS:-$ENV_DIR/dist}"
BOOTSTRAP_RELEASE="https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.15-r1%2Bapt.android-7"

log() { printf 'build: %s\n' "$*" >&2; }

# arch | termux bootstrap zip      | apk asset name         | deb dir
#
# The APK asset names are referenced by com.dsh.install.EnvPaths, so they are
# part of the app/bundle contract and must not drift.
ABI_TABLE="
aarch64 bootstrap-aarch64.zip bootstrap.tar.gz        aarch64-packages
arm     bootstrap-arm.zip     bootstrap-arm.tar.gz    arm-packages
i686    bootstrap-i686.zip    bootstrap-x86.tar.gz    x86-packages
x86_64  bootstrap-x86_64.zip  bootstrap-x86_64.tar.gz x86_64-packages
"

# fetch_packages <arch> -- stage the pinned Node stack for one ABI.
fetch_packages() {
    local arch="$1"
    python3 "$ENV_DIR/scripts/fetch_abi_packages.py" --out "$ENV_DIR" "$arch"
}

# build_one <arch> <zipname> <outname> <debdir>
build_one() {
    local arch="$1" zipname="$2" outname="$3" debdir="$4"
    local zip="$ENV_DIR/bootstrap/$zipname"
    local out="$ENV_DIR/bootstrap/$outname"

    if [ ! -f "$zip" ]; then
        log "downloading Termux $zipname ($arch)..."
        if curl -fsSL "$BOOTSTRAP_RELEASE/$zipname" -o "$zip.part"; then
            mv "$zip.part" "$zip"
        else
            log "cannot download $zipname"
            return 1
        fi
    fi

    # Ensure this ABI's Node stack is present (pinned versions, see the fetcher).
    if ! ls "$ENV_DIR/$debdir"/*.deb >/dev/null 2>&1; then
        fetch_packages "$arch" || log "continuing without extra packages for $arch"
    fi

    # Always include the shared packages (e.g. git), then the ABI-specific
    # Node.js stack. Both are required: the prefix must ship node AND git for
    # the app to install and boot.
    local extra=()
    local deb
    for deb in "$ENV_DIR/packages"/*.deb; do
        [ -f "$deb" ] && extra+=(--extra-deb "$deb")
    done
    if [ "$debdir" != "packages" ]; then
        for deb in "$ENV_DIR/$debdir"/*.deb; do
            [ -f "$deb" ] && extra+=(--extra-deb "$deb")
        done
    fi

    python3 "$ENV_DIR/scripts/pack_bootstrap.py" --zip "$zip" --out "$out" "${extra[@]}"

    # Fail closed: a bootstrap without node cannot install, so verify here rather
    # than letting the APK ship a prefix that always fails its smoke test.
    # NOTE: write the listing to a file instead of piping into grep. With
    # `set -o pipefail`, `printf | grep -q` reports SIGPIPE as a failure and
    # makes this check fail on a perfectly good archive.
    local member ok=1
    local listing="$out.members"
    tar -tzf "$out" > "$listing"
    for member in ./bin/bash ./bin/node ./bin/pkg ./lib/libtermux-exec.so; do
        if ! grep -qx -- "$member" "$listing"; then
            log "ERROR: $outname is missing $member"
            ok=0
        fi
    done
    if ! grep -qE '^\./lib/libsqlite3\.so' "$listing"; then
        log "ERROR: $outname is missing libsqlite3"
        ok=0
    fi
    rm -f "$listing"
    if [ "$ok" != "1" ]; then
        rm -f "$out"
        return 1
    fi

    log "$outname: $(du -h "$out" | cut -f1) ($arch)"
}

build_bootstraps() {
    local arch zipname outname debdir
    while read -r arch zipname outname debdir; do
        [ -n "$arch" ] || continue
        build_one "$arch" "$zipname" "$outname" "$debdir"
    done <<< "$ABI_TABLE"
}

pack_assets() {
    local arch zipname outname debdir
    while read -r arch zipname outname debdir; do
        [ -n "$arch" ] || continue
        if [ ! -f "$ENV_DIR/bootstrap/$outname" ]; then
            build_one "$arch" "$zipname" "$outname" "$debdir"
        fi
        if [ -f "$ENV_DIR/bootstrap/$outname" ]; then
            log "staged $outname ($arch)"
        else
            log "WARNING: $outname not built; $arch devices will not be installable"
        fi
    done <<< "$ABI_TABLE"

    rm -rf "$ASSETS"; mkdir -p "$ASSETS/env/bootstrap"
    cp -a "$ENV_DIR/pkg"     "$ASSETS/env/pkg"
    cp -a "$ENV_DIR/overlay" "$ASSETS/env/overlay"

    while read -r arch zipname outname debdir; do
        [ -n "$arch" ] || continue
        if [ -f "$ENV_DIR/bootstrap/$outname" ]; then
            cp -f "$ENV_DIR/bootstrap/$outname" "$ASSETS/env/bootstrap/"
        fi
    done <<< "$ABI_TABLE"

    [ -f "$ENV_DIR/seed-packages.txt" ] && cp -f "$ENV_DIR/seed-packages.txt" "$ASSETS/env/seed-packages.txt" || true
    python3 "$ENV_DIR/scripts/make_manifest.py" "$ASSETS/env"
    log "assets -> $ASSETS"
    find "$ASSETS/env/bootstrap" -maxdepth 1 -type f | sed 's/^/  /' >&2
}

case "${1:-all}" in
    bootstrap) build_bootstraps ;;
    assets)    pack_assets ;;
    all)       build_bootstraps; pack_assets ;;
    *) printf 'usage: build.sh [bootstrap|assets|all]\n' >&2; exit 1 ;;
esac
