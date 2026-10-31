import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as currenciesApi from '@/api/currencies'
import { ApiError } from '@/api/errors'

import { useCurrencyStore } from '../currencies'

vi.mock('@/api/currencies')

const list = [
  { code: 'HKD', name: '港币', roundingUnit: 1 },
  { code: 'JPY', name: '日元', roundingUnit: 100 },
]

describe('currency store', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    setActivePinia(createPinia())
  })

  it('loads the list once even when several components ask at the same time', async () => {
    vi.mocked(currenciesApi.listCurrencies).mockResolvedValue(list)
    const store = useCurrencyStore()

    await Promise.all([store.ensureLoaded(), store.ensureLoaded()])
    await store.ensureLoaded()

    expect(currenciesApi.listCurrencies).toHaveBeenCalledTimes(1)
    expect(store.roundingUnitOf('JPY')).toBe(100)
    expect(store.roundingUnitOf('HKD')).toBe(1)
  })

  it('knows nothing before loading or for a code not in the list', () => {
    const store = useCurrencyStore()

    expect(store.roundingUnitOf('JPY')).toBeUndefined()
    expect(store.roundingUnitOf('XYZ')).toBeUndefined()
  })

  it('tries again next time after a failure', async () => {
    vi.mocked(currenciesApi.listCurrencies)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'down'))
      .mockResolvedValueOnce(list)
    const store = useCurrencyStore()

    await expect(store.ensureLoaded()).rejects.toBeInstanceOf(ApiError)
    await store.ensureLoaded()

    expect(store.loaded).toBe(true)
    expect(currenciesApi.listCurrencies).toHaveBeenCalledTimes(2)
  })
})
