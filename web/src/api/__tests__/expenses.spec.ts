import { AxiosError, AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { beforeEach, describe, expect, it } from 'vitest'

import { ApiError } from '../errors'
import {
  createExpense,
  deleteExpense,
  getExpense,
  listExpenses,
  previewExpense,
  updateExpense,
  type CreateExpenseRequest,
  type ExpenseView,
} from '../expenses'
import { http } from '../http'

const expense: ExpenseView = {
  id: 12,
  tripId: 1,
  title: '拉麵',
  amountMinor: 120000,
  currency: 'JPY',
  paidByMemberId: 3,
  createdByMemberId: 3,
  splitMode: 'equal',
  visibility: 'trip',
  splits: [
    { memberId: 3, amountMinor: 60000 },
    { memberId: 5, amountMinor: 60000 },
  ],
  occurredAt: '2026-10-03T11:00:00Z',
  occurredTz: 'Asia/Tokyo',
  dayIndex: 3,
  category: 'food',
  note: null,
  source: 'form',
  version: 0,
  createdAt: '2026-10-03T11:02:10Z',
}

const request: CreateExpenseRequest = {
  clientUuid: '0b6f5c3e-6f0a-4e8e-9a51-6a4f7c0d2f11',
  title: '拉麵',
  amountMinor: 120000,
  currency: 'JPY',
  paidByMemberId: 3,
  splitMode: 'equal',
  splits: [{ memberId: 3 }, { memberId: 5 }],
  occurredAt: '2026-10-03T11:00:00Z',
  occurredTz: 'Asia/Tokyo',
  category: 'food',
  note: null,
}

interface Seen {
  method?: string
  url?: string
  params?: unknown
  body?: unknown
}

/** 假的网络层：记录收到的请求，并用给定的状态码和响应体回答 */
function fakeServer(status: number, data: unknown, seen: Seen): AxiosAdapter {
  return (config: InternalAxiosRequestConfig) => {
    seen.method = config.method
    seen.url = config.url
    seen.params = config.params
    seen.body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data
    const response = { status, data, statusText: '', headers: new AxiosHeaders(), config }
    if (status >= 400) {
      return Promise.reject(new AxiosError('failed', 'ERR_BAD_RESPONSE', config, null, response))
    }
    return Promise.resolve(response)
  }
}

describe('expenses api', () => {
  let seen: Seen

  beforeEach(() => {
    seen = {}
  })

  it('previewExpense posts to /preview and returns the computed splits', async () => {
    const preview = {
      amountMinor: 100000,
      currency: 'JPY',
      splits: [
        { memberId: 3, amountMinor: 33400, remainderMinor: 100 },
        { memberId: 5, amountMinor: 33300 },
        { memberId: 7, amountMinor: 33300 },
      ],
      warnings: [],
    }
    http.defaults.adapter = fakeServer(200, preview, seen)
    const { clientUuid: _unused, ...fields } = request

    expect(await previewExpense(1, { ...fields, expenseId: 12 })).toEqual(preview)
    expect(seen).toMatchObject({
      method: 'post',
      url: '/trips/1/expenses/preview',
      body: { ...fields, expenseId: 12 },
    })
  })

  it('createExpense reports a first create (201) as not replayed', async () => {
    http.defaults.adapter = fakeServer(201, expense, seen)

    expect(await createExpense(1, request)).toEqual({ expense, replayed: false })
    expect(seen).toMatchObject({ method: 'post', url: '/trips/1/expenses', body: request })
  })

  it('createExpense treats a same-content resubmit (200) as success, flagged as replayed', async () => {
    http.defaults.adapter = fakeServer(200, expense, seen)

    expect(await createExpense(1, request)).toEqual({ expense, replayed: true })
  })

  it('createExpense turns a reused idempotency key (409) into an ApiError with the code', async () => {
    http.defaults.adapter = fakeServer(409, { code: 'IDEMPOTENCY_KEY_REUSED', message: 'x' }, seen)

    const error = await createExpense(1, request).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, code: 'IDEMPOTENCY_KEY_REUSED' })
  })

  it('previewExpense keeps the field errors so the form can show them under the inputs', async () => {
    const body = {
      code: 'VALIDATION_FAILED',
      message: 'bad',
      fields: [{ field: 'title', message: 'must not be blank' }],
    }
    http.defaults.adapter = fakeServer(400, body, seen)

    const error = await previewExpense(1, request).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).fieldMessage('title')).toBe('must not be blank')
  })

  it('listExpenses unwraps items and sends no params by default', async () => {
    http.defaults.adapter = fakeServer(200, { items: [expense] }, seen)

    expect(await listExpenses(1)).toEqual([expense])
    expect(seen).toMatchObject({ method: 'get', url: '/trips/1/expenses', params: {} })
  })

  it('listExpenses passes the filters as query params and drops the empty ones', async () => {
    http.defaults.adapter = fakeServer(200, { items: [] }, seen)

    await listExpenses(1, { date: '2026-10-03', memberId: 5, currency: '', category: undefined })

    expect(seen.params).toEqual({ date: '2026-10-03', memberId: 5 })
  })

  it('getExpense asks for the given expense of the given trip', async () => {
    http.defaults.adapter = fakeServer(200, expense, seen)

    expect(await getExpense(1, 12)).toEqual(expense)
    expect(seen).toMatchObject({ method: 'get', url: '/trips/1/expenses/12' })
  })

  it('updateExpense is a PUT of the full body with the version', async () => {
    const { clientUuid: _unused, ...fields } = request
    const body = { ...fields, version: 4 }
    http.defaults.adapter = fakeServer(200, { ...expense, version: 5 }, seen)

    expect((await updateExpense(1, 12, body)).version).toBe(5)
    expect(seen).toMatchObject({ method: 'put', url: '/trips/1/expenses/12', body })
  })

  it('deleteExpense is a POST to /delete (soft delete) and returns nothing', async () => {
    http.defaults.adapter = fakeServer(204, '', seen)

    expect(await deleteExpense(1, 12)).toBeUndefined()
    expect(seen).toMatchObject({ method: 'post', url: '/trips/1/expenses/12/delete' })
  })
})
