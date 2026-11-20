// Copied as scripts/workspace.cjs by export-source.py; this file has no commercial workflow.
const fs = require('node:fs'), path = require('node:path'), { spawnSync, spawn } = require('node:child_process')
const root = path.resolve(__dirname, '..'), desktop = path.join(root, 'apps/desktop')
const [action, mode = 'offline', ...flags] = process.argv.slice(2)
function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, stdio: 'inherit', ...(process.platform === 'win32' && command.endsWith('.cmd') ? { shell: true } : {}), ...options })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`${command} failed (${result.status})`)
}
async function main() {
  if (mode !== 'offline' || !['dev', 'package'].includes(action)) throw new Error('Use dev|package offline')
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
  if (!fs.existsSync(path.join(desktop, 'node_modules/.bin/vite'))) run(npm, ['--prefix', desktop, 'ci'])
  run(npm, ['--prefix', desktop, 'run', 'build:offline'])
  run(path.join(root, 'tools/maven', process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw'), ['-B', '-f', 'apps/server/pom.xml', '-Poffline', 'clean', 'package', '-DskipTests'])
  if (action === 'package') {
    const platform = flags.includes('--win') ? 'win32' : flags.includes('--mac') ? 'darwin' : process.platform
    run(process.platform === 'win32' ? 'python' : 'python3', ['scripts/release/prepare-runtime.py', platform])
    run(process.execPath, [path.join(desktop, 'electron/package-release.cjs'), 'offline', '--publish', 'never', ...flags])
    return
  }
  const settings = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8' })
  const home = process.env.JAVA_HOME || settings.stderr?.match(/java.home = (.+)/)?.[1]?.trim()
  if (!home) throw new Error('Install JDK 17+ and configure JAVA_HOME')
  const java = path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  const jar = path.join(root, 'apps/server/target/story-runtime-0.1.0.jar'), data = path.join(root, '.local/dev/offline')
  if (flags.includes('--smoke')) {
    const { OfflineRunner } = require('../apps/desktop/electron/offline-runner.cjs')
    const runner = new OfflineRunner({ resources: path.join(root, 'packaging/release/staging', process.platform), data: path.join(data, 'data'), java, jar })
    try { await runner.start(); const r = await fetch(runner.base + '/api/roles', { headers: { Authorization: 'Bearer ' + runner.token } }); if (!r.ok) throw new Error('Offline API smoke failed'); console.log('SMOKE PASS') } finally { runner.stop() }
    return
  }
  const child = spawn(require(path.join(desktop, 'node_modules/electron')), [path.join(desktop, 'electron/release-main.cjs')], { cwd: root, stdio: 'inherit', env: { ...process.env, STORY_DISTRIBUTION: 'offline', STORY_DEV_DATA: data, STORY_DEV_JAVA: java, STORY_DEV_JAR: jar } })
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal))
  await new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', code => code === 0 ? resolve() : reject(new Error(`Electron exited (${code})`))) })
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
