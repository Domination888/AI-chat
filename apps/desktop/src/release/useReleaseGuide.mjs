import { json } from "./api.mjs"
import { nextTick } from "vue"

export function useReleaseGuide({
  choose, conversation, fileInput, guarded, loadChapters, loadConversationState, loadMessages,
  mobileNav, modeBusy, nativeInfo, newStory, openAccount, openCompanion, openTools, page,
  roleForm, roles, sceneTab, selected, showCharacter, showRoleForm, showScene, showSearch, stop,
  toast,
}) {
  async function refreshStory(){await guarded(()=>Promise.all([loadMessages(),loadChapters(),loadConversationState()]))}
  function openScene(tab='scene'){sceneTab.value=tab;showScene.value=true;mobileNav.value=false}
  async function switchConversationMode(mode){
    if(modeBusy.value||!conversation.value)return
    modeBusy.value=true
    const target=conversation.value
    try{await guarded(async()=>{
      await stop()
      if(target!==conversation.value)return
      const {state}=await json(`/api/rp/${target}`)
      if(target!==conversation.value)return
      await json(`/api/rp/${target}`,{...state,mode},'PUT')
      if(target!==conversation.value)return
      await loadConversationState()
      toast(mode==='companion'?'已切换为现实陪伴。可在“主动搭话设置”开启并保存偏好。':'已切换为剧情时间')
    })}finally{modeBusy.value=false}
  }
  async function openGuide(){page.value='guide';mobileNav.value=false;await nextTick();document.getElementById('feature-guide-title')?.focus()}
  async function navigateGuide(action){
    mobileNav.value=false
    if(action==='library'){page.value='library';await nextTick();document.querySelector('.release-role-card button')?.focus();return}
    if(action==='create-role'){roleForm.value={name:'',profile:'',background:'',personality:'',greeting:''};showRoleForm.value=true;return}
    if(['model','data'].includes(action)){await openAccount(action);return}
    if(['world','interests','proactive','daily'].includes(action)){openCompanion(action);return}
    if(['mcp','skills'].includes(action)){openTools(action);return}
    if(action==='character'){
      const role=roles.value.find(r=>r.roleCode==='shu')
      if(!nativeInfo.value.characterAvailable||!role)return
      if(!conversation.value||selected.value?.id!==role.id)await choose(role)
      else page.value='story'
      showCharacter.value=true
      return
    }
    if(!conversation.value){page.value='library';toast('先选择一个角色，开启故事后再试试这项功能。');return}
    if(['scene','memory','album'].includes(action)){openScene(action);return}
    if(action==='companion-mode'){await switchConversationMode('companion');return}
    if(action==='new-story'){await guarded(()=>newStory());return}
    page.value='story'
    await nextTick()
    if(action==='search'){showSearch.value=true;await nextTick();document.querySelector('.release-search input')?.focus()}
    else if(action==='images')fileInput.value?.click()
    else if(action==='voice'){document.querySelector('button[aria-label="语音输入"]')?.focus();toast('点“语音输入”开始录音，再点一次结束并发送；也可以朗读最新回复。')}
    else{document.querySelector('.message-actions button')?.focus();toast('在消息下方试试分支、回退和收藏；你的消息可编辑，角色的回复可重写。')}
  }

  return { refreshStory, openScene, switchConversationMode, openGuide, navigateGuide }
}
