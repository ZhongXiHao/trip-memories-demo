<script setup lang="ts">
import { computed } from 'vue'

import { currencyColor } from '@/utils/currency'

// 币别标签：一小段币别色条 + 币别代码（等宽）。颜色只是辅助，代码文字才是信息本身。
const props = defineProps<{ code: string }>()

const color = computed(() => currencyColor(props.code))
</script>

<template>
  <span class="currency-tag" :style="{ '--cur': color }">
    <span class="currency-tag__bar" aria-hidden="true" />
    <span class="currency-tag__code tm-num">{{ code }}</span>
  </span>
</template>

<style lang="less" scoped>
.currency-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.currency-tag__bar {
  width: 4px;
  height: 16px;
  border-radius: 2px;
  background: var(--cur);
}

.currency-tag__code {
  font-size: var(--tm-text-sm);
  font-weight: 600;
}
</style>
