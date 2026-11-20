#!/usr/bin/env python3
"""Create the Apache-2.0 local edition from an explicit allowlist."""
import json
import shutil
import subprocess
import tarfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
out = root / 'artifacts/open-source-local'
if out.exists():
    shutil.rmtree(out)
out.mkdir(parents=True)
allow = [
    'packages/rp', 'apps/server/src', 'apps/server/voice', 'apps/server/pom.xml',
    'apps/desktop/src/release', 'apps/desktop/src/components/RpPanel.vue', 'apps/desktop/src/components/chat/ChatMessageList.vue',
    'apps/desktop/src/utils/api.js', 'apps/desktop/src/utils/sse.js', 'apps/desktop/src/utils/worldLocation.js', 'apps/desktop/src/composables/useVoiceRecorder.js', 'apps/desktop/src/live2d',
    'apps/desktop/src/package.json', 'apps/desktop/src/story.css', 'apps/desktop/src/story-styles', 'apps/desktop/release-public', 'apps/desktop/index.html',
    'apps/desktop/tailwind.config.js', 'apps/desktop/package.json', 'apps/desktop/package-lock.json',
    'apps/desktop/vite.config.mjs', 'apps/desktop/electron-builder.release.cjs',
    'apps/desktop/electron/release-main.cjs', 'apps/desktop/electron/release-preload.cjs', 'apps/desktop/electron/character-assets.cjs', 'apps/desktop/electron/offline-runner.cjs',
    'apps/desktop/electron/package-release.cjs', 'apps/desktop/electron/local-service-supervisor.cjs', 'apps/desktop/electron/embedding-args.cjs', 'apps/desktop/electron/platform-location.cjs', 'apps/desktop/electron/prepare-macos-location.js', 'apps/desktop/electron/model-pack.cjs', 'apps/desktop/electron/model-pack-public.pem', 'apps/desktop/release-tests',
    'packaging/release/icon.svg', 'packaging/release/icon.png', 'tools/maven/mvnw', 'tools/maven/mvnw.cmd', 'tools/maven/.mvn', 'scripts/release/build.sh', 'scripts/release/stage-runtime.py', 'scripts/release/prepare-runtime.py',
    'scripts/release/fetch-models.py', 'scripts/release/sign-model-pack.py', 'scripts/release/export-legacy.py', 'scripts/release/merge-exports.py', 'scripts/release/stage-ci.py', 'scripts/release/native-smoke.cjs', 'packaging/release/model-pack.example.json', 'packaging/release/LICENSE', 'packaging/release/NOTICE',
]
for name in allow:
    source, target = root / name, out / name
    if not source.exists():
        raise SystemExit('Missing source allowlist entry: ' + name)
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.is_dir():
        shutil.copytree(source, target, ignore=shutil.ignore_patterns('__pycache__', 'target', 'node_modules'))
    else:
        shutil.copy2(source, target)
for name in ['apps/server/src/main/java/org/example/aichat/release/cloud', 'apps/server/src/test/java/org/example/aichat/release/cloud', 'apps/server/src/main/resources/static/admin', 'apps/server/src/main/resources/application-cloud.yml', 'apps/server/src/main/resources/db/V1__postgres.sql', 'apps/desktop/src/release/AdminApp.vue']:
    path = out / name
    if path.is_dir():
        shutil.rmtree(path)
    elif path.exists():
        path.unlink()
entry = out / 'apps/desktop/src/release/main.js'
entry.write_text("import {createApp} from 'vue'\nimport '../story.css'\nimport Root from './ReleaseApp.vue'\ncreateApp(Root).mount('#app')\n")
package = out / 'apps/desktop/package.json'
data = json.loads(package.read_text())
data['dependencies']={name:data['dependencies'][name] for name in ['vue','pixi.js','pixi-live2d-display-lipsyncpatch','@pixi/unsafe-eval']}
data.update(main='electron/release-main.cjs', license='Apache-2.0', storyDistribution='offline')
data['scripts'] = {'build:offline': 'vite build --mode offline', 'test:release': 'node --test release-tests/*.test.cjs release-tests/*.test.mjs', 'dev': 'node ../../scripts/workspace.cjs dev offline', 'package:offline': 'node ../../scripts/workspace.cjs package offline'}
data.pop('build', None)
package.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
subprocess.run(['npm','install','--package-lock-only','--ignore-scripts'],cwd=package.parent,check=True,stdout=subprocess.DEVNULL)
(out / 'scripts/workspace.cjs').write_text((root / 'scripts/release/offline-source-workflow.cjs').read_text())
(out / 'package.json').write_text(json.dumps({'name': 'story-space-offline', 'private': True, 'scripts': {'dev': 'node scripts/workspace.cjs dev offline', 'dev:offline': 'node scripts/workspace.cjs dev offline', 'package:offline': 'node scripts/workspace.cjs package offline', 'test': './tools/maven/mvnw -B -f apps/server/pom.xml -Poffline clean verify'}}, indent=2) + '\n')
(out / '.gitignore').write_text('.local/\n**/node_modules/\n**/target/\n**/dist-*/\n**/release-offline/\npackaging/release/staging/\n')
(out / 'README.md').write_text('# 幕间 · 离线版\n\nApache-2.0 自有代码，模型权重和第三方运行时遵循各自许可。\n\nNode 24、JDK 17+；根目录 `npm run dev` 自动安装前端依赖、构建离线前后端并启动；`npm run dev -- --smoke` 自动验证并停止。开发数据在 `.local/dev/offline/`。\n\n`npm run package:offline` 完整打包；首次原生组装需 Python 3.12、llama-server、ffmpeg、jlink，或提前准备目标平台 staging。应用与模型包分开发行。真实对话需导入签名模型包或配置自有本地模型，自建发行方应替换模型包公钥并自行签名。旧 MySQL 数据通过显式导出向新的离线数据目录导入。\n')
(out / 'NOTICE').write_text((root / 'packaging/release/NOTICE').read_text())
(out / 'LICENSE').write_text((root / 'packaging/release/LICENSE').read_text())
archive = root / 'artifacts/StorySpace-local-source.tar.gz'
with tarfile.open(archive, 'w:gz') as tar:
    tar.add(out, arcname='StorySpace-local')
print('Exported local-only source:', archive)
