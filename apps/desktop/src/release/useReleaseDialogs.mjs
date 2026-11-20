import { computed, nextTick, onBeforeUnmount, onMounted, watch } from "vue"

export function useReleaseDialogs({
  accountOpen, actionDialog, chapterMenu, chapterMenuOpen, companionOpen, installingModels,
  mobileNav, showRoleForm, showScene, showSearch, toggleSearch, toolsOpen,
}) {
  const dialogOpen=computed(()=>showScene.value||accountOpen.value||toolsOpen.value||companionOpen.value||showRoleForm.value)
  let dialogReturnFocus=null
  watch(dialogOpen,async open=>{
    if(open){dialogReturnFocus=document.activeElement;await nextTick();document.querySelector('.release-shell [role="dialog"] button')?.focus()}
    else{await nextTick();if(dialogReturnFocus?.isConnected)dialogReturnFocus.focus()}
  })
  function handleUiKeydown(event){
    if(installingModels.value&&event.key==='Escape'){event.preventDefault();return}
    if(actionDialog.value)return
    const dialog=document.querySelector('.release-shell [role="dialog"]')
    if(event.key==='Escape'){
      if(dialog){showScene.value=false;accountOpen.value=false;toolsOpen.value=false;companionOpen.value=false;showRoleForm.value=false}
      else if(chapterMenuOpen.value){chapterMenuOpen.value=false;chapterMenu.value?.querySelector('button')?.focus()}
      else if(mobileNav.value)mobileNav.value=false
      else if(showSearch.value)toggleSearch()
      event.preventDefault()
    }
    if(event.key==='Tab'&&dialog){
      const items=[...dialog.querySelectorAll('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href],summary,[tabindex="0"]')].filter(el=>el.getClientRects().length)
      const first=items[0],last=items.at(-1)
      if(event.shiftKey&&(document.activeElement===first||!dialog.contains(document.activeElement))){event.preventDefault();last?.focus()}
      else if(!event.shiftKey&&(document.activeElement===last||!dialog.contains(document.activeElement))){event.preventDefault();first?.focus()}
    }
  }
  onMounted(()=>document.addEventListener('keydown',handleUiKeydown))
  onBeforeUnmount(()=>document.removeEventListener('keydown',handleUiKeydown))
}
