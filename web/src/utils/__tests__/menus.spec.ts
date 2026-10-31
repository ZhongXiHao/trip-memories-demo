import { describe, expect, it } from 'vitest'

import { expenseMenuItems, toSheetActions, tripMenuItems } from '../menus'

describe('expenseMenuItems', () => {
  it('gives the payer or recorder edit and a dangerous delete', () => {
    expect(expenseMenuItems(true)).toEqual([
      { key: 'edit', name: '修改支出' },
      { key: 'delete', name: '删除支出', danger: true },
    ])
  })

  it('is empty for everyone else, so the page shows no menu button', () => {
    expect(expenseMenuItems(false)).toEqual([])
  })
})

describe('toSheetActions', () => {
  it('colours only the dangerous items', () => {
    expect(toSheetActions(expenseMenuItems(true))).toEqual([
      { key: 'edit', name: '修改支出' },
      { key: 'delete', name: '删除支出', color: 'var(--tm-danger)' },
    ])
  })
})

describe('tripMenuItems', () => {
  it('lets the owner edit or delete the trip, with delete marked dangerous', () => {
    expect(tripMenuItems('owner')).toEqual([
      { key: 'edit', name: '编辑行程' },
      { key: 'delete', name: '删除行程', danger: true },
    ])
  })

  it('lets other members only quit, marked dangerous', () => {
    expect(tripMenuItems('member')).toEqual([{ key: 'quit', name: '退出行程', danger: true }])
  })

  it('never offers quitting to the owner (they have to hand over first)', () => {
    expect(tripMenuItems('owner').map((i) => i.key)).not.toContain('quit')
  })
})
