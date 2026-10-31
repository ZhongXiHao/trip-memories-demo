import { describe, expect, it } from 'vitest'

import { ApiError } from '@/api/errors'
import type { ExpenseView } from '@/api/expenses'

import {
  balanceText,
  customBalance,
  expenseToFormState,
  hasErrors,
  newExpenseFormState,
  serverFieldErrors,
  toExpenseFields,
  validateExpenseForm,
  type ExpenseFormState,
} from '../expenseForm'
import { newClientUuid } from '../uuid'

const JPY = 100
const HKD = 1
const ME = 3

function form(overrides: Partial<ExpenseFormState> = {}): ExpenseFormState {
  return {
    ...newExpenseFormState({
      myMemberId: ME,
      activeMemberIds: [3, 5, 7],
      currency: 'JPY',
      timezone: 'Asia/Tokyo',
      now: { date: '2026-10-03', time: '20:00' },
    }),
    title: '拉麵',
    amount: '1000',
    ...overrides,
  }
}

describe('newExpenseFormState', () => {
  it('starts with me paying and everyone active splitting equally, at the trip-zone time', () => {
    const state = newExpenseFormState({
      myMemberId: ME,
      activeMemberIds: [3, 5],
      currency: 'HKD',
      timezone: 'Asia/Tokyo',
      now: { date: '2026-10-03', time: '20:00' },
    })

    expect(state).toMatchObject({
      paidByMemberId: ME,
      splitMode: 'equal',
      participants: [3, 5],
      currency: 'HKD',
      occurredTz: 'Asia/Tokyo',
      date: '2026-10-03',
      time: '20:00',
      category: 'other',
    })
  })
})

describe('validateExpenseForm', () => {
  it('accepts a complete equal split', () => {
    expect(validateExpenseForm(form(), JPY, ME)).toEqual({})
  })

  it('asks for a title, a positive amount and a payer', () => {
    const errors = validateExpenseForm(form({ title: '  ', amount: '0', paidByMemberId: null }), JPY, ME)

    expect(Object.keys(errors).sort()).toEqual(['amount', 'paidByMemberId', 'title'])
    expect(hasErrors(errors)).toBe(true)
  })

  it('limits the title to 128 and the note to 512 characters', () => {
    const errors = validateExpenseForm(form({ title: 'a'.repeat(129), note: 'b'.repeat(513) }), JPY, ME)

    expect(Object.keys(errors).sort()).toEqual(['note', 'title'])
    expect(validateExpenseForm(form({ title: 'a'.repeat(128), note: 'b'.repeat(512) }), JPY, ME)).toEqual({})
  })

  it('checks the decimals against the currency', () => {
    expect(validateExpenseForm(form({ amount: '1000.5' }), JPY, ME).amount).toBe('这个币别只能填整数')
    expect(validateExpenseForm(form({ amount: '1000.5', currency: 'HKD' }), HKD, ME)).toEqual({})
  })

  it('needs at least one person to split with', () => {
    expect(validateExpenseForm(form({ participants: [] }), JPY, ME).splits).toBe('至少选一个人分摊')
  })

  it('does not need participants for a personal expense, but I must be the payer', () => {
    expect(validateExpenseForm(form({ splitMode: 'none', participants: [] }), JPY, ME)).toEqual({})
    expect(validateExpenseForm(form({ splitMode: 'none', paidByMemberId: 5 }), JPY, ME).paidByMemberId).toBe(
      '个人支出只能由自己付款',
    )
  })

  it('requires custom amounts to add up to the total, and says by how much they are off', () => {
    const custom = { splitMode: 'custom' as const, participants: [3, 5] }

    const short = validateExpenseForm(form({ ...custom, customAmounts: { 3: '700', 5: '200' } }), JPY, ME)
    const over = validateExpenseForm(form({ ...custom, customAmounts: { 3: '700', 5: '400' } }), JPY, ME)
    const exact = validateExpenseForm(form({ ...custom, customAmounts: { 3: '700', 5: '300' } }), JPY, ME)

    expect(short.splits).toBe('每人的金额加起来要等于总额：还差 100 没分')
    expect(over.splits).toBe('每人的金额加起来要等于总额：多分了 100')
    expect(exact).toEqual({})
  })

  it('reports a bad custom amount on that person and allows zero', () => {
    const errors = validateExpenseForm(
      form({ splitMode: 'custom', participants: [3, 5, 7], customAmounts: { 3: '1000', 5: '0', 7: 'x' } }),
      JPY,
      ME,
    )

    expect(errors.splitAmounts).toEqual({ 7: '金额只能是数字' })
    expect(errors.splits).toBeUndefined()
  })

  it('ignores custom amounts of people who are no longer selected', () => {
    const state = form({ splitMode: 'custom', participants: [3], customAmounts: { 3: '1000', 5: 'x' } })

    expect(validateExpenseForm(state, JPY, ME)).toEqual({})
  })

  it('needs a valid date and time', () => {
    expect(validateExpenseForm(form({ date: '' }), JPY, ME).occurredAt).toBe('请选择日期和时间')
  })
})

describe('customBalance / balanceText', () => {
  it('tracks what is still left to assign', () => {
    const state = form({ splitMode: 'custom', participants: [3, 5], customAmounts: { 3: '600' } })

    expect(customBalance(state, JPY)).toEqual({ assignedMinor: 60000, remainingMinor: 40000 })
    expect(balanceText(40000, JPY)).toBe('还差 400 没分')
    expect(balanceText(-150, HKD)).toBe('多分了 1.50')
    expect(balanceText(0, HKD)).toBeNull()
  })

  it('has no remaining amount while the total is not filled in', () => {
    expect(customBalance(form({ amount: '' }), JPY).remainingMinor).toBeNull()
  })
})

describe('toExpenseFields', () => {
  it('builds an equal split with members only, and the time in UTC', () => {
    expect(toExpenseFields(form({ note: '  ' }), JPY)).toEqual({
      title: '拉麵',
      amountMinor: 100000,
      currency: 'JPY',
      paidByMemberId: 3,
      splitMode: 'equal',
      splits: [{ memberId: 3 }, { memberId: 5 }, { memberId: 7 }],
      occurredAt: '2026-10-03T11:00:00Z',
      occurredTz: 'Asia/Tokyo',
      category: 'other',
      note: null,
    })
  })

  it('sends each amount for a custom split, only for the selected people', () => {
    const state = form({
      splitMode: 'custom',
      participants: [5, 3],
      customAmounts: { 3: '700', 5: '300', 7: '999' },
    })

    expect(toExpenseFields(state, JPY).splits).toEqual([
      { memberId: 5, amountMinor: 30000 },
      { memberId: 3, amountMinor: 70000 },
    ])
  })

  it('sends no splits for a personal expense', () => {
    expect(toExpenseFields(form({ splitMode: 'none', participants: [3] }), JPY)).not.toHaveProperty('splits')
  })

  it('leaves the time out when editing without touching it, so the backend keeps the original', () => {
    const original = { date: '2026-10-03', time: '20:00', occurredTz: 'Asia/Tokyo' }

    const untouched = toExpenseFields(form(), JPY, original)
    const moved = toExpenseFields(form({ time: '21:00' }), JPY, original)

    expect(untouched).not.toHaveProperty('occurredAt')
    expect(untouched).not.toHaveProperty('occurredTz')
    expect(moved).toMatchObject({ occurredAt: '2026-10-03T12:00:00Z', occurredTz: 'Asia/Tokyo' })
  })
})

describe('expenseToFormState', () => {
  const expense: ExpenseView = {
    id: 12,
    tripId: 1,
    title: '拉麵',
    amountMinor: 100000,
    currency: 'JPY',
    paidByMemberId: 3,
    createdByMemberId: 3,
    splitMode: 'equal',
    visibility: 'trip',
    splits: [
      { memberId: 3, amountMinor: 33400 },
      { memberId: 5, amountMinor: 33300 },
      { memberId: 7, amountMinor: 33300 },
    ],
    occurredAt: '2026-10-03T11:00:00Z',
    occurredTz: 'Asia/Tokyo',
    dayIndex: 3,
    category: 'food',
    note: null,
    source: 'form',
    version: 2,
    createdAt: '2026-10-03T11:02:10Z',
  }

  it('fills the form in local time and major units, and prefills each share', () => {
    expect(expenseToFormState(expense, JPY)).toEqual({
      title: '拉麵',
      amount: '1000',
      currency: 'JPY',
      paidByMemberId: 3,
      splitMode: 'equal',
      participants: [3, 5, 7],
      customAmounts: { 3: '334', 5: '333', 7: '333' },
      date: '2026-10-03',
      time: '20:00',
      occurredTz: 'Asia/Tokyo',
      category: 'food',
      note: '',
    })
  })

  it('round-trips an unchanged edit into the same splits', () => {
    const state = expenseToFormState({ ...expense, splitMode: 'custom' }, JPY)

    expect(validateExpenseForm(state, JPY, ME)).toEqual({})
    expect(toExpenseFields(state, JPY).splits).toEqual(
      expense.splits.map((s) => ({ memberId: s.memberId, amountMinor: s.amountMinor })),
    )
  })

  it('has no participants for a personal expense', () => {
    const personal = { ...expense, splitMode: 'none' as const, splits: [{ memberId: 3, amountMinor: 100000 }] }

    expect(expenseToFormState(personal, JPY).participants).toEqual([])
  })
})

describe('serverFieldErrors', () => {
  it('maps backend field names onto the form fields', () => {
    const error = new ApiError(400, 'VALIDATION_FAILED', 'bad', [
      { field: 'amountMinor', message: 'must be > 0' },
      { field: 'splitMode', message: 'Cannot switch' },
      { field: 'unknown', message: '?' },
    ])

    expect(Object.keys(serverFieldErrors(error)).sort()).toEqual(['amount', 'splitMode'])
  })

  it('maps the expense error codes that belong to a field', () => {
    const codes = ['AMOUNT_NOT_ROUNDABLE', 'SPLIT_SUM_MISMATCH', 'MEMBER_LEFT', 'MEMBER_NOT_IN_TRIP']
    const fields = codes.map((code) => Object.keys(serverFieldErrors(new ApiError(400, code, ''))))

    expect(fields).toEqual([['amount'], ['splits'], ['splits'], ['splits']])
  })

  it('leaves other errors to the page', () => {
    expect(serverFieldErrors(new ApiError(409, 'VERSION_CONFLICT', ''))).toEqual({})
    expect(serverFieldErrors(new ApiError(403, 'FORBIDDEN', ''))).toEqual({})
  })
})

describe('newClientUuid', () => {
  it('makes a fresh v4 UUID each time', () => {
    const a = newClientUuid()
    const b = newClientUuid()

    expect(a).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(a).not.toBe(b)
  })
})
