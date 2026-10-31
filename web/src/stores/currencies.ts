import { defineStore } from 'pinia'
import { ref } from 'vue'

import { listCurrencies, type Currency } from '@/api/currencies'

/**
 * 币别列表（GET /currencies）。显示金额要按币别的 roundingUnit 决定小数位，
 * 列表页每一行都要用，所以整个应用只取一次。
 */
export const useCurrencyStore = defineStore('currencies', () => {
  const items = ref<Currency[]>([])
  const loaded = ref(false)
  let pending: Promise<void> | null = null

  /** 还没取过才取；多处同时调用只发一次请求。失败时抛 ApiError，下次调用会重试。 */
  function ensureLoaded(): Promise<void> {
    if (loaded.value) return Promise.resolve()
    pending ??= listCurrencies()
      .then((list) => {
        items.value = list
        loaded.value = true
      })
      .finally(() => {
        pending = null
      })
    return pending
  }

  function find(code: string): Currency | undefined {
    return items.value.find((c) => c.code === code)
  }

  /** 列表还没回来、或者是列表里没有的币别时为 undefined，调用方要能处理 */
  function roundingUnitOf(code: string): number | undefined {
    const unit = find(code)?.roundingUnit
    return typeof unit === 'number' && unit > 0 ? unit : undefined
  }

  return { items, loaded, ensureLoaded, find, roundingUnitOf }
})
