#!/usr/bin/env python3
"""Refresh the offline JAR; assemble native prerequisites when no staged runtime exists."""
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
platform = sys.argv[1] if len(sys.argv) > 1 else sys.platform
if platform not in ('darwin', 'win32'):
    raise SystemExit('离线桌面发行目前支持 macOS 和 Windows')
target = root / 'packaging/release/staging' / platform
suffix = '.exe' if platform == 'win32' else ''
jar = root / 'apps/server/target/story-runtime-0.1.0.jar'
with zipfile.ZipFile(jar) as archive:
    if any('/release/cloud/' in name or name.endswith('application-cloud.yml') or '/static/admin/' in name for name in archive.namelist()):
        raise SystemExit('拒绝把商业版 JAR 放入离线运行时')
required = ['java/bin/java' + suffix, 'bin/llama-server' + suffix, 'bin/ffmpeg' + suffix, 'voice/story-voice' + suffix]
backends_complete = platform != 'darwin' or ((target / 'bin/llama-server-native').is_file() and any((target / 'lib').glob('libggml-metal.*')) and any((target / 'lib').glob('libggml-cpu*')))
if all((target / name).is_file() for name in required) and backends_complete:
    shutil.copy2(jar, target / 'story-runtime.jar')
    print('已刷新离线运行时后端：', target)
    raise SystemExit(0)
if platform != sys.platform:
    raise SystemExit('跨平台打包需要先在目标系统准备完整的 packaging/release/staging/' + platform)
java_home = os.environ.get('JAVA_HOME')
if not java_home:
    settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True, text=True, check=True).stderr
    java_home = next(line.split('=', 1)[1].strip() for line in settings.splitlines() if 'java.home =' in line)
llama = shutil.which('llama-server')
ffmpeg = shutil.which('ffmpeg')
python = shutil.which('python3.12')
if not llama or not ffmpeg or not python:
    raise SystemExit('首次离线打包需要 JDK 17+、Python 3.12、llama-server、ffmpeg；请安装缺少的构建工具后重试同一命令。已准备目标运行时则无需这些原生构建工具。')
work = root / '.local/build'
work.mkdir(parents=True, exist_ok=True)
jre = work / 'java'
if jre.exists():
    shutil.rmtree(jre)
subprocess.run([str(Path(java_home) / 'bin' / ('jlink' + suffix)), '--add-modules', 'java.se,jdk.crypto.ec,jdk.unsupported,jdk.zipfs,jdk.management,jdk.charsets,jdk.localedata', '--strip-debug', '--compress=2', '--no-header-files', '--no-man-pages', '--output', str(jre)], check=True)
venv = work / 'voice-venv'
subprocess.run([python, '-m', 'venv', str(venv)], check=True)
venv_python = venv / ('Scripts/python.exe' if platform == 'win32' else 'bin/python')
subprocess.run([str(venv_python), '-m', 'pip', 'install', '-r', str(root / 'apps/server/voice/requirements.txt'), 'pyinstaller'], check=True)
subprocess.run([str(venv_python), '-m', 'PyInstaller', '--noconfirm', '--name', 'story-voice', '--distpath', str(work / 'voice-dist'), '--workpath', str(work / 'voice-work'), '--specpath', str(work), '--collect-all', 'sherpa_onnx', '--collect-all', 'sherpa_onnx_core', '--collect-all', 'soundfile', str(root / 'apps/server/voice/server.py')], check=True)
subprocess.run([sys.executable, str(root / 'scripts/release/stage-runtime.py'), platform, '--java', str(jre), '--llama', llama, '--voice', str(work / 'voice-dist/story-voice'), '--ffmpeg', ffmpeg], check=True)
