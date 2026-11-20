import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
test('audio mouth writes after motion at actual installed render boundary, and stop removes hook',()=>{
 const text=fs.readFileSync(new URL('../node_modules/pixi-live2d-display-lipsyncpatch/dist/cubism4.js',import.meta.url),'utf8')
 const start=text.lastIndexOf('    update(dt, now) {',text.indexOf('this.motionManager.mouthSync()'));const method=text.slice(start,text.indexOf('    updateFocus()',start))
 const source=fs.readFileSync(new URL('../src/live2d/live2d-controller.js',import.meta.url),'utf8').replace(/^import .*$/m,'').replaceAll('export ','')
 const c=vm.createContext({console:{log(){},warn(){}},setTimeout,clearTimeout,Uint8Array})
 vm.runInContext(`${source};this.Controller=Live2DController;class Base{update(){}};class Internal extends Base{${method}};this.Internal=Internal`,c)
 const internal=new c.Internal();const hooks=new Map();let mouth=0,rendered=0
 internal.on=(event,fn)=>hooks.set(event,fn);internal.off=(event,fn)=>{if(hooks.get(event)===fn)hooks.delete(event)};internal.emit=event=>hooks.get(event)?.()
 internal.motionManager={update(){mouth=0;return true}};internal.updateFocus=()=>{};internal.updateNaturalMovements=()=>{}
 internal.coreModel={getParameterIndex:()=>0,setParameterValueByIndex:(i,v)=>mouth=v,saveParameters(){},loadParameters(){},update(){rendered=mouth}}
 const controller=new c.Controller();controller.model={internalModel:internal}
 const analyser={fftSize:0,frequencyBinCount:128,getByteFrequencyData:data=>data.fill(25),disconnect(){}}
 const audioSource={connect(){},disconnect(){}};controller.startLipSync({createAnalyser:()=>analyser},audioSource)
 internal.update(16,1000);assert.ok(rendered>0);controller.stopLipSync();assert.equal(hooks.size,0);internal.update(16,1016);assert.equal(rendered,0)
})
