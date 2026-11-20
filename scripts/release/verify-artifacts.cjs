// Verify distribution boundaries against the actual packaged archives.
const fs=require('fs'),path=require('path'),assert=require('assert/strict'),crypto=require('crypto')
const asar=require('../../apps/desktop/node_modules/@electron/asar'),root=path.resolve(__dirname,'../..')
const banned=/live2d|cubism|runtime-config\.json|memos|mysql|redis|AdminApp|model-pack-public/i
const candidates=[['cloud','幕间.app'],['offline','幕间离线版.app']]
for(const [mode,app] of candidates){
 const resources=path.join(root,`apps/desktop/release-${mode}/mac-arm64`,app,'Contents/Resources'),archive=path.join(resources,'app.asar'),entries=asar.listPackage(archive)
 assert.ok(entries.some(n=>n.endsWith('/electron/release-main.cjs')))
 assert.ok(!entries.some(n=>n.includes('/node_modules/')))
 assert.ok(entries.every(n=>!n.includes('/dist/') && !n.includes('/characters/') && !n.includes('legacy/services/')))
 assert.ok(!entries.some(n=>/live2d|cubism|runtime-config\.json|AdminApp/i.test(n)))
 if(mode==='cloud'){assert.ok(!fs.existsSync(path.join(resources,'runtime')));assert.ok(!entries.some(n=>banned.test(n)))}
 else assert.ok(fs.existsSync(path.join(resources,'runtime/story-runtime.jar')))
 assert.ok(fs.existsSync(path.join(resources,'licenses/NOTICE')))
 console.log(mode,'distribution boundary: PASS')
}
const source=path.join(root,'artifacts/open-source-local')
for(const file of ['apps/server/src/main/java/org/example/aichat/release/cloud','apps/server/src/main/resources/static/admin','apps/desktop/src/release/AdminApp.vue'])assert.ok(!fs.existsSync(path.join(source,file)))
console.log('local source boundary: PASS')
const files=['artifacts/StorySpace-commercial-admin.tar.gz','artifacts/StorySpace-local-source.tar.gz','artifacts/story-cloud-runtime.jar','apps/desktop/release-cloud/StorySpace-cloud-1.0.0-mac-arm64.dmg','apps/desktop/release-offline/StorySpace-offline-1.0.0-mac-arm64.dmg','apps/desktop/release-cloud/StorySpace-cloud-1.0.0-win-x64.exe']
;(async()=>{const result=[];for(const file of files){const absolute=path.join(root,file);if(!fs.existsSync(absolute))continue;const hash=crypto.createHash('sha256');for await(const chunk of fs.createReadStream(absolute))hash.update(chunk);result.push({file,bytes:fs.statSync(absolute).size,sha256:hash.digest('hex')})}fs.writeFileSync(path.join(root,'artifacts/release-manifest.json'),JSON.stringify({builtAt:new Date().toISOString(),files:result},null,2));console.log('SHA-256 manifest: artifacts/release-manifest.json')})().catch(e=>{console.error(e.message);process.exitCode=1})
