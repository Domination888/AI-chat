#!/usr/bin/env node
// Workspace entry point. cloud/offline/admin are stable internal build identifiers.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const net = require('node:net')
const { spawn, spawnSync } = require('node:child_process')
const root = path.resolve(__dirname, '..')
const desktop = path.join(root, 'apps/desktop')
const local = path.join(root, '.local/dev')
const children = new Set()
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
const maven = path.join(root, 'tools/maven', process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw')
const jar = path.join(root, 'apps/server/target/story-runtime-0.1.0.jar')

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, stdio: 'inherit', ...(process.platform === 'win32' && command.endsWith('.cmd') ? { shell: true } : {}), ...options })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`${path.basename(command)} 执行失败（${result.status}）`)
  return result.stdout?.toString().trim()
}
function capture(command, args, options = {}) { return run(command, args, { stdio: ['ignore', 'pipe', 'inherit'], ...options }) }
function start(command, args, options = {}) {
  const child = spawn(command, args, { cwd: root, stdio: 'inherit', ...(process.platform === 'win32' && command.endsWith('.cmd') ? { shell: true } : {}), ...options })
  children.add(child)
  child.on('error', error => { children.delete(child); console.error(error.message); process.exitCode = 1 })
  child.on('exit', () => children.delete(child))
  return child
}
async function stopChildren() {
  await Promise.all([...children].map(child => new Promise(resolve => {
    if (!child.pid || child.exitCode !== null) return resolve()
    const timer = setTimeout(() => child.kill('SIGKILL'), 5000)
    child.once('exit', () => { clearTimeout(timer); resolve() })
    child.kill('SIGTERM')
  })))
}
function ensureDependencies() {
  if (!fs.existsSync(path.join(desktop, 'node_modules/.bin/vite'))) run(npm, ['--prefix', desktop, 'ci'])
}
function frontend(mode, env = process.env) { run(npm, ['--prefix', desktop, 'run', `build:${mode}`], { env }) }
function backend(mode, verify = false) {
  run(maven, ['-B', '-f', 'apps/server/pom.xml', ...(mode === 'offline' ? ['-Poffline'] : []), 'clean', verify ? 'verify' : 'package', ...(!verify ? ['-DskipTests'] : [])])
}
function adminAssets() {
  frontend('admin', { ...process.env, STORY_CLOUD_URL: '' })
  const target = path.join(root, 'apps/server/src/main/resources/static/admin')
  fs.rmSync(target, { recursive: true, force: true })
  fs.cpSync(path.join(desktop, 'dist-admin'), target, { recursive: true })
}
function javaBinary() {
  if (process.env.JAVA_HOME) return path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  const settings = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8' })
  const home = settings.stderr?.match(/java.home = (.+)/)?.[1]?.trim()
  if (!home) throw new Error('请先安装 JDK 17+，并配置 JAVA_HOME 或 PATH')
  return path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
}
function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer().on('error', reject)
    server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)) })
  })
}
async function waitHealth(base, child) {
  for (let i = 0; i < 180; i++) {
    if (child.exitCode !== null) throw new Error('后端启动失败，请检查上方日志')
    try { if ((await fetch(base + '/api/health', { signal: AbortSignal.timeout(1000) })).ok) return } catch {}
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error('后端启动超时')
}
function otp(secret, now = Date.now()) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = ''
  for (const char of secret) bits += alphabet.indexOf(char).toString(2).padStart(5, '0')
  const bytes = Buffer.from((bits.match(/.{8}/g) || []).map(byte => parseInt(byte, 2)))
  const counter = Buffer.alloc(8); counter.writeBigUInt64BE(BigInt(Math.floor(now / 30000)))
  const hash = crypto.createHmac('sha1', bytes).update(counter).digest(), offset = hash[hash.length - 1] & 15
  return String((hash.readUInt32BE(offset) & 0x7fffffff) % 1000000).padStart(6, '0')
}
function devSecrets() {
  const file = path.join(local, 'commercial-secrets.json')
  if (!fs.existsSync(file)) {
    fs.writeFileSync(file, JSON.stringify({ adminEmail: 'admin@dev.example', adminPassword: crypto.randomBytes(24).toString('base64url'), totp: crypto.randomBytes(20).toString('hex').split('').map(x => 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'[parseInt(x, 16)]).join(''), databasePassword: crypto.randomBytes(24).toString('hex'), encryptionKey: crypto.randomBytes(32).toString('base64'), signingKey: crypto.randomBytes(32).toString('hex'), s3Access: 'storydev', s3Secret: crypto.randomBytes(24).toString('hex') }, null, 2), { mode: 0o600 })
  }
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}
function composeArgs(...args) { return ['compose', '-p', 'story-space-dev', '-f', 'deployment/dev/compose.yml', ...args] }
function composeEnv(s) { return { ...process.env, STORY_DEV_DB_PASSWORD: s.databasePassword, STORY_DEV_S3_ACCESS: s.s3Access, STORY_DEV_S3_SECRET: s.s3Secret } }
function mappedPort(service, port, env) {
  return capture('docker', composeArgs('port', service, String(port)), { env }).split(':').at(-1)
}
function openBrowser(url) {
  const command = process.platform === 'darwin' ? ['open', url] : process.platform === 'win32' ? ['cmd', '/c', 'start', '', url] : ['xdg-open', url]
  start(command[0], command.slice(1))
}
function offlineDatabase(env = process.env, settingsFile = path.join(local, 'offline-database.json')) {
  const value = env.STORY_DEV_SQLITE_FILE ?? (fs.existsSync(settingsFile) ? JSON.parse(fs.readFileSync(settingsFile, 'utf8')).file : undefined)
  if (value === undefined) return undefined
  if (typeof value !== 'string' || !value.trim()) throw new Error('本地 SQLite 文件路径不能为空')
  const file = path.resolve(root, value)
  // An inspection connection must not silently become a new, empty database after a file move.
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) throw new Error('指定的本地 SQLite 文件不存在：' + file)
  return fs.realpathSync(file)
}
async function dev(mode, flags) {
  fs.mkdirSync(local, { recursive: true, mode: 0o700 })
  // One build directory and dev stack: reject concurrent invocations without touching their processes.
  const lock = path.join(local, 'session.lock')
  if (fs.existsSync(lock)) {
    const pid = Number(fs.readFileSync(lock, 'utf8'))
    try { process.kill(pid, 0); throw new Error('已有开发命令在运行，请先在原终端按 Ctrl+C') } catch (error) { if (error.code !== 'ESRCH') throw error }
    fs.rmSync(lock)
  }
  fs.writeFileSync(lock, String(process.pid), { flag: 'wx', mode: 0o600 })
  let runner, dockerEnv, shuttingDown = false
  async function cleanup() {
    if (shuttingDown) return
    shuttingDown = true
    runner?.stop()
    await stopChildren()
    try { if (dockerEnv) run('docker', composeArgs('stop'), { env: dockerEnv }) } finally { fs.rmSync(lock, { force: true }) }
  }
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => cleanup().then(() => process.exit(0)).catch(error => { console.error(error.message); process.exit(1) }))
  try {
    ensureDependencies()
    if (mode === 'offline') {
      const database = flags.includes('--smoke') ? undefined : offlineDatabase()
      frontend('offline'); backend('offline')
      const env = { ...process.env, STORY_DISTRIBUTION: 'offline', STORY_DEV_DATA: path.join(local, 'offline'), STORY_DEV_JAVA: javaBinary(), STORY_DEV_JAR: jar, STORY_DEV_SQLITE_FILE: database }
      console.log('本地实时 SQLite：' + (database || path.join(local, 'offline/data/stories.sqlite')))
      if (flags.includes('--smoke')) {
        const { OfflineRunner } = require('../apps/desktop/electron/offline-runner.cjs')
        runner = new OfflineRunner({ resources: path.join(root, 'packaging/release/staging', process.platform), data: path.join(local, 'offline/data'), java: env.STORY_DEV_JAVA, jar, database })
        await runner.start()
        const response = await fetch(runner.base + '/api/roles', { headers: { Authorization: 'Bearer ' + runner.token } })
        if (!response.ok || !(await response.json()).length) throw new Error('离线角色库验收失败')
        console.log('SMOKE PASS: 离线版前端构建、后端健康与已认证角色库')
      } else {
        run(process.execPath, [path.join(desktop, 'electron/prepare-macos-location.js')])
        const child = start(require(path.join(desktop, 'node_modules/electron')), [path.join(desktop, 'electron/release-main.cjs')], { env })
        await new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', code => code === 0 ? resolve() : reject(new Error(`Electron 退出（${code}）`))) })
      }
    } else {
      capture('docker', ['info', '--format', '{{.ServerVersion}}'])
      const secrets = devSecrets(); dockerEnv = composeEnv(secrets)
      run('docker', composeArgs('up', '-d', '--wait', '--wait-timeout', '180', 'postgres', 'objects', 'mail', 'egress'), { env: dockerEnv })
      // Repeated local UI checks can reuse the already-built initializer when the
      // image registry is unavailable. Normal startup still rebuilds it.
      const initializerBuild = process.env.STORY_DEV_REUSE_IMAGES === '1' ? [] : ['--build']
      run('docker', composeArgs('run', '--rm', ...initializerBuild, '-T', '--no-deps', 'objects-init'), { env: dockerEnv })
      const port = await freePort(), base = `http://127.0.0.1:${port}`
      const dbPort = mappedPort('postgres', 5432, dockerEnv), objectPort = mappedPort('objects', 9000, dockerEnv)
      const smtpPort = mappedPort('mail', 1025, dockerEnv), mailPort = mappedPort('mail', 8025, dockerEnv)
      const env = { ...process.env, SPRING_PROFILES_ACTIVE: 'cloud', STORY_MODE: 'cloud', STORY_BIND: '127.0.0.1', STORY_PORT: String(port), STORY_DATA: path.join(local, 'commercial/data'), STORY_DATABASE_URL: `jdbc:postgresql://127.0.0.1:${dbPort}/stories`, STORY_DATABASE_USER: 'stories', STORY_DATABASE_PASSWORD: secrets.databasePassword, STORY_S3_ENDPOINT: `http://127.0.0.1:${objectPort}`, STORY_S3_BUCKET: 'stories', STORY_S3_ACCESS_KEY: secrets.s3Access, STORY_S3_SECRET_KEY: secrets.s3Secret, STORY_ADMIN_EMAIL: secrets.adminEmail, STORY_ADMIN_PASSWORD: secrets.adminPassword, STORY_ADMIN_TOTP_SECRET: secrets.totp, STORY_SIGNING_KEY: secrets.signingKey, STORY_ENCRYPTION_KEY: secrets.encryptionKey, STORY_SMTP_HOST: '127.0.0.1', STORY_SMTP_PORT: smtpPort, SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH: 'false', SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE: 'false', STORY_MAIL_FROM: 'story@dev.example', STORY_ORIGINS: process.env.STORY_ORIGINS || 'app://story', STORY_BYOK_PROXY_HOST: '127.0.0.1', STORY_BYOK_PROXY_PORT: mappedPort('egress', 3128, dockerEnv) }
      adminAssets(); frontend('cloud', { ...process.env, STORY_CLOUD_URL: '' }); backend('cloud')
      const child = start(javaBinary(), ['-jar', jar], { env })
      await waitHealth(base, child)
      console.log(`商业版用户端服务：${base}\n商业版管理端：${base}/admin/\n开发邮箱：http://127.0.0.1:${mailPort}\n本地管理员凭据：${path.join(local, 'commercial-secrets.json')}\n动态验证码：npm run dev:otp\nCtrl+C 停止本次进程与开发容器，保留测试数据。`)
      if (flags.includes('--smoke')) {
        const page = await fetch(base + '/admin/')
        if (!page.ok || !(await page.text()).includes('/assets/')) throw new Error('管理端页面验收失败')
        const login = await fetch(base + '/api/v1/auth/admin-login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: secrets.adminEmail, password: secrets.adminPassword, otp: otp(secrets.totp) }) })
        if (!login.ok) throw new Error('管理员 MFA 验收失败')
        const session = await login.json()
        const users = await fetch(base + '/api/v1/admin/users', { headers: { Authorization: 'Bearer ' + session.accessToken } })
        if (!users.ok) throw new Error('管理 API 验收失败')
        await fetch(base + '/api/v1/auth/logout', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: session.refreshToken }) })
        console.log('SMOKE PASS: 商业版双前端、PostgreSQL/MinIO/SMTP/代理启动、后端健康、管理员 MFA 与管理 API')
      } else {
        if (mode === 'admin') { if (!flags.includes('--no-open')) openBrowser(base + '/admin/') }
        else {
          run(process.execPath, [path.join(desktop, 'electron/prepare-macos-location.js')])
          const electron = start(require(path.join(desktop, 'node_modules/electron')), [path.join(desktop, 'electron/release-main.cjs')], { env: { ...process.env, STORY_DISTRIBUTION: 'cloud', STORY_DEV_DATA: path.join(local, 'commercial/client'), STORY_DEV_SERVER: base } })
          electron.once('exit', () => child.kill())
        }
        await new Promise((resolve, reject) => child.once('exit', code => code === 0 || code === 143 || code === null ? resolve() : reject(new Error(`后端退出（${code}）`))))
      }
    }
  } finally { await cleanup() }
}
async function pack(mode, flags) {
  ensureDependencies()
  if (mode === 'admin') {
    if (flags.some(flag => flag !== '--dir')) throw new Error('管理端打包仅支持可选 --dir')
    adminAssets(); backend('cloud')
    const out = path.join(root, 'artifacts/commercial-admin')
    fs.rmSync(out, { recursive: true, force: true }); fs.mkdirSync(out, { recursive: true })
    fs.copyFileSync(jar, path.join(out, 'story-cloud-runtime.jar'))
    for (const name of ['compose.yml', 'Caddyfile', 'egress.Dockerfile', 'squid.conf', 'minio.Dockerfile', 'object-init.Dockerfile', 'init-objects.py', '.env.example']) fs.copyFileSync(path.join(root, 'deployment/cloud', name), path.join(out, name))
    const compose = fs.readFileSync(path.join(out, 'compose.yml'), 'utf8').replaceAll('context: ../..', 'context: .').replace('dockerfile: deployment/cloud/Dockerfile', 'dockerfile: Dockerfile').replaceAll('dockerfile: deployment/cloud/', 'dockerfile: ')
    fs.writeFileSync(path.join(out, 'compose.yml'), compose)
    const egressFile = path.join(out, 'egress.Dockerfile')
    fs.writeFileSync(egressFile, fs.readFileSync(egressFile, 'utf8').replace('COPY deployment/cloud/squid.conf', 'COPY squid.conf'))
    const initFile = path.join(out, 'object-init.Dockerfile')
    fs.writeFileSync(initFile, fs.readFileSync(initFile, 'utf8').replace('COPY deployment/cloud/init-objects.py', 'COPY init-objects.py'))
    fs.writeFileSync(path.join(out, 'Dockerfile'), 'FROM eclipse-temurin:17-jre\nWORKDIR /app\nRUN useradd --system --uid 10001 story && mkdir /data && chown story /data\nCOPY story-cloud-runtime.jar runtime.jar\nUSER story\nENV SPRING_PROFILES_ACTIVE=cloud STORY_DATA=/data STORY_BIND=0.0.0.0\nEXPOSE 8081\nENTRYPOINT ["java","-Xmx768m","-jar","/app/runtime.jar"]\n')
    fs.copyFileSync(jar, path.join(root, 'artifacts/story-cloud-runtime.jar'))
    fs.copyFileSync(path.join(root, 'docs/commercial-admin-deploy.md'), path.join(out, 'README.md'))
    fs.writeFileSync(path.join(out, '.dockerignore'), '.env\n*.tar.gz\n')
    fs.writeFileSync(path.join(out, 'SHA256SUMS'), crypto.createHash('sha256').update(fs.readFileSync(jar)).digest('hex') + '  story-cloud-runtime.jar\n')
    if (!flags.includes('--dir')) run('tar', ['-czf', path.join(root, 'artifacts/StorySpace-commercial-admin.tar.gz'), '-C', path.dirname(out), path.basename(out)])
    console.log('商业版管理端完整服务部署包：' + out)
  } else {
    frontend(mode)
    if (mode === 'offline') {
      backend('offline')
      const platform = flags.includes('--win') ? 'win32' : flags.includes('--mac') ? 'darwin' : process.platform
      run(process.platform === 'win32' ? 'python' : 'python3', ['scripts/release/prepare-runtime.py', platform])
    }
    run(process.execPath, [path.join(desktop, 'electron/package-release.cjs'), mode, ...(flags.includes('--publish') ? [] : ['--publish', 'never']), ...flags])
  }
}
async function main() {
  const [action, requested = 'offline', ...flags] = process.argv.slice(2)
  const mode = requested === 'commercial' ? 'cloud' : requested
  if (action === 'otp') {
    const file = path.join(local, 'commercial-secrets.json')
    if (!fs.existsSync(file)) throw new Error('请先启动商业版开发环境')
    console.log(otp(JSON.parse(fs.readFileSync(file, 'utf8')).totp)); return
  }
  if (!['cloud', 'offline', 'admin'].includes(mode)) throw new Error('版本参数：commercial | offline | admin')
  if (action === 'dev') await dev(mode, flags)
  else if (action === 'package') await pack(mode, flags)
  else throw new Error('用法：node scripts/workspace.cjs dev|package offline|commercial|admin [选项]')
}
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1 })
module.exports = { otp, offlineDatabase }
