#!/usr/bin/env python3
"""
prepare_runtime.py - Assembles and stages DeepSeek Harness runtime into destination path.
"""
import sys
import os
import shutil
import glob
import tarfile
import urllib.request
import json
import hashlib
import subprocess

RUNNER_DIR = os.path.dirname(os.path.abspath(__file__))
MODULES_DIR = os.path.dirname(RUNNER_DIR)
PROJECT_ROOT = os.path.dirname(MODULES_DIR)

DEFAULT_DIRECT_URL = "https://github.com/salmanbappi/deepseek-harness/releases/download/v0.2.2-rc.3/dsh-termux-prebuilt-v0.2.2-rc.3.tar.gz"


def sha256_file(path):
    h = hashlib.sha256()
    try:
        if os.path.islink(path):
            try:
                with open(path, "rb") as f:
                    while chunk := f.read(65536):
                        h.update(chunk)
                return h.hexdigest()
            except (OSError, IOError):
                target = os.readlink(path).encode("utf-8")
                return hashlib.sha256(target).hexdigest()
        with open(path, "rb") as f:
            while chunk := f.read(65536):
                h.update(chunk)
        return h.hexdigest()
    except Exception as e:
        print(f"[!] Warning: failed to hash {path}: {e}")
        return None


def find_dsh_source():
    candidates = [
        os.path.join(PROJECT_ROOT, "archives", "dsh-source"),
        os.path.join(PROJECT_ROOT, "dsh-source"),
        os.path.expanduser("~/workspace/archives/dsh-source"),
    ]
    for c in candidates:
        if os.path.isdir(c) and (
            os.path.exists(os.path.join(c, "apps"))
            or os.path.exists(os.path.join(c, "package.json"))
        ):
            return c
    return None


def find_prebuilt_archive(target_arch: str = None):
    target_arch = (target_arch or os.environ.get("TARGET_ARCH") or "").strip().lower()
    search_dirs = [
        os.path.join(PROJECT_ROOT, "archives"),
        os.path.join(PROJECT_ROOT, "vendor"),
        os.path.expanduser("~/workspace/archives"),
        os.path.expanduser("~/deepseek-harness"),
    ]
    if target_arch:
        for search_dir in search_dirs:
            if os.path.isdir(search_dir):
                arch_matches = sorted(glob.glob(os.path.join(search_dir, f"*dsh*prebuilt*{target_arch}*.tar.gz")))
                if arch_matches:
                    return arch_matches[0]
    candidates = []
    for search_dir in search_dirs:
        if os.path.isdir(search_dir):
            candidates.extend(sorted(glob.glob(os.path.join(search_dir, "*dsh*prebuilt*.tar.gz"))))
            candidates.extend(sorted(glob.glob(os.path.join(search_dir, "dsh*.tar.gz"))))
    if candidates:
        return candidates[0]
    return None


def download_prebuilt_archive(target_path):
    print(f"[*] Downloading prebuilt bundle from {DEFAULT_DIRECT_URL}...")
    req = urllib.request.Request(DEFAULT_DIRECT_URL, headers={"User-Agent": "DeepSeek-App-Builder"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp, open(target_path, "wb") as out:
            shutil.copyfileobj(resp, out)
        print(f"[OK] Downloaded prebuilt bundle -> {target_path}")
        return True
    except Exception as e:
        print(f"[!] Direct download failed: {e}")
    return False


def extract_archive(archive_path: str, dest_dir: str) -> bool:
    print(f"[*] Extracting prebuilt bundle {archive_path} -> {dest_dir} (preserving symlinks)...")
    if shutil.which("tar"):
        res = subprocess.run(["tar", "-xzf", archive_path, "-C", dest_dir], capture_output=True, text=True)
        if res.returncode == 0:
            print("[OK] Extracted prebuilt bundle using system tar.")
            return True
        print(f"[!] System tar failed ({res.stderr.strip()}), falling back to tarfile")

    with tarfile.open(archive_path, "r:gz") as tar:
        if hasattr(tarfile, 'tar_filter'):
            tar.extractall(path=dest_dir, filter='tar')
        else:
            tar.extractall(path=dest_dir)
    print("[OK] Extracted prebuilt bundle using tarfile.")
    return True


def prepare(dest_runtime_dir: str) -> bool:
    print("=" * 60)
    print("  PREPARING DEEPSEEK HARNESS RUNTIME")
    print("=" * 60)

    dest_runtime_dir = os.path.abspath(dest_runtime_dir)
    os.makedirs(dest_runtime_dir, exist_ok=True)
    dsh_tar = os.path.join(dest_runtime_dir, "dsh.tar.gz")

    archive = find_prebuilt_archive()
    if archive and os.path.isfile(archive):
        print(f"[*] Copying assembled prebuilt archive {archive} -> {dsh_tar}...")
        shutil.copy2(archive, dsh_tar)
        print(f"[OK] Staged dsh.tar.gz ({os.path.getsize(dsh_tar) / (1024*1024):.2f} MB)")
    else:
        dsh_source = find_dsh_source()
        if dsh_source and os.path.isdir(dsh_source):
            print(f"[*] Packaging dsh-source {dsh_source} -> {dsh_tar}...")
            subprocess.check_call(["tar", "-czf", dsh_tar, "-C", dsh_source, "."])
            print(f"[OK] Packaged dsh.tar.gz ({os.path.getsize(dsh_tar) / (1024*1024):.2f} MB)")
        else:
            tmp_archive = os.path.join(dest_runtime_dir, "dsh-prebuilt.tar.gz")
            if download_prebuilt_archive(tmp_archive):
                archive = tmp_archive
                shutil.copy2(archive, dsh_tar)
                print(f"[OK] Staged downloaded dsh.tar.gz ({os.path.getsize(dsh_tar) / (1024*1024):.2f} MB)")
            else:
                local_dsh = os.path.expanduser("~/deepseek-harness")
                if os.path.isdir(local_dsh):
                    print(f"[*] Packaging local checkout {local_dsh} -> {dsh_tar}...")
                    subprocess.check_call(["tar", "-czf", dsh_tar, "-C", local_dsh, "."])
                    print("[OK] Packaged local runtime files.")
                else:
                    print("[ERROR] No dsh-source, prebuilt archive, or local deepseek-harness found!")
                    return False

    manifest_path = os.path.join(dest_runtime_dir, "manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump({"files": {"dsh.tar.gz": sha256_file(dsh_tar)}}, f, indent=2)

    print(f"[OK] Generated runtime manifest -> {manifest_path}")
    return True


if __name__ == "__main__":
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(PROJECT_ROOT, "build", "staging", "assets", "runtime")
    if not prepare(out_dir):
        sys.exit(1)
