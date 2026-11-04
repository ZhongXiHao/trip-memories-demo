import { AxiosError, AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { beforeEach, describe, expect, it } from 'vitest'

import { ApiError } from '../errors'
import { http } from '../http'
import {
  cancelRepayment,
  cancelRepaymentGroup,
  confirmRepayment,
  confirmRepaymentGroup,
  createRepayment,
  listRepayments,
  type CreateRepaymentRequest,
  type RepaymentView,
} from '../repayments'

const groupId = '7d9c3f0e-1a2b-4c3d-8e9f-0a1b2c3d4e5f'

const repayment: RepaymentView = {
  id: 8,
  settlementGroupId: groupId,
  expenseId: null,
  fromMemberId: 5,
  toMemberId: 3,
  clearedAmountMinor: 820000,
  clearedCurrency: 'JPY',
  paidAmountMinor: 820000,
  paidCurrency: 'JPY',
  status: 'pending',
  confirmedAt: null,
  note: null,
  createdByMemberId: 5,
  version: 0,
  createdAt: '2026-10-10T09:00:00Z',
}

const request: CreateRepaymentRequest = {
  clientUuid: groupId,
  fromMemberId: 5,
  toMemberId: 3,
  paidCurrency: 'JPY',
  items: [{ clearedCurrency: 'JPY', clearedAmountMinor: 820000, paidAmountMinor: 820000 }],
}

interface Seen {
  method?: string
  url?: string
  params?: unknown
  body?: unknown
}

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

describe('repayments api', () => {
  let seen: Seen

  beforeEach(() => {
    seen = {}
  })

  it('createRepayment reports a first create (201) as not replayed', async () => {
    const group = { settlementGroupId: groupId, items: [repayment] }
    http.defaults.adapter = fakeServer(201, group, seen)

    expect(await createRepayment(1, request)).toEqual({ group, replayed: false })
    expect(seen).toMatchObject({ method: 'post', url: '/trips/1/repayments', body: request })
  })

  it('createRepayment treats a same-content resubmit (200) as success, flagged as replayed', async () => {
    const group = { settlementGroupId: groupId, items: [repayment] }
    http.defaults.adapter = fakeServer(200, group, seen)

    expect(await createRepayment(1, request)).toEqual({ group, replayed: true })
  })

  it('createRepayment turns an over-repayment into an ApiError with the code', async () => {
    http.defaults.adapter = fakeServer(400, { code: 'REPAYMENT_EXCEEDS_DEBT', message: 'x' }, seen)

    const error = await createRepayment(1, request).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 400, code: 'REPAYMENT_EXCEEDS_DEBT' })
  })

  it('createRepayment turns a reused idempotency key (409) into an ApiError with the code', async () => {
    http.defaults.adapter = fakeServer(409, { code: 'IDEMPOTENCY_KEY_REUSED', message: 'x' }, seen)

    const error = await createRepayment(1, request).catch((e: unknown) => e)

    expect(error).toMatchObject({ status: 409, code: 'IDEMPOTENCY_KEY_REUSED' })
  })

  it('listRepayments unwraps items and sends no params by default', async () => {
    http.defaults.adapter = fakeServer(200, { items: [repayment] }, seen)

    expect(await listRepayments(1)).toEqual([repayment])
    expect(seen).toMatchObject({ method: 'get', url: '/trips/1/repayments', params: {} })
  })

  it('listRepayments passes the filters and drops the empty ones', async () => {
    http.defaults.adapter = fakeServer(200, { items: [] }, seen)

    await listRepayments(1, { status: 'pending', memberId: 5, settlementGroupId: '' })

    expect(seen.params).toEqual({ status: 'pending', memberId: 5 })
  })

  it('confirmRepayment posts to the single confirm path and returns the repayment', async () => {
    const confirmed = { ...repayment, status: 'confirmed', confirmedAt: '2026-10-11T00:00:00Z', version: 1 }
    http.defaults.adapter = fakeServer(200, confirmed, seen)

    expect(await confirmRepayment(1, 8)).toEqual(confirmed)
    expect(seen).toMatchObject({ method: 'post', url: '/trips/1/repayments/8/confirm' })
  })

  it('confirmRepaymentGroup posts to the group path and unwraps every row, cancelled ones included', async () => {
    const rows = [
      { ...repayment, status: 'confirmed' },
      { ...repayment, id: 9, clearedCurrency: 'HKD', status: 'cancelled' },
    ]
    http.defaults.adapter = fakeServer(200, { items: rows }, seen)

    expect(await confirmRepaymentGroup(1, groupId)).toEqual(rows)
    expect(seen).toMatchObject({ method: 'post', url: `/trips/1/repayments/groups/${groupId}/confirm` })
  })

  it('cancelRepayment posts to the single cancel path', async () => {
    const cancelled = { ...repayment, status: 'cancelled', version: 1 }
    http.defaults.adapter = fakeServer(200, cancelled, seen)

    expect(await cancelRepayment(1, 8)).toEqual(cancelled)
    expect(seen).toMatchObject({ method: 'post', url: '/trips/1/repayments/8/cancel' })
  })

  it('cancelRepayment turns an already-confirmed repayment into an ApiError with the code', async () => {
    http.defaults.adapter = fakeServer(409, { code: 'REPAYMENT_ALREADY_CONFIRMED', message: 'x' }, seen)

    const error = await cancelRepayment(1, 8).catch((e: unknown) => e)

    expect(error).toMatchObject({ status: 409, code: 'REPAYMENT_ALREADY_CONFIRMED' })
  })

  it('cancelRepaymentGroup posts to the group cancel path and unwraps the rows', async () => {
    http.defaults.adapter = fakeServer(200, { items: [{ ...repayment, status: 'cancelled' }] }, seen)

    expect(await cancelRepaymentGroup(1, groupId)).toHaveLength(1)
    expect(seen).toMatchObject({ method: 'post', url: `/trips/1/repayments/groups/${groupId}/cancel` })
  })
})
