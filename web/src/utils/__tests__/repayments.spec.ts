import { describe, expect, it } from 'vitest'

import type { RepaymentView } from '@/api/repayments'

import {
  applyRepaymentUpdates,
  canCancelRepayment,
  canConfirmRepayment,
  groupActions,
  groupRepayments,
  repaymentStatusLabel,
} from '../repayments'

function repayment(id: number, over: Partial<RepaymentView> = {}): RepaymentView {
  return {
    id,
    settlementGroupId: `g${id}`,
    expenseId: null,
    fromMemberId: 5,
    toMemberId: 3,
    clearedAmountMinor: 100000,
    clearedCurrency: 'JPY',
    paidAmountMinor: 100000,
    paidCurrency: 'JPY',
    status: 'pending',
    confirmedAt: null,
    note: null,
    createdByMemberId: 5,
    version: 0,
    createdAt: '2026-10-10T09:00:00Z',
    ...over,
  }
}

describe('repaymentStatusLabel', () => {
  it('names the three statuses', () => {
    expect(repaymentStatusLabel('pending')).toBe('待确认')
    expect(repaymentStatusLabel('confirmed')).toBe('已确认')
    expect(repaymentStatusLabel('cancelled')).toBe('已取消')
  })
})

describe('permissions', () => {
  it('lets only the receiver confirm, and only while pending', () => {
    expect(canConfirmRepayment(repayment(1), 3)).toBe(true)
    expect(canConfirmRepayment(repayment(1), 5)).toBe(false)
    expect(canConfirmRepayment(repayment(1), 7)).toBe(false)
    expect(canConfirmRepayment(repayment(1, { status: 'confirmed' }), 3)).toBe(false)
    expect(canConfirmRepayment(repayment(1, { status: 'cancelled' }), 3)).toBe(false)
  })

  it('lets either side cancel while pending, and nobody else', () => {
    expect(canCancelRepayment(repayment(1), 3)).toBe(true)
    expect(canCancelRepayment(repayment(1), 5)).toBe(true)
    expect(canCancelRepayment(repayment(1), 7)).toBe(false)
    expect(canCancelRepayment(repayment(1, { status: 'confirmed' }), 5)).toBe(false)
    expect(canCancelRepayment(repayment(1, { status: 'cancelled' }), 5)).toBe(false)
  })
})

describe('groupRepayments', () => {
  it('keeps the rows of one transfer together, in the order the groups first appear', () => {
    const list = [
      repayment(1, { settlementGroupId: 'a' }),
      repayment(2, { settlementGroupId: 'b' }),
      repayment(3, { settlementGroupId: 'a', clearedCurrency: 'HKD' }),
    ]

    const groups = groupRepayments(list)

    expect(groups.map((g) => g.settlementGroupId)).toEqual(['a', 'b'])
    expect(groups[0]!.items.map((r) => r.id)).toEqual([1, 3])
  })
})

describe('groupActions', () => {
  const two = (over: Partial<RepaymentView> = {}, second: Partial<RepaymentView> = {}) => ({
    settlementGroupId: 'a',
    items: [repayment(1, { settlementGroupId: 'a', ...over }), repayment(2, { settlementGroupId: 'a', clearedCurrency: 'HKD', ...second })],
  })

  it('offers nothing for a single-row transfer: the row actions already cover it', () => {
    expect(groupActions({ settlementGroupId: 'a', items: [repayment(1)] }, 3)).toEqual({ confirm: false, cancel: false })
  })

  it('lets the receiver confirm and either side cancel a pending transfer of several currencies', () => {
    expect(groupActions(two(), 3)).toEqual({ confirm: true, cancel: true })
    expect(groupActions(two(), 5)).toEqual({ confirm: false, cancel: true })
    expect(groupActions(two(), 7)).toEqual({ confirm: false, cancel: false })
  })

  it('offers nothing once nothing in the group is pending', () => {
    expect(groupActions(two({ status: 'confirmed' }, { status: 'confirmed' }), 3)).toEqual({ confirm: false, cancel: false })
  })

  it('ignores cancelled rows when counting the group', () => {
    expect(groupActions(two({}, { status: 'cancelled' }), 3)).toEqual({ confirm: false, cancel: false })
  })
})

describe('applyRepaymentUpdates', () => {
  it('replaces rows by id, keeps the rest and does not add rows that were not shown', () => {
    const list = [repayment(1), repayment(2)]
    const updates = [repayment(2, { status: 'cancelled', version: 1 }), repayment(9, { status: 'cancelled' })]

    const next = applyRepaymentUpdates(list, updates)

    expect(next.map((r) => [r.id, r.status])).toEqual([
      [1, 'pending'],
      [2, 'cancelled'],
    ])
  })
})
