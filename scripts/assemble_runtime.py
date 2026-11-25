#!/usr/bin/env python3
"""
Assemble compact DeepSeek Harness runtime bundle (<= 45 MB).
Combines:
1. Compiled JS from dsh-termux-prebuilt-v0.2.2-rc.3.tar.gz
2. package.json manifests & @deepseek-ai/* workspace symlinks from source
3. Minimal hoisted production dependencies for apps/cli (ajv, commander, execa, js-yaml, node-addon-require-builtin, resolve.exports, semver, ws)
4. Mobile runtime shims (@img/sharp-wasm32, @emnapi/runtime, android-arm64 pty.node)
"""
import os
import sys
import shutil
import subprocess
import json
import glob
import tempfile
import tarfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
ARCHIVES_DIR = os.path.join(PROJECT_ROOT, "archives")
TARGET_ARCHIVE = os.path.join(ARCHIVES_DIR, "dsh-termux-prebuilt-v0.2.2-rc.3.tar.gz")


def run(cmd, cwd=None, check=True):
    print(f"[*] Running: {' '.join(cmd) if isinstance(cmd, list) else cmd}")
    res = subprocess.run(cmd, cwd=cwd, shell=isinstance(cmd, str), check=check)
    return res.returncode == 0


def copy_tree_files(src_dir, dst_dir):
    for root, dirs, files in os.walk(src_dir):
        rel = os.path.relpath(root, src_dir)
        target_dir = os.path.join(dst_dir, rel) if rel != "." else dst_dir
        os.makedirs(target_dir, exist_ok=True)
        for f in files:
            src_f = os.path.join(root, f)
            dst_f = os.path.join(target_dir, f)
            if os.path.islink(src_f):
                link_target = os.readlink(src_f)
                if os.path.lexists(dst_f):
                    os.remove(dst_f)
                os.symlink(link_target, dst_f)
            else:
                shutil.copy2(src_f, dst_f)


def normalize_arch(arch: str) -> str:
    val = (arch or "universal").strip().lower()
    if val in ("universal", "all", "fat"):
        return "universal"
    if val in ("arm64-v8a", "arm64", "aarch64", "v8a", "r8a"):
        return "arm64-v8a"
    if val in ("armeabi-v7a", "armeabi", "arm", "v7a", "7a"):
        return "armeabi-v7a"
    raise ValueError(f"Unsupported arch: {arch}")


def assemble_bundle(source_repo: str, out_archive: str, target_arch: str = "universal"):
    target_arch = normalize_arch(target_arch)
    work_dir = os.path.join(ARCHIVES_DIR, "bundle_staging")
    if os.path.exists(work_dir):
        shutil.rmtree(work_dir)
    os.makedirs(work_dir, exist_ok=True)

    print("[*] 1. Extracting compiled lib prebuilt...")
    prebuilt_url = "https://github.com/salmanbappi/deepseek-harness/releases/download/v0.2.2-rc.3/dsh-termux-prebuilt-v0.2.2-rc.3.tar.gz"
    prebuilt_tmp = os.path.join(ARCHIVES_DIR, "prebuilt_cache.tar.gz")
    if not os.path.isfile(prebuilt_tmp):
        print(f"[*] Downloading prebuilt from {prebuilt_url}...")
        run(f'curl -fsSL -o "{prebuilt_tmp}" "{prebuilt_url}"')

    run(["tar", "-xzf", prebuilt_tmp, "-C", work_dir])

    print("[*] 2. Copying package manifests, cordis configs, and workspace metadata...")
    symlinks = {}
    RUNTIME_EXTS = (
        ".json", ".yml", ".yaml", ".sql", ".txt", ".html", ".css", ".svg", ".png", ".ico", ".md"
    )
    for root, dirs, files in os.walk(source_repo):
        # Skip node_modules, git, tests, benchmarks, agent notes, and TypeScript source dirs
        skip_dirs = ["node_modules", ".git", ".github", ".agents", "tests", "benchmarks", "src"]
        if root == source_repo:
            skip_dirs.append("docs")
        dirs[:] = [d for d in dirs if d not in skip_dirs]
        rel = os.path.relpath(root, source_repo)
        dest_dir = os.path.join(work_dir, rel) if rel != "." else work_dir

        if "package.json" in files:
            try:
                with open(os.path.join(root, "package.json"), "r", encoding="utf-8") as f:
                    pj_data = json.load(f)
                    name = pj_data.get("name")
                    if name and name.startswith("@deepseek-ai/"):
                        symlinks[name] = rel
            except Exception:
                pass

        for f in files:
            if f.endswith(RUNTIME_EXTS) or f in ("pnpm-workspace.yaml", ".npmrc"):
                os.makedirs(dest_dir, exist_ok=True)
                shutil.copy2(os.path.join(root, f), os.path.join(dest_dir, f))

    print(f"[OK] Discovered and mapped {len(symlinks)} @deepseek-ai workspace packages.")

    print("[*] 3. Creating @deepseek-ai workspace symlinks in node_modules...")
    # Root node_modules/@deepseek-ai
    root_nm_dsh = os.path.join(work_dir, "node_modules", "@deepseek-ai")
    os.makedirs(root_nm_dsh, exist_ok=True)
    for name, rel in symlinks.items():
        if rel in (".", ""):
            continue
        short_name = name.split("/", 1)[1]
        link_target = os.path.join("../../", rel)
        link_path = os.path.join(root_nm_dsh, short_name)
        if not os.path.exists(link_path) and not os.path.islink(link_path):
            os.symlink(link_target, link_path)

    # Symlink packages/node_modules and apps/node_modules to root node_modules for ESM resolution fallback
    for parent_dir in ["packages", "apps"]:
        parent_nm = os.path.join(work_dir, parent_dir, "node_modules")
        if not os.path.exists(parent_nm) and not os.path.islink(parent_nm):
            try:
                os.symlink("../node_modules", parent_nm)
            except Exception:
                pass

    # apps/cli/node_modules/@deepseek-ai (NEVER link apps/cli to itself to avoid circular symlink loop!)
    cli_dir = os.path.join(work_dir, "apps", "cli")
    cli_nm_dsh = os.path.join(cli_dir, "node_modules", "@deepseek-ai")
    os.makedirs(cli_nm_dsh, exist_ok=True)
    for name, rel in symlinks.items():
        if rel in (".", "", "apps/cli") or rel.startswith("apps/cli"):
            continue
        short_name = name.split("/", 1)[1]
        link_target = os.path.join("../../../../", rel)
        link_path = os.path.join(cli_nm_dsh, short_name)
        if not os.path.exists(link_path) and not os.path.islink(link_path):
            os.symlink(link_target, link_path)

    print("[*] 4. Installing production dependencies in isolated temp directory...")
    prod_pkgs = [
        "zod@4.6.6",
        "yaml@^2.7.0",
        "semver@^7.8.5",
        "commander@^15.0.0",
        "chokidar@^4.0.3",
        "picomatch@^4.0.2",
        "js-yaml@^4.3.2",
        "node-addon-require-builtin@^0.1.9",
        "ws@^8.21.3",
        "open@^11.0.2",
        "@earendil-works/pi-ai@^1.3.0",
        "@xterm/headless@^6.0.0",
        "@xterm/addon-serialize@^0.14.0",
        "@agentclientprotocol/sdk@^1.4.0",
        "@modelcontextprotocol/sdk@^1.31.0",
        "@modelcontextprotocol/client@2.2.0",
        "@modelcontextprotocol/server@2.2.0",
        "@anthropic-ai/sdk@^0.39.0",
        "@noble/hashes@^2.4.0",
        "buffer@^6.0.3",
        "readable-stream@^4.7.0",
        "string_decoder@^1.3.0",
        "acorn@^8.18.0",
        "acorn-walk@^8.3.5",
        "got@^14.6.6",
        "birpc@^4.2.0",
        "@yarnpkg/parsers@^3.1.0",
        "@babel/code-frame@^7.29.0",
        "@jridgewell/gen-mapping@^0.3.13",
        "@opentelemetry/api@^1.9.1",
        "execa@^10.0.1",
        "undici@^7.2.1",
        "hosted-git-info@^8.0.2",
        "resolve.exports@^2.0.3",
        "diff@^9.0.0",
        "@js-temporal/polyfill@^0.4.4",
        "fflate@^0.8.2",
        "@standard-schema/spec@^1.0.0",
        "mime-types@^2.1.35",
        "mime@^4.0.6",
        "ipaddr.js@^2.2.0",
        "negotiator@^1.0.0",
        "eventsource-parser@^3.0.0",
        "p-limit@^6.2.0",
        "minisearch@^7.1.1",
        "compression@^1.8.0",
        "serve-static@^1.16.2",
        "turndown@^7.2.0",
        "@joplin/turndown-plugin-gfm@^1.0.45",
        "fuzzysort@^3.1.0",
        "rrule@^2.8.1",
        "shell-quote@^1.8.2",
        "cross-spawn@^7.0.6",
        "which@^5.0.0",
        "@eslint-community/regexpp@^4.12.2",
        "ajv@^8.20.0"
    ]
    iso_dir = tempfile.mkdtemp(prefix="dsh_npm_iso_")
    try:
        run(["npm", "install", "--no-package-lock", "--omit=dev", "--legacy-peer-deps"] + prod_pkgs, cwd=iso_dir)
        iso_nm = os.path.join(iso_dir, "node_modules")
        if os.path.isdir(iso_nm):
            # Copy into root node_modules and apps/cli/node_modules
            for target_nm in [os.path.join(work_dir, "node_modules"), os.path.join(cli_dir, "node_modules")]:
                os.makedirs(target_nm, exist_ok=True)
                for item in os.listdir(iso_nm):
                    if item.startswith("."):
                        continue
                    src_item = os.path.join(iso_nm, item)
                    dst_item = os.path.join(target_nm, item)
                    if os.path.isdir(src_item):
                        copy_tree_files(src_item, dst_item)
                    else:
                        shutil.copy2(src_item, dst_item)
    finally:
        shutil.rmtree(iso_dir, ignore_errors=True)

    print("[*] 4b. Injecting Koffi fallback stub for Android...")
    koffi_stub_code = """function createKoffiStub() {
  const dummyFn = () => {};
  return {
    LibraryHandle: class {},
    TypeObject: class {},
    Union: class {},
    address: dummyFn,
    alias: (name, type) => type,
    alignof: () => 8,
    alloc: () => 0n,
    array: (t, len) => ({ size: (len || 1) * 2, type: t, length: len }),
    as: (v2) => v2,
    call: dummyFn,
    config: dummyFn,
    decode: () => null,
    disposable: (v2) => v2,
    encode: dummyFn,
    enumeration: dummyFn,
    errno: () => 0,
    extension: dummyFn,
    free: dummyFn,
    in: (v2) => v2,
    inout: (v2) => v2,
    introspect: (spec) => ({ size: (spec && spec.size) || 0, alignment: 8, members: {} }),
    load: (libName) => {
      throw new Error(`Cannot load native library "${libName}": Koffi is not supported on Android`);
    },
    opaque: () => ({ size: 0, alignment: 8 }),
    os: { errno: () => 0 },
    out: (v2) => v2,
    pointer: (type) => ({ size: 8, alignment: 8, type }),
    proto: () => ({ size: 8, alignment: 8 }),
    register: dummyFn,
    reset: dummyFn,
    resolve: (type) => type,
    sizeof: () => 8,
    stats: () => ({}),
    struct: () => ({ size: 8, alignment: 8 }),
    types: {}
  };
}
module.exports = createKoffiStub();
module.exports.default = module.exports;
"""
    koffi_pj = json.dumps({"name": "koffi", "version": "2.9.12", "main": "index.js", "type": "commonjs"}, indent=2)
    for target_nm in [os.path.join(work_dir, "node_modules"), os.path.join(cli_dir, "node_modules")]:
        k_dir = os.path.join(target_nm, "koffi")
        os.makedirs(k_dir, exist_ok=True)
        with open(os.path.join(k_dir, "index.js"), "w", encoding="utf-8") as f:
            f.write(koffi_stub_code)
        with open(os.path.join(k_dir, "package.json"), "w", encoding="utf-8") as f:
            f.write(koffi_pj)

    print("[*] 5. Installing mobile sharp-wasm32 and @emnapi/runtime...")
    for target_nm in [os.path.join(work_dir, "node_modules"), os.path.join(cli_dir, "node_modules")]:
        img_dir = os.path.join(target_nm, "@img")
        emnapi_dir = os.path.join(target_nm, "@emnapi")
        os.makedirs(img_dir, exist_ok=True)
        os.makedirs(emnapi_dir, exist_ok=True)
        run(f'curl -sL https://registry.npmjs.org/@img/sharp-wasm32/-/sharp-wasm32-0.35.5.tgz | tar -xz -C "{img_dir}"')
        shutil.rmtree(os.path.join(img_dir, "sharp-wasm32"), ignore_errors=True)
        os.rename(os.path.join(img_dir, "package"), os.path.join(img_dir, "sharp-wasm32"))

        run(f'curl -sL https://registry.npmjs.org/@emnapi/runtime/-/runtime-1.11.3.tgz | tar -xz -C "{emnapi_dir}"')
        shutil.rmtree(os.path.join(emnapi_dir, "runtime"), ignore_errors=True)
        os.rename(os.path.join(emnapi_dir, "package"), os.path.join(emnapi_dir, "runtime"))

    print(f"[*] 6. Installing complete node-pty package (arch={target_arch})...")
    node_pty_tmp = tempfile.mkdtemp(prefix="dsh_node_pty_")
    try:
        run(f'curl -sL https://registry.npmjs.org/node-pty/-/node-pty-1.2.0-beta.15.tgz | tar -xz -C "{node_pty_tmp}"')
        src_pty_pkg = os.path.join(node_pty_tmp, "package")
        # Apply patch if present in source_repo
        pty_patch = os.path.join(source_repo, "patches", "node-pty@1.2.0-beta.15.patch")
        if os.path.isfile(pty_patch):
            run(f'patch -p1 -i "{pty_patch}"', cwd=src_pty_pkg, check=False)
        # Prune build/src/third_party/other-arch prebuilds
        for prune_dir in ["src", "third_party"]:
            shutil.rmtree(os.path.join(src_pty_pkg, prune_dir), ignore_errors=True)
        allowed_prebuilds = set()
        if target_arch in ("universal", "arm64-v8a"):
            allowed_prebuilds.add("android-arm64")
        if target_arch in ("universal", "armeabi-v7a"):
            allowed_prebuilds.add("android-arm")

        pb_dir = os.path.join(src_pty_pkg, "prebuilds")
        if os.path.isdir(pb_dir):
            for arch in os.listdir(pb_dir):
                if arch not in allowed_prebuilds:
                    shutil.rmtree(os.path.join(pb_dir, arch), ignore_errors=True)

        # Inject native pty.node binaries from vendor/native/<arch>/pty.node
        injected_pty = []
        for abi_dir in sorted(allowed_prebuilds):
            pty_source = os.path.join(PROJECT_ROOT, "vendor", "native", abi_dir, "pty.node")
            if os.path.isfile(pty_source):
                android_pb = os.path.join(src_pty_pkg, "prebuilds", abi_dir)
                os.makedirs(android_pb, exist_ok=True)
                shutil.copy2(pty_source, os.path.join(android_pb, "pty.node"))
                injected_pty.append((abi_dir, pty_source))
                print(f"[OK] Staged node-pty prebuild: {abi_dir}/pty.node")

        # Only populate build/Release/pty.node for single-binary bundles so a 64-bit binary
        # never shadows prebuilds/android-arm/pty.node on 32-bit devices.
        if len(injected_pty) == 1:
            build_rel = os.path.join(src_pty_pkg, "build", "Release")
            os.makedirs(build_rel, exist_ok=True)
            shutil.copy2(injected_pty[0][1], os.path.join(build_rel, "pty.node"))
        else:
            shutil.rmtree(os.path.join(src_pty_pkg, "build", "Release"), ignore_errors=True)

        # Install node-pty into work_dir/node_modules, apps/cli/node_modules, and packages/subprocess/subprocess-local/node_modules
        subprocess_local_dir = os.path.join(work_dir, "packages", "subprocess", "subprocess-local")
        target_dirs = [
            os.path.join(work_dir, "node_modules", "node-pty"),
            os.path.join(cli_dir, "node_modules", "node-pty"),
            os.path.join(subprocess_local_dir, "node_modules", "node-pty")
        ]
        for td in target_dirs:
            os.makedirs(os.path.dirname(td), exist_ok=True)
            if os.path.exists(td):
                shutil.rmtree(td, ignore_errors=True)
            copy_tree_files(src_pty_pkg, td)
    finally:
        shutil.rmtree(node_pty_tmp, ignore_errors=True)

    print("[*] 7. Applying mobile patches...")
    patch_script = os.path.join(source_repo, "scripts", "patch_termux.py")
    if os.path.isfile(patch_script):
        # Copy patch script & dsh_arch.py if present
        shutil.copy2(patch_script, os.path.join(work_dir, "patch_termux.py"))
        dsh_arch = os.path.join(source_repo, "scripts", "dsh_arch.py")
        if os.path.isfile(dsh_arch):
            shutil.copy2(dsh_arch, os.path.join(work_dir, "dsh_arch.py"))
        run(["python3", "patch_termux.py", "--apply"], cwd=work_dir, check=False)
        for f in ["patch_termux.py", "dsh_arch.py"]:
            if os.path.exists(os.path.join(work_dir, f)):
                os.remove(os.path.join(work_dir, f))

    print("[*] 8. Pruning development and test files...")
    for root, dirs, files in os.walk(work_dir, topdown=False):
        for d in dirs:
            if d in ("tests", "benchmarks", ".git", ".github", "__tests__"):
                shutil.rmtree(os.path.join(root, d), ignore_errors=True)
        for f in files:
            # Preserve .md (README, plugin details, docs) and .d.ts
            if f.endswith((".ts", ".map", ".test.js", ".spec.js", ".tsbuildinfo")) and not f.endswith(".d.ts"):
                try:
                    os.remove(os.path.join(root, f))
                except Exception:
                    pass

    print(f"[*] 9. Packaging streamlined archive -> {out_archive}...")
    os.makedirs(os.path.dirname(os.path.abspath(out_archive)), exist_ok=True)
    if os.path.exists(out_archive):
        os.remove(out_archive)

    run(["tar", "-czf", out_archive, "-C", work_dir, "."])

    size_mb = os.path.getsize(out_archive) / (1024 * 1024)
    print("=" * 60)
    print(f"[OK] RUNTIME ARCHIVE CREATED: {out_archive} ({size_mb:.2f} MB)")
    print("=" * 60)
    if size_mb > 55:
        print(f"[!] Warning: archive size {size_mb:.2f} MB exceeds expected target (~45 MB)")
    else:
        print("[OK] Archive size is well within target compact footprint!")

    # Clean up staging
    shutil.rmtree(work_dir, ignore_errors=True)
    return True


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Assemble compact DeepSeek Harness runtime bundle.")
    parser.add_argument("source_repo", nargs="?", default=os.path.join(ARCHIVES_DIR, "dsh-source"))
    parser.add_argument("out_archive", nargs="?", default=TARGET_ARCHIVE)
    parser.add_argument("--arch", default=os.environ.get("TARGET_ARCH", "universal"))
    cli_args = parser.parse_args()
    assemble_bundle(cli_args.source_repo, cli_args.out_archive, cli_args.arch)
