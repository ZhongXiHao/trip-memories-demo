import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import Components from 'unplugin-vue-components/vite'
import { VantResolver } from '@vant/auto-import-resolver'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
    // 模板里直接写 <van-button> 即可，Vant 组件及其样式会按需自动引入。
    // 类型声明写到 src/ 下，这样 vue-tsc 能认出 van-* 标签。
    Components({
      resolvers: [VantResolver()],
      dts: 'src/components.d.ts',
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      // 后端的 context-path 就是 /api，所以不需要改写路径。
      // 浏览器看到的是同源请求，后端不用开 CORS。
      '/api': 'http://localhost:8080',
    },
  },
})
