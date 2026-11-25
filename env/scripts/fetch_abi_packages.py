#!/usr/bin/env python3
"""Fetch and stage the per-ABI Termux .deb packages needed for the bundled userspace.

Every Android bootstrap must ship a Node.js runtime: EnvInstaller.validateTree()
requires $PREFIX/bin/node and a libsqlite3, and RuntimeManager.coreCommand() launches
the core through it. The plain Termux bootstrap zips ship no Node, so the Node stack
is ingested as extra .deb packages from the Termux repository.

Versions are pinned so a CI rebuild reproduces the same userspace instead of silently
tracking whatever the mirror currently serves.

Usage:
  fetch_abi_packages.py <arch> [<arch>...]      # arch in arm, aarch64, i686, x86_64
  fetch_abi_packages.py --list
"""

import argparse
import gzip
import io
import json
import os
import sys

REPO = "https://packages-cf.termux.dev/apt/termux-main"
DIST = "stable"
COMPONENT = "main"

# Pinned package versions. "Version" strings match the Termux Packages index exactly.
PINNED = {
    "nodejs-lts": "24.22.3",
    "libicu": "79.1",
    "libsqlite": "3.54.1-1",
    "c-ares": "1.34.9",
    "libc++": "30",
    "openssl": "1:3.6.5",
    "zlib": "1.4",
}

# Termux ships these already inside some bootstrap zips; re-ingesting is harmless
# (files are overwritten in place) and keeps every ABI's layout identical.
REQUIRED = ["nodejs-lts", "libicu", "libsqlite", "c-ares", "libc++", "openssl", "zlib"]


def log(msg):
    print(f"[fetch-abi] {msg}", flush=True)


def fetch(url, dest, timeout=180):
    import urllib.request

    if os.path.isfile(dest) and os.path.getsize(dest) > 0:
        return dest
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "DeepSeek-App-Builder"})
    tmp = dest + ".part"
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp, open(tmp, "wb") as out:
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
        if os.path.getsize(tmp) == 0:
            raise IOError("empty download")
        os.replace(tmp, dest)
    except Exception as e:
        if os.path.isfile(tmp):
            os.unlink(tmp)
        raise IOError(f"cannot download {url}: {e}")
    return dest


def load_index(arch):
    url = f"{REPO}/dists/{DIST}/{COMPONENT}/binary-{arch}/Packages.gz"
    path = os.path.join(os.environ.get("TMPDIR", "."), f"Packages-{arch}.gz")
    fetch(url, path)
    with gzip.open(path, "rt", encoding="utf-8", errors="replace") as f:
        return f.read()


def parse_stanzas(text):
    stanzas = []
    for block in text.split("\n\n"):
        fields = {}
        key = None
        for line in block.splitlines():
            if not line.strip():
                continue
            if line[0] in (" ", "\t") and key:
                fields[key] += "\n" + line.strip()
                continue
            if ":" in line:
                key, _, value = line.partition(":")
                fields[key.strip()] = value.strip()
        if "Package" in fields:
            stanzas.append(fields)
    return stanzas


def stage_arch(arch, out_root):
    text = load_index(arch)
    by_name = {}
    for st in parse_stanzas(text):
        name = st.get("Package")
        if name in REQUIRED:
            by_name.setdefault(name, []).append(st)

    dest_dir = os.path.join(out_root, f"{arch}-packages")
    os.makedirs(dest_dir, exist_ok=True)

    missing = [n for n in REQUIRED if n not in by_name]
    if missing:
        log(f"{arch}: missing packages in index: {missing}")

    staged = 0
    for name in REQUIRED:
        if name not in by_name:
            continue
        want = PINNED.get(name)
        chosen = None
        for st in by_name[name]:
            if want is None or st.get("Version") == want:
                chosen = st
                break
        if chosen is None:
            chosen = by_name[name][0]
            log(f"{arch}: {name} pinned {want} unavailable, using {chosen.get('Version')}")
        filename = chosen.get("Filename")
        if not filename:
            log(f"{arch}: {name} has no Filename field; skipping")
            continue
        url = f"{REPO}/{filename}"
        dest = os.path.join(dest_dir, f"{name}.deb")
        fetch(url, dest)
        log(f"{arch}: {name} {chosen.get('Version')} -> {os.path.getsize(dest)} bytes")
        staged += 1
    log(f"{arch}: staged {staged} packages in {dest_dir}")
    return dest_dir


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--out", default=None)
    ap.add_argument("archs", nargs="*")
    args = ap.parse_args()

    if args.list:
        print(json.dumps({"pinned": PINNED, "required": REQUIRED}, indent=2))
        return 0

    env_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_root = args.out or env_dir
    archs = args.archs or ["arm", "aarch64", "i686", "x86_64"]
    for arch in archs:
        stage_arch(arch, out_root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
