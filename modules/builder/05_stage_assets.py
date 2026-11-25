#!/usr/bin/env python3
"""
05_stage_assets.py - Stages runtime, env assets, and manifests into build/staging/assets.
"""
import sys
import os
import shutil
import subprocess

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

    target_arch = os.environ.get("TARGET_ARCH", "universal")
    env_src = os.path.join(PROJECT_ROOT, "env", "dist", "env")
    print(f"[*] Staging env assets (arch={target_arch}) from {env_src} -> {env_dst}...")
    manifest_file = os.path.join(env_src, "manifest.json")
    arch_file = os.path.join(env_src, ".target_arch")
    staged_arch = ""
    if os.path.isfile(arch_file):
        with open(arch_file, "r", encoding="utf-8") as f:
            staged_arch = f.read().strip()
    if not os.path.isfile(manifest_file) or staged_arch != target_arch:
        print(f"[*] Rebuilding env assets for TARGET_ARCH={target_arch}...")
        env_cmd_env = os.environ.copy()
        env_cmd_env["TARGET_ARCH"] = target_arch
        subprocess.check_call(
            ["bash", os.path.join(PROJECT_ROOT, "env", "build.sh"), "assets"],
            env=env_cmd_env,
        )
    if os.path.exists(env_dst):
        shutil.rmtree(env_dst)
    shutil.copytree(env_src, env_dst)
    print(f"[OK] Staged env assets ({target_arch}).")

    web_src = os.path.join(PROJECT_ROOT, "web")
    web_dst = os.path.join(assets_dir, "web")
    if os.path.isdir(web_src):
        if os.path.exists(web_dst):
            shutil.rmtree(web_dst)
        shutil.copytree(web_src, web_dst)
        print(f"[OK] Staged web assets from {web_src} -> {web_dst}")

    print(f"[OK] Staged all assets into {assets_dir}")
    return assets_dir


def main():
    staging_dir = os.path.join(PROJECT_ROOT, "build", "staging")
    for arg in sys.argv[1:]:
        if not arg.startswith("-"):
            staging_dir = arg
    stage_assets(staging_dir)


if __name__ == "__main__":
    main()
