#!/usr/bin/env python3
"""Stage only the offline runtime, never the legacy infrastructure or personal config."""
import argparse
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('platform', choices=['darwin', 'win32'])
p.add_argument('--java', type=Path, required=True)
p.add_argument('--llama', type=Path, required=True)
p.add_argument('--voice', type=Path, required=True, help='PyInstaller story-voice directory')
p.add_argument('--ffmpeg', type=Path, required=True)
args = p.parse_args()
root = Path(__file__).resolve().parents[2]
target = root / 'packaging/release/staging' / args.platform
if target.exists():
    shutil.rmtree(target)
(target / 'bin').mkdir(parents=True)
shutil.copytree(args.java, target / 'java', symlinks=True)
jar=root / 'apps/server/target/story-runtime-0.1.0.jar'
with zipfile.ZipFile(jar) as archive:
    if any('/release/cloud/' in name or name.endswith('application-cloud.yml') for name in archive.namelist()):
        raise SystemExit('Build the runtime with -Poffline clean package before staging')
shutil.copyfile(jar, target / 'story-runtime.jar')
shutil.copytree(args.voice, target / 'voice', symlinks=False)
suffix = '.exe' if args.platform == 'win32' else ''
shutil.copy2(args.llama, target / 'bin' / ('llama-server' + suffix))
shutil.copy2(args.ffmpeg, target / 'bin' / ('ffmpeg' + suffix))
if args.platform == 'darwin':
    # Resolve Homebrew @rpath and absolute dependencies, then make the copied graph relocatable.
    libroot = target / 'lib'
    libroot.mkdir()
    pending = [(args.llama.resolve(), target / 'bin/llama-server')]
    copied = set()
    # Dynamic backends are dlopen plugins, absent from otool's dependency graph.
    backend_libraries = list(Path('/opt/homebrew/opt/ggml/lib').glob('libggml-*.dylib'))
    backend_libraries += list(Path('/opt/homebrew/opt/ggml/libexec').glob('libggml-*.so'))
    if not any(x.name == 'libggml-metal.so' or 'metal' in x.name for x in backend_libraries) or not any('cpu' in x.name for x in backend_libraries):
        raise SystemExit('ggml CPU/Metal backend plugins are required for a self-contained runtime')
    for library in backend_libraries:
        shutil.copy2(library.resolve(),libroot / library.name)
        copied.add(library.name)
        pending.append((library.resolve(),libroot / library.name))
    for shader in Path('/opt/homebrew/opt/ggml/lib').glob('*.metal*'):
        shutil.copy2(shader,libroot / shader.name)
    def dependencies(file):
        return [line.strip().split(' (')[0] for line in subprocess.check_output(['otool', '-L', str(file)], text=True).splitlines()[1:]]
    def resolve(dep, origin):
        if dep.startswith('@rpath/'):
            name = dep.split('/')[-1]
            candidates = [origin.parent.parent / 'lib' / name, Path('/opt/homebrew/opt/ggml/lib') / name, Path('/opt/homebrew/opt/llama.cpp/lib') / name]
            return next((x.resolve() for x in candidates if x.exists()), None)
        if dep.startswith('@loader_path/'):
            return (origin.parent / dep.removeprefix('@loader_path/')).resolve()
        if dep.startswith('/opt/homebrew/') or dep.startswith('/usr/local/'):
            return Path(dep).resolve()
        return None
    while pending:
        origin, output = pending.pop()
        for dep in dependencies(origin):
            source = resolve(dep, origin)
            if source is None:
                if dep.startswith('@'):
                    raise SystemExit('Unresolved library: ' + dep)
                continue
            name = dep.split('/')[-1]
            library = libroot / name
            if name not in copied:
                copied.add(name)
                shutil.copy2(source, library)
                pending.append((source, library))
            relative = '@loader_path/' + ('../lib/' if output.parent.name == 'bin' else '') + name
            subprocess.run(['install_name_tool', '-change', dep, relative, str(output)], check=True, capture_output=True)
        if output.parent == libroot:
            subprocess.run(['install_name_tool', '-id', '@loader_path/' + output.name, str(output)], check=True, capture_output=True)
    # Homebrew's compiled default takes precedence over GGML_BACKEND_PATH.
    # Relocate that directory to cwd in the copied registry only; the launcher
    # below sets cwd to its own lib directory before executing the native server.
    registry = libroot / 'libggml.0.dylib'
    data = registry.read_bytes()
    import re
    for match in re.findall(rb'/(?:opt/homebrew|usr/local)/[^\x00]*?/libexec\x00', data):
        replacement = b'.'
        if len(replacement) >= len(match):
            raise SystemExit('Cannot disable host backend search path safely')
        data = data.replace(match, replacement + b'\x00' * (len(match) - len(replacement)))
    registry.chmod(registry.stat().st_mode | 0o200)
    registry.write_bytes(data)
    for binary in list(libroot.iterdir()) + [target / 'bin/llama-server']:
        subprocess.run(['codesign', '--force', '--sign', '-', str(binary)], check=True, capture_output=True)
    # The compiled backend directory is relative to cwd; isolate it in a launcher.
    server = target / 'bin/llama-server'
    server.rename(target / 'bin/llama-server-native')
    server.write_text('#!/bin/sh\nset -eu\nbase=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)\ncd "$base/lib"\nexport GGML_BACKEND_PATH="$base/lib/libggml-metal.so"\nexec "$base/bin/llama-server-native" "$@"\n')
    server.chmod(0o755)
else:
    for dll in args.llama.parent.glob('*.dll'):
        shutil.copy2(dll, target / 'bin' / dll.name)
print('Staged offline runtime:', target)
