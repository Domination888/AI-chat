import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from 'tailwindcss'
import autoprefixer from 'autoprefixer'
import { fileURLToPath } from 'node:url'

const tailwindConfig = fileURLToPath(new URL('./tailwind.config.js', import.meta.url))

export default defineConfig(({ mode }) => {
  const distribution = mode === 'cloud' || mode === 'admin' ? mode : 'offline'
  return {
    plugins: [vue()],
    publicDir: 'release-public',
    base: './',
    css: {
      postcss: { plugins: [tailwindcss({ config: tailwindConfig }), autoprefixer()] },
    },
    build: {
      outDir: `dist-${distribution}`,
      emptyOutDir: true,
      modulePreload: false,
    },
    define: {
      'import.meta.env.VITE_API_BASE': JSON.stringify(process.env.STORY_CLOUD_URL || ''),
      'import.meta.env.VITE_DISTRIBUTION': JSON.stringify(distribution),
    },
  }
})
