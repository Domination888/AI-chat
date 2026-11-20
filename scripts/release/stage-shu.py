#!/usr/bin/env python3
"""Stage the user's existing Shu assets for a personal desktop build, without story data."""
import argparse, json, shutil
from pathlib import Path
root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=root/'artifacts/shu-personal-pack');args=parser.parse_args()
output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
for source,target in [(root/'.local/personal/live2d/shu',output/'live2d/shu'),(root/'.local/personal/live2d-core',output/'live2d-core'),(root/'.local/personal/characters',output/'characters'),(root/'.local/personal/personas/shu',output/'personas/shu')]:
    if not source.is_dir():raise SystemExit('Missing personal character source: '+str(source))
    shutil.copytree(source,target,dirs_exist_ok=True)
# The personal pack includes editable skill text and the owned builtin example,
# never arbitrary local MCP credentials or commands.
if (root/'config/skills').is_dir():shutil.copytree(root/'config/skills',output/'config/skills',dirs_exist_ok=True)
(output/'config').mkdir(exist_ok=True)
(output/'config/mcp-servers.json').write_text(json.dumps([{'id':'prime','name':'Prime 示例工具','builtin':True,'transport':'builtin','enabled':True,'allowModelCalls':True}],ensure_ascii=False,indent=2))
model=json.loads((output/'live2d/shu/黍.model3.json').read_text())
for name in [model['FileReferences']['Moc'],*model['FileReferences']['Textures']]:
    if not (output/'live2d/shu'/name).is_file():raise SystemExit('Missing model reference: '+name)
(output/'PERSONAL-ASSETS.txt').write_text('Personal character materials copied from the existing local project. These are not covered by the application source license. Do not include them in public source exports.\n')
print(json.dumps({'output':str(output),'files':sum(1 for p in output.rglob('*') if p.is_file()),'model':'黍','voiceProfile':'shu'},ensure_ascii=False))
