const fs=require('node:fs'),path=require('node:path')
const MIME={'.js':'text/javascript','.json':'application/json','.png':'image/png','.jpg':'image/jpeg','.moc3':'application/octet-stream'}
function assetPath(root,url){
 const u=new URL(url),name=decodeURIComponent(u.pathname),base=u.host==='core'?path.join(root,'live2d-core'):u.host==='shu'?path.join(root,'live2d','shu'):u.host==='manifest'?path.join(root,'characters'):null
 if(!base)throw new Error('Unknown character asset')
 const target=path.resolve(base,'.'+name)
 if(!target.startsWith(base+path.sep)||!MIME[path.extname(target)]||path.extname(target)==='.js'&&(u.host!=='core'||path.basename(target)!=='live2dcubismcore.min.js'))throw new Error('Invalid character asset')
 const real=fs.realpathSync(target),realBase=fs.realpathSync(base)
 if(!real.startsWith(realBase+path.sep))throw new Error('Character asset escapes its folder')
 return {path:real,type:MIME[path.extname(target)]}
}
function characterPack(personaRoot){
 const root=path.join(personaRoot,'shu'),card=JSON.parse(fs.readFileSync(path.join(root,'persona_card.json'),'utf8')),lore={}
 function scan(dir){for(const entry of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,entry.name);if(entry.isDirectory())scan(p);else if(entry.isFile()&&entry.name.endsWith('.md')){const value=fs.readFileSync(p,'utf8');if(value.length<200000)lore[path.relative(root,p)]=value}}}
 if(fs.existsSync(path.join(root,'lore')))scan(path.join(root,'lore'))
 const greeting=fs.existsSync(path.join(root,'greetings.txt'))?fs.readFileSync(path.join(root,'greetings.txt'),'utf8').split('\n').find(s=>s.trim()&&!s.startsWith('#')):''
 const result={card,lore,greeting};if(JSON.stringify(result).length>2*1024*1024)throw new Error('角色资料超过 2MiB');return result
}
module.exports={assetPath,characterPack}
