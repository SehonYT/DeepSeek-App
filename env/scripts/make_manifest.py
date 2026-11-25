#!/usr/bin/env python3
"""Create or verify the deterministic manifest for a staged env bundle."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import shutil
import subprocess
import tarfile
from pathlib import Path

SCHEMA = 1


def inspect_bootstrap_archive(path: Path) -> tuple[int, str | None]:
    prepatched_pkg = None
    symlink_count = 0
    if path.name.endswith((".tar.zst", ".tzst")):
        zstd_bin = shutil.which("zstd")
        if not zstd_bin:
            raise RuntimeError(f"zstd binary required to inspect {path}")
        proc = subprocess.Popen([zstd_bin, "-d", "-c", str(path)], stdout=subprocess.PIPE)
        try:
            with tarfile.open(fileobj=proc.stdout, mode="r|") as archive:
                for member in archive:
                    if member.issym():
                        symlink_count += 1
                    elif member.isfile() and member.name.lstrip("./") == ".dsh-prepatched":
                        f = archive.extractfile(member)
                        if f:
                            prepatched_pkg = f.read().decode("utf-8", errors="replace").strip() or None
        finally:
            if proc.stdout:
                proc.stdout.close()
            rc = proc.wait()
            if rc != 0:
                raise RuntimeError(f"zstd failed inspecting {path} (exit {rc})")
        return symlink_count, prepatched_pkg

    with tarfile.open(path, "r:gz") as archive:
        for member in archive:
            if member.issym():
                symlink_count += 1
            elif member.isfile() and member.name.lstrip("./") == ".dsh-prepatched":
                f = archive.extractfile(member)
                if f:
                    prepatched_pkg = f.read().decode("utf-8", errors="replace").strip() or None
    return symlink_count, prepatched_pkg


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def tree_digest(root: Path) -> str:
    """Hash relative names and bytes, independent of mtimes and host paths."""
    digest = hashlib.sha256()
    files = sorted(path for path in root.rglob("*") if path.is_file())
    for path in files:
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(relative)
        digest.update(b"\0")
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        digest.update(b"\0")
    return digest.hexdigest()


def read_seed_packages(root: Path) -> list[str]:
    path = root / "seed-packages.txt"
    if not path.is_file():
        return []
    packages = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.partition("#")[0].strip()
        if not line:
            continue
        if not all(ch.isalnum() or ch in "+.-_" for ch in line):
            raise ValueError(f"invalid seed package name: {line!r}")
        packages.append(line)
    return sorted(set(packages))


def make_manifest(root: Path) -> dict:
    bootstrap = root / "bootstrap" / "bootstrap.tar.zst"
    if not bootstrap.is_file():
        bootstrap = root / "bootstrap" / "bootstrap.tar.gz"
    bootstrap_arm = root / "bootstrap" / "bootstrap-arm.tar.zst"
    if not bootstrap_arm.is_file():
        bootstrap_arm = root / "bootstrap" / "bootstrap-arm.tar.gz"
    overlay = root / "overlay"
    pkg = root / "pkg"
    for required in (overlay, pkg):
        if not required.exists():
            raise FileNotFoundError(f"required env bundle input is missing: {required}")
    # A per-ABI APK may legitimately bundle only x86 userspaces, so require at
    # least ONE bootstrap archive rather than specifically an ARM one.
    bootstrap_dir = root / "bootstrap"
    any_bootstrap = False
    if bootstrap_dir.is_dir():
        for name in bootstrap_dir.iterdir():
            if name.name.startswith("bootstrap") and name.suffix in (".gz", ".zst", ".tar"):
                any_bootstrap = True
                break
    if not any_bootstrap:
        raise FileNotFoundError(
            f"required env bootstrap archive is missing under {bootstrap_dir}"
        )

    identity = {
        "schema": SCHEMA,
        "overlaySha256": tree_digest(overlay),
        "pkgSha256": tree_digest(pkg),
        "seedPackages": read_seed_packages(root),
    }

    prepatched_pkg = None
    if bootstrap.is_file():
        symlink_count, pkg_tag = inspect_bootstrap_archive(bootstrap)
        identity["bootstrapSha256"] = sha256_file(bootstrap)
        identity["bootstrapSymlinkCount"] = symlink_count
        prepatched_pkg = prepatched_pkg or pkg_tag

    if bootstrap_arm.is_file():
        arm_symlinks, pkg_tag = inspect_bootstrap_archive(bootstrap_arm)
        identity["bootstrapArmSha256"] = sha256_file(bootstrap_arm)
        identity["bootstrapArmSymlinkCount"] = arm_symlinks
        prepatched_pkg = prepatched_pkg or pkg_tag

    # x86 / x86_64 userspaces (gzip only). Both are bundled in the universal APK
    # and in their own split APKs so Intel devices can install and run too.
    for key, fname in (("X86", "bootstrap-x86.tar.gz"),
                       ("X64", "bootstrap-x86_64.tar.gz")):
        archive_path = root / "bootstrap" / fname
        if archive_path.is_file():
            symlinks, pkg_tag = inspect_bootstrap_archive(archive_path)
            identity["bootstrap%sSha256" % key] = sha256_file(archive_path)
            identity["bootstrap%sSymlinkCount" % key] = symlinks
            prepatched_pkg = prepatched_pkg or pkg_tag

    if prepatched_pkg:
        identity["prepatchedPackage"] = prepatched_pkg

    canonical = json.dumps(identity, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {**identity, "bundleId": hashlib.sha256(canonical).hexdigest()}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify an existing manifest")
    parser.add_argument("root", type=Path, help="staged env directory")
    args = parser.parse_args()

    root = args.root.resolve()
    manifest_path = root / "manifest.json"
    expected = json.dumps(make_manifest(root), indent=2, sort_keys=True) + "\n"
    if args.check:
        if not manifest_path.is_file():
            raise SystemExit(f"manifest missing: {manifest_path}")
        actual = manifest_path.read_text(encoding="utf-8")
        if actual != expected:
            raise SystemExit(f"manifest is stale: {manifest_path}")
        print(f"[env.manifest] verified {manifest_path}")
    else:
        manifest_path.write_text(expected, encoding="utf-8")
        print(f"[env.manifest] bundle={json.loads(expected)['bundleId']} -> {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
