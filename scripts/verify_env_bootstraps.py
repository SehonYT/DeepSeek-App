#!/usr/bin/env python3
"""Verify that staged Termux userspaces contain the files the installer requires."""
import os, sys, tarfile

REQUIRED_GZIP = {
    "env/bootstrap/bootstrap-x86.tar.gz": ("x86",),
    "env/bootstrap/bootstrap-x86_64.tar.gz": ("x86_64",),
}
REQUIRED = ("bin/bash", "bin/node", "bin/pkg", "bin/rg", "lib/libtermux-exec.so")

def check_gzip(path):
    with tarfile.open(path) as t:
        names = set(n.lstrip("./") for n in t.getnames())
    missing = [r for r in REQUIRED if r not in names]
    if not any(n.startswith("lib/libsqlite3.so") for n in names):
        missing.append("lib/libsqlite3.so")
    return missing

def main():
    failures = []
    for path in REQUIRED_GZIP:
        if not os.path.isfile(path):
            failures.append(f"{path}: missing (ABI would be uninstallable)")
            continue
        missing = check_gzip(path)
        if missing:
            failures.append(f"{path}: missing {missing}")
        else:
            print(f"[verify] {path}: OK")
    if failures:
        for f in failures:
            print("::error::" + f)
        return 1
    return 0

if __name__ == "__main__":
    raise SystemExit(main())