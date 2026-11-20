import {ref,computed,watch} from 'vue'

// Rejected requests stay only in this page's memory and belong to one account,
// service and chapter. Restoring a draft never sends it.
export function useSendDraftRecovery({scope,readDraft,writeDraft}) {
  const saved=ref([])
  let revision=0,nextId=0
  let owner=scope()
  const identityMatches=(a,b)=>a.userId!=null&&a.userId===b.userId&&a.base===b.base
  const sameChapter=(a,b)=>identityMatches(a,b)&&a.conversationId===b.conversationId
  const current=entry=>entry.revision===revision&&identityMatches(entry,scope())
  const visible=computed(()=>saved.value.filter(entry=>current(entry)&&sameChapter(entry,scope())))
  function clear(){revision++;saved.value=[]}
  watch([()=>scope().userId,()=>scope().base],()=>{
    const next=scope()
    // Expiry temporarily hides the account. Keep its drafts unavailable until
    // that same account returns; explicit sign-out clears them immediately.
    if(next.userId==null)return
    if(!identityMatches(owner,next))clear()
    owner=next
  },{flush:'sync'})
  function capture(text,images,dailyIssueDate=null,continuationHistoryId=null){return {...scope(),id:++nextId,revision,text,dailyIssueDate,continuationHistoryId,images:[...images]}}
  function recover(entry,{inline=true}={}){
    if(!entry||!current(entry))return 'stale'
    if(!entry.text&&!entry.images.length)return 'empty'
    const draft=readDraft(),place=scope()
    if(inline&&place.inStory&&sameChapter(entry,place)&&!draft.text&&!draft.images.length){
      writeDraft({text:entry.text,images:[...entry.images],continuationHistoryId:entry.continuationHistoryId,dailyIssueDate:entry.dailyIssueDate})
      return 'restored'
    }
    saved.value.push(entry)
    return 'saved'
  }
  function restore(id){
    const index=saved.value.findIndex(entry=>entry.id===id&&current(entry)&&sameChapter(entry,scope()))
    if(index<0)return null
    const entry=saved.value[index],draft=readDraft()
    if(draft.text||draft.images.length)saved.value.splice(index,1,{...entry,text:draft.text,images:[...draft.images],continuationHistoryId:draft.continuationHistoryId,dailyIssueDate:draft.dailyIssueDate})
    else saved.value.splice(index,1)
    writeDraft({text:entry.text,images:[...entry.images],continuationHistoryId:entry.continuationHistoryId,dailyIssueDate:entry.dailyIssueDate})
    return draft.text||draft.images.length?'swapped':'restored'
  }
  return {visible,capture,recover,restore,clear}
}
