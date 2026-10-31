<script setup lang="ts" generic="K extends string">
import { computed, ref } from 'vue'

import { toSheetActions, type MenuItem } from '@/utils/menus'

// 页头右上角的"⋯"：图标按钮 + 底部操作菜单（Vant ActionSheet）。
// 手机上页头是深色，图标是白色；PC 上页头退成浅色标题区，图标变成深色。
// 只负责显示和把选中的项报给页面；确认框和请求都在页面里。没有菜单项时什么都不显示。
const props = defineProps<{
  items: MenuItem<K>[]
  /** 图标按钮的无障碍名称，比如"行程操作" */
  label: string
  /** 菜单标题（比如行程名），可选 */
  title?: string
  disabled?: boolean
}>()

const emit = defineEmits<{ select: [key: K] }>()

const show = ref(false)
const actions = computed(() => toSheetActions(props.items))

function onSelect(action: { key?: K }) {
  show.value = false
  if (action.key) emit('select', action.key)
}
</script>

<template>
  <template v-if="items.length">
    <button
      type="button"
      class="more-menu"
      :aria-label="label"
      aria-haspopup="dialog"
      :disabled="disabled"
      @click="show = true"
    >
      <van-icon name="ellipsis" size="24" aria-hidden="true" />
    </button>
    <van-action-sheet
      v-model:show="show"
      :title="title"
      :actions="actions"
      cancel-text="取消"
      close-on-click-action
      @select="onSelect"
    />
  </template>
</template>

<style lang="less" scoped>
@import (reference) '../styles/breakpoints.less';

.more-menu {
  display: grid;
  place-items: center;
  width: var(--tm-tap-min);
  height: var(--tm-tap-min);
  margin-right: calc(var(--tm-space-2) * -1);
  border: 0;
  border-radius: 50%;
  background: transparent;
  // 深色页头上的白色图标
  color: var(--tm-surface);
  cursor: pointer;

  &:hover:not(:disabled) {
    background: rgb(255 255 255 / 12%);
  }

  &:disabled {
    cursor: default;
    opacity: 0.5;
  }
}

@media (min-width: @bp-desktop) {
  .more-menu {
    color: var(--tm-navy);

    &:hover:not(:disabled) {
      background: var(--tm-line);
    }
  }
}
</style>
