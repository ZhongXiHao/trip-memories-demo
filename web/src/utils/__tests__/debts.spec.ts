import { describe, expect, it } from 'vitest'

import type { DebtView } from '@/api/debts'

import { debtKey, debtSide, repayableMinor, repayRole, sortDebtsForMe } from '../debts'

function debt(from: number, to: number, currency = 'JPY', amountMinor = 100000, pendingMinor = 0): DebtView {
  return { fromMemberId: from, toMemberId: to, currency, amountMinor, pendingMinor }
}

describe('debtSide', () => {
  it('tells whether I owe, am owed, or am not involved', () => {
    expect(debtSide(debt(3, 5), 3)).toBe('i-owe')
    expect(debtSide(debt(5, 3), 3)).toBe('owes-me')
    expect(debtSide(debt(5, 7), 3)).toBe('others')
  })
})

describe('sortDebtsForMe', () => {
  it('puts what I owe first, then what is owed to me, then the rest, keeping the server order within each', () => {
    const list = [debt(1, 2), debt(1, 3, 'HKD'), debt(2, 3), debt(3, 1, 'JPY'), debt(3, 1, 'HKD'), debt(2, 4)]

    const sorted = sortDebtsForMe(list, 3)

    expect(sorted.map(debtKey)).toEqual(['3-1-JPY', '3-1-HKD', '1-3-HKD', '2-3-JPY', '1-2-JPY', '2-4-JPY'])
  })

  it('does not change the input array', () => {
    const list = [debt(1, 2), debt(3, 1)]
    sortDebtsForMe(list, 3)
    expect(list.map(debtKey)).toEqual(['1-2-JPY', '3-1-JPY'])
  })
})

describe('repayableMinor', () => {
  it('subtracts what is already marked as paid but not confirmed yet', () => {
    expect(repayableMinor(debt(5, 3, 'JPY', 340000, 100000))).toBe(240000)
    expect(repayableMinor(debt(5, 3, 'JPY', 340000, 0))).toBe(340000)
  })

  it('never goes below zero, even when the pending repayments exceed the debt', () => {
    // 支出被删之后，待确认的可能比现在的欠款还多
    expect(repayableMinor(debt(5, 3, 'JPY', 100000, 340000))).toBe(0)
  })
})

describe('repayRole', () => {
  it('lets the debtor mark it paid and the creditor record it received, and nobody else', () => {
    expect(repayRole(debt(5, 3), 5)).toBe('payer')
    expect(repayRole(debt(5, 3), 3)).toBe('receiver')
    expect(repayRole(debt(5, 3), 7)).toBeNull()
  })
})
