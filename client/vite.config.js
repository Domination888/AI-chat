import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from 'tailwindcss'
import autoprefixer from 'autoprefixer'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const tailwindConfig = path.resolve(__dirname, 'src/tailwind.config.js')

/** Electron 生产环境用 app:// 加载；去掉 crossorigin 以兼容 file:// 回退 */
function electronBuildPlugin() {
  return {
    name: 'electron-build',
    transformIndexHtml(html) {
      return html.replace(/\s+crossorigin(="anonymous")?/g, '')
    },
  }
}

export default defineConfig(({ mode }) => ({
  plugins: [vue(), ...(mode === 'production' ? [electronBuildPlugin()] : [])],
  root: './src',
  base: mode === 'production' ? './' : '/',
  css: {
    postcss: {
      plugins: [
        tailwindcss({ config: tailwindConfig }),
        autoprefixer(),
      ],
    },
  },
  build: {
    outDir: '../dist',
    emptyOutDir: true,
    modulePreload: false,
  },
  define: {
    'import.meta.env.VITE_API_BASE': JSON.stringify(
      mode === 'production' ? 'http://127.0.0.1:8080' : ''
    ),
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
}))
