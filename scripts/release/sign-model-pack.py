#!/usr/bin/env python3
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

p = argparse.ArgumentParser(description="Create a checksummed, signed offline model pack")
p.add_argument("directory", type=Path)
p.add_argument("--spec", type=Path, required=True)
p.add_argument("--key", type=Path, required=True, help="Ed25519 private key outside the source tree")
args = p.parse_args()
root = args.directory.resolve()
manifest = json.loads(args.spec.read_text())
files = []
for path in sorted(root.rglob("*")):
    if path.is_symlink():
        raise SystemExit("Symlinks are not allowed: " + str(path))
    if not path.is_file() or path.name in {"manifest.json", "manifest.sig"}:
        continue
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    files.append({"path": path.relative_to(root).as_posix(), "size": path.stat().st_size, "sha256": digest.hexdigest()})
if not files:
    raise SystemExit("The model directory is empty")
manifest["files"] = files
target = root / "manifest.json"
target.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(args.key), "-in", str(target), "-out", str(root / "manifest.sig")], check=True)
print("Signed", len(files), "files in", root)
