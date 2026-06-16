const path = require('path');
const { app } = require('electron');
const fs = require('fs');

/** 默认端口（打包启动时若冲突会自动顺延，见 port-manager.js） */
const PORTS = {
  redis: 6379,
  mysql: 3306,
  neo4jHttp: 7474,
  neo4jBolt: 7687,
  qdrant: 6333,
  memos: 8000,
  searxng: 8888,
  asr: 9000,
  backend: 8080,
};

function isPackaged() {
  return app.isPackaged;
}

function getResourcesRoot() {
  if (isPackaged()) {
    return path.join(process.resourcesPath, 'runtime');
  }
  return path.join(__dirname, '..', '..');
}

function getBundledConfigRoot() {
  if (isPackaged()) {
    return path.join(process.resourcesPath, 'config');
  }
  return path.join(__dirname, '..', '..', 'config');
}

function getUserDataRoot() {
  return path.join(app.getPath('userData'), 'data');
}

function getUserConfigDir() {
  return path.join(getUserDataRoot(), 'config');
}

function getLogsDir() {
  return path.join(getUserDataRoot(), 'logs');
}

/** 开发：项目 unified-logs；打包：userData/data/logs（与 service-manager 一致） */
function getLogsRoot() {
  if (isPackaged()) {
    return getLogsDir();
  }
  const candidates = [
    path.join(app.getAppPath(), '..', 'unified-logs'),
    path.join(__dirname, '..', '..', 'unified-logs'),
  ];
  for (const dir of candidates) {
    const resolved = path.resolve(dir);
    if (fs.existsSync(resolved)) return resolved;
  }
  return path.resolve(candidates[0]);
}

function getLogsMode() {
  return isPackaged() ? 'packaged' : 'dev';
}

function getRuntimeDir() {
  if (isPackaged()) {
    return path.join(process.resourcesPath, 'runtime');
  }
  return path.join(__dirname, '..', '..', 'packaging', 'staging', 'dev');
}

function binName(name) {
  return process.platform === 'win32' ? `${name}.exe` : name;
}

function runtimeBin(...segments) {
  return path.join(getRuntimeDir(), ...segments);
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function copyDirIfMissing(src, dest) {
  if (!fs.existsSync(src)) return false;
  if (fs.existsSync(dest)) return true;
  fs.cpSync(src, { recursive: true });
  return true;
}

function copyFileIfMissing(src, dest) {
  if (!fs.existsSync(src)) return false;
  ensureDir(path.dirname(dest));
  if (fs.existsSync(dest)) return true;
  fs.copyFileSync(src, dest);
  return true;
}

function seedUserConfig() {
  const configDir = getUserConfigDir();
  ensureDir(configDir);

  const bundled = getBundledConfigRoot();
  copyFileIfMissing(path.join(bundled, 'runtime-config.json'), path.join(configDir, 'runtime-config.json'));
  copyFileIfMissing(path.join(bundled, 'mcp-servers.json'), path.join(configDir, 'mcp-servers.json'));

  const skillsSrc = path.join(bundled, 'skills');
  const skillsDest = path.join(configDir, 'skills');
  if (fs.existsSync(skillsSrc) && !fs.existsSync(skillsDest)) {
    fs.cpSync(skillsSrc, skillsDest, { recursive: true });
  }

  return configDir;
}

function setupMarkerPath() {
  return path.join(getUserDataRoot(), '.setup-complete');
}

function isFirstRun() {
  return !fs.existsSync(setupMarkerPath());
}

function markSetupComplete() {
  ensureDir(getUserDataRoot());
  fs.writeFileSync(setupMarkerPath(), new Date().toISOString());
}

module.exports = {
  PORTS,
  isPackaged,
  getResourcesRoot,
  getBundledConfigRoot,
  getUserDataRoot,
  getUserConfigDir,
  getLogsDir,
  getLogsRoot,
  getLogsMode,
  getRuntimeDir,
  binName,
  runtimeBin,
  ensureDir,
  seedUserConfig,
  setupMarkerPath,
  isFirstRun,
  markSetupComplete,
};
