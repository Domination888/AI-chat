const fs=require('fs'),fsp=fs.promises,path=require('path'),crypto=require('crypto')
async function verifyPack(root,publicKey){
 try{
 const raw=await fsp.readFile(path.join(root,'manifest.json')),signature=await fsp.readFile(path.join(root,'manifest.sig'))
 if(!crypto.verify(null,raw,publicKey,signature))throw new Error('模型包签名无效，请使用发行方提供的模型包')
 const manifest=JSON.parse(raw)
 if(manifest.format!=='story-model-pack-v1'||!manifest.files?.length||!manifest.models?.llm?.file||!manifest.models?.embedding?.file)throw new Error('模型包格式不支持')
 const realRoot=await fsp.realpath(root),seen=new Set()
 for(const file of manifest.files){
  if(typeof file.path!=='string'||path.isAbsolute(file.path)||file.path.includes('\\')||file.path.split('/').includes('..')||seen.has(file.path)||!/^[a-f0-9]{64}$/.test(file.sha256)||!Number.isSafeInteger(file.size)||file.size<=0)throw new Error('模型包路径或文件信息不合法')
  seen.add(file.path);const target=path.join(root,file.path),real=await fsp.realpath(target);if(!real.startsWith(realRoot+path.sep)||(await fsp.lstat(target)).isSymbolicLink())throw new Error('模型包不能引用外部文件')
  const stat=await fsp.stat(target);if(!stat.isFile()||stat.size!==file.size)throw new Error('模型包文件大小不一致：'+file.path)
  const hash=crypto.createHash('sha256');for await(const chunk of fs.createReadStream(target))hash.update(chunk)
  if(hash.digest('hex')!==file.sha256)throw new Error('模型包文件损坏：'+file.path)
 }
 for(const model of Object.values(manifest.models)){if(!model.source||!model.license)throw new Error('模型包缺少模型来源或许可');for(const name of ['file','voices','config','tokens','directory'])if(model[name]&&name!=='directory'&&!seen.has(model[name]))throw new Error('模型配置引用未校验的文件')}
 return manifest
 }catch(error){
  if(['ENOENT','ENOTDIR'].includes(error.code))throw new Error('所选目录缺少模型包必需文件。请完整解压发行方提供的离线模型包，再选择包含 manifest.json 和 manifest.sig 的根目录。')
  if(['EACCES','EPERM'].includes(error.code))throw new Error('无法读取模型包目录，请检查目录的读取权限后重试。')
  if(error instanceof SyntaxError)throw new Error('模型包清单格式无效，请重新取得发行方提供的完整模型包。')
  throw error
 }
}
async function installPack(source,target,publicKey){
 const manifest=await verifyPack(source,publicKey),temporary=target+'.incoming-'+crypto.randomUUID()
 await fsp.mkdir(temporary,{recursive:true});try{
  for(const file of manifest.files){const dest=path.join(temporary,file.path);await fsp.mkdir(path.dirname(dest),{recursive:true});await fsp.copyFile(path.join(source,file.path),dest)}
  await fsp.copyFile(path.join(source,'manifest.json'),path.join(temporary,'manifest.json'));await fsp.copyFile(path.join(source,'manifest.sig'),path.join(temporary,'manifest.sig'))
  await verifyPack(temporary,publicKey) // verify the copied bytes before activation
  return {manifest,temporary}
 }catch(e){await fsp.rm(temporary,{recursive:true,force:true});throw e}
}
module.exports={verifyPack,installPack}
