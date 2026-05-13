import { createApp } from 'vue'
import App from './App.vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

// 检查是否在Electron环境中
const isElectron = !!window.electronAPI;

// 创建应用
const app = createApp(App)

// 在Electron环境中添加全局属性
if (isElectron) {
  app.config.globalProperties.$electron = window.electronAPI;
}

app.use(ElementPlus)
app.mount('#app')