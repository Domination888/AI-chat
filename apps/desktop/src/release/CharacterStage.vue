<template>
  <aside v-if="enabled" class="character-stage" aria-label="黍 Live2D"><header class="character-heading"><strong>黍</strong><button @click="$emit('close')" aria-label="收起角色形象" title="收起角色形象">×</button></header><canvas ref="canvas" @click="interact" aria-label="与黍互动"></canvas><p v-if="error" class="release-error" role="alert">{{ error }}</p><p v-else-if="!ready">正在加载角色形象…</p><div class="character-controls"><label>动作<select :disabled="!ready" v-model="motion"><option value="">选择动作</option><option v-for="{item:m,index:i} in selectableMotions" :value="String(i)" :key="m.name">{{ m.name }}</option></select></label><button @click="playMotion" :disabled="!ready || actionBusy || motion===''">播放</button><label>表情<select :disabled="!ready" v-model="expression"><option value="">自然表情</option><option v-for="e in allLive2dExpressions" :key="e">{{ e }}</option></select></label><button @click="playExpression" :disabled="!ready || actionBusy">切换</button></div><p v-if="actionStatus" role="status" aria-live="polite">{{ actionStatus }}</p></aside>
</template>
<script setup>
import {ref,onMounted,onBeforeUnmount,watch,nextTick} from 'vue'
import {live2dController} from '../live2d/live2d-controller.js'
import {allLive2dMotions,allLive2dExpressions} from '../live2d/live2d-options.js'
// Keep original indices because playback and compatibility mappings use the full list.
const selectableMotions=allLive2dMotions.map((item,index)=>({item,index})).filter(({item})=>!(item.group==='Tap#4'&&item.index===4))
const props=defineProps({enabled:Boolean})
const emit=defineEmits(['interact','close','ready','failure'])
const canvas=ref(null),error=ref(''),ready=ref(false),motion=ref(''),expression=ref(''),actionStatus=ref(''),actionBusy=ref(false)
let app=null,model=null,version=0
async function core(){if(window.Live2DCubismCore)return;await new Promise((resolve,reject)=>{const script=document.createElement('script');script.src='character://core/live2dcubismcore.min.js';script.onload=resolve;script.onerror=()=>reject(new Error('Live2D Core 未能加载，请检查黍素材包'));document.head.appendChild(script)})}
async function mount(){const current=++version;await nextTick();if(!props.enabled||!canvas.value)return;error.value='';try{
 await core();const PIXI=await import('pixi.js');await import('@pixi/unsafe-eval');window.PIXI=PIXI;const {Live2DModel,config}=await import('pixi-live2d-display-lipsyncpatch/cubism4');config.sound=false;if(current!==version)return
 app=new PIXI.Application({view:canvas.value,width:340,height:480,backgroundAlpha:0,antialias:true,resolution:Math.min(window.devicePixelRatio||1,2),autoDensity:true});
 const loaded=await Live2DModel.from('character://shu/黍.model3.json',{autoInteract:false});if(current!==version){loaded.destroy();return}model=loaded;model.anchor.set(.5,.5);model.scale.set(Math.min(320/model.width,430/model.height));model.position.set(170,240);app.stage.addChild(model);live2dController.setModel(model);ready.value=true;emit('ready')
}catch(e){error.value=e.message;emit('failure',e.message)}}
function clear(){version++;live2dController.clearModel();app?.destroy(false,{children:true,texture:true,baseTexture:true});app=null;model=null;ready.value=false;actionStatus.value='';actionBusy.value=false}
async function performAction(label, action, success){
 const current=version;actionBusy.value=true;actionStatus.value=`正在${label}…`
 try{const ok=await action();if(current===version)actionStatus.value=ok?success:`${label}未成功，请重试或重新打开角色形象。`}
 catch(e){if(current===version)actionStatus.value=`${label}失败：${e.message}`}
 finally{if(current===version)actionBusy.value=false}
}
function playMotion(){const item=allLive2dMotions[Number(motion.value)];if(item&&model)performAction(`加载动作「${item.name}」`,()=>live2dController.triggerMotion(item,true,3),item.group==='Idle'||item.group==='Tap#4'&&item.index===4?'已恢复待机。':`正在播放「${item.name}」，可选择「待机」恢复。`)}
function playExpression(){const name=expression.value||'自然表情';performAction(`切换「${name}」`,()=>expression.value?live2dController.triggerExpression(expression.value,{manual:true}):live2dController.resetExpression({manual:true}),`已切换为「${name}」。`)}
function interact(){emit('interact','（轻轻向黍招手）')}
watch(()=>props.enabled,value=>{clear();if(value)mount()})
onMounted(()=>{if(props.enabled)mount()});onBeforeUnmount(clear)
</script>
