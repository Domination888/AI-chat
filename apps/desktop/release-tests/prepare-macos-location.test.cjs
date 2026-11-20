const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const { prepareMacosDevelopmentApp, description, locationKeys } = require('../electron/prepare-macos-location.js');

function fixture(t, { running = false, prepared = false, validSignature = prepared, failAfterSign = false } = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'story-location-test-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const appPath = path.join(directory, 'Electron.app');
  fs.mkdirSync(path.join(appPath, 'Contents'), { recursive: true });
  fs.writeFileSync(path.join(appPath, 'Contents', 'Info.plist'), '{}');
  const keys = new Map([['NSMicrophoneUsageDescription', 'existing microphone notice']]);
  if (prepared) locationKeys.forEach(key => keys.set(key, description));
  const calls = [], signatures = [];
  let signed = validSignature;
  const execute = (binary, args) => {
    calls.push({ binary, args });
    if (binary === '/usr/bin/plutil') {
      const [action, key] = args;
      if (action === '-extract') return { status: keys.has(key) ? 0 : 1, stdout: keys.get(key) || '', stderr: '' };
      if (action === '-replace') { keys.set(key, args[3]); return { status: 0, stdout: '', stderr: '' }; }
    }
    if (binary === '/bin/ps') return { status: 0, stdout: running ? path.join(appPath, 'Contents', 'MacOS', 'Electron') + '\n' : '', stderr: '' };
    if (binary === '/usr/bin/codesign') return { status: signed && !failAfterSign ? 0 : 1, stdout: '', stderr: 'invalid signature' };
    assert.fail('unexpected command ' + binary);
  };
  const sign = async options => { signatures.push(options); signed = true; };
  return { appPath, execute, sign, keys, calls, signatures };
}

test('a newly installed Electron gets privacy descriptions before verified development signing', async t => {
  const f = fixture(t);
  const result = await prepareMacosDevelopmentApp({ platform: 'darwin', ...f });
  assert.equal(result.changed, true);
  locationKeys.forEach(key => assert.equal(f.keys.get(key), description));
  assert.equal(f.keys.get('NSMicrophoneUsageDescription'), 'existing microphone notice');
  assert.equal(f.signatures.length, 1);
  const signed = f.signatures[0];
  assert.equal(signed.identity, '-');
  assert.equal(signed.identityValidation, false);
  assert.equal(signed.preAutoEntitlements, false);
  assert.equal(signed.preEmbedProvisioningProfile, false);
  const options = signed.optionsForFile(f.appPath);
  assert.equal(options.hardenedRuntime, false);
  assert.ok(options.entitlements.includes('com.apple.security.personal-information.location'));
  assert.ok(!options.entitlements.includes('com.apple.security.app-sandbox'));
  assert.equal(f.calls.at(-1).binary, '/usr/bin/codesign');
});

test('repeated launch does not rewrite or resign an already prepared bundle', async t => {
  const f = fixture(t, { prepared: true });
  assert.equal((await prepareMacosDevelopmentApp({ platform: 'darwin', ...f })).changed, false);
  assert.equal(f.signatures.length, 0);
  assert.equal(f.calls.some(c => c.args[0] === '-replace'), false);
  assert.equal(f.calls.some(c => c.binary === '/bin/ps'), false);
});

test('development preparation refuses to change a running Electron bundle', async t => {
  const f = fixture(t, { running: true });
  await assert.rejects(prepareMacosDevelopmentApp({ platform: 'darwin', ...f }), /请先关闭/);
  assert.equal(f.calls.some(c => c.args[0] === '-replace'), false);
  assert.equal(f.signatures.length, 0);
});

test('a broken signature is repaired even when purpose strings are already present', async t => {
  const f = fixture(t, { prepared: true, validSignature: false });
  assert.equal((await prepareMacosDevelopmentApp({ platform: 'darwin', ...f })).changed, true);
  assert.equal(f.signatures.length, 1);
  assert.equal(f.calls.some(c => c.args[0] === '-replace'), false);
});

test('invalid post-sign verification blocks development startup', async t => {
  const f = fixture(t, { failAfterSign: true });
  await assert.rejects(prepareMacosDevelopmentApp({ platform: 'darwin', ...f }), /签名验证失败/);
});

test('non-macOS setup never runs signing or macOS tools', async () => {
  for (const platform of ['linux', 'win32']) {
    const result = await prepareMacosDevelopmentApp({ platform, execute() { assert.fail('macOS tool was called'); }, sign() { assert.fail('signer was called'); } });
    assert.equal(result.prepared, false);
  }
});
