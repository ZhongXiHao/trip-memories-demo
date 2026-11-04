import { AxiosError, AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { beforeEach, describe, expect, it } from 'vitest'

import { getDebtsWith, listDebts, type DebtView } from '../debts'
import { ApiError } from '../errors'
import { http } from '../http'

const debt: DebtView = { fromMemberId: 5, toMemberId: 3, currency: 'JPY', amountMinor: 340000, pendingMinor: 100000 }

interface Seen {
  method?: string
  url?: string
  params?: unknown
}

function fakeServer(status: number, data: unknown, seen: Seen): AxiosAdapter {
  return (config: InternalAxiosRequestConfig) => {
    seen.method = config.method
    seen.url = config.url
    seen.params = config.params
    const response = { status, data, statusText: '', headers: new AxiosHeaders(), config }
    if (status >= 400) {
      return Promise.reject(new AxiosError('failed', 'ERR_BAD_RESPONSE', config, null, response))
    }
    return Promise.resolve(response)
  }
}

describe('debts api', () => {
  let seen: Seen

  beforeEach(() => {
    seen = {}
  })

  it('listDebts unwraps items and sends no params by default', async () => {
    http.defaults.adapter = fakeServer(200, { items: [debt] }, seen)

    expect(await listDebts(1)).toEqual([debt])
    expect(seen).toMatchObject({ method: 'get', url: '/trips/1/debts', params: {} })
  })

  it('listDebts passes memberId and simplify=true', async () => {
    http.defaults.adapter = fakeServer(200, { items: [] }, seen)

    await listDebts(1, { memberId: 5, simplify: true })

    expect(seen.params).toEqual({ memberId: 5, simplify: 'true' })
  })

  it('listDebts leaves simplify out when it is false (the default on the server)', async () => {
    http.defaults.adapter = fakeServer(200, { items: [] }, seen)

    await listDebts(1, { simplify: false })

    expect(seen.params).toEqual({})
  })

  it('getDebtsWith asks for the pair with the given member', async () => {
    http.defaults.adapter = fakeServer(200, { items: [debt] }, seen)

    expect(await getDebtsWith(1, 3)).toEqual([debt])
    expect(seen).toMatchObject({ method: 'get', url: '/trips/1/debts/3' })
  })

  it('getDebtsWith keeps the field error when asking about oneself', async () => {
    const body = { code: 'VALIDATION_FAILED', message: 'x', fields: [{ field: 'memberId', message: 'is yourself' }] }
    http.defaults.adapter = fakeServer(400, body, seen)

    const error = await getDebtsWith(1, 5).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).fieldMessage('memberId')).toBe('is yourself')
  })
})
