#!/usr/bin/env python3
"""
fetch_termux_debs.py - Resolve and download Termux .deb packages (plus missing
transitive dependencies) for a target Termux architecture (`arm` or `aarch64`).
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import os
import re
import tarfile
import urllib.request
import zipfile
from collections import deque
from pathlib import Path

MIRRORS = [
    "https://packages-cf.termux.dev/apt/termux-main",
    "https://packages.termux.dev/apt/termux-main",
    "https://mirrors.bfsu.edu.cn/termux/apt/termux-main",
    "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
]

USER_AGENT = "DeepSeek-App-Builder/1.0"


def read_installed_from_zip(zip_path: Path) -> set[str]:
    installed: set[str] = set()
    with zipfile.ZipFile(zip_path, "r") as zf:
        for name in zf.namelist():
            norm = name.lstrip("./")
            if norm.startswith("var/lib/dpkg/info/") and norm.endswith(".list"):
                installed.add(Path(norm).stem)
    return installed


def read_installed_from_tar(tar_path: Path) -> set[str]:
    installed: set[str] = set()
    with tarfile.open(tar_path, "r:gz") as tf:
        for m in tf.getmembers():
            norm = m.name.lstrip("./")
            if norm.startswith("var/lib/dpkg/info/") and norm.endswith(".list"):
                installed.add(Path(norm).stem)
    return installed


def download_bytes(url: str, timeout: int = 30) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def fetch_packages_index(arch: str) -> tuple[str, dict[str, dict[str, str]], dict[str, list[str]]]:
    last_err = None
    for base in MIRRORS:
        url = f"{base}/dists/stable/main/binary-{arch}/Packages.gz"
        try:
            raw = gzip.decompress(download_bytes(url, timeout=30)).decode("utf-8", errors="replace")
            pkgs: dict[str, dict[str, str]] = {}
            provides: dict[str, list[str]] = {}
            for stanza in raw.strip().split("\n\n"):
                entry: dict[str, str] = {}
                cur_key = None
                for line in stanza.splitlines():
                    if not line:
                        continue
                    if line[0].isspace() and cur_key:
                        entry[cur_key] = entry[cur_key] + "\n" + line.strip()
                    elif ":" in line:
                        k, v = line.split(":", 1)
                        cur_key = k.strip()
                        entry[cur_key] = v.strip()
                name = entry.get("Package")
                if not name:
                    continue
                pkgs[name] = entry
                for prov in entry.get("Provides", "").split(","):
                    pname = prov.strip().split("(", 1)[0].strip()
                    if pname:
                        provides.setdefault(pname, []).append(name)
            return base, pkgs, provides
        except Exception as e:
            last_err = e
            print(f"[!] Mirror failed ({url}): {e}")
    raise RuntimeError(f"Failed to fetch Packages.gz for arch={arch}: {last_err}")


def parse_depends(dep_str: str) -> list[list[str]]:
    groups: list[list[str]] = []
    if not dep_str:
        return groups
    for part in dep_str.split(","):
        alts = []
        for alt in part.split("|"):
            pkg = alt.strip().split("(", 1)[0].split(":", 1)[0].strip()
            if pkg:
                alts.append(pkg)
        if alts:
            groups.append(alts)
    return groups


def resolve_packages(
    roots: list[str],
    installed: set[str],
    pkgs: dict[str, dict[str, str]],
    provides: dict[str, list[str]],
) -> list[dict[str, str]]:
    visited: set[str] = set()
    queue: deque[str] = deque(roots)
    selected: list[dict[str, str]] = []

    while queue:
        name = queue.popleft()
        if name in visited:
            continue
        if name not in pkgs:
            cands = provides.get(name, [])
            resolved = next((c for c in cands if c in installed), None) or (cands[0] if cands else None)
            if not resolved:
                raise RuntimeError(f"Package '{name}' not found in Termux index")
            name = resolved
            if name in visited:
                continue
        visited.add(name)
        if name in installed and name not in roots:
            continue
        meta = pkgs[name]
        if name not in installed:
            selected.append(meta)
        for alts in parse_depends(meta.get("Depends", "")):
            # Prefer an alternative already installed or already queued/visited
            chosen = next((a for a in alts if a in installed or a in visited), None)
            if chosen is None:
                chosen = next((a for a in alts if a in pkgs or a in provides), alts[0])
            queue.append(chosen)

    return selected


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def download_deb(base_url: str, meta: dict[str, str], out_dir: Path) -> Path:
    filename = meta["Filename"]
    expected_sha = meta.get("SHA256", "").lower()
    deb_name = os.path.basename(filename)
    dest = out_dir / deb_name

    if dest.is_file() and expected_sha and sha256_file(dest) == expected_sha:
        return dest

    url = f"{base_url}/{filename}"
    print(f"[*] Downloading {meta['Package']} ({meta.get('Version', '?')}) -> {deb_name}")
    data = download_bytes(url, timeout=60)
    if expected_sha:
        actual = hashlib.sha256(data).hexdigest()
        if actual != expected_sha:
            raise RuntimeError(f"SHA256 mismatch for {deb_name}: got {actual}, want {expected_sha}")
    dest.write_bytes(data)
    return dest


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch Termux .deb packages and missing deps.")
    parser.add_argument("--arch", required=True, choices=["arm", "aarch64"])
    parser.add_argument("--out-dir", required=True, type=Path)
    parser.add_argument("--bootstrap-zip", type=Path)
    parser.add_argument("--bootstrap-tar", type=Path)
    parser.add_argument("packages", nargs="+")
    args = parser.parse_args()

    installed: set[str] = set()
    if args.bootstrap_zip and args.bootstrap_zip.is_file():
        installed |= read_installed_from_zip(args.bootstrap_zip)
    elif args.bootstrap_tar and args.bootstrap_tar.is_file():
        installed |= read_installed_from_tar(args.bootstrap_tar)

    base_url, pkgs, provides = fetch_packages_index(args.arch)
    selected = resolve_packages(args.packages, installed, pkgs, provides)

    args.out_dir.mkdir(parents=True, exist_ok=True)
    expected_files = {os.path.basename(m["Filename"]) for m in selected}
    for old in args.out_dir.glob("*.deb"):
        if old.name not in expected_files:
            old.unlink()

    for meta in selected:
        download_deb(base_url, meta, args.out_dir)

    print(f"[OK] Ready {len(selected)} .deb package(s) for {args.arch} in {args.out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
