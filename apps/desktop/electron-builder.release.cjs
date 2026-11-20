const mode=process.env.STORY_DISTRIBUTION || 'cloud'
if(!['cloud','offline'].includes(mode))throw new Error('STORY_DISTRIBUTION must be cloud or offline')
const targetPlatform=process.env.STORY_TARGET_PLATFORM || process.platform
const offline=mode==='offline'
module.exports={
 appId:offline?'app.storyspace.offline':'app.storyspace.cloud',productName:offline?'幕间离线版':'幕间',
 directories:{output:`release-${mode}`},
 extraMetadata:{main:'electron/release-main.cjs',storyDistribution:mode,storyServiceUrl:offline?'':process.env.STORY_CLOUD_URL || ''},
 files:['!node_modules/**',`dist-${mode}/**`,'electron/release-main.cjs','electron/release-preload.cjs','electron/character-assets.cjs','electron/platform-location.cjs',...(offline?['electron/offline-runner.cjs','electron/local-service-supervisor.cjs','electron/embedding-args.cjs','electron/model-pack.cjs','electron/model-pack-public.pem']:[])],
 extraResources:[...(process.env.STORY_PERSONAL_SHU==='1'?[{from:'../../artifacts/shu-personal-pack',to:'personal'}]:[]),{from:'../../packaging/release/NOTICE',to:'licenses/NOTICE'},{from:'../../packaging/release/LICENSE',to:'licenses/LICENSE'},...(offline?[{from:`../../packaging/release/staging/${targetPlatform}`,to:'runtime'}]:[])],
 asar:true,icon:'../../packaging/release/icon.png',
 mac:{target:['dmg'],category:'public.app-category.entertainment',hardenedRuntime:true,extendInfo:{NSMicrophoneUsageDescription:'用于你主动开启的语音输入。',NSLocationUsageDescription:'用于现实陪伴中的城市位置与当地时间。',NSLocationWhenInUseUsageDescription:'用于你主动开启的城市定位与当地天气。'}},
 win:{target:['nsis']},nsis:{oneClick:false,allowToChangeInstallationDirectory:true},
 artifactName:`StorySpace-${mode}-\${version}-\${os}-\${arch}.\${ext}`,
}
