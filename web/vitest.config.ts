import { fileURLToPath } from 'node:url'
import { mergeConfig, defineConfig, configDefaults } from 'vitest/config'
import viteConfig from './vite.config'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      exclude: [...configDefaults.exclude, 'e2e/**'],
      // Vant 的组件会 import .css；交给 Vite 处理，不然 Node 直接加载会报 Unknown file extension
      server: { deps: { inline: ['vant'] } },
      root: fileURLToPath(new URL('./', import.meta.url)),
    },
  }),
)
