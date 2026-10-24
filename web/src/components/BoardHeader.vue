<script setup lang="ts">
// 页面标题区。
// 手机：深海军蓝页头 + 底部一条琥珀色信号线（这个应用的视觉签名）。
// PC：顶部已经有通栏导航条（AppTopBar），这里退成内容区里普通的页面标题，不再画第二条深色带。
defineProps<{
  title: string
  subtitle?: string
  /** PC 上和窄内容区（表单类页面）对齐，要和页面 <main> 的 tm-container--narrow 配套 */
  narrow?: boolean
  /** 有值时在标题上方显示一个"返回"按钮，文字就是这个值（比如"我的行程"） */
  backLabel?: string
}>()

defineEmits<{ back: [] }>()
</script>

<template>
  <header class="board-header">
    <div class="board-header__inner tm-container" :class="{ 'tm-container--narrow': narrow }">
      <div class="board-header__text">
        <button v-if="backLabel" type="button" class="board-header__back" @click="$emit('back')">
          <van-icon name="arrow-left" aria-hidden="true" />
          <span>{{ backLabel }}</span>
        </button>
        <h1 class="board-header__title">{{ title }}</h1>
        <p v-if="subtitle" class="board-header__subtitle">{{ subtitle }}</p>
      </div>
      <div v-if="$slots.action" class="board-header__action">
        <slot name="action" />
      </div>
    </div>
  </header>
</template>

<style lang="less" scoped>
@import (reference) '../styles/breakpoints.less';

.board-header {
  // 顶部留出刘海屏的安全区
  padding-top: calc(var(--tm-space-5) + env(safe-area-inset-top));
  padding-bottom: var(--tm-space-4);
  background: var(--tm-navy);
  border-bottom: 3px solid var(--tm-amber);
  color: #fff;
}

.board-header__inner {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--tm-space-3);
}

.board-header__text {
  min-width: 0;
}

.board-header__back {
  display: inline-flex;
  align-items: center;
  gap: var(--tm-space-1);
  min-height: var(--tm-tap-min);
  margin: calc(var(--tm-space-2) * -1) 0 0 calc(var(--tm-space-2) * -1);
  padding: 0 var(--tm-space-2);
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 80%);
  font: inherit;
  font-size: var(--tm-text-sm);
  cursor: pointer;

  &:hover {
    color: #fff;
  }
}

.board-header__title {
  margin: 0;
  font-size: var(--tm-text-xl);
  font-weight: 600;
  line-height: 1.3;
}

.board-header__subtitle {
  margin: var(--tm-space-1) 0 0;
  // 深色底上的次要文字：白色 80%，对比度仍 > 7:1
  color: rgb(255 255 255 / 80%);
  font-size: var(--tm-text-sm);
}

.board-header__action {
  flex: none;
}

@media (min-width: @bp-desktop) {
  .board-header {
    padding-top: var(--tm-space-5);
    padding-bottom: var(--tm-space-2);
    background: transparent;
    border-bottom: 0;
    color: var(--tm-navy);
  }

  .board-header__back {
    color: var(--tm-muted);

    &:hover {
      color: var(--tm-navy);
    }
  }

  .board-header__subtitle {
    color: var(--tm-muted);
  }
}
</style>
