const fs = require('fs');
const path = require('path');
const net = require('net');
const { getUserDataRoot, getUserConfigDir } = require('./paths');

/** 默认端口（冲突时从此值起顺延，不影响本机其它程序） */
const DEFAULT_PORTS = {
  redis: 6379,
  mysql: 3306,
  neo4jHttp: 7474,
  neo4jBolt: 7687,
  qdrant: 6333,
  qdrantGrpc: 6334,
  memos: 8000,
  searxng: 8888,
  asr: 9000,
  backend: 8080,
};

const MAX_OFFSET = 200;
let activePorts = { ...DEFAULT_PORTS };

function portsFile() {
  return path.join(getUserDataRoot(), 'service-ports.json');
}

function isPortFree(port) {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.unref();
    server.on('error', () => resolve(false));
    server.listen({ port, host: '127.0.0.1' }, () => {
      server.close(() => resolve(true));
    });
  });
}

async function findFreePort(preferred, reserved = new Set()) {
  for (let offset = 0; offset <= MAX_OFFSET; offset++) {
    const port = preferred + offset;
    if (reserved.has(port)) continue;
    // eslint-disable-next-line no-await-in-loop
    if (await isPortFree(port)) {
      reserved.add(port);
      return port;
    }
  }
  throw new Error(`127.0.0.1 上找不到可用端口（起始于 ${preferred}）`);
}

async function allocateAllPorts() {
  const reserved = new Set();
  const next = {};
  for (const [key, preferred] of Object.entries(DEFAULT_PORTS)) {
    next[key] = await findFreePort(preferred, reserved);
  }
  activePorts = next;
  savePorts();
  return activePorts;
}

function savePorts() {
  fs.mkdirSync(getUserDataRoot(), { recursive: true });
  fs.writeFileSync(
    portsFile(),
    JSON.stringify({ ports: activePorts, updatedAt: new Date().toISOString() }, null, 2)
  );
}

function getActivePorts() {
  return { ...activePorts };
}

function patchSearchRuntimeUrl() {
  const file = path.join(getUserConfigDir(), 'runtime-config.json');
  if (!fs.existsSync(file)) return;
  try {
    const config = JSON.parse(fs.readFileSync(file, 'utf8'));
    const url = `http://127.0.0.1:${activePorts.searxng}`;
    config.search = config.search || {};
    if (config.search.searxngUrl !== url) {
      config.search.searxngUrl = url;
      fs.writeFileSync(file, JSON.stringify(config, null, 2));
    }
  } catch (e) {
    console.warn('[port-manager] patch Search-RAG SearXNG URL failed:', e.message);
  }
}

function portEnvExtras() {
  const p = activePorts;
  return {
    SERVER_PORT: String(p.backend),
    SPRING_DATA_REDIS_PORT: String(p.redis),
    SPRING_DATASOURCE_URL:
      `jdbc:mysql://127.0.0.1:${p.mysql}/ai_chat?useSSL=false&serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci&allowPublicKeyRetrieval=true`,
    AI_CHAT_MEMOS_URL: `http://127.0.0.1:${p.memos}`,
    AI_CHAT_ASR_URL: `http://127.0.0.1:${p.asr}/v1/audio/transcriptions`,
    AI_CHAT_BACKEND_URL: `http://127.0.0.1:${p.backend}`,
    SEARCH_RESEARCH_SEARXNG_URL: `http://127.0.0.1:${p.searxng}`,
    REDIS_PORT: String(p.redis),
    MYSQL_PORT: String(p.mysql),
    MEMOS_PORT: String(p.memos),
    SEARXNG_PORT: String(p.searxng),
    SENSEVOICE_PORT: String(p.asr),
    QDRANT__SERVICE__HTTP_PORT: String(p.qdrant),
    QDRANT__SERVICE__GRPC_PORT: String(p.qdrantGrpc),
    NEO4J_server_http_listen__address: `127.0.0.1:${p.neo4jHttp}`,
    NEO4J_server_bolt_listen__address: `127.0.0.1:${p.neo4jBolt}`,
    NEO4J_URI: `bolt://127.0.0.1:${p.neo4jBolt}`,
    QDRANT_PORT: String(p.qdrant),
  };
}

module.exports = {
  DEFAULT_PORTS,
  allocateAllPorts,
  getActivePorts,
  patchSearchRuntimeUrl,
  portEnvExtras,
  isPortFree,
};
