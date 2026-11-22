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

RUNNER_DIR = os.path.dirname(os.path.abspath(__file__))
MODULES_DIR = os.path.dirname(RUNNER_DIR)
PROJECT_ROOT = os.path.dirname(MODULES_DIR)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def find_prebuilt_archive():
    # 1. Search in project vendor/ or archives/
    candidates = []
    for search_dir in [
        os.path.join(PROJECT_ROOT, "vendor"),
        os.path.join(PROJECT_ROOT, "archives"),
        os.path.expanduser("~/workspace/archives"),
        os.path.expanduser("~/deepseek-harness"),
    ]:
        if os.path.isdir(search_dir):
            candidates.extend(glob.glob(os.path.join(search_dir, "dsh-termux-prebuilt-*.tar.gz")))
    if candidates:
        return candidates[0]
    return None


def download_prebuilt_archive(target_path):
    print("[*] Fetching prebuilt DeepSeek Harness Termux release info from GitHub...")
    api_url = "https://api.github.com/repos/salmanbappi/deepseek-harness/releases/latest"
    req = urllib.request.Request(api_url, headers={"User-Agent": "DeepSeek-App-Builder"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            for asset in data.get("assets", []):
                if asset["name"].startswith("dsh-termux-prebuilt") and asset["name"].endswith(".tar.gz"):
                    download_url = asset["browser_download_url"]
                    print(f"[*] Downloading {asset['name']} from {download_url}...")
                    urllib.request.urlretrieve(download_url, target_path)
                    return True
    except Exception as e:
        print(f"[!] Release download failed: {e}")
    return False


def prepare(dest_runtime_dir: str) -> bool:
    print("=" * 60)
    print("  PREPARING DEEPSEEK HARNESS RUNTIME")
    print("=" * 60)

    dest_runtime_dir = os.path.abspath(dest_runtime_dir)
    dsh_dest = os.path.join(dest_runtime_dir, "dsh")
    os.makedirs(dsh_dest, exist_ok=True)

    archive = find_prebuilt_archive()
    if not archive or not os.path.isfile(archive):
        # Attempt download
        tmp_archive = os.path.join(dest_runtime_dir, "dsh-prebuilt.tar.gz")
        if download_prebuilt_archive(tmp_archive):
            archive = tmp_archive

    if archive and os.path.isfile(archive):
        print(f"[*] Extracting prebuilt bundle {archive} -> {dsh_dest}...")
        with tarfile.open(archive, "r:gz") as tar:
            tar.extractall(path=dsh_dest)
        print("[OK] Extracted prebuilt bundle.")
    else:
        # Fallback: copy from local checkout if present
        local_dsh = os.path.expanduser("~/deepseek-harness")
        if os.path.isdir(local_dsh):
            print(f"[*] Copying runtime from local checkout {local_dsh} -> {dsh_dest}...")
            for rel in ["apps", "packages", "package.json", "pnpm-workspace.yaml"]:
                src = os.path.join(local_dsh, rel)
                dst = os.path.join(dsh_dest, rel)
                if os.path.isdir(src):
                    shutil.copytree(src, dst, dirs_exist_ok=True)
                elif os.path.isfile(src):
                    shutil.copy2(src, dst)
            print("[OK] Copied local runtime files.")
        else:
            print("[ERROR] No prebuilt archive or local deepseek-harness found!")
            return False

    # Generate runtime manifest.json
    manifest = {}
    for root, _, files in os.walk(dest_runtime_dir):
        for f in files:
            full_path = os.path.join(root, f)
            rel_path = os.path.relpath(full_path, dest_runtime_dir)
            if rel_path != "manifest.json":
                manifest[rel_path] = sha256_file(full_path)

    manifest_path = os.path.join(dest_runtime_dir, "manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump({"files": manifest}, f, indent=2)

    print(f"[OK] Generated runtime manifest ({len(manifest)} files) -> {manifest_path}")
    return True


if __name__ == "__main__":
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(PROJECT_ROOT, "build", "staging", "assets", "runtime")
    if not prepare(out_dir):
        sys.exit(1)
