const { existsSync } = require('fs');
const { join } = require('path');
const { spawnSync } = require('child_process');

const description = '幕间仅在你开启城市定位后使用设备位置，为现实陪伴提供当地时间、天气与节气。';
const locationKeys = ['NSLocationUsageDescription', 'NSLocationWhenInUseUsageDescription'];

// This prepares only the local Electron development bundle, before it starts.
// Packaged apps retain electron-builder's signing and privacy configuration.
async function prepareMacosDevelopmentApp({
  platform = process.platform,
  appPath = join(__dirname, '..', 'node_modules', 'electron', 'dist', 'Electron.app'),
  execute = spawnSync,
  sign = options => require('@electron/osx-sign').signAsync(options),
} = {}) {
  if (platform !== 'darwin') return { prepared: false, reason: 'not-macos' };
  const plist = join(appPath, 'Contents', 'Info.plist');
  if (!existsSync(plist)) return { prepared: false, reason: 'electron-not-installed' };
  const run = (binary, args) => execute(binary, args, { encoding: 'utf8' });
  const changedKeys = locationKeys.filter(key => {
    const result = run('/usr/bin/plutil', ['-extract', key, 'raw', '-o', '-', plist]);
    return result.status !== 0 || result.stdout.trim() !== description;
  });
  const signature = () => run('/usr/bin/codesign', ['--verify', '--deep', '--strict', appPath]);
  if (!changedKeys.length && signature().status === 0) return { prepared: true, changed: false };

  // Replacing a running bundle can invalidate its loaded code and its OS identity.
  const processes = run('/bin/ps', ['-axo', 'comm=']);
  if (processes.status !== 0) throw new Error('无法检查开发客户端是否正在运行，请关闭幕间后重试');
  const executable = join(appPath, 'Contents', 'MacOS', 'Electron');
  if (processes.stdout.split('\n').some(value => value.trim() === executable))
    throw new Error('请先关闭正在运行的幕间开发客户端，再准备定位权限');

  for (const key of changedKeys) {
    const result = run('/usr/bin/plutil', ['-replace', key, '-string', description, plist]);
    if (result.status !== 0) throw new Error(`无法写入开发客户端的 ${key}: ${(result.stderr || '').trim()}`);
  }
  // The official signer signs nested code inside-out and verifies the result.
  // An explicit ad-hoc identity avoids looking up certificates or provisioning
  // profiles. It does not grant macOS privacy consent; normal OS prompts remain.
  await sign({
    app: appPath,
    platform: 'darwin',
    type: 'development',
    identity: '-',
    identityValidation: false,
    preAutoEntitlements: false,
    preEmbedProvisioningProfile: false,
    optionsForFile: () => ({
      // Stock development Electron does not enable Hardened Runtime either.
      hardenedRuntime: false,
      entitlements: [
        'com.apple.security.cs.allow-jit',
        'com.apple.security.device.audio-input',
        'com.apple.security.personal-information.location',
      ],
    }),
  });
  const verified = signature();
  if (verified.status !== 0) throw new Error(`开发客户端签名验证失败: ${(verified.stderr || '').trim()}`);
  return { prepared: true, changed: true, changedKeys };
}

if (require.main === module) prepareMacosDevelopmentApp().catch(error => {
  console.error(`[location] ${error.message}`);
  process.exitCode = 1;
});

module.exports = { prepareMacosDevelopmentApp, description, locationKeys };
