import { createApp } from 'vue'
import App from './App.vue'
import Live2DOverlay from './components/Live2DOverlay.vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './tailwind.css'

const isElectron = !!window.electronAPI;
const urlParams = new URLSearchParams(window.location.search)
const isLive2DMode = urlParams.get('mode') === 'live2d'

// Live2D 模式下：body 透明（Electron 透明窗口需要）
// 主窗口：恢复灰色背景
if (isLive2DMode) {
  document.body.style.background = 'transparent'
} else {
  document.body.style.background = ''
  document.body.classList.add('bg-gray-50')
}

const app = createApp(isLive2DMode ? Live2DOverlay : App)

if (isElectron) {
  app.config.globalProperties.$electron = window.electronAPI;
}

app.use(isLive2DMode ? {} : ElementPlus)
app.mount('#app')