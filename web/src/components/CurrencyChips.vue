<script setup lang="ts">
import { computed } from 'vue'

import { currencyColor } from '@/utils/currency'

// 币别快捷选项：一排可以直接点选的币别，点一下发 select。只管显示和点选，不知道是谁在用它。
// 当前值不在候选里时（比如从完整列表里选的），额外显示在最后，让用户看得到现在选的是哪个。
const props = defineProps<{
  /** 候选币别代码，按显示顺序 */
  options: string[]
  /** 当前选中的币别代码；没有选中时传空字符串 */
  selected: string
  /** 整组的无障碍名称 */
  label?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  select: [code: string]
}>()

const codes = computed(() => {
  if (!props.selected || props.options.includes(props.selected)) return props.options
  return [...props.options, props.selected]
})

function choose(code: string) {
  if (props.disabled || code === props.selected) return
  emit('select', code)
}
</script>

<template>
  <div class="currency-chips" role="radiogroup" :aria-label="label ?? '币别'">
    <button
      v-for="code in codes"
      :key="code"
      type="button"
      role="radio"
      class="currency-chips__item"
      :class="{ 'currency-chips__item--active': code === selected }"
      :style="{ '--cur': currencyColor(code) }"
      :aria-checked="code === selected"
      :disabled="disabled"
      @click="choose(code)"
    >
      <span class="currency-chips__bar" aria-hidden="true" />
      <span class="tm-num">{{ code }}</span>
    </button>
  </div>
</template>

<style lang="less" scoped>
.currency-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tm-space-2);
}

.currency-chips__item {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 72px;
  min-height: var(--tm-tap-min);
  padding: 0 var(--tm-space-3);
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
  color: var(--tm-navy);
  font: inherit;
  font-size: var(--tm-text-sm);
  cursor: pointer;

  &:disabled {
    color: var(--tm-muted);
    cursor: not-allowed;
  }
}

.currency-chips__bar {
  width: 4px;
  height: 16px;
  border-radius: 2px;
  background: var(--cur);
}

// 选中：币别色的双倍描边 + 加粗，不用实心色块（同屏已有实心的提交按钮，每屏只有一个实心按钮）
.currency-chips__item--active {
  border-color: var(--cur);
  box-shadow: inset 0 0 0 1px var(--cur);
  font-weight: 600;
}
</style>
