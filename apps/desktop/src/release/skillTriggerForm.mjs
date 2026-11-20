export function triggerMode(skill) {
  if (['always','keywords','schedule','keywords_and_schedule'].includes(skill.triggerMode)) return skill.triggerMode
  return skill.schedule ? (skill.keywords?.length ? 'keywords_and_schedule' : 'schedule') : (skill.keywords?.length ? 'keywords' : 'always')
}
export function readTriggerForm(skill, isNew = false) {
  return {mode:isNew?'keywords':triggerMode(skill),time:`${String(skill.schedule?.hour??10).padStart(2,'0')}:${String(skill.schedule?.minute??0).padStart(2,'0')}`,zone:skill.schedule?.zone||'Asia/Shanghai',enabled:!!skill.schedule?.enabled}
}
export function writeTriggerForm(skill, advanced, form, keywords) {
  const scheduled=['schedule','keywords_and_schedule'].includes(form.mode)
  if (['keywords','keywords_and_schedule'].includes(form.mode)&&!keywords.length) throw new Error('请填写至少一个关键词，或选择每次聊天／仅定时。')
  const value={...skill,...advanced,triggerMode:form.mode,keywords:['keywords','keywords_and_schedule'].includes(form.mode)?keywords:[]}
  if (scheduled) {
    if (!/^\d{2}:\d{2}$/.test(form.time)) throw new Error('请选择每日执行时间。')
    const [hour,minute]=form.time.split(':').map(Number)
    if(hour>23||minute>59)throw new Error('执行时间无效。')
    try{new Intl.DateTimeFormat('zh-CN',{timeZone:form.zone})}catch{throw new Error('请输入有效时区，例如 Asia/Shanghai。')}
    value.schedule={...advanced.schedule,enabled:form.enabled,hour,minute,zone:form.zone}
    value.proactive={...advanced.proactive,enabled:form.enabled}
  } else {
    if(value.schedule)value.schedule={...value.schedule,enabled:false}
    if(value.proactive)value.proactive={...value.proactive,enabled:false}
  }
  return value
}
