#!/usr/bin/env node
// One-time operator tool. Never included in the desktop or HTTP migration surface.
const fs=require('node:fs'),path=require('node:path')
const yaml=require('../../apps/desktop/node_modules/js-yaml')
const {legacyCapabilities}=require('./read-legacy-capabilities.cjs')
const root=path.resolve(__dirname,'../..')
function plan(configRoot,projectRoot=root){
 const input=legacyCapabilities(configRoot,projectRoot)
 const skills=input.skills.map(item=>{
  const text=item.markdown,end=text.indexOf('\n---',3)
  if(!text.startsWith('---')||end<0||text.length>20000)throw new Error('Invalid skill frontmatter: '+item.name)
  const value=yaml.load(text.slice(3,end),{schema:yaml.JSON_SCHEMA})
  if(!value||typeof value!=='object'||Array.isArray(value)||!/^[a-zA-Z0-9_-]{1,80}$/.test(value.name))throw new Error('Invalid skill name: '+item.name)
  return {...value,instructions:text.slice(end+4).trim()}
 })
 const servers=input.servers.map(value=>({...value,allowModelCalls:false}))
 return {skills,servers}
}
async function main(){
 const args=process.argv.slice(2),value=name=>{const i=args.indexOf(name);return i<0?undefined:args[i+1]}
 const config=path.resolve(value('--config')||path.join(root,'config')),prepared=plan(config,value('--project-root')||root)
 const summary={mode:'dry-run',skills:prepared.skills.map(s=>s.name),servers:prepared.servers.map(s=>s.id),existingEntries:'preserved',automaticToolAuthorization:false}
 if(!args.includes('--apply')){console.log(JSON.stringify(summary,null,2));return}
 const base=new URL(value('--base')||''),reportPath=value('--report'),backup=value('--verified-backup')
 if(base.protocol!=='http:'||base.hostname!=='127.0.0.1'||base.username||base.password||base.pathname!=='/'||base.search||base.hash)throw new Error('Use an isolated offline backend on http://127.0.0.1:<port>')
 if(!backup||!fs.statSync(backup).isFile()||!reportPath||fs.existsSync(reportPath))throw new Error('An existing consistent database backup and a new private report path are required')
 const token=process.env.STORY_MIGRATION_TOKEN;if(!token)throw new Error('Set STORY_MIGRATION_TOKEN in the environment')
 async function request(endpoint,body){const response=await fetch(base.origin+endpoint,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});if(!response.ok)throw new Error('Migration request failed ('+response.status+') at '+endpoint);const text=await response.text();return text?JSON.parse(text):null}
 const me=await request('/api/v1/me');if(me.mode!=='offline'||me.user?.id!==1)throw new Error('Migration target must be offline user 1')
 const existingSkills=new Set((await request('/api/skills')).map(s=>s.name)),existingServers=new Set((await request('/api/mcp/servers')).map(s=>s.id))
 // Built-in defaults are virtual, not necessarily persisted. A caller may explicitly
 // migrate the two legacy built-in manifests after checking their current settings.
 if(args.includes('--include-builtin-defaults'))for(const name of ['weather-lookup','ai-daily-juya'])existingSkills.delete(name)
 const report={...summary,mode:'applied-to-isolated-backend',backup:path.resolve(backup),imported:[],skipped:[]}
 for(const [items,existing,endpoint,key] of [[prepared.skills,existingSkills,'/api/skills','name'],[prepared.servers,existingServers,'/api/mcp/servers','id']])for(const item of items){if(existing.has(item[key])){report.skipped.push(item[key]);continue}await request(endpoint,item);report.imported.push(item[key])}
 fs.writeFileSync(reportPath,JSON.stringify(report,null,2)+'\n',{mode:0o600,flag:'wx'});console.log(JSON.stringify({mode:report.mode,imported:report.imported,skipped:report.skipped,report:reportPath}))
}
module.exports={plan}
if(require.main===module)main().catch(error=>{console.error(error.message);process.exitCode=1})
