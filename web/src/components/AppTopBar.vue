<script setup lang="ts">
import { ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { useSessionStore } from '@/stores/session'

// PC 版式的顶部导航条：左边品牌和导航，右上角头像（暂用空白用户图标，以后换成真头像）。
// 手机上整个隐藏（display: none），手机用每页自己的 BoardHeader。
const session = useSessionStore()
const router = useRouter()

const menuOpen = ref(false)

// 有身份但还没取过用户信息（比如刚刷新页面）时，取一次，菜单里要显示昵称
watch(
  () => session.userId,
  () => {
    // 失败不在这里提示：401 由 http.ts 处理，其它错误页面自己会在用到时提示
    session.ensureMe().catch(() => undefined)
  },
  { immediate: true },
)

function switchUser() {
  menuOpen.value = false
  router.push({ name: 'select-user' })
}

function signOut() {
  menuOpen.value = false
  session.signOut()
  router.replace({ name: 'select-user' })
}
</script>

<template>
  <nav class="topbar" aria-label="主导航">
    <div class="topbar__inner">
      <RouterLink :to="{ name: 'home' }" class="topbar__brand">Trip Memories</RouterLink>

      <template v-if="session.userId !== null">
        <ul class="topbar__links">
          <li>
            <RouterLink :to="{ name: 'home' }" class="topbar__link" exact-active-class="is-active">
              我的行程
            </RouterLink>
          </li>
        </ul>

        <van-popover v-model:show="menuOpen" placement="bottom-end" :offset="[0, 8]">
          <div class="menu" role="menu">
            <div class="menu__who">
              <span class="menu__name">{{ session.me?.nickname ?? '…' }}</span>
              <span class="menu__id tm-num">ID {{ session.userId }}</span>
            </div>
            <button type="button" class="menu__item" role="menuitem" @click="switchUser">切换身份</button>
            <button type="button" class="menu__item" role="menuitem" @click="signOut">退出登录</button>
          </div>
          <template #reference>
            <button
              type="button"
              class="topbar__account"
              aria-haspopup="menu"
              :aria-expanded="menuOpen"
              aria-label="账号菜单"
            >
              <span class="topbar__avatar" aria-hidden="true"><van-icon name="user-o" size="20" /></span>
              <span v-if="session.me" class="topbar__nickname">{{ session.me.nickname }}</span>
            </button>
          </template>
        </van-popover>
      </template>
    </div>
  </nav>
</template>

<style lang="less" scoped>
@import (reference) '../styles/breakpoints.less';

// 手机上不显示
.topbar {
  display: none;
}

@media (min-width: @bp-desktop) {
  .topbar {
    display: block;
    position: sticky;
    top: 0;
    z-index: 10;
    background: var(--tm-navy);
    border-bottom: 3px solid var(--tm-amber);
    color: #fff;
  }

  .topbar__inner {
    display: flex;
    align-items: center;
    gap: var(--tm-space-5);
    height: var(--tm-topbar-h);
    width: 100%;
    max-width: var(--tm-page-max);
    margin-inline: auto;
    padding-inline: var(--tm-space-5);
  }
}

.topbar__brand {
  color: #fff;
  font-size: var(--tm-text-lg);
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
}

.topbar__links {
  display: flex;
  flex: 1;
  align-self: stretch;
  gap: var(--tm-space-1);
  margin: 0;
  padding: 0;
  list-style: none;
}

.topbar__link {
  display: flex;
  align-items: center;
  height: 100%;
  padding-inline: var(--tm-space-3);
  // 当前页：白字 + 底部琥珀线；其它：白色 80%（深色底上对比度仍 > 7:1）
  border-bottom: 3px solid transparent;
  margin-bottom: -3px;
  color: rgb(255 255 255 / 80%);
  text-decoration: none;

  &:hover {
    color: #fff;
  }

  &.is-active {
    border-bottom-color: var(--tm-amber);
    color: #fff;
    font-weight: 600;
  }
}

.topbar__account {
  display: flex;
  align-items: center;
  gap: var(--tm-space-2);
  min-height: var(--tm-tap-min);
  margin-left: auto;
  padding: 0 var(--tm-space-2) 0 var(--tm-space-1);
  border: 0;
  border-radius: 22px;
  background: transparent;
  color: #fff;
  font: inherit;
  cursor: pointer;

  &:hover {
    background: rgb(255 255 255 / 10%);
  }
}

.topbar__avatar {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border: 1px solid rgb(255 255 255 / 60%);
  border-radius: 50%;
}

.topbar__nickname {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu {
  min-width: 180px;
  padding: var(--tm-space-1) 0;
}

.menu__who {
  display: flex;
  flex-direction: column;
  padding: var(--tm-space-2) var(--tm-space-4) var(--tm-space-3);
  border-bottom: 1px solid var(--tm-line);
}

.menu__name {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu__id {
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.menu__item {
  display: block;
  width: 100%;
  min-height: var(--tm-tap-min);
  padding: 0 var(--tm-space-4);
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;

  &:hover {
    background: var(--tm-board);
  }
}
</style>
