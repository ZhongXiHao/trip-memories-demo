<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import BoardHeader from '@/components/BoardHeader.vue'
import { useSessionStore } from '@/stores/session'
import { showError } from '@/utils/toast'

// 占位首页：行程列表在下一步做。现在只展示当前身份并提供切换入口。
const session = useSessionStore()
const router = useRouter()

onMounted(async () => {
  try {
    await session.refreshMe()
  } catch (error) {
    // 401 已经由 http.ts 的拦截器处理（清身份并跳去选择页），这里不再重复提示
    if (session.userId !== null) showError(error)
  }
})

function switchUser() {
  router.push({ name: 'select-user' })
}
</script>

<template>
  <div>
    <BoardHeader title="我的行程">
      <template #action>
        <van-button size="small" plain hairline type="default" class="switch" @click="switchUser">
          切换身份
        </van-button>
      </template>
    </BoardHeader>

    <main class="home">
      <section class="me" aria-label="当前身份">
        <template v-if="session.me">
          <span class="me__name">{{ session.me.nickname }}</span>
          <span class="me__id tm-num">ID {{ session.me.id }}</span>
        </template>
        <van-loading v-else size="20" />
      </section>

      <p class="empty">行程列表马上就做。</p>
    </main>
  </div>
</template>

<style lang="less" scoped>
.switch {
  // 深色页头上的描边按钮：白字白边，高度满足 44px 点击区
  min-height: var(--tm-tap-min);
  background: transparent;
  color: #fff;
  border-color: rgb(255 255 255 / 60%);
}

.home {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-4);
  padding: var(--tm-space-4);
}

.me {
  display: flex;
  align-items: baseline;
  gap: var(--tm-space-2);
  min-height: 24px;
}

.me__name {
  font-size: var(--tm-text-lg);
  font-weight: 600;
}

.me__id {
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.empty {
  margin: 0;
  padding: var(--tm-space-4);
  border: 1px dashed var(--tm-line);
  border-radius: var(--tm-radius-card);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
}
</style>
