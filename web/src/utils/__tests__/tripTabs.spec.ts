import { describe, expect, it } from 'vitest'

import { DEFAULT_TRIP_TAB, TRIP_TABS, queryWithTab, tabFromQuery } from '../tripTabs'

describe('TRIP_TABS', () => {
  it('is expenses, debts, members in that order, starting on expenses', () => {
    expect(TRIP_TABS.map((t) => [t.name, t.title])).toEqual([
      ['expenses', '支出'],
      ['debts', '欠款'],
      ['members', '成员'],
    ])
    expect(DEFAULT_TRIP_TAB).toBe('expenses')
  })
})

describe('tabFromQuery', () => {
  it('reads a known tab', () => {
    expect([tabFromQuery('expenses'), tabFromQuery('debts'), tabFromQuery('members')]).toEqual([
      'expenses',
      'debts',
      'members',
    ])
  })

  it('falls back to expenses when the tab is missing or unknown', () => {
    expect([tabFromQuery(undefined), tabFromQuery(null), tabFromQuery(''), tabFromQuery('Members'), tabFromQuery('x')]).toEqual([
      'expenses',
      'expenses',
      'expenses',
      'expenses',
      'expenses',
    ])
  })

  it('takes the first value when the parameter is repeated', () => {
    expect(tabFromQuery(['members', 'debts'])).toBe('members')
    expect(tabFromQuery([])).toBe('expenses')
  })
})

describe('queryWithTab', () => {
  it('writes a non-default tab into the query and keeps the other parameters', () => {
    expect(queryWithTab({ from: 'home' }, 'members')).toEqual({ from: 'home', tab: 'members' })
  })

  it('leaves the default tab out of the address', () => {
    expect(queryWithTab({ tab: 'members', from: 'home' }, 'expenses')).toEqual({ from: 'home' })
  })

  it('does not change the query it was given', () => {
    const query = { tab: 'debts' }
    queryWithTab(query, 'members')
    expect(query).toEqual({ tab: 'debts' })
  })

  it('round-trips with tabFromQuery for every tab', () => {
    const broken = TRIP_TABS.map((t) => t.name).filter((tab) => tabFromQuery(queryWithTab({}, tab).tab) !== tab)
    expect(broken).toEqual([])
  })
})
