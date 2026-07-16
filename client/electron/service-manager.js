const { spawn, execFile } = require('child_process');
const fs = require('fs');
const net = require('net');
const path = require('path');
const { dialog } = require('electron');
const {
  isPackaged,
  getUserDataRoot,
  getLogsDir,
  getRuntimeDir,
  binName,
  runtimeBin,
  ensureDir,
  seedUserConfig,
} = require('./paths');
const {
  allocateAllPorts,
  getActivePorts,
  patchMcpSearxngUrl,
  portEnvExtras,
} = require('./port-manager');

const PROCESSES = [];
let startPromise = null;

function ports() {
  return getActivePorts();
}

function logPath(name) {
  return path.join(getLogsDir(), `${name}.log`);
}

function appendLog(name, chunk) {
  ensureDir(getLogsDir());
  fs.appendFileSync(logPath(name), chunk);
}

function track(name, child) {
  PROCESSES.push({ name, child, pid: child.pid });
  const logFile = logPath(name);
  ensureDir(path.dirname(logFile));
  const stream = fs.createWriteStream(logFile, { flags: 'a' });
  child.stdout?.pipe(stream);
  child.stderr?.pipe(stream);
  child.on('exit', (code, signal) => {
    appendLog(name, `\n[exit] code=${code} signal=${signal}\n`);
  });
  return child;
}

/** Unix 下 detached  spawn，便于退出时 kill 整个进程组 */
function spawnManaged(name, cmd, args, options = {}) {
  const unix = process.platform !== 'win32';
  const child = spawn(cmd, args, {
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: unix,
    ...options,
  });
  if (unix && child.pid) {
    // 保持引用，退出时按进程组 SIGTERM/SIGKILL
  }
  return track(name, child);
}

async function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function waitForUrl(url, name, attempts = 40, intervalMs = 1500, child = null) {
  for (let i = 1; i <= attempts; i++) {
    if (child && child.exitCode != null) {
      throw new Error(`${name} 进程已退出 (code=${child.exitCode})，请查看 logs/${name}.log`);
    }
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(4000) });
      if (res.ok) return true;
    } catch (_) {
      /* retry */
    }
    await sleep(intervalMs);
  }
  throw new Error(`${name} 未在预期时间内就绪: ${url}`);
}

async function waitForBackendReady(port, child) {
  const url = `http://127.0.0.1:${port}/api/roles`;
  for (let i = 1; i <= 120; i++) {
    if (child && child.exitCode != null) {
      throw new Error(`Backend 进程已退出 (code=${child.exitCode})，请查看 logs/backend.log`);
    }
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(4000) });
      if (res.ok) {
        const roles = await res.json();
        if (Array.isArray(roles)) return true;
      }
    } catch (_) {
      /* retry until both Spring and MySQL are ready */
    }
    await sleep(1500);
  }
  throw new Error(`Backend 或数据库未在预期时间内就绪: ${url}`);
}

async function waitForAsrReady(port, child) {
  const url = `http://127.0.0.1:${port}/healthz`;
  for (let i = 1; i <= 240; i++) {
    if (child && child.exitCode != null) {
      throw new Error(`ASR 进程已退出 (code=${child.exitCode})，请查看 logs/asr.log`);
    }
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(4000) });
      if (res.ok) {
        const data = await res.json();
        if (data.model_loaded) return true;
      }
    } catch (_) {
      /* retry */
    }
    await sleep(1500);
  }
  throw new Error(`ASR 模型加载超时: ${url}`);
}

async function waitForMysql(port, mysqlDir, child, attempts = 45) {
  const mysqladmin = path.join(mysqlDir, 'bin', binName('mysqladmin'));
  for (let i = 1; i <= attempts; i++) {
    if (child && child.exitCode != null) {
      throw new Error(`MySQL 进程已退出 (code=${child.exitCode})，请查看 logs/mysql.log`);
    }
    if (exists(mysqladmin)) {
      try {
        await new Promise((resolve, reject) => {
          execFile(
            mysqladmin,
            ['ping', '-h127.0.0.1', `-P${port}`, '-uroot'],
            { env: baseEnv() },
            (err) => (err ? reject(err) : resolve())
          );
        });
        return true;
      } catch (_) {
        /* retry */
      }
    } else {
      try {
        await new Promise((resolve, reject) => {
          const socket = net.createConnection({ port, host: '127.0.0.1' }, () => {
            socket.end();
            resolve();
          });
          socket.on('error', reject);
          socket.setTimeout(2000, () => {
            socket.destroy();
            reject(new Error('timeout'));
          });
        });
        return true;
      } catch (_) {
        /* retry */
      }
    }
    await sleep(1000);
  }
  throw new Error(`MySQL 未在预期时间内就绪: 127.0.0.1:${port}，请查看 logs/mysql.log`);
}

function exists(p) {
  return fs.existsSync(p);
}

function javaBin() {
  const bundled = runtimeBin('jre', 'bin', binName('java'));
  if (exists(bundled)) return bundled;
  return 'java';
}

function baseEnv(extra = {}) {
  const runtime = getRuntimeDir();
  const userData = getUserDataRoot();
  const configDir = seedUserConfig();
  const jreBin = path.join(runtime, 'jre', 'bin');
  const pathSep = process.platform === 'win32' ? ';' : ':';
  const pathPrefix = exists(jreBin) ? `${jreBin}${pathSep}${process.env.PATH || ''}` : process.env.PATH;

  return {
    ...process.env,
    AI_CHAT_HOME: runtime,
    AI_CHAT_DATA: userData,
    AI_CHAT_CONFIG: configDir,
    PATH: pathPrefix,
    JAVA_HOME: exists(path.join(runtime, 'jre')) ? path.join(runtime, 'jre') : process.env.JAVA_HOME,
    ...portEnvExtras(),
    ...extra,
  };
}

async function startRedis() {
  const p = ports();
  const dataDir = path.join(getUserDataRoot(), 'redis');
  ensureDir(dataDir);
  const conf = path.join(dataDir, 'redis.conf');
  fs.writeFileSync(
    conf,
    `port ${p.redis}\nbind 127.0.0.1\ndir ${dataDir.replace(/\\/g, '/')}\ndaemonize no\npidfile ${path.join(dataDir, 'redis.pid').replace(/\\/g, '/')}\n`
  );
  const redisServer = runtimeBin('redis', 'bin', binName('redis-server'));
  if (!exists(redisServer)) {
    if (isPackaged()) throw new Error(`Redis 二进制不存在: ${redisServer}`);
    console.warn('[service-manager] skip redis (binary missing, dev mode)');
    return;
  }
  spawnManaged('redis', redisServer, [conf], { env: baseEnv(), cwd: dataDir });
  await sleep(1500);
}

async function initMysqlIfNeeded(mysqlDir, dataDir) {
  if (exists(path.join(dataDir, 'mysql'))) return;
  ensureDir(dataDir);
  const mysqld = path.join(mysqlDir, 'bin', binName('mysqld'));
  await new Promise((resolve, reject) => {
    execFile(
      mysqld,
      ['--initialize-insecure', `--datadir=${dataDir}`],
      { env: baseEnv() },
      (err) => (err ? reject(err) : resolve())
    );
  });
}

async function startMysql() {
  const p = ports();
  const mysqlDir = runtimeBin('mysql');
  const dataDir = path.join(getUserDataRoot(), 'mysql');
  const mysqld = path.join(mysqlDir, 'bin', binName('mysqld'));
  if (!exists(mysqld)) {
    if (isPackaged()) throw new Error(`MySQL 二进制不存在: ${mysqld}`);
    console.warn('[service-manager] skip mysql (binary missing, dev mode)');
    return;
  }

  await initMysqlIfNeeded(mysqlDir, dataDir);

  const myCnf = path.join(dataDir, 'my.cnf');
  fs.writeFileSync(
    myCnf,
    `[mysqld]
port=${p.mysql}
bind-address=127.0.0.1
datadir=${dataDir.replace(/\\/g, '/')}
socket=${path.join(dataDir, 'mysql.sock').replace(/\\/g, '/')}
pid-file=${path.join(dataDir, 'mysqld.pid').replace(/\\/g, '/')}
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
mysqlx=0

[client]
default-character-set=utf8mb4

[mysql]
default-character-set=utf8mb4
`
  );

  const mysqlChild = spawnManaged('mysql', mysqld, [`--defaults-file=${myCnf}`], { env: baseEnv(), cwd: dataDir });
  await waitForMysql(p.mysql, mysqlDir, mysqlChild);

  const mysqlClient = path.join(mysqlDir, 'bin', binName('mysql'));
  const initSql = isPackaged()
    ? path.join(process.resourcesPath, 'config', 'init.sql')
    : path.join(__dirname, '..', '..', 'backend', 'init.sql');
  const marker = path.join(dataDir, '.schema-loaded');
  if (!exists(marker) && exists(initSql) && exists(mysqlClient)) {
    try {
      const mysqlArgs = [
        '--default-character-set=utf8mb4',
        '-uroot',
        `-h127.0.0.1`,
        `-P${p.mysql}`,
      ];
      if (process.platform === 'win32') {
        await new Promise((resolve, reject) => {
          execFile(
            'cmd.exe',
            ['/c', `"${mysqlClient}" ${mysqlArgs.join(' ')} < "${initSql}"`],
            { env: baseEnv(), shell: true },
            (err) => (err ? reject(err) : resolve())
          );
        });
      } else {
        await new Promise((resolve, reject) => {
          execFile(
            '/bin/bash',
            ['-lc', `"${mysqlClient}" ${mysqlArgs.join(' ')} < "${initSql}"`],
            { env: baseEnv() },
            (err) => (err ? reject(err) : resolve())
          );
        });
      }
      fs.writeFileSync(marker, 'ok');
    } catch (err) {
      console.warn('[service-manager] mysql init.sql import warning:', err.message);
    }
  }

  await applyEncodingFixIfNeeded(mysqlClient, p.mysql);
}

async function applyEncodingFixIfNeeded(mysqlClient, mysqlPort) {
  if (!exists(mysqlClient)) return;
  const fixSql = isPackaged()
    ? path.join(process.resourcesPath, 'config', 'fix-encoding.sql')
    : path.join(__dirname, '..', '..', 'packaging', 'config', 'fix-encoding.sql');
  const marker = path.join(getUserDataRoot(), '.encoding-v2-applied');
  if (exists(marker) || !exists(fixSql)) return;
  try {
    const mysqlArgs = [
      '--default-character-set=utf8mb4',
      '-uroot',
      `-h127.0.0.1`,
      `-P${mysqlPort}`,
    ];
    if (process.platform === 'win32') {
      await new Promise((resolve, reject) => {
        execFile(
          'cmd.exe',
          ['/c', `"${mysqlClient}" ${mysqlArgs.join(' ')} < "${fixSql}"`],
          { env: baseEnv(), shell: true },
          (err) => (err ? reject(err) : resolve())
        );
      });
    } else {
      await new Promise((resolve, reject) => {
        execFile(
          '/bin/bash',
          ['-lc', `"${mysqlClient}" ${mysqlArgs.join(' ')} < "${fixSql}"`],
          { env: baseEnv() },
          (err) => (err ? reject(err) : resolve())
        );
      });
    }
    fs.writeFileSync(marker, new Date().toISOString());
    appendLog('service-manager', '[mysql] applied fix-encoding.sql\n');
  } catch (err) {
    console.warn('[service-manager] encoding fix warning:', err.message);
  }
}

async function startNeo4j() {
  const p = ports();
  const neo4jHome = runtimeBin('neo4j');
  const bin = path.join(neo4jHome, 'bin', binName(process.platform === 'win32' ? 'neo4j.bat' : 'neo4j'));
  const binConsole = path.join(neo4jHome, 'bin', binName('neo4j'));
  const neo4jBin = exists(bin) ? bin : binConsole;
  if (!exists(neo4jBin)) {
    if (isPackaged()) throw new Error(`Neo4j 二进制不存在: ${neo4jBin}`);
    console.warn('[service-manager] skip neo4j');
    return;
  }
  const dataDir = path.join(getUserDataRoot(), 'neo4j');
  ensureDir(dataDir);
  spawnManaged('neo4j', neo4jBin, ['console'], {
    env: baseEnv({
      NEO4J_HOME: neo4jHome,
      NEO4J_CONF: path.join(neo4jHome, 'conf'),
      NEO4J_DATA: dataDir,
    }),
    cwd: neo4jHome,
  });
  await waitForUrl(`http://127.0.0.1:${p.neo4jHttp}`, 'Neo4j', 90);
}

async function startQdrant() {
  const p = ports();
  const qdrant = runtimeBin('qdrant', 'bin', binName('qdrant'));
  const qdrantAlt = runtimeBin('qdrant', binName('qdrant'));
  const bin = exists(qdrant) ? qdrant : qdrantAlt;
  if (!exists(bin)) {
    if (isPackaged()) throw new Error(`Qdrant 二进制不存在: ${bin}`);
    console.warn('[service-manager] skip qdrant');
    return;
  }
  const storage = path.join(getUserDataRoot(), 'qdrant');
  ensureDir(storage);
  spawnManaged('qdrant', bin, [], {
    env: baseEnv({ QDRANT__STORAGE__STORAGE_PATH: storage }),
    cwd: storage,
  });
  await waitForUrl(`http://127.0.0.1:${p.qdrant}/healthz`, 'Qdrant', 30);
}

async function startMemos() {
  const p = ports();
  const memosDir = runtimeBin('memos');
  const startScript =
    process.platform === 'win32'
      ? path.join(memosDir, 'start-memos.bat')
      : path.join(memosDir, 'start-memos.sh');
  if (!exists(startScript)) {
    if (isPackaged()) throw new Error(`MemOS 启动脚本不存在: ${startScript}`);
    console.warn('[service-manager] skip memos');
    return;
  }
  const cmd = process.platform === 'win32' ? startScript : '/bin/bash';
  const args = process.platform === 'win32' ? [] : [startScript];
  spawnManaged('memos', cmd, args, {
    env: baseEnv(),
    cwd: memosDir,
    shell: process.platform === 'win32',
  });
  await waitForUrl(`http://127.0.0.1:${p.memos}/docs`, 'MemOS', 90).catch(async () => {
    await waitForUrl(`http://127.0.0.1:${p.memos}/`, 'MemOS', 30);
  });
}

async function startSearxng() {
  const p = ports();
  const searxDir = runtimeBin('searxng');
  const startScript =
    process.platform === 'win32'
      ? path.join(searxDir, 'start-searxng.bat')
      : path.join(searxDir, 'start-searxng.sh');
  if (!exists(startScript)) {
    if (isPackaged()) throw new Error(`SearXNG 启动脚本不存在: ${startScript}`);
    console.warn('[service-manager] skip searxng');
    return;
  }
  const cmd = process.platform === 'win32' ? startScript : '/bin/bash';
  const args = process.platform === 'win32' ? [] : [startScript];
  spawnManaged('searxng', cmd, args, {
    env: baseEnv(),
    cwd: searxDir,
    shell: process.platform === 'win32',
  });
  await waitForUrl(
    `http://127.0.0.1:${p.searxng}/search?q=test&format=json`,
    'SearXNG',
    60
  );
}

async function startAsr() {
  const p = ports();
  const asrDir = runtimeBin('asr');
  const server =
    process.platform === 'win32'
      ? path.join(asrDir, 'sensevoice-server.exe')
      : path.join(asrDir, 'sensevoice-server');
  const startScript = path.join(asrDir, 'start-asr.sh');
  const modelsDir = path.join(asrDir, 'models');
  const asrEnv = baseEnv({
    SENSEVOICE_MODEL_DIR: modelsDir,
    MODELSCOPE_CACHE: modelsDir,
    FUNASR_MODEL_DIR: modelsDir,
    FFMPEG_PATH: path.join(asrDir, binName('ffmpeg')),
  });

  if (exists(startScript)) {
    const child = spawnManaged('asr', '/bin/bash', [startScript], { env: asrEnv, cwd: asrDir });
    await waitForAsrReady(p.asr, child);
    return;
  }

  if (!exists(server)) {
    if (isPackaged()) throw new Error(`ASR 服务不存在: ${server} 或 ${startScript}`);
    const devServer = path.join(__dirname, '..', '..', 'services', 'sense-voice', 'server.py');
    if (exists(devServer)) {
      const child = spawnManaged('asr', 'python', [devServer], {
        env: asrEnv,
        cwd: path.dirname(devServer),
      });
      await waitForAsrReady(p.asr, child);
    }
    return;
  }
  const child = spawnManaged('asr', server, [], { env: asrEnv, cwd: asrDir });
  await waitForAsrReady(p.asr, child);
}

async function startBackend() {
  const p = ports();
  const jar = runtimeBin('backend', 'AI-Chat-0.0.1-SNAPSHOT.jar');
  if (!exists(jar)) {
    if (!isPackaged()) {
      console.warn('[service-manager] backend jar missing — expect external backend');
      await waitForUrl(`http://127.0.0.1:${p.backend}/api/health`, 'Backend', 5);
      return;
    }
    throw new Error(`Backend JAR 不存在: ${jar}`);
  }
  const logsDir = getLogsDir();
  const child = spawnManaged(
    'backend',
    javaBin(),
    ['-jar', jar, '--spring.profiles.active=packaged', `--app.log-base-dir=${logsDir}`],
    { env: baseEnv(), cwd: path.dirname(jar) }
  );
  await waitForBackendReady(p.backend, child);
}

async function startAll(onProgress) {
  if (startPromise) return startPromise;
  startPromise = (async () => {
    ensureDir(getUserDataRoot());
    ensureDir(getLogsDir());
    seedUserConfig();
    await allocateAllPorts();
    patchMcpSearxngUrl();
    appendLog('service-manager', `\n[ports] ${JSON.stringify(ports())}\n`);

    const steps = [
      ['Redis', startRedis],
      ['MySQL', startMysql],
      ['Neo4j', startNeo4j],
      ['Qdrant', startQdrant],
      ['MemOS', startMemos],
      ['SearXNG', startSearxng],
      ['ASR', startAsr],
      ['Backend', startBackend],
    ];

    for (const [label, fn] of steps) {
      onProgress?.(label);
      await fn();
    }
  })();

  try {
    await startPromise;
  } catch (err) {
    await stopAll();
    startPromise = null;
    await dialog.showMessageBox({
      type: 'error',
      title: 'AI-Chat 启动失败',
      message: String(err.message || err),
      detail: `日志目录: ${getLogsDir()}`,
    });
    throw err;
  }

  return startPromise;
}

function killPid(pid, signal) {
  if (!pid) return;
  if (process.platform === 'win32') {
    spawn('taskkill', ['/pid', String(pid), '/f', '/t']);
    return;
  }
  try {
    process.kill(-pid, signal);
  } catch (_) {
    try {
      process.kill(pid, signal);
    } catch (_2) {
      /* already dead */
    }
  }
}

function stopProcess(entry) {
  return new Promise((resolve) => {
    const { name, child, pid } = entry;
    if (!pid) return resolve();

    let settled = false;
    const done = () => {
      if (settled) return;
      settled = true;
      resolve();
    };

    if (child) {
      child.once('exit', done);
    } else {
      setTimeout(done, 100);
    }

    killPid(pid, 'SIGTERM');
    setTimeout(() => killPid(pid, 'SIGKILL'), 4000);
    setTimeout(done, 6000);
  });
}

async function stopAll() {
  const reversed = [...PROCESSES].reverse();
  await Promise.all(reversed.map(stopProcess));
  PROCESSES.length = 0;
  startPromise = null;
  appendLog('service-manager', `\n[stopAll] all managed processes signalled\n`);
}

function getApiBaseUrl() {
  const p = getActivePorts();
  return `http://127.0.0.1:${p.backend}`;
}

module.exports = {
  getActivePorts,
  getApiBaseUrl,
  startAll,
  stopAll,
  getLogsDir,
  isPackaged,
};
