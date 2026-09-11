import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Fork GitHub Pages uses a project-site subpath; upstream/custom-domain builds stay at the root.
const publicBase = process.env.GITHUB_REPOSITORY === 'Lorenzo-Holmes/jarvis-finance-platform'
  ? '/jarvis-finance-platform/'
  : '/'

export default defineConfig({
  base: publicBase,
  plugins: [vue()],
  server: {
    port: 5173,
    // 浏览器只访问 Java 主后端；Java 再通过内部令牌调用本机 Python AI 服务。
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8200',
        changeOrigin: true
      }
    }
  }
})
