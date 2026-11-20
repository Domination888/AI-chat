const {spawnSync}=require('child_process')
const mode=process.argv[2]
if(!['cloud','offline'].includes(mode))throw new Error('Use cloud|offline')
const binary=require.resolve('electron-builder/out/cli/cli.js')
const result=spawnSync(process.execPath,[binary,'--config','electron-builder.release.cjs',...process.argv.slice(3)],{stdio:'inherit',cwd:require('path').join(__dirname,'..'),env:{...process.env,STORY_DISTRIBUTION:mode,STORY_TARGET_PLATFORM:process.argv.includes("--win")?"win32":process.argv.includes("--mac")?"darwin":process.platform}})
process.exit(result.status ?? 1)
