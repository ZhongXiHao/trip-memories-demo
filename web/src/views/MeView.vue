<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import BoardHeader from '@/components/BoardHeader.vue'
import { useSessionStore } from '@/stores/session'

// "我的"：当前身份，以及切换身份、退出登录。手机上从底部导航条进入；PC 上这些在右上角头像菜单里。
const session = useSessionStore()
const router = useRouter()

onMounted(() => {
  // 失败不在这里提示：401 由 http.ts 处理，其它错误在用到用户信息的页面上自然会出现
  session.ensureMe().catch(() => undefined)
})

function switchUser() {
  router.push({ name: 'select-user' })
}

function signOut() {
  session.signOut()
  router.replace({ name: 'select-user' })
}
</script>

<template>
  <div>
    <BoardHeader title="我的" narrow />

    <main class="me tm-container tm-container--narrow">
      <section class="who" aria-label="当前身份">
        <span class="who__name">{{ session.me?.nickname ?? '…' }}</span>
        <span class="who__id tm-num">ID {{ session.userId }}</span>
      </section>

      <ul class="actions">
        <li>
          <button type="button" class="action" @click="switchUser">
            <span>切换身份</span>
            <van-icon name="arrow" class="action__chevron" aria-hidden="true" />
          </button>
        </li>
        <li>
          <button type="button" class="action action--danger" @click="signOut">
            <span>退出登录</span>
          </button>
        </li>
      </ul>
    </main>
  </div>
</template>

<style lang="less" scoped>
.me {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-4);
  padding-block: var(--tm-space-4);
}

.who {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-1);
  padding: var(--tm-space-4);
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
}

.who__name {
  overflow-wrap: anywhere;
  font-size: var(--tm-text-lg);
  font-weight: 600;
}

.who__id {
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.actions {
  margin: 0;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
  list-style: none;
}

.actions li + li {
  border-top: 1px solid var(--tm-line);
}

.action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-height: 52px;
  padding: 0 var(--tm-space-4);
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;

  &:active {
    background: var(--tm-board);
  }
}

.action--danger {
  color: var(--tm-danger);
}

.action__chevron {
  color: var(--tm-muted);
}
</style>
