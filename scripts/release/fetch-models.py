#!/usr/bin/env python3
"""Publisher-side download only. Desktop installations never run this script."""
import argparse
import concurrent.futures
import hashlib
import json
import shutil
import tarfile
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, default=root / 'packaging/release/model-packs/standard')
args = p.parse_args()
target = args.output.resolve()
target.mkdir(parents=True, exist_ok=True)
cache = target.parent / 'downloads'
cache.mkdir(exist_ok=True)

def download(url, output):
    if output.exists():
        return
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + '.part')
    with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as file:
        total = int(response.headers.get('Content-Length', 0))
        downloaded = 0
        while chunk := response.read(1024 * 1024):
            file.write(chunk)
            downloaded += len(chunk)
        if total and downloaded != total:
            raise RuntimeError('Incomplete download: ' + output.name)
    temporary.rename(output)
    print('Downloaded', output.name, flush=True)

def qwen(repo, filename, directory):
    with urllib.request.urlopen('https://huggingface.co/api/models/' + repo + '?blobs=true', timeout=30) as response:
        info = json.load(response)
    entry = next(s for s in info['siblings'] if s['rfilename'] == filename)
    expected = entry.get('lfs', {}).get('sha256')
    output = target / directory / filename
    download('https://huggingface.co/' + repo + '/resolve/' + info['sha'] + '/' + filename, output)
    if expected:
        digest = hashlib.file_digest(output.open('rb'), 'sha256').hexdigest()
        if digest != expected:
            raise RuntimeError('Upstream SHA-256 mismatch: ' + filename)
    download('https://huggingface.co/' + repo + '/resolve/' + info['sha'] + '/README.md', target / directory / 'MODEL_CARD.md')
    (target / directory / 'REVISION').write_text(info['sha'])

def speech(filename, release, directory):
    archive = cache / filename
    download('https://github.com/k2-fsa/sherpa-onnx/releases/download/' + release + '/' + filename, archive)
    unpack = cache / filename.replace('.tar.bz2', '')
    if not unpack.exists():
        with tarfile.open(archive) as file:
            file.extractall(cache, filter='data')
    destination = target / directory
    if not destination.exists():
        shutil.copytree(unpack, destination, symlinks=False)

tasks = [
    lambda: qwen('Qwen/Qwen3-4B-GGUF', 'Qwen3-4B-Q4_K_M.gguf', 'llm'),
    lambda: qwen('Qwen/Qwen3-Embedding-0.6B-GGUF', 'Qwen3-Embedding-0.6B-Q8_0.gguf', 'embedding'),
    lambda: speech('sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2', 'asr-models', 'asr'),
    lambda: speech('kokoro-multi-lang-v1_1.tar.bz2', 'tts-models', 'tts'),
]
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
    for result in executor.map(lambda task: task(), tasks):
        pass
shutil.copyfile(root / 'packaging/release/LICENSE', target / 'LICENSE-APPLICATION-NOT-MODELS')
shutil.copyfile(root / 'packaging/release/NOTICE', target / 'NOTICE')
print('Model files ready; review licenses and sign the pack:', target)
