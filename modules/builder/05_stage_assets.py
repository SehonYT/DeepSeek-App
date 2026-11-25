#!/usr/bin/env python3
"""
05_stage_assets.py - Stages runtime, env assets, and manifests into build/staging/assets.
"""
import sys
import os
import shutil
import subprocess
import json
import hashlib

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
MODULES_DIR = os.path.join(PROJECT_ROOT, "modules")
if MODULES_DIR not in sys.path:
    sys.path.insert(0, MODULES_DIR)

from runner import prepare_runtime


def stage_assets(staging_dir):
    assets_dir = os.path.join(staging_dir, "assets")
    runtime_dst = os.path.join(assets_dir, "runtime")
    env_dst = os.path.join(assets_dir, "env")
    os.makedirs(assets_dir, exist_ok=True)

    print(f"[*] Staging DeepSeek runtime assets -> {runtime_dst}...")
    ok = prepare_runtime.prepare(dest_runtime_dir=runtime_dst)
    if not ok:
        raise RuntimeError("Failed to prepare and stage runtime assets!")

    env_src = os.path.join(PROJECT_ROOT, "env", "dist", "env")
    print(f"[*] Staging env assets from {env_src} -> {env_dst}...")
    manifest_file = os.path.join(env_src, "manifest.json")
    if not os.path.isfile(manifest_file):
        print(f"[*] env manifest missing, building env assets...")
        subprocess.check_call(["bash", os.path.join(PROJECT_ROOT, "env", "build.sh"), "assets"])
    if os.path.exists(env_dst):
        shutil.rmtree(env_dst)
    shutil.copytree(env_src, env_dst)

    # ABI-specific APKs ship only the userspace their devices can execute, which
    # keeps each download small. The universal APK (DSH_ABI unset or "universal")
    # keeps every ABI so one file installs everywhere.
    prune_env_for_abi(env_dst, os.environ.get("DSH_ABI", "").strip())
    print(f"[OK] Staged env assets.")

    web_src = os.path.join(PROJECT_ROOT, "web")
    web_dst = os.path.join(assets_dir, "web")
    if os.path.isdir(web_src):
        if os.path.exists(web_dst):
            shutil.rmtree(web_dst)
        shutil.copytree(web_src, web_dst)
        print(f"[OK] Staged web assets from {web_src} -> {web_dst}")

    print(f"[OK] Staged all assets into {assets_dir}")
    return assets_dir


# Userspace tarball in assets/env/bootstrap -> ABI it can run on.
BOOTSTRAP_BY_ABI = {
    "arm64-v8a": "bootstrap.tar.gz",
    "armeabi-v7a": "bootstrap-arm.tar.gz",
    "x86": "bootstrap-x86.tar.gz",
    "x86_64": "bootstrap-x86_64.tar.gz",
}

# Manifest field suffix per tarball (bootstrap<Key>Sha256).
MANIFEST_KEY_BY_FILE = {
    "bootstrap.tar.gz": "",
    "bootstrap-arm.tar.gz": "Arm",
    "bootstrap-x86.tar.gz": "X86",
    "bootstrap-x86_64.tar.gz": "X64",
}


def prune_env_for_abi(env_dir, abi):
    """Keep only the userspace for abi (no-op for universal builds).

    EnvInstaller selects its bootstrap by device ABI, so an ABI-specific
    APK containing only the matching tarball still resolves. The env
    manifest is rewritten to drop references to removed tarballs so
    verification stays fail-closed.
    """
    if not abi or abi == "universal":
        return
    keep = BOOTSTRAP_BY_ABI.get(abi)
    if keep is None:
        print(f"[!] unknown DSH_ABI={abi!r}; keeping all userspaces")
        return

    boot_dir = os.path.join(env_dir, "bootstrap")
    if not os.path.isdir(boot_dir):
        return
    removed = []
    for name in os.listdir(boot_dir):
        if name in BOOTSTRAP_BY_ABI.values() and name != keep:
            target = os.path.join(boot_dir, name)
            if os.path.isdir(target):
                shutil.rmtree(target)
            else:
                os.unlink(target)
            removed.append(name)

    manifest_path = os.path.join(env_dir, "manifest.json")
    if os.path.isfile(manifest_path):
        with open(manifest_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        for name in removed:
            key = MANIFEST_KEY_BY_FILE.get(name, "")
            if key:
                data.pop("bootstrap%sSha256" % key, None)
                data.pop("bootstrap%sSymlinkCount" % key, None)
        canonical = {k: v for k, v in data.items() if k != "bundleId"}
        payload = json.dumps(canonical, sort_keys=True, separators=(",", ":"))
        data["bundleId"] = hashlib.sha256(payload.encode("utf-8")).hexdigest()
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, sort_keys=True)
            f.write(chr(10))
    print(f"[OK] {abi}: kept {keep}; removed {removed}")

def main():
    staging_dir = os.path.join(PROJECT_ROOT, "build", "staging")
    for arg in sys.argv[1:]:
        if not arg.startswith("-"):
            staging_dir = arg
    stage_assets(staging_dir)


if __name__ == "__main__":
    main()
