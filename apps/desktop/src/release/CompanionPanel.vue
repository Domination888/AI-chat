<template>
  <div v-if="show" class="release-modal-backdrop" @click.self="$emit('close')">
    <section class="release-modal companion-panel" role="dialog" aria-modal="true" :aria-label="isDaily?'AI 日报':'陪伴偏好'">
      <header><h2>{{ isDaily?'AI 日报':'陪伴偏好' }}</h2><button @click="$emit('close')" :aria-label="isDaily?'关闭 AI 日报':'关闭陪伴偏好'">×</button></header>
      <div v-if="!isDaily" class="release-tabs" role="tablist" aria-label="陪伴功能"><button v-for="t in tabs" :key="t.id" role="tab" :aria-selected="tab===t.id" :class="{active:tab===t.id}" @click="tab=t.id">{{ t.label }}</button></div>
      <p v-if="error" class="release-error" role="alert">{{ error }}</p><p v-if="status" role="status">{{ status }}</p>
      <p v-if="loading">正在读取设置…</p>
      <template v-else-if="settings">
        <div v-show="tab==='world'">
          <p>位置和时间仅用于现实陪伴模式。</p>
          <label class="release-check"><input type="checkbox" v-model="settings.worldEnabled"/>让角色使用当地时间和城市</label>
          <label>时区<input v-model="settings.timezone" placeholder="Asia/Shanghai"/></label>
          <label>城市<input v-model="city" maxlength="80" placeholder="例如杭州"/></label>
          <div class="release-button-row"><button :disabled="busy" @click="manual">保存城市与时区</button><button :disabled="busy" @click="clearLocation">清除位置</button></div>
          <label class="release-check"><input type="checkbox" v-model="settings.geolocationEnabled"/>启动时自动使用设备定位</label>
          <label class="release-check"><input type="checkbox" v-model="settings.worldNetworkEnabled"/>联网查询城市与天气</label>
          <small>联网查询会向服务提供城市名或城市级坐标。IP 粗定位可能有偏差。</small>
          <div class="release-button-row"><button :disabled="busy||!settings.worldNetworkEnabled" @click="refreshWeather">刷新天气</button><button :disabled="busy" @click="locate">{{ locating?'定位中…':'立即定位' }}</button></div>
          <div v-if="world" class="companion-snapshot"><p>{{ world.location || '尚未设置城市' }} · {{ sourceLabel(world.locationSource) }}</p><p>{{ world.localDateTime }}</p><p v-if="world.lunarDate">农历：{{ world.lunarDate }}</p><p v-if="world.solarTerm">节气：{{ world.solarTerm }}</p><p v-if="world.festivals?.length">节日：{{ world.festivals.join('、') }}</p><p v-if="world.weatherLocation">天气地点：{{ world.weatherLocation }}</p><p v-if="world.weather">天气：{{ world.weather }}</p><p v-if="world.weatherObservedLocal">观测时间：{{ world.weatherObservedLocal }}</p><p v-if="world.weatherStatus && world.weatherStatus!==status" role="status">{{ world.weatherStatus }}</p><p v-if="world.latitude!=null">城市级坐标：{{ world.latitude }}，{{ world.longitude }}</p></div>
        </div>
        <div v-show="tab==='interests'">
          <label class="release-check"><input type="checkbox" v-model="settings.autoCollect"/>自动收集你在陪伴对话中明确表达的喜好</label>
          <label class="release-check"><input type="checkbox" v-model="settings.semanticInterests"/>用模型整理长期兴趣</label><small>收集范围仅限你在现实陪伴对话中的发言。</small>
          <form class="companion-interest-form" @submit.prevent="add"><label>主题<input v-model="topic" maxlength="64" required placeholder="例如摄影"/></label><label>态度<select v-model="sentiment"><option value="like">喜欢</option><option value="dislike">不喜欢</option></select></label><button :disabled="busy || !topic.trim()">添加</button></form>
          <ul class="companion-interest-list"><li v-for="item in interests" :key="item.id"><label class="release-check"><input type="checkbox" :checked="!!item.enabled" :disabled="busy" @change="toggle(item,$event.target.checked)"/><span>{{ item.sentiment==='dislike'?'不喜欢':'喜欢' }} · {{ item.topic }}<small>{{ item.source==='manual'?'手动添加':item.source==='legacy'?'历史数据':'来自现实陪伴对话' }}</small></span></label><details v-if="item.evidence"><summary>查看原句</summary><p>{{ item.evidence }}</p></details><button :disabled="busy" @click="remove(item)" :aria-label="`删除喜好：${item.topic}`">删除</button></li></ul>
          <p v-if="!interests.length">暂无喜好。</p><button :disabled="busy" @click="collect">从近期对话收集</button><small>已停用的主题需要手动重新启用。</small>
        </div>
        <div v-show="tab==='proactive'">
          <label class="release-check"><input type="checkbox" v-model="settings.proactiveEnabled"/>允许角色主动搭话</label><p>主动搭话需要打开现实陪伴篇章。输入或发送消息会打断主动回复。</p>
          <label>触发前的空闲时间（秒）<input type="number" min="10" max="86400" v-model.number="settings.proactiveIdleSeconds"/></label><label>开场指令<textarea v-model="settings.proactivePrompt" rows="3" maxlength="1000"/></label>
          <label class="release-check"><input type="checkbox" v-model="settings.searchEnabled"/>允许对话联网搜索</label><label class="release-check"><input type="checkbox" v-model="settings.researchEnabled"/>按喜好搜索话题</label>
          <div class="release-button-row"><button :disabled="busy || !conversationId" @click="research">搜索话题</button><button :disabled="busy || !conversationId" @click="trigger">让角色现在搭话</button></div>
          <p v-if="!conversationId">打开篇章后即可使用。</p><article v-for="c in candidates" :key="c.id" class="capability-row"><b>{{ c.title }}</b><small>{{ c.topic }} · {{ c.status==='delivered'?'已送达':c.status==='pending'?'待开场':c.status }}</small><button @click="candidateFeedback(c,'interested')" :disabled="busy">喜欢这类</button><button @click="candidateFeedback(c,'less_like')" :disabled="busy">少聊这类</button></article>
        </div>
        <div v-show="tab==='daily'">
          <details class="daily-settings" :open="!settings.dailyEnabled"><summary>订阅与提醒</summary><label class="release-check"><input type="checkbox" v-model="settings.dailyEnabled"/>允许联网读取 AI 日报</label><small>角色可投递未读期刊，刊期以原文日期为准。提醒需要应用或服务保持运行。</small>
          <label class="release-check"><input type="checkbox" v-model="settings.dailyScheduleEnabled" :disabled="!settings.dailyEnabled"/>每天读取并在应用内提醒</label>
          <label>提醒时间（{{ settings.dailyTimezone || settings.timezone }}）<input type="time" v-model="settings.dailyTime" :disabled="!settings.dailyEnabled || !settings.dailyScheduleEnabled"/></label>
          <button class="release-primary" :disabled="busy" @click="save">保存日报设置</button></details>
          <div class="release-button-row"><button class="release-primary" :disabled="busy || !settings.dailyEnabled" @click="refreshDaily">{{ refreshing?'正在读取…':'读取最新日报' }}</button><button v-if="daily.digest && daily.unread" :disabled="busy" @click="markRead">标为已读</button></div>
          <article v-if="daily.digest" class="companion-digest"><h3>{{ daily.digest.date }} 期</h3><p role="status">{{ daily.unread?'未读':'已读' }}</p><a :href="daily.digest.sourceUrl" @click.prevent="openLink(daily.digest.sourceUrl)">查看日报来源</a><ol><li v-for="item in daily.digest.items" :key="item.link+item.title"><h4>{{ item.title }}</h4><p>{{ dailyText(item.summary) }}</p><details v-if="item.detail"><summary>展开详情</summary><p>{{ dailyText(item.detail) }}</p></details><a v-if="item.link" :href="item.link" @click.prevent="openLink(item.link)">阅读原文</a></li></ol><button :disabled="!conversationId || busy" @click="discuss">和角色讨论这期日报</button><small v-if="!conversationId">打开篇章后即可讨论。</small></article>
          <p v-else>尚未读取日报。</p>
        </div>
        <footer v-if="!isDaily" class="release-button-row"><button class="release-primary" :disabled="busy" @click="save">{{ busy?'保存中…':'保存陪伴偏好' }}</button></footer>
      </template>
    </section>
  </div>
</template>

<script setup>
import { ref, watch, computed, onBeforeUnmount } from 'vue'
import { json, distribution } from './api.mjs'
import { createPanelRefresh } from './panelRefresh.mjs'
import { dailyText } from './dailyText.mjs'
import { locateWorldContext, locationSourceLabel as sourceLabel, describeDeviceLocationError } from '../utils/worldLocation.js'
const props=defineProps({show:Boolean,initialTab:{type:String,default:'world'},conversationId:String})
const emit=defineEmits(['close','saved','discuss','daily-state'])
const tabs=[{id:'world',label:'定位与时间'},{id:'interests',label:'我的喜好'},{id:'proactive',label:'主动搭话'}]
const isDaily=computed(()=>props.initialTab==='daily')
const candidates=ref([])
const tab=ref('world'),settings=ref(null),world=ref(null),interests=ref([]),daily=ref({}),city=ref(''),topic=ref(''),sentiment=ref('like'),loading=ref(false),busy=ref(false),locating=ref(false),refreshing=ref(false),error=ref(''),status=ref('')
const lifecycle=createPanelRefresh(json)
let timer,listVersion=0
function stopRefresh(){listVersion++;clearInterval(timer);lifecycle.invalidate();busy.value=false;loading.value=false;settings.value=null}
async function refreshLists(){const version=++listVersion;const request=lifecycle.request();const [i,c]=await Promise.all([request('/api/proactive-research/interests'),props.conversationId?request(`/api/proactive-research/conversation/${props.conversationId}`):[]]);if(version===listVersion){interests.value=i;candidates.value=c}}
async function run(fn){if(busy.value)return;const current=lifecycle.version();const request=lifecycle.request();busy.value=true;error.value='';status.value='';try{await fn(request)}catch(e){if(current===lifecycle.version())error.value=e.message}finally{if(current===lifecycle.version())busy.value=false}}
async function load(){
 const current=lifecycle.version(),request=lifecycle.request()
  loading.value=true;error.value='';status.value=''
  try{
    if(isDaily.value){
      const [s,d]=await Promise.all([request('/api/v1/me/companion'),request('/api/v1/me/ai-daily')]);settings.value=s;city.value=s.city;daily.value=d;emit('daily-state',d)
    }else{
      const [s,w,i]=await Promise.all([request('/api/v1/me/companion'),request('/api/world-context'),request('/api/proactive-research/interests')]);settings.value=s;city.value=s.city;world.value=w;interests.value=i
      candidates.value=props.conversationId?await request(`/api/proactive-research/conversation/${props.conversationId}`):[]
    }
  }catch(e){if(current===lifecycle.version())error.value=e.message}finally{if(current===lifecycle.version())loading.value=false}
}
async function persist(request){const cityChanged=city.value!==settings.value.city;settings.value=await request('/api/v1/me/companion',settings.value,'PUT');if(cityChanged){world.value=await request('/api/world-context/location',{city:city.value,timezone:settings.value.timezone},'PUT');settings.value=await request('/api/v1/me/companion')}else if(!isDaily.value){world.value=await request('/api/world-context')}emit('saved');return cityChanged}
async function save(){await run(async(request)=>{await persist(request);status.value=isDaily.value?'日报设置已保存':'陪伴偏好已保存'})}
async function refreshWeather(){await run(async(request)=>{const cityChanged=await persist(request);if(!cityChanged)world.value=await request('/api/world-context/weather',{},'POST');status.value=world.value.weatherStatus||'天气查询已完成'})}
async function manual(){await run(async(request)=>{const cityChanged=await persist(request);if(!cityChanged){world.value=await request('/api/world-context/location',{city:city.value,timezone:settings.value.timezone},'PUT');settings.value=await request('/api/v1/me/companion')}status.value='城市与时区已保存'})}
async function clearLocation(){await run(async(request)=>{await request('/api/world-context/location',undefined,'DELETE');city.value='';settings.value=await request('/api/v1/me/companion');world.value=await request('/api/world-context');status.value='位置信息已清除'})}
async function locate(){await run(async(request)=>{locating.value=true;try{await persist(request);const result=await locateWorldContext({timezone:settings.value.timezone,allowIpFallback:distribution==='offline'&&settings.value.worldNetworkEnabled});world.value=result.snapshot;settings.value=await request('/api/v1/me/companion');city.value=settings.value.city;status.value=result.degraded?'设备定位不可用，已使用 IP 粗定位，城市可能有偏差。':'设备位置已更新'}catch(e){throw new Error(describeDeviceLocationError(e.deviceError || e,window.storyDesktop?.platform || false)+'。可在系统隐私设置中允许定位，或手动填写城市。')}finally{locating.value=false}})}
async function research(){await run(async(request)=>{await persist(request);const result=await request('/api/proactive-research/actions/run-now',{conversationId:props.conversationId},'POST');status.value=result.queued?'已准备一个有来源的新话题':result.reason||'当前没有新的候选话题';candidates.value=await request(`/api/proactive-research/conversation/${props.conversationId}`)})}
async function trigger(){await run(async(request)=>{await persist(request);const result=await request('/api/chat/proactive/trigger',{conversationId:props.conversationId},'POST');status.value=result.triggered?'角色正在准备开场':({paused_or_story_mode:'请先开启主动搭话，并在故事设定切换到现实陪伴。',no_listener:'当前篇章还没有建立主动对话连接。',busy:'角色正在回复，请稍后重试。'})[result.status]||result.status})}
async function candidateFeedback(item,feedback){await run(async(request)=>{await request(`/api/proactive-research/candidates/${item.id}/feedback`,{feedback},'POST');await refreshLists();status.value='话题反馈已保存'})}
async function add(){await run(async(request)=>{interests.value=await request('/api/proactive-research/interests',{topic:topic.value,sentiment:sentiment.value},'POST');topic.value=''})}
async function toggle(item,enabled){await run(async(request)=>{await request(`/api/proactive-research/interests/${item.id}`,{enabled},'PUT');item.enabled=enabled})}
async function remove(item){await run(async(request)=>{await request(`/api/proactive-research/interests/${item.id}`,undefined,'DELETE');interests.value=interests.value.filter(i=>i.id!==item.id)})}
async function collect(){await run(async(request)=>{await persist(request);const r=await request('/api/proactive-research/actions/refresh-interests',{},'POST');interests.value=await request('/api/proactive-research/interests');status.value=`已新增 ${r.added} 个明确表达的喜好`})}
async function refreshDaily(){await run(async(request)=>{refreshing.value=true;try{await persist(request);daily.value=await request('/api/v1/me/ai-daily/refresh',{},'POST');emit('saved');status.value='日报已更新'}finally{refreshing.value=false}})}
async function markRead(){await run(async(request)=>{await request('/api/v1/me/ai-daily/read',{},'POST');daily.value.unread=false;emit('daily-state',{...daily.value});emit('saved')})}
async function discuss(){await markRead();emit('discuss',{prompt:`我们聊聊 AI 日报（${daily.value.digest.date}）吧，请挑几条看点并附上来源。`,issueDate:daily.value.digest.date});emit('close')}
async function openLink(value){try{const url=new URL(value);if(url.protocol!=='https:')return;if(window.storyDesktop?.openSource)await window.storyDesktop.openSource(url.href);else window.open(url.href,'_blank','noopener,noreferrer')}catch{error.value='无法打开来源链接'}}
watch(()=>[props.show,props.initialTab,props.conversationId],()=>{stopRefresh();if(props.show){tab.value=props.initialTab;load();timer=setInterval(()=>{if(!busy.value&&!loading.value&&!isDaily.value)refreshLists().catch(()=>{})},3000)}},{immediate:true})
onBeforeUnmount(stopRefresh)
</script>
