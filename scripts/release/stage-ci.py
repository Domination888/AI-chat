#!/usr/bin/env python3
import os
import shutil
import subprocess
import sys
from pathlib import Path
import imageio_ffmpeg

root = Path(__file__).resolve().parents[2]
platform = 'win32' if sys.platform == 'win32' else 'darwin'
java = Path(os.environ['JAVA_HOME'])
llama = next((root / 'llama-runtime').rglob('llama-server.exe')) if platform == 'win32' else Path(shutil.which('llama-server'))
subprocess.run([sys.executable, str(root / 'scripts/release/stage-runtime.py'), platform, '--java', str(java), '--llama', str(llama), '--voice', str(root / 'packaging/release/staging/voice-dist/story-voice'), '--ffmpeg', imageio_ffmpeg.get_ffmpeg_exe()], check=True)
