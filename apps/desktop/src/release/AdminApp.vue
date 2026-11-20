<template>
  <div class="release-shell admin-shell">
    <form v-if="!admin" class="admin-login" @submit.prevent="login">
      <h1>幕间管理端</h1>
      <label>服务地址<input v-model="server" type="url" required placeholder="https://story.example.com" /></label>
      <label>邮箱<input v-model="credentials.email" type="email" required autocomplete="username" /></label>
      <label>密码<input v-model="credentials.password" type="password" required autocomplete="current-password" /></label>
      <label>动态验证码<input v-model="credentials.otp" inputmode="numeric" maxlength="6" autocomplete="one-time-code" required /></label>
      <p v-if="error" class="release-error" role="alert">{{ error }}</p>
      <button class="release-primary" :disabled="busy">登录</button>
    </form>
    <template v-else>
      <aside class="release-nav">
        <span class="admin-brand">幕间管理端</span>
        <nav class="admin-nav" aria-label="管理端导航">
          <button v-for="section in sections" :key="section.id" class="release-nav-item"
            :class="{ active: page === section.id }" :aria-current="page === section.id ? 'page' : null"
            @click="page = section.id; load()">
            {{ section.label }}
          </button>
        </nav>
        <div class="admin-account">
          <span>{{ admin.username }}</span>
          <button class="release-text" @click="signOut">退出登录</button>
        </div>
      </aside>
      <main class="release-main admin-content">
        <header class="admin-heading">
          <h1 id="admin-section-title">{{ sections.find(section => section.id === page)?.label }}</h1>
          <div class="admin-actions">
            <button :disabled="busy" @click="load">刷新</button>
            <button v-if="page === 'users'" class="release-primary" :disabled="busy" @click="invite">创建邀请码</button>
          </div>
        </header>
        <p v-if="error" class="release-error" role="alert">{{ error }}</p>
        <div v-if="invitation" class="admin-invite">
          <strong>邀请码仅显示一次</strong>
          <code>{{ invitation.code }}</code>
          <small>有效期至 {{ invitation.expiresAt }}</small>
          <button @click="copy">复制邀请码</button>
        </div>
        <div v-if="rows.length" class="admin-table-wrap" tabindex="0" role="region" aria-labelledby="admin-section-title">
          <table>
            <thead>
              <tr>
                <th v-for="column in columns" :key="column" scope="col">{{ labels[column] || column }}</th>
                <th v-if="page === 'users'" scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in rows" :key="row.id || index">
                <td v-for="column in columns" :key="column">{{ display(row[column], column) }}</td>
                <td v-if="page === 'users'">
                  <button v-if="!row.admin" :disabled="busy" @click="toggleStatus(row)">
                    {{ row.status === 'active' ? '暂停账号' : '恢复账号' }}
                  </button>
                  <span v-else>—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="admin-empty">{{ busy ? '加载中…' : '暂无记录' }}</p>
      </main>
    </template>
    <ActionDialog v-if="actionDialog" :key="actionDialog.requestId" :options="actionDialog" @resolve="settleAction" />
  </div>
</template>
<script setup>
import { failureDescription } from './failureCategory.mjs'
import { ref,computed,onBeforeUnmount } from 'vue'
import { connect,acceptSession,json,logout } from './api.mjs'
import ActionDialog from './ActionDialog.vue'
import { createActionRequest } from './actionRequest.mjs'
import './release.css'
const admin=ref(null),server=ref(import.meta.env.VITE_API_BASE || window.location.origin),credentials=ref({email:'',password:'',otp:''}),busy=ref(false),error=ref(''),page=ref('users'),rows=ref([]),invitation=ref(null)
const actionDialog=ref(null)
const {ask:askAction,settle:settleAction}=createActionRequest(value=>{actionDialog.value=value})
let sessionVersion=0,disposed=false
const sections=[{id:'users',label:'注册用户'},{id:'usage',label:'模型用量'},{id:'faults',label:'服务故障'},{id:'audit',label:'操作审计'}]
const labels={id:'ID',email:'邮箱',username:'称呼',status:'状态',admin:'管理员',created_at:'创建时间',user_id:'用户 ID',day:'日期',provider:'模型来源',requests:'请求数',input_tokens:'输入 Token',output_tokens:'输出 Token',estimated:'是否估算',actor:'操作者',action:'操作',subject:'对象',category:'故障类别'}
const columns=computed(()=>rows.value.length?Object.keys(rows.value[0]):[])
function display(value, column) {
  if (column === 'category') return failureDescription(value)
  if (column === 'admin' || column === 'estimated') return value ? '是' : '否'
  if (column === 'status' && value === 'active') return '已启用'
  if (column === 'status' && value === 'suspended') return '已暂停'
  return value ?? '—'
}
async function guard(fn){busy.value=true;error.value='';try{await fn()}catch(e){error.value=e.message}finally{busy.value=false}}
async function login(){await guard(async()=>{await connect(server.value);const session=await json('/api/v1/auth/admin-login',credentials.value,'POST');admin.value=await acceptSession(session);credentials.value.password='';credentials.value.otp='';await load()})}
async function load(){await guard(async()=>{rows.value=await json('/api/v1/admin/'+page.value)})}
async function invite(){await guard(async()=>{invitation.value=await json('/api/v1/admin/invitations',{},'POST')})}
async function copy(){await guard(()=>navigator.clipboard.writeText(invitation.value.code))}
async function toggleStatus(row){
  if(busy.value||!admin.value||row.admin||disposed)return
  const actor=admin.value,version=sessionVersion,target=row.id,status=row.status==='active'?'suspended':'active'
  const pausing=status==='suspended'
  const confirmed=await askAction({
    title:pausing?'暂停这个账号':'恢复这个账号',
    description:pausing?'暂停后，该账号将无法登录，当前登录会失效，正在进行的回复会停止。已有故事会保留，可以稍后恢复账号。':'恢复后，该账号可以重新登录并继续已有故事。先前失效的登录需要重新验证。',
    preview:`邮箱：${row.email}\n用户 ID：${target}`,
    confirmLabel:pausing?'确认暂停':'确认恢复',
  })
  const current=()=>!disposed&&admin.value===actor&&sessionVersion===version
  if(!confirmed||!current())return
  await guard(async()=>{
    if(!current())return
    await json(`/api/v1/admin/users/${target}/status`,{status},'PUT')
    if(current())await load()
  })
}
async function signOut(){sessionVersion++;settleAction();admin.value=null;rows.value=[];invitation.value=null;await logout()}
onBeforeUnmount(()=>{disposed=true;sessionVersion++;settleAction()})
</script>
<style>
.admin-shell {
  min-height: 100dvh;
  height: auto;
  background: var(--r-paper);
}
.admin-shell h1 {
  font-size: 24px;
  font-weight: 600;
}
.admin-login {
  width: min(360px, calc(100% - 40px));
  margin: auto;
  padding: 24px 0;
}
.admin-login .release-primary {
  margin-top: 8px;
}
.admin-shell .release-nav {
  background: var(--r-bg);
}
.admin-brand {
  font-size: 14px;
  font-weight: 600;
  padding: 4px 12px;
  margin-bottom: 24px;
}
.admin-shell .release-nav .release-nav-item.active {
  background: var(--r-paper);
  border-color: transparent;
}
.admin-shell .release-nav .release-nav-item:hover:not(.active) {
  background: var(--r-paper);
  border-color: transparent;
}
.admin-account {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
  padding: 12px;
  color: var(--r-muted);
  font-size: 12px;
}
.admin-account span {
  min-width: 0;
  overflow-wrap: anywhere;
}
.admin-account .release-text {
  padding: 6px 0;
  white-space: nowrap;
}
.admin-account .release-text:hover {
  text-decoration: underline;
}
.admin-content {
  padding: 28px 40px;
}
.admin-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 24px;
}
.admin-actions {
  display: flex;
  gap: 8px;
}
.admin-shell .admin-actions .release-primary {
  padding: 8px 13px;
}
.admin-table-wrap {
  overflow: auto;
}
.admin-table-wrap:focus-visible {
  outline: 2px solid var(--r-accent);
  outline-offset: 3px;
}
.admin-table-wrap table {
  border-collapse: collapse;
  width: 100%;
  white-space: nowrap;
  font-size: 14px;
}
.admin-table-wrap th,
.admin-table-wrap td {
  text-align: left;
  padding: 12px 16px;
  border-bottom: 1px solid var(--r-line);
}
.admin-table-wrap th {
  font-size: 12px;
  font-weight: 600;
  color: var(--r-muted);
}
.admin-table-wrap button {
  font-size: 12px;
}
.admin-empty {
  padding-top: 16px;
  color: var(--r-muted);
}
.admin-invite {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  padding: 12px 0;
  border-block: 1px solid var(--r-line);
  margin-bottom: 16px;
}
.admin-invite code {
  overflow-wrap: anywhere;
}
.admin-invite small {
  color: var(--r-muted);
  font-size: 12px;
}
@media (max-width: 780px) {
  .admin-shell {
    flex-direction: column;
  }
  .admin-login {
    margin: 24px auto;
  }
  .admin-shell .release-nav {
    position: static;
    visibility: visible;
    width: 100%;
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    align-items: center;
    padding: 12px 16px 0;
    border-right: 0;
    border-bottom: 1px solid var(--r-line);
  }
  .admin-brand {
    padding: 0;
    margin-bottom: 0;
  }
  .admin-nav {
    grid-column: 1 / -1;
    grid-row: 2;
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .admin-shell .release-nav .release-nav-item {
    justify-content: center;
    padding: 8px 4px;
    border-radius: 0;
    border-bottom-width: 2px;
    font-size: 12px;
    text-align: center;
  }
  .admin-shell .release-nav .release-nav-item.active {
    border-bottom-color: var(--r-ink);
    background: transparent;
  }
  .admin-account {
    justify-content: flex-end;
    margin-top: 0;
    padding: 0;
  }
  .admin-shell .admin-content {
    padding: 20px 16px;
    overflow: visible;
  }
  .admin-heading {
    margin-bottom: 12px;
  }
}
</style>
