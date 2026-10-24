<script setup lang="ts">
// 深海军蓝页头 + 底部一条琥珀色信号线：这个应用的视觉签名，每个页面顶部都用它。
defineProps<{
  title: string
  subtitle?: string
}>()
</script>

<template>
  <header class="board-header">
    <div class="board-header__text">
      <h1 class="board-header__title">{{ title }}</h1>
      <p v-if="subtitle" class="board-header__subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.action" class="board-header__action">
      <slot name="action" />
    </div>
  </header>
</template>

<style lang="less" scoped>
.board-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--tm-space-3);
  // 顶部留出刘海屏的安全区
  padding: calc(var(--tm-space-5) + env(safe-area-inset-top)) var(--tm-space-4) var(--tm-space-4);
  background: var(--tm-navy);
  border-bottom: 3px solid var(--tm-amber);
  color: #fff;
}

.board-header__text {
  min-width: 0;
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
</style>
