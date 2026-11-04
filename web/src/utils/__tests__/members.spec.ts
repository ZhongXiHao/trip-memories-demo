import { describe, expect, it } from 'vitest'

import type { MemberView } from '@/api/members'

import { roleLabel, splitMembers, unsettledDebtsOf } from '../members'

function member(memberId: number, role: MemberView['role'], status: MemberView['status']): MemberView {
  return { memberId, userId: memberId + 100, nickname: `m${memberId}`, role, status, joinedAt: '2026-10-01T00:00:00Z' }
}

describe('splitMembers', () => {
  it('separates active from left members', () => {
    const { active, left } = splitMembers([member(1, 'owner', 'active'), member(2, 'member', 'left'), member(3, 'member', 'active')])
    expect(active.map((m) => m.memberId)).toEqual([1, 3])
    expect(left.map((m) => m.memberId)).toEqual([2])
  })

  it('puts the owner first even when someone with a smaller memberId joined earlier', () => {
    // 转让之后，负责人可能是后加入的人
    const { active } = splitMembers([member(1, 'member', 'active'), member(2, 'owner', 'active'), member(3, 'member', 'active')])
    expect(active.map((m) => m.memberId)).toEqual([2, 1, 3])
  })

  it('orders everyone else by memberId regardless of the input order', () => {
    const { active, left } = splitMembers([
      member(9, 'member', 'active'),
      member(4, 'member', 'left'),
      member(1, 'owner', 'active'),
      member(2, 'member', 'left'),
      member(5, 'member', 'active'),
    ])
    expect(active.map((m) => m.memberId)).toEqual([1, 5, 9])
    expect(left.map((m) => m.memberId)).toEqual([2, 4])
  })

  it('does not change the array it was given', () => {
    const input = [member(2, 'member', 'active'), member(1, 'owner', 'active')]
    splitMembers(input)
    expect(input.map((m) => m.memberId)).toEqual([2, 1])
  })

  it('handles an empty list', () => {
    expect(splitMembers([])).toEqual({ active: [], left: [] })
  })
})

describe('roleLabel', () => {
  it('calls the owner 负责人 (not 创建者: ownership can be transferred)', () => {
    expect(roleLabel('owner')).toBe('负责人')
    expect(roleLabel('member')).toBe('成员')
  })
})

describe('unsettledDebtsOf', () => {
  const debt = { fromMemberId: 5, toMemberId: 3, currency: 'JPY', amountMinor: 340000, pendingMinor: 0 }

  it('takes the debts carried by the UNSETTLED_DEBTS warning', () => {
    expect(unsettledDebtsOf([{ code: 'UNSETTLED_DEBTS', message: 'x', debts: [debt] }])).toEqual([debt])
  })

  it('is empty without that warning, or when the warning carries no debts', () => {
    expect(unsettledDebtsOf([])).toEqual([])
    expect(unsettledDebtsOf([{ code: 'UNSETTLED_DEBTS', message: 'x' }])).toEqual([])
    expect(unsettledDebtsOf([{ code: 'SOMETHING_ELSE', message: 'x', debts: [debt] }])).toEqual([])
  })
})
