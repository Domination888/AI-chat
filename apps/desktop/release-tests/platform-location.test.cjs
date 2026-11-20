const { test } = require('node:test');
const assert = require('node:assert/strict');
const { configurePlatformLocation, storyPermissionAllowed } = require('../electron/platform-location.cjs');

test('macOS selects the current native provider without losing other feature settings', () => {
  let value = 'OtherFeature:setting/value,MacCoreLocationBackend,LocationProviderManager:LocationProviderManagerMode/NetworkOnly';
  const commandLine = {
    getSwitchValue: name => { assert.equal(name, 'enable-features'); return value; },
    appendSwitch: (name, next) => { assert.equal(name, 'enable-features'); value = next; },
  };
  configurePlatformLocation(commandLine, 'darwin');
  assert.equal(value, 'OtherFeature:setting/value,LocationProviderManager:LocationProviderManagerMode/PlatformOnly');
  configurePlatformLocation(commandLine, 'darwin');
  assert.equal(value.split('LocationProviderManager:').length, 2, 'repeated preparation must not add duplicate providers');
});

test('other platforms keep their existing geolocation configuration', () => {
  for (const platform of ['win32', 'linux']) configurePlatformLocation({
    getSwitchValue() { assert.fail('must not read platform settings'); },
    appendSwitch() { assert.fail('must not mutate platform settings'); },
  }, platform);
});

test('both normal and approximate geolocation work only in the trusted main frame', () => {
  const contents = { getURL: () => 'app://story/index.html' };
  const main = { isMainFrame: true, requestingUrl: 'app://story/index.html' };
  for (const permission of ['geolocation', 'geolocation-approximate', 'media']) {
    assert.equal(storyPermissionAllowed(contents, contents, permission, main, 'app://story'), true);
    assert.equal(storyPermissionAllowed(contents, contents, permission, { ...main, isMainFrame: false }, 'app://story'), false);
    assert.equal(storyPermissionAllowed(contents, contents, permission, main, 'https://outside.example'), false);
    assert.equal(storyPermissionAllowed(contents, contents, permission, { ...main, requestingUrl: 'app://story.evil/index.html' }), false);
    assert.equal(storyPermissionAllowed({ ...contents }, contents, permission, main), false);
    assert.equal(storyPermissionAllowed(null, contents, permission, main), false);
    assert.equal(storyPermissionAllowed(contents, contents, permission, undefined), false);
  }
  assert.equal(storyPermissionAllowed(contents, contents, 'notifications', main), false);
  for (const url of ['https://story/index.html', 'app://story.evil/index.html', 'app://user@story/index.html']) {
    const untrusted = { getURL: () => url };
    assert.equal(storyPermissionAllowed(untrusted, untrusted, 'geolocation', main), false);
  }
});
