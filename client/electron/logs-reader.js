const fs = require('fs');
const path = require('path');
const { getLogsRoot, getLogsMode } = require('./paths');

const LABELS = {
  'backend/app.log': '后端',
  'backend/latency.log': '延迟',
  'backend/prompt.log': 'Prompt',
  'asr/app.log': 'ASR',
  'frontend/app.log': '前端',
  'client/app.log': '客户端',
  'backend.log': '后端',
  'asr.log': 'ASR',
  'mysql.log': 'MySQL',
  'redis.log': 'Redis',
  'neo4j.log': 'Neo4j',
  'qdrant.log': 'Qdrant',
  'memos.log': 'MemOS',
  'searxng.log': 'SearXNG',
  'service-manager.log': '服务管理',
};

function labelFor(relativePath) {
  if (LABELS[relativePath]) return LABELS[relativePath];
  const base = path.basename(relativePath, '.log');
  const dir = path.dirname(relativePath);
  if (dir === '.') return base;
  return `${dir}/${base}`;
}

function walkLogFiles(dir, root, out) {
  if (!fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walkLogFiles(full, root, out);
      continue;
    }
    if (!entry.name.endsWith('.log')) continue;
    const relative = path.relative(root, full).split(path.sep).join('/');
    out.push(relative);
  }
}

function listLogSources() {
  const root = path.resolve(getLogsRoot());
  const found = [];
  walkLogFiles(root, root, found);
  found.sort((a, b) => {
    const order = Object.keys(LABELS);
    const ai = order.indexOf(a);
    const bi = order.indexOf(b);
    if (ai >= 0 && bi >= 0) return ai - bi;
    if (ai >= 0) return -1;
    if (bi >= 0) return 1;
    return a.localeCompare(b);
  });
  return {
    root,
    mode: getLogsMode(),
    exists: fs.existsSync(root),
    sources: found.map((id) => ({
      id,
      label: labelFor(id),
      exists: true,
    })),
  };
}

function readLogTail(relativePath, maxLines = 400, maxBytes = 512 * 1024) {
  const root = path.resolve(getLogsRoot());
  const filePath = path.resolve(root, relativePath);
  if (filePath !== root && !filePath.startsWith(root + path.sep)) {
    throw new Error('非法日志路径');
  }
  if (!fs.existsSync(filePath)) {
    return { content: '', exists: false, size: 0, truncated: false };
  }

  const stat = fs.statSync(filePath);
  if (stat.size === 0) {
    return { content: '(空文件)', exists: true, size: 0, truncated: false };
  }

  const readBytes = Math.min(stat.size, maxBytes);
  const fd = fs.openSync(filePath, 'r');
  try {
    const buffer = Buffer.alloc(readBytes);
    fs.readSync(fd, buffer, 0, readBytes, stat.size - readBytes);
    let text = buffer.toString('utf8');
    const truncated = stat.size > maxBytes;
    if (truncated) {
      const firstNewline = text.indexOf('\n');
      text = firstNewline >= 0
        ? `...(仅显示末尾 ${formatBytes(readBytes)})\n${text.slice(firstNewline + 1)}`
        : `...(仅显示末尾 ${formatBytes(readBytes)})\n${text}`;
    }
    const lines = text.split('\n');
    const tail = lines.slice(-maxLines).join('\n');
    return { content: tail, exists: true, size: stat.size, truncated };
  } finally {
    fs.closeSync(fd);
  }
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

module.exports = {
  listLogSources,
  readLogTail,
  formatBytes,
};
