<script setup lang="ts">
import { computed, onMounted } from 'vue'

import { useCurrencyStore } from '@/stores/currencies'
import { currencyColor } from '@/utils/currency'
import { formatMinor } from '@/utils/money'

// 金额条：币别色条 + 币别代码 + 等宽数字（见 DESIGN.md「记忆点」）。
// 小数位按 GET /currencies 的 roundingUnit：JPY 1200 显示"1,200"，HKD 12.5 显示"12.50"。
// 颜色只是辅助，币别代码一定同时写出来。
const props = withDefaults(
  defineProps<{
    amountMinor: number
    currency: string
    /** md：列表行里；lg：详情页的总额 */
    size?: 'sm' | 'md' | 'lg'
  }>(),
  { size: 'md' },
)

const currencies = useCurrencyStore()

// 失败也不要紧：没有 roundingUnit 时 formatMinor 有保守的显示方式，下次用到时会再取
onMounted(() => currencies.ensureLoaded().catch(() => {}))

const text = computed(() => formatMinor(props.amountMinor, currencies.roundingUnitOf(props.currency)))
const color = computed(() => currencyColor(props.currency))
</script>

<template>
  <span class="currency-amount" :class="`currency-amount--${size}`" :style="{ '--cur': color }">
    <span class="currency-amount__bar" aria-hidden="true" />
    <span class="currency-amount__code tm-num">{{ currency }}</span>
    <span class="currency-amount__value tm-num">{{ text }}</span>
  </span>
</template>

<style lang="less" scoped>
.currency-amount {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.currency-amount__bar {
  flex: none;
  width: 4px;
  height: 16px;
  border-radius: 2px;
  background: var(--cur);
}

.currency-amount__code {
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
  font-weight: 600;
}

.currency-amount__value {
  font-size: var(--tm-text-md);
  font-weight: 600;
}

.currency-amount--sm {
  .currency-amount__bar {
    height: 12px;
  }

  .currency-amount__value {
    font-size: var(--tm-text-sm);
  }
}

.currency-amount--lg {
  gap: var(--tm-space-2);

  .currency-amount__bar {
    width: 6px;
    height: 28px;
    border-radius: 3px;
  }

  .currency-amount__code {
    font-size: var(--tm-text-sm);
  }

  .currency-amount__value {
    font-size: var(--tm-text-xl);
  }
}
</style>
