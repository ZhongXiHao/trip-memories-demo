// 自托管的等宽数字字体（只要拉丁子集的 500/600 两个字重），金额和 ID 用，见 styles/tokens.less
import '@fontsource/ibm-plex-mono/latin-500.css'
import '@fontsource/ibm-plex-mono/latin-600.css'
// Vant 组件及其样式由 unplugin-vue-components 按需引入，这里不要整包引 vant 的 css
import './styles/tokens.less'
import './styles/base.less'

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import { setUnauthenticatedHandler } from '@/api/http'
import { useSessionStore } from '@/stores/session'
import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)

// 任何请求收到 401：清掉本地身份，回到选择身份页（选完回到原来的页面）
setUnauthenticatedHandler(() => {
  useSessionStore().signOut()
  const current = router.currentRoute.value
  if (!current.meta.public) {
    router.replace({ name: 'select-user', query: { redirect: current.fullPath } })
  }
})

app.mount('#app')
