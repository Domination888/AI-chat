import { createApp, defineAsyncComponent } from 'vue'
import '../story.css'
const Root=defineAsyncComponent(()=>import.meta.env.VITE_DISTRIBUTION==='admin'?import('./AdminApp.vue'):import('./ReleaseApp.vue'))
createApp(Root).mount('#app')
