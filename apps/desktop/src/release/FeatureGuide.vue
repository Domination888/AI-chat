<template>
  <section class="release-guide" aria-labelledby="feature-guide-title">
    <header class="release-guide-heading">
      <h1 id="feature-guide-title" tabindex="-1">功能导览</h1>
      <p v-if="!hasConversation">选择角色，开启篇章后即可使用对话功能。</p>
    </header>
    <div class="release-guide-grid">
      <article>
        <h2>角色</h2>
        <p>从角色库选一个角色，或为自建角色填写背景和第一句话。</p>
        <div class="release-button-row"><button @click="go('library')">浏览角色</button><button @click="go('create-role')">创建角色</button></div>
      </article>
      <article>
        <h2>对话</h2>
        <p>可以从一条消息分支、回退或收藏，也可以编辑自己的消息、重写角色回复。</p>
        <div class="release-button-row"><button :disabled="!hasConversation" @click="go('messages')">打开当前对话</button><button :disabled="!hasConversation" @click="go('search')">搜索对话</button></div>
      </article>
      <article>
        <h2>场景与篇章</h2>
        <p>在场景里设定身份、地点和关系。延续新章会沿用当前场景和经历；独立新故事从头开始。</p>
        <div class="release-button-row"><button :disabled="!hasConversation" @click="go('scene')">设置场景</button><button :disabled="!hasConversation" @click="go('new-story')">开启独立新故事</button></div>
      </article>
      <article>
        <h2>记忆与相册</h2>
        <p>可以纠正记忆或停止召回。相册收录收藏的对话和发送的照片，每次可发送最多 5 张 PNG、JPEG 或 WebP，每张不超过 5 MB。识别图片需要支持看图的模型。</p>
        <div class="release-button-row"><button :disabled="!hasConversation" @click="go('memory')">查看记忆</button><button :disabled="!hasConversation" @click="go('album')">打开相册</button><button :disabled="!hasConversation" @click="go('images')">添加照片</button></div>
      </article>
      <article>
        <h2>{{ hasCharacter ? '语音与角色形象' : '语音' }}</h2>
        <p>点一次语音输入开始录音，再点一次发送。朗读可手动播放，也可以自动播放，两项功能均需语音服务。<template v-if="hasCharacter">打开黍的形象可选择动作，朗读时会同步口型。</template></p>
        <div class="release-button-row"><button :disabled="!hasConversation" @click="go('voice')">打开语音对话</button><button v-if="hasCharacter" @click="go('character')">查看黍的角色形象</button></div>
      </article>
      <article>
        <h2>现实陪伴</h2>
        <p>角色可参考当地时间、天气和你的喜好。主动搭话和定时技能需要开启主动搭话，并保持现实陪伴篇章打开至设定的空闲时间。</p>
        <p v-if="mode" class="release-guide-context">当前篇章使用{{ mode==='companion'?'现实陪伴':'剧情时间' }}。</p>
        <div class="release-button-row"><button @click="go('world')">设置城市与时间</button><button @click="go('interests')">管理喜好</button><button v-if="mode!=='companion'" :disabled="!hasConversation || !mode || modeSwitching" @click="go('companion-mode')">切换为现实陪伴</button><button @click="go('proactive')">设置主动搭话</button></div>
      </article>
      <article>
        <h2>AI 日报</h2>
        <p>可在当前篇章讨论日报，也可在订阅与提醒中设置每日读取时间。</p>
        <button @click="go('daily')">打开 AI 日报</button>
      </article>
      <article>
        <h2>工具与技能</h2>
        <p>内置质数工具可直接试用。连接其他工具后，可授权模型调用。自建技能支持按关键词或定时触发。</p>
        <div class="release-button-row"><button @click="go('mcp')">试用工具</button><button @click="go('skills')">管理技能</button></div>
      </article>
      <article>
        <h2>模型与数据</h2>
        <p>{{ offline?'可导入离线模型包或连接自己的模型。故事备份可导入空的本地目录。':'可使用平台模型或连接自己的模型，并在「数据与账号」导出故事或管理账号。' }}删除篇章前，可以先导出备份。</p>
        <div class="release-button-row"><button @click="go('model')">模型连接</button><button @click="go('data')">{{ offline?'导入与导出备份':'导出与账号设置' }}</button></div>
      </article>
    </div>
  </section>
</template>

<script setup>
defineProps({hasConversation:Boolean,hasCharacter:Boolean,offline:Boolean,mode:String,modeSwitching:Boolean})
const emit=defineEmits(['navigate'])
function go(action){emit('navigate',action)}
</script>
