// Chromium 152 replaced MacCoreLocationBackend with LocationProviderManager.
// Use the native provider so a Chromium network fallback is not reported as
// successful device location. macOS still controls and prompts for OS consent.
const nativeMacFeature = 'LocationProviderManager:LocationProviderManagerMode/PlatformOnly';
const featureName = value => value.split(/[<:]/, 1)[0];

function configurePlatformLocation(commandLine, platform = process.platform) {
  if (platform !== 'darwin') return;
  const features = commandLine.getSwitchValue('enable-features').split(',').map(value => value.trim()).filter(Boolean);
  const retained = features.filter(value => !['MacCoreLocationBackend', 'LocationProviderManager'].includes(featureName(value)));
  commandLine.appendSwitch('enable-features', [...retained, nativeMacFeature].join(','));
}

function isStoryUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'app:' && url.hostname === 'story' && !url.port && !url.username && !url.password;
  } catch { return false; }
}

function storyPermissionAllowed(contents, mainContents, permission, details, requestingOrigin) {
  if (!mainContents || contents !== mainContents || details?.isMainFrame !== true) return false;
  if (!['media', 'geolocation', 'geolocation-approximate'].includes(permission)) return false;
  return isStoryUrl(contents.getURL())
    && (!details.requestingUrl || isStoryUrl(details.requestingUrl))
    && (!requestingOrigin || isStoryUrl(requestingOrigin));
}

module.exports = { configurePlatformLocation, storyPermissionAllowed, nativeMacFeature };
