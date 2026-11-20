import test from 'node:test'
import assert from 'node:assert/strict'
import {readTriggerForm,writeTriggerForm,triggerMode} from '../src/release/skillTriggerForm.mjs'
test('new timer needs no JSON and paused timer stays schedule',()=>{const f={mode:'schedule',enabled:true,time:'09:30',zone:'Asia/Shanghai'};const v=writeTriggerForm({}, {}, f,[]);assert.equal(v.schedule.hour,9);assert.equal(v.proactive.enabled,true);assert.equal(writeTriggerForm(v,v,{...f,enabled:false},[]).triggerMode,'schedule')})
test('legacy cron and unknown fields survive editing',()=>{const s={schedule:{cron:'0 0 9 * * *',extra:42},proactive:{promptTemplate:'hello',extra:true}};const v=writeTriggerForm(s,s,readTriggerForm(s),[]);assert.equal(v.schedule.cron,s.schedule.cron);assert.equal(v.schedule.extra,42);assert.equal(v.proactive.extra,true)})
test('switching away disables schedule and proactive',()=>{const s={schedule:{enabled:true},proactive:{enabled:true}};const v=writeTriggerForm(s,s,{mode:'always'},[]);assert.equal(v.schedule.enabled,false);assert.equal(v.proactive.enabled,false)})
test('legacy modes and new explicit choice',()=>{assert.equal(triggerMode({keywords:['hello']}),'keywords');assert.equal(triggerMode({}),'always');assert.equal(triggerMode({schedule:{enabled:false}}),'schedule');assert.equal(readTriggerForm({},true).mode,'keywords');assert.throws(()=>writeTriggerForm({}, {},{mode:'keywords'},[]),/关键词/)})

test('component selection reacts custom → built-in → custom and save retains paused master switch',async()=>{
  const {readFile}=await import('node:fs/promises')
  const {ref,computed}=await import('vue')
  const {parseAdvanced,advancedExample}=await import('../src/release/capabilityConfig.mjs')
  const source=await readFile(new URL('../src/release/CapabilityPanel.vue',import.meta.url),'utf8')
  const script=source.split('<script setup>')[1].split('</script>')[0]
  const declarations=script.match(/const triggerForm=ref\(\{\}\),builtinSkill=computed\([^\n]+/)[0]
  const edit=script.match(/function editSkill\([^\n]+/)[0]
  const save=script.match(/async function saveSkill\([^\n]+/)[0]
  let posted
  const fixture=new Function('ref','computed','readTriggerForm','writeTriggerForm','parseAdvanced','json',`${declarations};const editingSkillName=ref(''),creatingSkill=ref(false),skillForm=ref(null),keywords=ref(''),advanced=ref('{}'),skills=ref([]),notice=ref('');async function run(fn){await fn(json)};${edit};${save};return {editSkill,saveSkill,builtinSkill,creatingSkill,skills,skillForm,advanced}`)(ref,computed,readTriggerForm,writeTriggerForm,parseAdvanced,async(path,value)=>{if(value)posted=value;return []})
  fixture.editSkill({name:'custom',keywords:['hi'],enabled:true});assert.equal(fixture.builtinSkill.value,false)
  fixture.editSkill({name:'ai-daily-juya',enabled:false,schedule:{enabled:true,cron:'0 0 9 * * *'}});assert.equal(fixture.builtinSkill.value,true)
  await fixture.saveSkill();assert.equal(posted.enabled,false);assert.equal(posted.schedule.cron,'0 0 9 * * *');assert.equal(posted.triggerMode,undefined)
  fixture.editSkill({name:'custom',keywords:['hi'],enabled:true});assert.equal(fixture.builtinSkill.value,false)
  fixture.skills.value=[{name:'custom',enabled:false}];fixture.skillForm.value.name='accidental-duplicate';await fixture.saveSkill();assert.equal(posted.name,'custom');assert.equal(posted.enabled,false);assert.equal(posted.triggerMode,'keywords')
  fixture.editSkill({name:'new-skill',keywords:['hello'],enabled:true},true);assert.equal(fixture.creatingSkill.value,true);fixture.skillForm.value.name='new-reminder';await fixture.saveSkill();assert.equal(posted.name,'new-reminder')
  assert.match(source,/:readonly="!creatingSkill"/)
  assert.match(source,/@click="advanced=advancedExample;readAdvancedTrigger\(\)"/)
})
