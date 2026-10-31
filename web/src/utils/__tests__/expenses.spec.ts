import { describe, expect, it } from 'vitest'

import type { ExpenseView } from '@/api/expenses'
import type { MemberView } from '@/api/members'

import {
  canModifyExpense,
  categoryLabel,
  dayLabel,
  groupByLocalDate,
  isMemberLeft,
  memberName,
  totalsByCurrency,
} from '../expenses'

function expense(id: number, occurredAt: string, occurredTz = 'Asia/Tokyo', dayIndex: number | null = 1): ExpenseView {
  return {
    id,
    tripId: 1,
    title: `#${id}`,
    amountMinor: 100,
    currency: 'JPY',
    paidByMemberId: 3,
    createdByMemberId: 5,
    splitMode: 'equal',
    visibility: 'trip',
    splits: [],
    occurredAt,
    occurredTz,
    dayIndex,
    category: 'food',
    note: null,
    source: 'form',
    version: 0,
    createdAt: occurredAt,
  }
}

const members: MemberView[] = [
  { memberId: 3, userId: 1, nickname: 'Ken', role: 'owner', status: 'active', joinedAt: '2026-09-01T00:00:00Z' },
  { memberId: 5, userId: 2, nickname: 'Amy', role: 'member', status: 'left', joinedAt: '2026-09-01T00:00:00Z' },
]

describe('groupByLocalDate', () => {
  it('groups by the local date of each expense and keeps the order from the backend', () => {
    const list = [
      expense(3, '2026-10-03T16:00:00Z', 'Asia/Tokyo', 3), // 东京 10/04 01:00
      expense(2, '2026-10-03T11:00:00Z', 'Asia/Tokyo', 2), // 东京 10/03 20:00
      expense(1, '2026-10-03T01:00:00Z', 'Asia/Tokyo', 2), // 东京 10/03 10:00
    ]

    const groups = groupByLocalDate(list)

    expect(groups.map((g) => [g.date, g.dayIndex, g.items.map((e) => e.id)])).toEqual([
      ['2026-10-04', 3, [3]],
      ['2026-10-03', 2, [2, 1]],
    ])
  })

  it('puts two zones landing on the same local date into one group', () => {
    const list = [
      expense(2, '2026-10-03T20:00:00Z', 'America/Los_Angeles'), // 洛杉矶 10/03 13:00
      expense(1, '2026-10-03T05:00:00Z', 'Asia/Tokyo'), // 东京 10/03 14:00
    ]

    expect(groupByLocalDate(list).map((g) => g.items.length)).toEqual([2])
  })

  it('tolerates an older backend without dayIndex', () => {
    const old = { ...expense(1, '2026-10-03T05:00:00Z') } as Partial<ExpenseView>
    delete old.dayIndex

    expect(groupByLocalDate([old as ExpenseView])[0]?.dayIndex).toBeNull()
  })
})

describe('dayLabel', () => {
  it('labels trip days and days before the start, and hides unknown ones', () => {
    expect([dayLabel(1), dayLabel(5), dayLabel(0), dayLabel(-2), dayLabel(null), dayLabel(undefined), dayLabel('3')]).toEqual([
      '第 1 天',
      '第 5 天',
      '出发前',
      '出发前',
      null,
      null,
      null,
    ])
  })
})

describe('labels and people', () => {
  it('names categories and files unknown ones under 其他', () => {
    expect(categoryLabel('food')).toBe('餐饮')
    expect(categoryLabel('spa')).toBe('其他')
  })

  it('finds members by memberId, including members who left', () => {
    expect(memberName(members, 5)).toBe('Amy')
    expect(isMemberLeft(members, 5)).toBe(true)
    expect(isMemberLeft(members, 3)).toBe(false)
    expect(memberName(members, 99)).toBe('成员 99')
  })

  it('lets only the payer or the person who recorded it change an expense', () => {
    const e = expense(1, '2026-10-03T05:00:00Z')
    expect([3, 5, 7].map((me) => canModifyExpense(e, me))).toEqual([true, true, false])
  })
})

describe('totalsByCurrency', () => {
  const list = [
    { ...expense(4, '2026-10-03T12:00:00Z'), currency: 'KRW', amountMinor: 3000000 },
    { ...expense(3, '2026-10-03T11:00:00Z'), currency: 'JPY', amountMinor: 120000 },
    { ...expense(2, '2026-10-03T10:00:00Z'), currency: 'KRW', amountMinor: 2000000 },
    { ...expense(1, '2026-10-03T09:00:00Z'), currency: 'HKD', amountMinor: 1250 },
  ]

  it('adds up each currency separately, without converting', () => {
    expect(totalsByCurrency(list, 'HKD')).toEqual([
      { currency: 'HKD', amountMinor: 1250 },
      { currency: 'JPY', amountMinor: 120000 },
      { currency: 'KRW', amountMinor: 5000000 },
    ])
  })

  it('puts the display currency first and sorts the rest by code', () => {
    expect(totalsByCurrency(list, 'KRW').map((t) => t.currency)).toEqual(['KRW', 'HKD', 'JPY'])
  })

  it('keeps the same order whatever order the expenses come in', () => {
    const orders = [list, [...list].reverse(), [list[2]!, list[0]!, list[3]!, list[1]!]]
    const seen = orders.map((items) => totalsByCurrency(items, 'JPY').map((t) => t.currency).join(','))
    expect(new Set(seen)).toEqual(new Set(['JPY,HKD,KRW']))
  })

  it('sorts by code alone when the display currency is not spent that day or not given', () => {
    expect(totalsByCurrency(list, 'CNY').map((t) => t.currency)).toEqual(['HKD', 'JPY', 'KRW'])
    expect(totalsByCurrency(list).map((t) => t.currency)).toEqual(['HKD', 'JPY', 'KRW'])
  })

  it('is empty for no expenses', () => {
    expect(totalsByCurrency([], 'HKD')).toEqual([])
  })
})
