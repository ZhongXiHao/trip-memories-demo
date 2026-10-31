import { describe, expect, it } from 'vitest'

import { expenseMenuItems, toSheetActions } from '../menus'

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
