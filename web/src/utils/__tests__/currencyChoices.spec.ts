import { describe, expect, it } from 'vitest'

import type { ExpenseView } from '@/api/expenses'

import { CURRENCY_CHOICE_LIMIT, currencyChoices, recentCurrencies } from '../currencyChoices'

function expense(id: number, currency: string, createdAt: string, occurredAt = createdAt): ExpenseView {
  return {
    id,
    tripId: 1,
    title: `#${id}`,
    amountMinor: 100,
    currency,
    paidByMemberId: 3,
    createdByMemberId: 3,
    splitMode: 'equal',
    visibility: 'trip',
    splits: [],
    occurredAt,
    occurredTz: 'Asia/Tokyo',
    dayIndex: 1,
    category: 'food',
    note: null,
    source: 'form',
    version: 0,
    createdAt,
  }
}

describe('recentCurrencies', () => {
  it('is empty when the trip has no expenses', () => {
    expect(recentCurrencies([])).toEqual([])
  })

  it('lists currencies from the most recently recorded, without repeats', () => {
    const expenses = [
      expense(1, 'JPY', '2026-10-01T01:00:00Z'),
      expense(2, 'HKD', '2026-10-02T01:00:00Z'),
      expense(3, 'JPY', '2026-10-03T01:00:00Z'),
      expense(4, 'USD', '2026-10-01T12:00:00Z'),
    ]
    expect(recentCurrencies(expenses)).toEqual(['JPY', 'HKD', 'USD'])
  })

  it('goes by when the expense was recorded, not when it happened', () => {
    // 第 2 笔是后来补记的前几天的消费，它的币别才是最近用过的
    const expenses = [
      expense(1, 'JPY', '2026-10-03T01:00:00Z', '2026-10-03T01:00:00Z'),
      expense(2, 'KRW', '2026-10-04T01:00:00Z', '2026-09-30T01:00:00Z'),
    ]
    expect(recentCurrencies(expenses)).toEqual(['KRW', 'JPY'])
  })

  it('breaks ties on the same moment by the newer id', () => {
    const at = '2026-10-03T01:00:00Z'
    expect(recentCurrencies([expense(7, 'JPY', at), expense(9, 'EUR', at)])).toEqual(['EUR', 'JPY'])
  })

  it('does not reorder the list it was given', () => {
    const expenses = [expense(1, 'JPY', '2026-10-01T01:00:00Z'), expense(2, 'HKD', '2026-10-02T01:00:00Z')]
    recentCurrencies(expenses)
    expect(expenses.map((e) => e.id)).toEqual([1, 2])
  })
})

describe('currencyChoices', () => {
  it('puts the default currency first, then the recent ones', () => {
    expect(currencyChoices('JPY', ['HKD', 'USD'])).toEqual(['JPY', 'HKD', 'USD'])
  })

  it('does not repeat the default currency when it was also used recently', () => {
    expect(currencyChoices('JPY', ['HKD', 'JPY', 'USD'])).toEqual(['JPY', 'HKD', 'USD'])
    expect(currencyChoices('JPY', ['JPY'])).toEqual(['JPY'])
  })

  it('removes repeats among the recent ones', () => {
    expect(currencyChoices('JPY', ['HKD', 'HKD', 'USD'])).toEqual(['JPY', 'HKD', 'USD'])
  })

  it(`keeps at most ${CURRENCY_CHOICE_LIMIT} by default`, () => {
    expect(currencyChoices('JPY', ['HKD', 'USD', 'EUR', 'KRW'])).toEqual(['JPY', 'HKD', 'USD'])
  })

  it('takes a different limit', () => {
    expect(currencyChoices('JPY', ['HKD', 'USD'], 2)).toEqual(['JPY', 'HKD'])
    expect(currencyChoices('JPY', ['HKD'], 0)).toEqual([])
  })

  it('uses only the recent ones when the default currency is missing', () => {
    expect(currencyChoices(undefined, ['HKD', 'USD'])).toEqual(['HKD', 'USD'])
    expect(currencyChoices(null, ['HKD'])).toEqual(['HKD'])
    expect(currencyChoices('', ['HKD', 'USD', 'EUR', 'KRW'])).toEqual(['HKD', 'USD', 'EUR'])
  })

  it('is just the default currency when nothing was used yet', () => {
    expect(currencyChoices('JPY', [])).toEqual(['JPY'])
  })

  it('is empty when there is nothing at all', () => {
    expect(currencyChoices(undefined, [])).toEqual([])
  })

  it('skips empty codes', () => {
    expect(currencyChoices('JPY', ['', 'HKD'])).toEqual(['JPY', 'HKD'])
  })
})
