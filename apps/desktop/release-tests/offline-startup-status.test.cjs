const {test}=require('node:test')
const assert=require('node:assert/strict')
const {OfflineRunner}=require('../electron/offline-runner.cjs')

test('a crashed model fails preparation immediately with its name and a recovery action',async()=>{
  const runner=new OfflineRunner({resources:'.',data:'.'})
  runner.generation=1
  const ready=await runner.monitor([{name:'记忆模型',port:1,child:{exitCode:null,signalCode:'SIGSEGV'}}],1)
  assert.equal(ready,false)
  assert.equal(runner.modelState,'error')
  assert.match(runner.modelError,/记忆模型未能启动/)
  assert.match(runner.modelError,/重新导入完整模型包/)
})

test('all ready services publish completion rather than remaining in preparation',async()=>{
  const previous=global.fetch
  try{
    const runner=new OfflineRunner({resources:'.',data:'.'});runner.generation=1
    global.fetch=async()=>({ok:true})
    const ready=await runner.monitor([{name:'聊天模型',port:1,child:{exitCode:null}}],1)
    assert.equal(ready,true)
    assert.equal(runner.modelState,'ready')
    assert.match(runner.modelProgress,/已就绪/)
  }finally{global.fetch=previous}
})
