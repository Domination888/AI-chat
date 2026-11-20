// One-time maintenance reader; excluded from the desktop runtime.
const fs=require('node:fs'),path=require('node:path')
function legacyCapabilities(configRoot,projectRoot){
 const skills=[],servers=[]
 const dir=path.join(configRoot,'skills');if(fs.existsSync(dir))for(const entry of fs.readdirSync(dir,{withFileTypes:true})){const file=path.join(dir,entry.name,'SKILL.md');if(entry.isDirectory()&&fs.existsSync(file)&&fs.statSync(file).size<80000)skills.push({name:entry.name,markdown:fs.readFileSync(file,'utf8')})}
 const file=path.join(configRoot,'mcp-servers.json');if(fs.existsSync(file)){const raw=JSON.parse(fs.readFileSync(file,'utf8'));for(const value of Array.isArray(raw)?raw:raw.servers||[]){const copy={...value};if(Array.isArray(copy.command))copy.command=copy.command.map(v=>typeof v==='string'&&v.endsWith('.jar')&&!path.isAbsolute(v)?path.resolve(projectRoot,v):v);copy.allowModelCalls=!!copy.enabled;servers.push(copy)}}
 return {skills,servers}
}
module.exports={legacyCapabilities}
