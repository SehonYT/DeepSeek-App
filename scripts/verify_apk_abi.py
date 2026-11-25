#!/usr/bin/env python3
"""
verify_apk_abi.py - Verify that an APK's bundled bootstrap and native addons match its target ABI.
"""
from __future__ import annotations

import argparse
import io
import shutil
import struct
import subprocess
import tarfile
import zipfile
from pathlib import Path

EM_ARM = 40
EM_AARCH64 = 183


def elf_info(data: bytes) -> tuple[int, int] | None:
    if len(data) < 20 or data[:4] != b"\x7fELF":
        return None
    ei_class = data[4]  # 1 = ELF32, 2 = ELF64
    e_machine = struct.unpack_from("<H", data, 18)[0]
    return ei_class, e_machine


def scan_tar_bytes(raw: bytes, label: str) -> list[tuple[str, int, int]]:
    results: list[tuple[str, int, int]] = []
    if label.endswith((".tar.zst", ".tzst")):
        zstd_bin = shutil.which("zstd")
        if not zstd_bin:
            raise RuntimeError(f"zstd binary required to scan {label}")
        proc = subprocess.Popen([zstd_bin, "-d", "-c"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        assert proc.stdin is not None and proc.stdout is not None
        # Feed in a thread to avoid pipe deadlock on large archives
        import threading
        def feed():
            try:
                proc.stdin.write(raw)
                proc.stdin.close()
            except OSError:
                pass
        t = threading.Thread(target=feed, daemon=True)
        t.start()
        try:
            with tarfile.open(fileobj=proc.stdout, mode="r|") as tf:
                for m in tf:
                    if not m.isfile() or m.size < 20:
                        continue
                    f = tf.extractfile(m)
                    if not f:
                        continue
                    info = elf_info(f.read(20))
                    if info:
                        results.append((f"{label}:{m.name}", info[0], info[1]))
        finally:
            t.join(timeout=30)
            rc = proc.wait()
            if rc != 0:
                raise RuntimeError(f"zstd failed scanning {label} (exit {rc})")
        return results

    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as tf:
        for m in tf.getmembers():
            if not m.isfile() or m.size < 20:
                continue
            f = tf.extractfile(m)
            if not f:
                continue
            hdr = f.read(20)
            info = elf_info(hdr)
            if info:
                results.append((f"{label}:{m.name}", info[0], info[1]))
    return results


def verify_apk(apk_path: Path, arch: str) -> int:
    elfs: list[tuple[str, int, int]] = []
    jni_elfs: list[tuple[str, int, int]] = []

    with zipfile.ZipFile(apk_path, "r") as zf:
        names = set(zf.namelist())
        has_arm64_bs = (
            "assets/env/bootstrap/bootstrap.tar.zst" in names
            or "assets/env/bootstrap/bootstrap.tar.gz" in names
        )
        has_arm32_bs = (
            "assets/env/bootstrap/bootstrap-arm.tar.zst" in names
            or "assets/env/bootstrap/bootstrap-arm.tar.gz" in names
        )

        for name in sorted(names):
            if name.startswith("lib/"):
                with zf.open(name) as f:
                    info = elf_info(f.read(20))
                    if info:
                        jni_elfs.append((name, info[0], info[1]))
                continue
            if not name.startswith("assets/"):
                continue
            if name in (
                "assets/env/bootstrap/bootstrap.tar.gz",
                "assets/env/bootstrap/bootstrap.tar.zst",
                "assets/env/bootstrap/bootstrap-arm.tar.gz",
                "assets/env/bootstrap/bootstrap-arm.tar.zst",
                "assets/runtime/dsh.tar.gz",
                "assets/runtime/dsh.tar.zst",
            ):
                elfs.extend(scan_tar_bytes(zf.read(name), name))
            else:
                with zf.open(name) as f:
                    info = elf_info(f.read(20))
                    if info:
                        elfs.append((name, info[0], info[1]))

    all_elfs = elfs + jni_elfs
    arm32_count = sum(1 for _, cls, mach in all_elfs if cls == 1 and mach == EM_ARM)
    arm64_count = sum(1 for _, cls, mach in all_elfs if cls == 2 and mach == EM_AARCH64)
    print(f"[verify_apk_abi] {apk_path.name} ({arch}): arm32_elfs={arm32_count}, arm64_elfs={arm64_count}, jni={len(jni_elfs)}")

    if arch == "armeabi-v7a":
        if not has_arm32_bs or has_arm64_bs:
            raise SystemExit(f"FAIL: expected only bootstrap-arm (got arm32={has_arm32_bs}, arm64={has_arm64_bs})")
        if arm64_count > 0:
            bad = [p for p, c, m in all_elfs if c == 2 or m == EM_AARCH64][:10]
            raise SystemExit(f"FAIL: 64-bit ELFs found in armeabi-v7a APK: {bad}")
        if arm32_count == 0:
            raise SystemExit("FAIL: no 32-bit ARM ELFs found in armeabi-v7a APK")
        node_ok = any(p.endswith("bin/node") and c == 1 and m == EM_ARM for p, c, m in elfs)
        if not node_ok:
            raise SystemExit("FAIL: 32-bit ARM bin/node missing from armeabi-v7a APK")
    elif arch == "arm64-v8a":
        if not has_arm64_bs or has_arm32_bs:
            raise SystemExit(f"FAIL: expected only arm64 bootstrap (got arm32={has_arm32_bs}, arm64={has_arm64_bs})")
        if arm32_count > 0:
            bad = [p for p, c, m in all_elfs if c == 1 or m == EM_ARM][:10]
            raise SystemExit(f"FAIL: 32-bit ELFs found in arm64-v8a APK: {bad}")
        if arm64_count == 0:
            raise SystemExit("FAIL: no 64-bit ARM ELFs found in arm64-v8a APK")
    elif arch == "universal":
        if not (has_arm64_bs and has_arm32_bs):
            raise SystemExit(f"FAIL: universal APK must contain both bootstraps (arm32={has_arm32_bs}, arm64={has_arm64_bs})")
        if arm32_count == 0 or arm64_count == 0:
            raise SystemExit("FAIL: universal APK missing either 32-bit or 64-bit ELFs")

    print(f"[OK] {apk_path.name} passed ABI verification ({arch})")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--arch", required=True, choices=["universal", "arm64-v8a", "armeabi-v7a"])
    args = parser.parse_args()
    return verify_apk(args.apk, args.arch)


if __name__ == "__main__":
    raise SystemExit(main())
